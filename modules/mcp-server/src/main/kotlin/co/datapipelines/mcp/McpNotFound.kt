package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The not-found codes this surface returns, sourced from the
 * [§13 catalog](../../../../../../../docs/pipeline-contract.md) — the single registry of record.
 *
 * ## Reported spec gap
 *
 * §13 catalogues no *read*-side not-found code for a pipeline, template or datasource: those
 * REST endpoints are documented as plain `404`s (rest-api §5.4, §8) without a code, and
 * mcp-server.md §9.2 requires every tool error to carry a §13 code. Rather than invent four new
 * codes — which would silently extend a frozen catalog — each case below reuses the catalogued
 * code that names exactly the condition. This is flagged to the orchestrator as an open question;
 * if the catalog later gains dedicated read codes, only this file changes.
 */
object McpNotFound {
    /** Unknown or soft-deleted pipeline — the code rest-api §14 gives a pipeline that is gone. */
    fun pipeline(id: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "Pipeline $id does not exist.",
            details = mapOf("pipeline_id" to id.toString()),
        )

    /** Unknown pipeline version (the pipeline exists, that version does not). */
    fun pipelineVersion(
        id: UUID,
        version: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "Pipeline $id has no version $version.",
            details = mapOf("pipeline_id" to id.toString(), "version" to version),
        )

    /** Unknown template id (§13.3 `pipeline.validation.template_not_found`). */
    fun template(id: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Validation.TEMPLATE_NOT_FOUND,
            message = "Template '$id' does not exist.",
            details = mapOf("template_id" to id),
        )

    /** Known template, unknown version (§13.3 `pipeline.validation.template_version_not_found`). */
    fun templateVersion(
        id: String,
        version: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Validation.TEMPLATE_VERSION_NOT_FOUND,
            message = "Template '$id' has no version $version.",
            details = mapOf("template_id" to id, "version" to version),
        )

    /** Unknown datasource name (§13.3 `pipeline.validation.unknown_datasource`). */
    fun datasource(name: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE,
            message = "Datasource '$name' is not registered in this environment.",
            details = mapOf("datasource" to name),
        )

    /** Unknown execution id (§13.10 `result.execution_not_found`). */
    fun execution(id: UUID): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Result.EXECUTION_NOT_FOUND,
            message = "Execution $id does not exist.",
            details = mapOf("execution_id" to id.toString()),
        )
}
