package co.datapipelines.mcp

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
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

    /** Unknown template id (§13.9 `template.not_found`). */
    fun template(id: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "Template '$id' does not exist.",
            details = mapOf("template_id" to id),
        )

    /** Known template, unknown version (§13.9 `template.not_found`). */
    fun templateVersion(
        id: String,
        version: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "Template '$id' has no version $version.",
            details = mapOf("template_id" to id, "version" to version),
        )

    /** Unknown datasource name (§13.8 `datasource.not_found`). */
    fun datasource(name: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Datasource.NOT_FOUND,
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

/**
 * The §5.3 visibility gate every datasource-name-taking tool applies BEFORE touching pools,
 * connectivity probes or the introspector: a datasource bound to another workspace is
 * INVISIBLE, so by-name access resolves as the same not-found an unknown name gets — no
 * existence oracle, and no pool opened on a connection the caller may not see. This is the
 * gate `datasources_get` and the resource reader already had; the 022 review (F2/F3) caught
 * the schema tools and `datasources_test` resolving names without it.
 */
internal fun DatasourceRegistry.requireVisible(
    name: String,
    ctx: McpToolContext,
): Datasource = requireVisible(name, ctx.principal.requireWorkspace().id)

/**
 * The same gate for callers holding the workspace as data (the resource reader) — 025 C3
 * also unified the two inline `getVisible ?: throw` spellings onto this one home, so the
 * gate has a single spelling across the surface.
 */
internal fun DatasourceRegistry.requireVisible(
    name: String,
    workspaceId: UUID,
): Datasource = getVisible(name, workspaceId) ?: throw McpNotFound.datasource(name)
