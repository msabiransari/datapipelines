package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Builds the execution [ExecutionContext] from a client's supplied parameter values
 * (pipeline-contract §7.1 steps 2–4).
 *
 * Binding is **exhaustive**, like validation: every supplied value is checked and all
 * failures come back together. A client fixing a five-parameter execution request one
 * rejection per round-trip is the same avoidable cost §17.2 rules out for save time.
 *
 * Values the pipeline does not declare are ignored rather than rejected — §7.2 defines the
 * Context as "all declared pipeline parameters", so an undeclared extra simply never enters
 * it, and §13 has no code for one. (Ignoring is also what keeps a client that upgrades
 * before the pipeline does from breaking.)
 */
class ParameterBinder(
    private val parameters: Map<String, Parameter>,
) {
    /** Binds [inputs] — the `parameters` object of an execute request — into a Context. */
    fun bind(inputs: Map<String, JsonNode>): ParameterBindingResult {
        val failures = mutableListOf<ValidationFailure>()
        val bound = LinkedHashMap<String, Any?>()

        parameters.forEach { (name, parameter) ->
            val supplied = inputs[name]?.takeUnless { it.isNull }
            val value = supplied ?: parameter.default?.takeUnless { it.isNull }
            when {
                value != null -> coerceInto(name, parameter, value, bound, failures)

                parameter.required -> failures += requiredMissing(name)

                // Optional, unsupplied, no default: the key exists in the Context with no
                // value, so a template referencing it is defined-but-null rather than a
                // render failure on an undefined variable (§7.4).
                else -> bound[name] = null
            }
        }
        return if (failures.isEmpty()) {
            ParameterBindingResult.Bound(ExecutionContext(bound))
        } else {
            ParameterBindingResult.Rejected(failures.toList())
        }
    }

    /** As [bind], for the `parameters` object of a request body still in tree form. */
    fun bind(inputs: JsonNode): ParameterBindingResult =
        bind(if (inputs.isObject) inputs.properties().associate { it.key to it.value } else emptyMap())

    /** As [bind], but throws [PipelineValidationException] instead of returning a rejection. */
    fun bindOrThrow(inputs: Map<String, JsonNode>): ExecutionContext =
        when (val result = bind(inputs)) {
            is ParameterBindingResult.Bound -> result.context
            is ParameterBindingResult.Rejected -> throw PipelineValidationException(ValidationResult(result.failures))
        }

    /**
     * The context §12.6's save-time dry render uses: "defaults where present, type-appropriate
     * sample values otherwise" (§7.4).
     *
     * No key is ever null here, unlike [bind]'s optional-unsupplied case. A dry render exists
     * to prove the *template* references only declared variables; failing it on a null the
     * author cannot supply at save time would report a template defect that does not exist.
     */
    fun sampleContext(): Map<String, Any?> =
        parameters.mapValues { (_, parameter) ->
            parameter.default
                ?.takeUnless { it.isNull }
                ?.let { ParameterCoercion.coerce(parameter.type, it) }
                ?.let { (it as? ParameterCoercion.Outcome.Coerced)?.value }
                ?: sampleValue(parameter.type)
        }

    private fun coerceInto(
        name: String,
        parameter: Parameter,
        value: JsonNode,
        bound: MutableMap<String, Any?>,
        failures: MutableList<ValidationFailure>,
    ) {
        when (val outcome = ParameterCoercion.coerce(parameter.type, value)) {
            is ParameterCoercion.Outcome.Coerced -> {
                bound[name] = outcome.value
            }

            is ParameterCoercion.Outcome.Rejected -> {
                failures +=
                    validationFailure(
                        code = PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                        path = "parameters.${name.truncateForError()}",
                        message = "Parameter '${name.truncateForError()}': ${outcome.reason}.",
                        details = mapOf("parameter" to name.truncateForError(), "declared_type" to parameter.type.wire),
                    )
            }
        }
    }

    private fun requiredMissing(name: String) =
        validationFailure(
            code = PipelineErrorCodes.Execution.PARAMETER_REQUIRED,
            path = "parameters.${name.truncateForError()}",
            message = "Required parameter '${name.truncateForError()}' was not supplied.",
            details = mapOf("parameter" to name.truncateForError()),
        )

    private companion object {
        /**
         * One representative value per canonical type. Fixed, never random: a dry render that
         * passes on Tuesday and fails on Wednesday is worse than one that never ran.
         */
        fun sampleValue(type: LogicalType): Any =
            when (type) {
                LogicalType.INTEGER -> 1

                LogicalType.BIGINTEGER -> BigInteger.ONE

                LogicalType.DECIMAL -> BigDecimal("1.0")

                LogicalType.BIGDECIMAL -> BigDecimal("1.0")

                LogicalType.BOOLEAN -> false

                LogicalType.STRING -> "sample"

                LogicalType.BINARY -> byteArrayOf(0)

                LogicalType.DATE -> LocalDate.EPOCH

                LogicalType.TIME -> LocalTime.MIDNIGHT

                LogicalType.TIMESTAMP -> Instant.EPOCH

                // Unreachable: NULL is not a declarable parameter type (§6.2).
                LogicalType.NULL -> ""
            }
    }
}

/** The outcome of [ParameterBinder.bind]. */
sealed interface ParameterBindingResult {
    data class Bound(
        val context: ExecutionContext,
    ) : ParameterBindingResult

    data class Rejected(
        val failures: List<ValidationFailure>,
    ) : ParameterBindingResult
}
