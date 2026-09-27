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
        failures += ValidationFailure(code, path.safeEcho(MAX_REFLECTED_PATH_LENGTH), message, details)
    }

    fun addAll(other: List<ValidationFailure>) {
        failures += other
    }

    fun toResult(): ValidationResult = ValidationResult(failures.toList())
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
