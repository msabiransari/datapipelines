package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * `resources/read` (mcp-server.md §7.1, §7.2) — entities the agent reads as files.
 *
 * Reads are **inspection only** (§2 principle 2): every URI here maps to a repository read, and
 * nothing on this path mutates anything. Two rules the §13 checklist calls out explicitly are
 * enforced here rather than left to the caller:
 *
 * - **Datasource passwords are never included** — the projection is [toMcpMetadata], the same
 *   credential-free field list the tools emit.
 * - **Execution ownership** — an execution belonging to another user is reported as *not found*
 *   ([visibleTo]); a `read` key cannot read another user's execution or its events.
 *
 * An unknown or malformed URI is the SDK's `RESOURCE_NOT_FOUND` JSON-RPC error, which is a
 * protocol-level answer (§9.1) — `resources/read` has no `isError` content channel.
 */
class McpResourceReader(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val datasources: DatasourceRegistry,
    private val executions: ExecutionRepository,
    private val events: ExecutionEventRepository,
) {
    /** Reads [uri] for [ctx], or raises `RESOURCE_NOT_FOUND`. */
    fun read(
        uri: String,
        ctx: McpToolContext,
    ): McpSchema.ReadResourceResult {
        requireReadScope(ctx)
        val workspaceId = ctx.principal.requireWorkspace().id
        val parsed = McpResourceUri.parse(uri) ?: throw notFound(uri)
        val contents =
            when (parsed) {
                is McpResourceUri.PipelineLatest -> {
                    json(uri, pipelineBody(workspaceId, parsed.id, null))
                }

                is McpResourceUri.PipelineVersion -> {
                    json(uri, pipelineBody(workspaceId, parsed.id, parsed.version))
                }

                is McpResourceUri.PipelineParameters -> {
                    json(uri, parameters(workspaceId, parsed.id))
                }

                is McpResourceUri.TemplateLatest -> {
                    template(workspaceId, uri, parsed.id, null)
                }

                is McpResourceUri.TemplateVersion -> {
                    template(workspaceId, uri, parsed.id, parsed.version)
                }

                is McpResourceUri.DatasourceList -> {
                    json(
                        uri,
                        ExecutorJson.write(
                            datasources.listVisible(workspaceId = workspaceId).map {
                                it.toMcpMetadata()
                            },
                        ),
                    )
                }

                is McpResourceUri.DatasourceByName -> {
                    json(uri, datasource(workspaceId, parsed.name, uri))
                }

                is McpResourceUri.Execution -> {
                    json(uri, execution(workspaceId, parsed.executionId, ctx, uri))
                }

                is McpResourceUri.ExecutionEvents -> {
                    text(uri, eventReplay(workspaceId, parsed.executionId, ctx, uri))
                }
            }
        return McpSchema.ReadResourceResult.builder(listOf(contents)).build()
    }

    private fun pipelineBody(
        workspaceId: UUID,
        id: UUID,
        version: Int?,
    ): String {
        val record = pipelines.findById(workspaceId, id) ?: throw notFound(McpResourceUri.pipeline(id))
        return pipelines.findVersionBody(workspaceId, id, version ?: record.currentVersion)
            ?: throw notFound(McpResourceUri.pipeline(id))
    }

    /** `…/parameters` — the pipeline's parameter declarations only (§7.1). */
    private fun parameters(
        workspaceId: UUID,
        id: UUID,
    ): String {
        val body = ExecutorJson.mapper.readTree(pipelineBody(workspaceId, id, null))
        return ExecutorJson.write(body.path("parameters"))
    }

    private fun template(
        workspaceId: UUID,
        uri: String,
        id: String,
        version: Int?,
    ): McpSchema.TextResourceContents {
        val body =
            if (version == null) {
                templates.findLatest(workspaceId, id)?.body
            } else {
                templates.lookupVersion(workspaceId, id, version)?.body
            } ?: throw notFound(uri)
        return McpSchema.TextResourceContents(uri, McpResourceCatalog.MIME_FREEMARKER_SQL, body, null)
    }

    /** §5.3: by-name read of a datasource the pinned workspace cannot see is not-found, like every surface. */
    private fun datasource(
        workspaceId: UUID,
        name: String,
        uri: String,
    ): String {
        val datasource = datasources.getVisible(name, workspaceId) ?: throw notFound(uri)
        return ExecutorJson.write(datasource.toMcpMetadata())
    }

    private fun execution(
        workspaceId: UUID,
        executionId: UUID,
        ctx: McpToolContext,
        uri: String,
    ): String {
        val record = executions.findById(workspaceId, executionId)?.takeIf { it.visibleTo(ctx) } ?: throw notFound(uri)
        return ExecutorJson.write(record.toMcpMetadata())
    }

    /**
     * `…/events` — "SSE event replay as text" (§7.1), rendered in the wire framing rest-api §6.2
     * defines (`id:` / `event:` / `data:` per event, blank-line separated) so an agent sees the
     * same bytes a REST client would have streamed.
     *
     * The durable 7-day `execution_events` record is the source (metadata-db §4.7); an execution
     * whose events have aged out replays as an empty document rather than a 404 — the execution
     * itself still exists.
     */
    private fun eventReplay(
        workspaceId: UUID,
        executionId: UUID,
        ctx: McpToolContext,
        uri: String,
    ): String {
        executions.findById(workspaceId, executionId)?.takeIf { it.visibleTo(ctx) } ?: throw notFound(uri)
        return events.findByExecution(executionId).joinToString(separator = "\n") { record ->
            "id: ${record.eventId}\nevent: ${record.eventType}\ndata: ${record.payloadJson}\n"
        }
    }

    private fun json(
        uri: String,
        body: String,
    ): McpSchema.TextResourceContents = McpSchema.TextResourceContents(uri, McpResourceCatalog.MIME_JSON, body, null)

    private fun text(
        uri: String,
        body: String,
    ): McpSchema.TextResourceContents = McpSchema.TextResourceContents(uri, MIME_EVENT_STREAM, body, null)

    private fun notFound(uri: String): McpError = McpError.RESOURCE_NOT_FOUND.apply(uri)

    private companion object {
        /** rest-api §6.2 — the media type the replayed framing belongs to. */
        const val MIME_EVENT_STREAM = "text/event-stream"
    }
}
