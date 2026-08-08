package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException

/**
 * One rejected rule: a §12 check at save time, or a §6.3 coercion at execution time.
 *
 * [code] is always a constant from [PipelineErrorCodes] — never a literal — so
 * the drift test that reads §12/§13 out of the document guards every spelling in the system.
 *
 * [path] is a JSON pointer-ish location (`nodes[2].output.table`, `parameters.start_date`)
 * so an editor can highlight the offending field instead of the whole document, and
 * [details] carries the machine-readable facts behind the message for the unified error
 * response's `details` object (rules/02-error-handling.md). Reflected inbound values in
 * either field are truncated ([truncateForError]).
 */
data class ValidationFailure(
    val code: String,
    val path: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * The outcome of validating a pipeline — **exhaustive**, never fail-fast (§17.2).
 *
 * "Validation is exhaustive — all checks run, all failures collected, returned together.
 * This gives authors the full picture on a broken pipeline." An author fixing an
 * LLM-generated pipeline one error per round-trip is the failure mode this rule exists to
 * prevent, so every consumer gets the whole list or none of it.
 */
data class ValidationResult(
    val failures: List<ValidationFailure>,
) {
    val isValid: Boolean get() = failures.isEmpty()

    /** The distinct codes present, in first-seen order — the handle most assertions and logs want. */
    val codes: List<String> get() = failures.map { it.code }.distinct()

    /** Every failure carrying [code]. */
    fun withCode(code: String): List<ValidationFailure> = failures.filter { it.code == code }

    /** Throws [PipelineValidationException] unless the pipeline is valid; returns nothing otherwise. */
    fun orThrow() {
        if (!isValid) throw PipelineValidationException(this)
    }

    companion object {
        val VALID = ValidationResult(emptyList())
    }
}

/**
 * Thrown when an invalid pipeline reaches a boundary that cannot report a list — never at
 * the save path, which returns [ValidationResult] so the author sees every failure at once.
 *
 * The exception's `code` is the **first** failure's code (the unified error response carries
 * one code); the full list travels in `details["failures"]`, which is what the REST layer
 * renders. Extends `DatapipelinesException` per module-structure §4.3 — every module's
 * exceptions share that base and it lives in `typesystem`, the one module everyone may
 * depend on.
 */
class PipelineValidationException(
    val result: ValidationResult,
) : DatapipelinesException(
        code = result.failures.firstOrNull()?.code ?: PipelineErrorCodes.Validation.EMPTY_PIPELINE,
        message = "Pipeline validation failed with ${result.failures.size} error(s): ${result.codes.joinToString()}",
        details =
            mapOf(
                "failures" to
                    result.failures.map {
                        mapOf("code" to it.code, "path" to it.path, "message" to it.message, "details" to it.details)
                    },
            ),
    )
