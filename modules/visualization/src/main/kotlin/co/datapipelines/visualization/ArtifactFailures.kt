package co.datapipelines.visualization

import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.DatapipelinesException

/**
 * Accumulates the refusals of one read or one validation of a visualization or dashboard document —
 * exhaustive, never fail-fast (pipeline-contract §17.2's rule). The rules are handed a collector and return
 * `Unit`, so no early-return shape can skip a later rule; every [ValidationFailure] this module builds goes
 * through [add], so a reflected `path` cannot escape the CF-1/CF-2 rules.
 *
 * [bodyInvalid] is the family's `*.validation.body_invalid` — the code of the terminal marker.
 */
internal class ArtifactFailures(
    private val bodyInvalid: String,
) {
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

    /**
     * The list is bounded (the parameter-set 194b security pass, F1's sink): past [MAX_FAILURES] one terminal
     * `body_invalid` / `too_many_failures` marker lands and everything after it is dropped, so a body built to
     * produce a refusal per byte cannot answer a response hundreds of times its own size.
     */
    private fun admit(): Boolean {
        if (failures.size < MAX_FAILURES) return true
        if (failures.size == MAX_FAILURES) {
            failures +=
                ValidationFailure(
                    bodyInvalid,
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
 * A visualization or dashboard document refused at a boundary that cannot return a list. The code is the
 * FIRST failure's ([fallbackCode] for an empty result, which no caller builds); the whole list travels in
 * `details.failures` — the `PipelineValidationException` shape, so a surface renders every family the same way.
 */
class ArtifactValidationException(
    val result: ValidationResult,
    fallbackCode: String,
) : DatapipelinesException(
        code = result.failures.firstOrNull()?.code ?: fallbackCode,
        message = "Validation failed with ${result.failures.size} error(s): ${result.codes.joinToString()}",
        details =
            mapOf(
                "failures" to
                    result.failures.map {
                        mapOf("code" to it.code, "path" to it.path, "message" to it.message, "details" to it.details)
                    },
            ),
    )
