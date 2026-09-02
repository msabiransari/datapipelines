package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/** `pipelines_list` (mcp-server.md §6.2.1). Scope: `read`. */
class PipelinesListTool(
    private val pipelines: PipelineRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_list",
            description =
                "List the pipelines of the key's pinned workspace, filtered by owner, datasource, or text search. Returns " +
                    "metadata (id, name, display_name, description, version, updated_at) — not the full body. Use " +
                    "pipelines_get for the body; pipelines in other workspaces are absent from this listing and " +
                    "resolve as not-found by id.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "owner": {"type": "string", "description": "Filter by owner user ID."},
                    "datasource": {"type": "string", "description": "Filter by datasource name."},
                    "q": {"type": "string", "description": "Full-text search on name and description."},
                    "limit": {"type": "integer", "default": 50, "maximum": 200}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val owner = args.uuid("owner")
        val query = args.string("q")?.lowercase()
        val datasource = args.string("datasource")
        val limit = args.int("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)

        val records =
            if (datasource != null) {
                pipelines.findAllByDatasource(workspaceId, datasource, owner)
            } else {
                pipelines.findAll(workspaceId, owner)
            }

        return records
            .asSequence()
            .filter { query == null || it.matches(query) }
            .take(limit)
            .map { it.toMetadata() }
            .toList()
    }

    private fun PipelineRecord.matches(query: String): Boolean =
        name.lowercase().contains(query) ||
            displayName.lowercase().contains(query) ||
            description.lowercase().contains(query)

    private fun PipelineRecord.toMetadata(): Map<String, Any?> =
        mapOf(
            "id" to id.toString(),
            "name" to name,
            "display_name" to displayName,
            "description" to description,
            "version" to currentVersion,
            "owner_id" to ownerId.toString(),
            "updated_at" to updatedAt,
        )

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/**
 * `pipelines_get` (mcp-server.md §6.2.2). Scope: `read`.
 *
 * Returns the **pipeline JSON body** exactly as stored (pipeline-contract §3) — not a wrapper
 * carrying server-assigned fields — merged with the fields the hash protocol needs
 * (versioning §4.2/§12): the version's `body_hash` and `status` (echo this hash back as
 * `expected_hash` when you update), `current_version` (the latest RELEASED version — what
 * execute-default runs), and the `draft` pointer when one exists.
 *
 * Since 039 the DEFAULT is the **working version** (versioning §7): the DRAFT when one
 * exists, else `current_version` — an agent that read released while a draft was open
 * would rebase on stale content and quietly discard the draft with its next write. The
 * response always states which `version` and `status` it returned; an explicit `version`
 * argument still wins.
 *
 * Since 040 the response also carries `upgrade_available` WHENEVER a node's pinned template
 * has a newer RELEASED version (040 D5): one `{node, template_id, pinned, latest_released}`
 * row per outdating pin, absent when there is nothing to say (the envelope's
 * omit-when-empty convention). Surfaced, never applied — moving a pin is a pipeline edit
 * (`pipelines_update`) and stays the caller's decision.
 */
class PipelinesGetTool(
    private val pipelines: PipelineRepository,
    private val usage: co.datapipelines.templates.TemplateUsageService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_get",
            description =
                "Get the full definition of a pipeline (the working version by default — the draft when unreleased " +
                    "edits exist, else the latest released version — or a specific version). Use this to read the " +
                    "pipeline body before executing or modifying it. The result carries the version, its status and " +
                    "body_hash — echo body_hash back as expected_hash on pipelines_update; a draft pointer is present " +
                    "when unreleased edits exist. When a node pins a template version that a newer released version " +
                    "outdates, an upgrade_available array names the node, the template and both versions — an offer " +
                    "to re-pin via pipelines_update, never an automatic change.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid", "description": "Pipeline ID."},
                    "version": {"type": "integer", "description": "Specific version. Defaults to the working version: the draft when one exists, else the latest released."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        val record = pipelines.findById(workspaceId, id) ?: throw McpNotFound.pipeline(id)
        // The explicit argument is validated and wins BEFORE any working-version lookup
        // (B3) — then the working version (§7): the draft if one exists, else
        // current_version. Derived, never stored — current_version keeps meaning
        // "latest released" everywhere else.
        val explicit = args.version()
        val version = explicit ?: pipelines.findDraftDetail(workspaceId, id)?.version ?: record.currentVersion
        return body(workspaceId, id, version)
    }

    private fun body(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): JsonNode {
        val json = pipelines.findVersionBody(workspaceId, id, version) ?: throw McpNotFound.pipelineVersion(id, version)
        val detail =
            pipelines.findVersionDetail(workspaceId, id, version) ?: throw McpNotFound.pipelineVersion(id, version)
        val tree = McpTools.readTree(json) as? com.fasterxml.jackson.databind.node.ObjectNode ?: error("body of $id is not an object")
        tree.put("version", detail.version)
        tree.put("status", detail.status.name)
        tree.put("body_hash", detail.bodyHash)
        val draftPointer = tree.putObject("draft")
        val draft = pipelines.findDraftDetail(workspaceId, id)
        if (draft != null) {
            draftPointer
                .put("version", draft.version)
                .put("body_hash", draft.bodyHash)
                .put("updated_at", draft.updatedAt?.toString() ?: "")
        } else {
            tree.remove("draft")
        }
        // 040 D5 rides this payload rather than a new endpoint: the upgrade signal is computed
        // from the SAME body being returned (the service walks its template pins against each
        // template's latest released version). Absent when no pin is outdated — omit-when-empty.
        val signal = usage.upgradeAvailable(workspaceId, json)
        if (signal.isNotEmpty()) {
            val array = tree.putArray("upgrade_available")
            signal.forEach {
                array
                    .addObject()
                    .put("node", it.node)
                    .put("template_id", it.templateId)
                    .put("pinned", it.pinned)
                    .put("latest_released", it.latestReleased)
            }
        }
        return tree
    }
}
