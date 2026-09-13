package co.datapipelines.mcp

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.templates.TemplateRepository
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
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
 *
 * Every read is written to the audit log as `mcp.resource.read` (120) — uri, outcome, error
 * code on failure, elapsed_ms, correlation id, key id and owner — emitted HERE, at the one
 * place every read passes through, for the dispatcher's own reason: a read path that forgets
 * is the failure mode, and a choke point cannot forget. Emission follows the tool event's
 * discipline (§14): after the read, on success and failure alike, and a sink failure is
 * logged and swallowed — the caller's read never changes outcome because bookkeeping did.
 *
 * The one kind that is not an entity is the skill (095 §C2): `datapipelines://docs/skill` and
 * `…/skill/{reference}` serve the packaged Markdown ([SkillDocs]) — the same bytes the
 * deployment serves at `GET /skill.md`, and the same file a checkout holds at
 * `.agents/skills/datapipelines/`. It is workspace-independent and needs `read` like every
 * other resource; an unknown reference is not-found in the same shape as an unknown pipeline.
 */
class McpResourceReader(
    private val pipelines: PipelineService,
    private val templates: TemplateRepository,
    private val datasources: DatasourceRegistry,
    private val executions: ExecutionRepository,
    private val events: ExecutionEventRepository,
    private val auditSink: AuditEventSink,
) {
    private val log = LoggerFactory.getLogger(McpResourceReader::class.java)

    /** Reads [uri] for [ctx], or raises `RESOURCE_NOT_FOUND`. */
    @Suppress("TooGenericExceptionCaught")
    fun read(
        uri: String,
        ctx: McpToolContext,
    ): McpSchema.ReadResourceResult {
        val startedAt = System.nanoTime()
        try {
            return readInternal(uri, ctx).also { audit(uri, ctx, outcome = "success", code = null, startedAt = startedAt) }
        } catch (e: McpError) {
            audit(uri, ctx, outcome = "error", code = auditCode(e), startedAt = startedAt)
            throw e
        } catch (e: Exception) {
            // The handler's own catch renders this as the §9.1 internal error; the audit row
            // names it the way the dispatcher's internal_error outcome names a tool's.
            audit(uri, ctx, outcome = "error", code = "internal_error", startedAt = startedAt)
            throw e
        }
    }

    private fun readInternal(
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
                    json(uri, datasource(workspaceId, parsed.name))
                }

                is McpResourceUri.Execution -> {
                    json(uri, execution(workspaceId, parsed.executionId, ctx, uri))
                }

                is McpResourceUri.ExecutionEvents -> {
                    text(uri, eventReplay(workspaceId, parsed.executionId, ctx, uri))
                }

                is McpResourceUri.Skill -> {
                    markdown(uri, SkillDocs.skill)
                }

                is McpResourceUri.SkillReference -> {
                    markdown(uri, SkillDocs.reference(parsed.reference) ?: throw notFound(uri))
                }
            }
        return McpSchema.ReadResourceResult.builder(listOf(contents)).build()
    }

    // Three absences, one answer: unknown pipeline, no version to serve at all (D55/§3.4), and a
    // version whose body is gone. Each is the SAME catalogued not-found, and merging them into one
    // branch would hide which read actually came back empty.
    @Suppress("ThrowsCount")
    private fun pipelineBody(
        workspaceId: UUID,
        id: UUID,
        version: Int?,
    ): String {
        val record = pipelines.findRecord(workspaceId, id) ?: throw notFound(McpResourceUri.pipeline(id))
        // D55: with no version in the URI this serves the WORKING version — the draft when one
        // exists, else the latest release — the same default `pipelines_execute` runs, so an
        // agent reading the body and then running it sees one pipeline, not two.
        val resolved = version ?: pipelines.workingVersion(workspaceId, record) ?: throw notFound(McpResourceUri.pipeline(id))
        return pipelines.findVersionBody(workspaceId, id, resolved) ?: throw notFound(McpResourceUri.pipeline(id))
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
                // D55/§7.1: the WORKING version — `findLatest` is null for a template nobody has
                // released yet, and the resource would have answered not-found for one an agent
                // had just created.
                templates.findWorking(workspaceId, id)?.body
            } else {
                templates.lookupVersion(workspaceId, id, version)?.body
            } ?: throw notFound(uri)
        return McpSchema.TextResourceContents(uri, McpResourceCatalog.MIME_FREEMARKER_SQL, body, null)
    }

    /**
     * §5.3: by-name read of a datasource the pinned workspace cannot see is not-found,
     * like every surface. (025 C3: the gate's spelling is requireVisible; the not-found
     * throw inside it answers with the resource's own shape.)
     */
    private fun datasource(
        workspaceId: UUID,
        name: String,
    ): String {
        val datasource = datasources.requireVisible(name, workspaceId)
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

    /** The skill and its references (095 §C2) — the packaged bytes, never a second copy. */
    private fun markdown(
        uri: String,
        body: String,
    ): McpSchema.TextResourceContents = McpSchema.TextResourceContents(uri, McpResourceCatalog.MIME_MARKDOWN, body, null)

    private fun notFound(uri: String): McpError = McpError.RESOURCE_NOT_FOUND.apply(uri)

    /**
     * The single emission point for `mcp.resource.read` (120). Only the URI is recorded — the
     * read's CONTENT never is, for the same reason the tool audit never records parameter
     * values (§14): the row says THAT a read happened and by whom, never what it returned.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun audit(
        uri: String,
        ctx: McpToolContext,
        outcome: String,
        code: String?,
        startedAt: Long,
    ) {
        val details =
            buildMap {
                put("uri", uri)
                put("outcome", outcome)
                put("correlation_id", ctx.correlationId.toString())
                code?.let { put("code", it) }
                put("elapsed_ms", (System.nanoTime() - startedAt) / NANOS_PER_MILLI)
                // D-R8, the dispatcher's own row shape: a super admin acting in a workspace
                // they hold no membership in is marked, or the audit promise is not a promise.
                if (ctx.principal.workspace?.actingViaSuperAdmin == true) put("acting_via", "super_admin")
            }
        try {
            auditSink.log(
                event = AUDIT_EVENT,
                userId = ctx.principal.userId,
                keyId = ctx.principal.keyId,
                details = details,
            )
        } catch (e: Exception) {
            log.warn("MCP resource-read audit emission failed uri={} correlation_id={}", uri, ctx.correlationId, e)
        }
    }

    /** The failure's identity for the audit row — a name, never the JSON-RPC number. */
    private fun auditCode(e: McpError): String =
        when (e.jsonRpcError.code()) {
            McpSchema.ErrorCodes.RESOURCE_NOT_FOUND -> "resource_not_found"
            McpArguments.FORBIDDEN -> "forbidden"
            McpArguments.INVALID_PARAMS -> "invalid_params"
            else -> "internal_error"
        }

    private companion object {
        /** rest-api §6.2 — the media type the replayed framing belongs to. */
        const val MIME_EVENT_STREAM = "text/event-stream"

        const val AUDIT_EVENT = "mcp.resource.read"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
