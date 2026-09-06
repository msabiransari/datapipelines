package co.datapipelines.mcp

import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * The `prefix` argument's description, shared verbatim by `pipelines_list` and `templates_list`
 * (§6.2.1, §6.2.6) — 067's convention is one convention, so an agent must not read two
 * different accounts of it.
 */
internal const val PREFIX_ARG_DESC: String =
    "Browse ONE level of the folder tree instead of listing flat: returns that prefix's direct sub-folders " +
        "(with counts) and its direct children. An empty string is the root. Use this to discover which roots and " +
        "folders exist; use q to search across full paths."

/**
 * `pipelines_list` (mcp-server.md §6.2.1). Scope: `read`.
 *
 * The owner/datasource/`q` filter is [PipelineService.list] (056, ARCH-AUDIT S2/D2) — this tool
 * and `GET /pipelines` had separate implementations of the same three rules. What stays here is
 * the tool's own contract: its `limit` truncation and its metadata projection.
 *
 * ## Two presentations, chosen by `prefix` (067)
 *
 * Pipeline names are paths and folders are name prefixes, so an agent needs to BROWSE as well
 * as search — and until 067 it could do neither: `q` is a substring match over three columns,
 * which answers "find me something" and cannot answer "what roots exist here?".
 *
 * - **`prefix` absent** → the flat listing under `owner`/`datasource`/`q`, unchanged.
 * - **`prefix` present** (`""` is the ROOT) → **one level** of the tree: that prefix's direct
 *   sub-folders with their subtree counts, and its direct pipeline leaves. Never a subtree,
 *   never the whole list — the same one-level-per-request contract the explorer's partial
 *   serves, with the same payload shape.
 *
 * `owner`, `datasource` and `q` are ignored while `prefix` is present: browse and search are
 * different presentations (template-hierarchy-design §9.2) and a folder listing is
 * unambiguously a browse.
 */
class PipelinesListTool(
    private val pipelines: PipelineService,
    private val repository: PipelineRepository,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_list",
            description =
                "List the pipelines of the key's pinned workspace, filtered by owner, datasource, or text search. Returns " +
                    "metadata (id, name, display_name, description, version, updated_at) — not the full body. Use " +
                    "pipelines_get for the body; pipelines in other workspaces are absent from this listing and " +
                    "resolve as not-found by id. Pipeline names are FOLDER PATHS (finance/payments/daily_settlement): " +
                    "pass prefix to BROWSE one level of that tree — prefix:\"\" lists the roots, prefix:\"finance\" lists " +
                    "what is directly under finance — and q to SEARCH across full paths. Start with prefix:\"\" to see " +
                    "which roots this workspace already uses before creating a pipeline under a new one.",
            schema =
                """
                {
                  "type": "object",
                  "properties": {
                    "owner": {"type": "string", "description": "Filter by owner user ID."},
                    "datasource": {"type": "string", "description": "Filter by datasource name."},
                    "q": {"type": "string", "description": "Full-text search on name and description. Searches across full paths; use prefix to browse instead."},
                    "prefix": {"type": "string", "description": "$PREFIX_ARG_DESC"},
                    "limit": {"type": "integer", "default": 50, "maximum": 200}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val limit = args.int("limit", default = DEFAULT_LIMIT, min = 1, max = MAX_LIMIT)
        val workspaceId = ctx.principal.requireWorkspace().id
        // `has` and not `string`: `prefix: ""` is PRESENT and means the root, while
        // `string("prefix")` normalizes blank to null. The distinction is the whole browse
        // contract — absent is "flat list", empty is "the tree's top level".
        if (args.has("prefix")) return level(workspaceId, args.string("prefix"), limit)
        return pipelines
            .list(
                workspaceId = workspaceId,
                ownerId = args.uuid("owner"),
                datasourceName = args.string("datasource"),
                query = args.string("q"),
            ).asSequence()
            .take(limit)
            .map { it.toMetadata() }
            .toList()
    }

    /**
     * ONE level of the folder tree, in the same shape the explorer's partial renders from.
     *
     * A prefix that is not a legal pipeline name cannot name a real folder, so it answers an
     * ordinary EMPTY level rather than an error — the same rule the templates browser settled
     * on. It is not a security boundary (the prefix is bound and LIKE-escaped in the
     * repository); it is what keeps an arbitrary-length string from becoming an
     * arbitrary-length pattern match.
     */
    private fun level(
        workspaceId: java.util.UUID,
        prefix: String?,
        limit: Int,
    ): Map<String, Any?> {
        if (prefix != null && !PipelineNameGrammar.matchesPrefix(prefix)) return emptyLevel(prefix)
        val level = repository.listFolder(workspaceId, prefix, offset = 0, limit = limit)
        return mapOf(
            "prefix" to prefix.orEmpty(),
            "folders" to level.folders.map { mapOf("path" to it.path, "segment" to it.segment, "pipeline_count" to it.pipelineCount) },
            "pipelines" to level.pipelines.map { it.toMetadata() },
            "total" to level.total,
            "has_more" to level.hasMore,
        )
    }

    private fun emptyLevel(prefix: String): Map<String, Any?> =
        mapOf(
            "prefix" to prefix,
            "folders" to emptyList<Map<String, Any?>>(),
            "pipelines" to emptyList<Map<String, Any?>>(),
            "total" to 0,
            "has_more" to false,
        )

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
    private val pipelines: PipelineService,
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
        val record = pipelines.findRecord(workspaceId, id) ?: throw McpNotFound.pipeline(id)
        // The explicit argument is validated and wins BEFORE any working-version lookup
        // (B3) — then the working version (§7): the draft if one exists, else
        // current_version. Derived, never stored — current_version keeps meaning
        // "latest released" everywhere else.
        val explicit = args.version()
        val version = explicit ?: pipelines.findDraft(workspaceId, id)?.version ?: record.currentVersion
        return body(workspaceId, record, version)
    }

    private fun body(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
    ): JsonNode {
        val id = record.id
        val loaded = pipelines.findVersion(workspaceId, record, version) ?: throw McpNotFound.pipelineVersion(id, version)
        val json = loaded.bodyJson
        val detail = loaded.version
        val tree = McpTools.readTree(json) as? com.fasterxml.jackson.databind.node.ObjectNode ?: error("body of $id is not an object")
        tree.put("version", detail.version)
        tree.put("status", detail.status.name)
        tree.put("body_hash", detail.bodyHash)
        val draftPointer = tree.putObject("draft")
        val draft = pipelines.findDraft(workspaceId, id)
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
