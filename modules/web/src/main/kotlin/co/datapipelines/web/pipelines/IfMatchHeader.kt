package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException

/**
 * The `If-Match` precondition header every version-lifecycle mutation carries (versioning
 * §4.2; the exact spelling rest-api.md fixes). A missing header is a protocol error, not a
 * conflict — the caller has not participated in the protocol at all.
 *
 * A REST-surface concern, and it stays one: 056 moved the pipeline lifecycle service into
 * `pipeline-contract`, and a service that imports [ApiException] is the layering violation
 * the module graph exists to prevent. The header parse is the surface's; the hash it yields
 * is what the service takes.
 */
object IfMatchHeader {
    const val NAME = "If-Match"

    /** @throws ApiException when the header is absent or blank. */
    fun required(value: String?): String =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(
                PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "The $NAME header (the body hash you based this change on) is required.",
                mapOf(ApiErrors.REASON to "precondition_missing", "header" to NAME),
            )
}
