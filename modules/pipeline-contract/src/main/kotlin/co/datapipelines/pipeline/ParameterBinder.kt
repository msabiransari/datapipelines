package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorInput
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
 *
 * ## Calculator output keys (078 A5, owner ruling 2026-09-05)
 *
 * [calculatorOutputs] carries the pipeline's CALCULATOR `context_key`s, typed by the kind's
 * output (`null` = an ANY-output kind — coalesce, if_null, map). Each is an implicit
 * **optional** input: supplied, it is coerced exactly like a parameter — the same
 * [ParameterCoercion], the same `invalid_parameter_type` code and `parameters.<name>` path —
 * and enters the Context from the caller; unsupplied, it puts NOTHING in the bound map (these
 * are not §7.2 declared parameters), and the node runs and writes the key itself. An ANY-typed
 * key accepts any JSON scalar, read the way [CalculatorInputResolver] reads an ANY input; a
 * container is refused with the same code — §13 has no other code for a bad execute input.
 * A key that collides with a declared parameter binds as the parameter (the collision itself
 * is §12.10's save-time refusal, `CalculatorRules`' job — never the binder's).
 *
 * ## All-or-nothing per multi-output node (121 D5)
 *
 * [calculatorGroups] carries every multi-output node's written key set, by node id
 * ([Pipeline.calculatorOutputGroups]). Override is all-or-nothing per node: every key supplied
 * and the node is skipped, none and it computes — a PROPER subset is refused with
 * `pipeline.execution.calculator_keys_partial` (`details` names the `node`, the `supplied`
 * keys and the `missing` ones), because a half-written window is the silent wrong answer the
 * mapping completeness rule (§12.10 `calculator_outputs_incomplete`) already refuses at save.
 * The refusal rides the same [ParameterBindingResult.Rejected] list as a type failure, so the
 * caller gets it with the identical shape and before anything runs.
 */
class ParameterBinder(
    private val parameters: Map<String, Parameter>,
    private val calculatorOutputs: Map<String, LogicalType?> = emptyMap(),
    private val calculatorGroups: Map<String, Set<String>> = emptyMap(),
    /**
     * The pipeline's value-mode TRANSFORM `context_key`s ([Pipeline.transformOutputKeys], 7c
     * #7) — implicit optional inputs exactly like the calculator keys above, with one
     * deliberate difference: the body carries no contract, so a supplied value binds
     * UNCOERCED (any JSON value, containers included — an R4 object key accepts any JSON
     * object) and the pinned contract's type gate checks it at the node, where the contract
     * lives. Defaulted so every pre-7c construction is unchanged.
     */
    private val transformKeys: Set<String> = emptySet(),
) {
    /** Binds [inputs] — the `parameters` object of an execute request — into a Context. */
    fun bind(inputs: Map<String, JsonNode>): ParameterBindingResult {
        val failures = mutableListOf<ValidationFailure>()
        val bound = LinkedHashMap<String, Any?>()

        parameters.forEach { (name, parameter) ->
            val supplied = inputs[name]?.takeUnless { it.isNull }
            val value = supplied ?: parameter.default?.takeUnless { it.isNull }
            when {
                value != null -> coerceInto(name, parameter.type, value, bound, failures)

                parameter.required -> failures += requiredMissing(name)

                // Optional, unsupplied, no default: the key exists in the Context with no
                // value, so a template referencing it is defined-but-null rather than a
                // render failure on an undefined variable (§7.4).
                else -> bound[name] = null
            }
        }
        // The calculator tier AFTER the parameter one: a supplied calculator key is optional in
        // both directions (unsupplied → nothing; an explicit JSON null reads as unsupplied,
        // exactly as it does for a declared parameter), and a name the pipeline also declares
        // as a parameter is the parameter's — the collision refusal lives at save time.
        calculatorOutputs.forEach { (name, outputType) ->
            if (name in parameters) return@forEach
            val supplied = inputs[name]?.takeUnless { it.isNull } ?: return@forEach
            coerceCalculatorInto(name, outputType, supplied, bound, failures)
        }
        bindTransformKeys(inputs, bound)
        // 121 D5: the all-or-nothing check reads the REQUEST, not the bound map — a key whose
        // value failed coercion above is still "supplied", and its type failure is already
        // reported beside this one (exhaustive, like every rule here).
        calculatorGroups.forEach { (nodeId, keys) ->
            val supplied = keys.filter { inputs[it]?.takeUnless { v -> v.isNull } != null }.sorted()
            if (supplied.isNotEmpty() && supplied.size < keys.size) {
                failures += keysPartial(nodeId, keys, supplied)
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
        } +
            // Calculator output keys sample by the kind's output type, STRING for an ANY-output
            // kind: the dry render needs a value of *some* defined type, never a particular one.
            // A name the pipeline also declares as a parameter keeps the parameter's sample —
            // the collision is §12.10's save-time refusal, and the parameter wins its own name.
            calculatorOutputs
                .filterKeys { it !in parameters }
                .mapValues { (_, outputType) -> sampleValue(outputType ?: LogicalType.STRING) } +
            // 7c (#7): a value-mode TRANSFORM's key samples as STRING like an ANY-output
            // calculator key — the dry render needs a value of some defined type, never a
            // particular one, and the body carries no contract to sample from.
            transformKeys
                .filter { it !in parameters && it !in calculatorOutputs }
                .associateWith { sampleValue(LogicalType.STRING) }

    private fun coerceInto(
        name: String,
        type: LogicalType,
        value: JsonNode,
        bound: MutableMap<String, Any?>,
        failures: MutableList<ValidationFailure>,
    ) {
        when (val outcome = ParameterCoercion.coerce(type, value)) {
            is ParameterCoercion.Outcome.Coerced -> {
                bound[name] = outcome.value
            }

            is ParameterCoercion.Outcome.Rejected -> {
                failures += invalidType(name, type.wire, outcome.reason)
            }
        }
    }

    /**
     * The calculator-key twin of [coerceInto] (078 A5). A typed output coerces through the same
     * [ParameterCoercion] and reports the same code, path and details as a parameter failure —
     * to the caller there is no second kind of execute input. An ANY output takes any JSON
     * scalar ([CalculatorInputResolver.natural]); a container has no canonical reading and is
     * refused with the same code rather than a new one.
     */
    private fun coerceCalculatorInto(
        name: String,
        outputType: LogicalType?,
        value: JsonNode,
        bound: MutableMap<String, Any?>,
        failures: MutableList<ValidationFailure>,
    ) {
        if (outputType == null) {
            val scalar = CalculatorInputResolver.natural(value)
            if (scalar == null) {
                failures += invalidType(name, CalculatorInput.ANY_TYPE, "a JSON ${value.nodeType} has no canonical reading")
            } else {
                bound[name] = scalar
            }
            return
        }
        coerceInto(name, outputType, value, bound, failures)
    }

    /**
     * The value-mode TRANSFORM keys (7c #7), the third bind tier after the parameters and
     * calculator ones — supplied, the value enters the Context uncoerced (the contract's gate
     * is the node's, at run); unsupplied, nothing, and the node runs. A name a parameter or
     * calculator key owns is theirs (save-time collision).
     */
    private fun bindTransformKeys(
        inputs: Map<String, JsonNode>,
        bound: LinkedHashMap<String, Any?>,
    ) {
        transformKeys.forEach { name ->
            if (name in parameters || name in calculatorOutputs) return@forEach
            val supplied = inputs[name]?.takeUnless { it.isNull } ?: return@forEach
            bound[name] = naturalJson(supplied)
        }
    }

    private fun invalidType(
        name: String,
        declaredType: String,
        reason: String,
    ) = validationFailure(
        code = PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
        path = "parameters.${name.truncateForError()}",
        message = "Parameter '${name.truncateForError()}': $reason.",
        details = mapOf("parameter" to name.truncateForError(), "declared_type" to declaredType),
    )

    /**
     * 121 D5's refusal — a proper subset of a multi-output node's keys. Same shape as
     * [invalidType] (one [ValidationFailure] on the rejected bind), because to the caller it
     * is the same category of answer: this execute input is not acceptable, nothing ran.
     */
    private fun keysPartial(
        nodeId: String,
        keys: Set<String>,
        supplied: List<String>,
    ) = validationFailure(
        code = PipelineErrorCodes.Execution.CALCULATOR_KEYS_PARTIAL,
        path = "parameters",
        message =
            "Calculator node '${nodeId.truncateForError()}' writes ${keys.size} keys and override is all-or-nothing: " +
                "supply every one of them or none — ${supplied.joinToString()} supplied, " +
                "${(keys - supplied.toSet()).sorted().joinToString()} missing.",
        details =
            mapOf(
                "node" to nodeId.truncateForError(),
                "supplied" to supplied,
                "missing" to (keys - supplied.toSet()).sorted(),
            ),
    )

    private fun requiredMissing(name: String) =
        validationFailure(
            code = PipelineErrorCodes.Execution.PARAMETER_REQUIRED,
            path = "parameters.${name.truncateForError()}",
            message = "Required parameter '${name.truncateForError()}' was not supplied.",
            details = mapOf("parameter" to name.truncateForError()),
        )

    internal companion object {
        /**
         * A supplied TRANSFORM key's value as a plain JVM value (7c, #7) — containers
         * included, because an R4 object key IS one. Uncoerced on purpose: the pinned
         * contract's gate at the node is the type check (the body carries no contract).
         */
        private fun naturalJson(value: JsonNode): Any? = PipelineJson.objectMapper().convertValue(value, Any::class.java)

        /**
         * One representative value per canonical type. Fixed, never random: a dry render that
         * passes on Tuesday and fails on Wednesday is worse than one that never ran.
         *
         * Internal, not private: §12.6's declared set includes CALCULATOR output keys (078 A1),
         * and those sample by the kind's output type through this same table.
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
