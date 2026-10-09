package co.datapipelines.parameters

import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.DatapipelinesException

/**
 * Accumulates the parameter-set refusals of one read or one validation — exhaustive, never
 * fail-fast (pipeline-contract §17.2's rule, which the record's §4 inherits: "all checks run, all
 * failures collected, returned together"). The rules are handed a collector and return `Unit`, so no
 * early-return shape can skip a later rule.
 *
 * The house [ValidationFailure] / [ValidationResult] shape is reused on purpose: the surfaces (lane
 * D) render one envelope for a pipeline and a parameter set. Every [ValidationFailure] this module
 * builds goes through [add], so a reflected `path` cannot escape the CF-1/CF-2 rules.
 */
internal class ParameterSetFailures {
    private val failures = mutableListOf<ValidationFailure>()

    val isEmpty: Boolean get() = failures.isEmpty()

    fun add(
        code: String,
        path: String,
        message: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        if (!admit()) return
        failures += ValidationFailure(code, path.safeEcho(MAX_REFLECTED_PATH_LENGTH), message, details)
    }

    fun addAll(other: List<ValidationFailure>) {
        other.forEach { if (admit()) failures += it }
    }

    /**
     * The list is bounded (the 194b security pass, F1's sink): past [MAX_FAILURES] one terminal
     * `body_invalid` / `too_many_failures` marker lands and everything after it is dropped, so a body
     * built to produce a refusal per byte cannot answer a response hundreds of times its own size.
     */
    private fun admit(): Boolean {
        if (failures.size < MAX_FAILURES) return true
        if (failures.size == MAX_FAILURES) {
            failures +=
                ValidationFailure(
                    ParameterErrorCodes.BODY_INVALID,
                    "",
                    "The document produced more than $MAX_FAILURES refusals; the rest are not listed.",
                    mapOf("reason" to "too_many_failures", "max" to MAX_FAILURES),
                )
        }
        return false
    }

    fun toResult(): ValidationResult = ValidationResult(failures.toList())

    companion object {
        /** Refusals listed before the terminal marker; the marker makes it one more. */
        const val MAX_FAILURES = 1_000
    }
}

/**
 * A parameter-set document refused at a boundary that cannot return a list. The code is the FIRST
 * failure's; the whole list travels in `details.failures` — the `PipelineValidationException` shape,
 * so a surface renders both the same way.
 */
class ParameterSetValidationException(
    val result: ValidationResult,
) : DatapipelinesException(
        code = result.failures.firstOrNull()?.code ?: ParameterErrorCodes.BODY_INVALID,
        message = "Parameter set validation failed with ${result.failures.size} error(s): ${result.codes.joinToString()}",
        details =
            mapOf(
                "failures" to
                    result.failures.map {
                        mapOf("code" to it.code, "path" to it.path, "message" to it.message, "details" to it.details)
                    },
            ),
    )

/**
 * A search needle over [ParameterSetService.MAX_QUERY_LENGTH] (#490) — `parameter.validation.query_too_long`,
 * `details.limit` and `details.length`. A type of its own so the agent surface can translate exactly this
 * refusal into a protocol error (an argument it must fix) while REST answers it through the catalog's 400.
 * The message carries the two numbers, never the needle: a long term is not reflected into an error body.
 */
class ParameterSetQueryTooLongException(
    val limit: Int,
    val length: Int,
) : DatapipelinesException(
        code = ParameterErrorCodes.QUERY_TOO_LONG,
        message = "The search term is $length characters; at most $limit are searched.",
        details = mapOf("limit" to limit, "length" to length),
    )
