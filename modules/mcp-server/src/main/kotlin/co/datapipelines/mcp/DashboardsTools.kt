package co.datapipelines.mcp

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactValidation
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardDocument
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardReader
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.PipelineReleaseFacts
import co.datapipelines.visualization.VisualizationService
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * `dashboards_list` (mcp-server.md §6.2.55) — browse one level of the dashboard tree, the `visualizations_list`
 * shape. Permission: `dashboard.read`; a promoter's key sees the RELEASED dashboards whose every source pipeline
 * its pipeline lens admits.
 */
class DashboardsListTool(
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "dashboards_list",
            description =
                "List the dashboards of the key's pinned workspace by BROWSING one level of the name tree. A " +
                    "dashboard pins released pipelines as sources, maps their results onto pinned visualizations' " +
                    "inputs and lays them out; every row carries the id the other dashboards_* tools take. Names are " +
                    "FOLDER PATHS (acme/dashboards/revenue_overview): prefix:\"\" lists the roots. A promoter's key sees " +
                    "only released dashboards whose every source pipeline its lens admits.",
            schema = ArtifactTools.listSchema("dashboard"),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val view = lens.viewFor(ctx.principal).dashboards
        val limit = args.int("limit", default = ArtifactTools.DEFAULT_LIMIT, min = 1, max = ArtifactTools.MAX_LIMIT)
        val prefix = args.string("prefix")
        val folders = dashboards.listChildFolders(workspaceId, view, prefix)
        val loaded = dashboards.listChildren(workspaceId, view, prefix, 0, limit)
        return mapOf(
            "prefix" to (prefix ?: ""),
            "folders" to folders.map { ArtifactTools.folder(it, "dashboard_count") },
            "dashboards" to loaded.map { ArtifactTools.row(it) },
            "returned" to (folders.size + loaded.size),
        )
    }
}

/**
 * `dashboards_get` (§6.2.56) — the WORKING version by id with its RESOLVED dependency state: each pinned
 * visualization's version status and each source pipeline's release status and read-only verdict — every one read
 * through the caller's lens, one read per pin (bounded by the reader's 50 visualizations and the sources it declares),
 * a hidden or absent pin answering `status: null` alike. `last_refresh` is the runtime's (L2): null until it lands.
 * Permission: `dashboard.read`.
 */
class DashboardsGetTool(
    private val dashboards: DashboardService,
    private val visualizations: VisualizationService,
    private val pipelines: PipelineReleaseFacts,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "dashboards_get",
            description =
                "Read one dashboard by ID: the WORKING version's full document and lifecycle state (version, status, " +
                    "body_hash for an update's expected_hash, current_version), plus dependencies — each pinned " +
                    "visualization's version status and each source pipeline release's status and read_only verdict " +
                    "as they are NOW (a status of null: absent, or not visible to this key) — and last_refresh, null " +
                    "until the dashboard runtime ships. A dashboard of another workspace, or one the promoter lens " +
                    "hides, answers not-found.",
            schema = ArtifactTools.idSchema("dashboard", withHash = false),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        val view = lens.viewFor(ctx.principal)
        val loaded = dashboards.findWorking(workspaceId, view.dashboards, id) ?: throw notFound(id)
        return ArtifactTools.full(loaded, view.dashboards.isEverything) +
            mapOf(
                "dependencies" to dependencies(workspaceId, loaded.body, view),
                // The runtime's refresh record (L2's V43 tables) does not exist yet: the key is here so an agent's
                // parser is stable across the landing, and null says "never refreshed" honestly.
                "last_refresh" to null,
            )
    }

    private fun dependencies(
        workspaceId: UUID,
        body: DashboardBody,
        view: LensedView,
    ): Map<String, Any?> =
        mapOf(
            "visualizations" to
                body.visualizations.map { occurrence ->
                    val pin = occurrence.visualization
                    val pinned = visualizations.findVersionByName(workspaceId, view.visualizations, pin.name, pin.version)
                    mapOf(
                        "occurrence" to occurrence.name,
                        "name" to pin.name,
                        "version" to pin.version,
                        "status" to pinned?.detail?.status?.name,
                    )
                },
            "pipelines" to
                body.sources.map { source ->
                    val pin = source.pipeline
                    // The pipeline lens decides visibility BEFORE the fact is read: a hidden release reads as absent.
                    val fact = pin.takeIf { view.pipelines.admits(it.name) }?.let { pipelines.releaseOf(workspaceId, it) }
                    mapOf(
                        "source" to source.name,
                        "name" to pin.name,
                        "version" to pin.version,
                        "status" to fact?.status?.name,
                        "read_only" to fact?.readOnly,
                    )
                },
        )
}

/** `dashboards_create` (§6.2.57) — version 1 DRAFT, validated in full; the new-root confirmation. Permission: `dashboard.create`. */
class DashboardsCreateTool(
    private val dashboards: DashboardService,
    private val reader: DashboardReader,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "dashboards_create",
            description =
                "Create a dashboard: version 1 lands as a DRAFT (a human releases it; no tool releases anything). The " +
                    "document is validated in FULL against the pins as they are now — every source pipeline release " +
                    "RELEASED and read-only with its required parameters bound, every visualization input mapped to a " +
                    "source, one namespace for occurrences, groups, actions, controls and the set's parameters, every " +
                    "occurrence and control placed on the 12-column grid — and a refusal names every failing path. A " +
                    "pinned visualization or parameter set may still be a DRAFT at save. A NEW top-level folder is " +
                    "refused until you confirm it: reuse an existing root, or ask the person first and then pass " +
                    "confirm_new_root: true.",
            schema = DASHBOARD_DOCUMENT_SCHEMA_CREATE,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val name = args.requiredString("name")
        val view = lens.viewFor(ctx.principal)
        NewRootConfirmation.require(
            name = name,
            confirmed = args.boolean(NewRootConfirmation.ARG),
            code = DashboardErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION,
        ) { dashboards.listChildFolders(workspaceId, view.dashboards, null).map { it.segment } }
        val document = reader.readOrThrow(ArtifactTools.document(args, DashboardReader.DOCUMENT_KEYS))
        return ArtifactTools.written(dashboards.create(workspaceId, document, ctx.principal.userId, WriteSurface.MCP))
    }
}

/** `dashboards_update` (§6.2.58) — the hash-preconditioned draft write. Permission: `dashboard.update`. */
class DashboardsUpdateTool(
    private val dashboards: DashboardService,
    private val reader: DashboardReader,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "dashboards_update",
            description =
                "Edit a dashboard by ID: the first change after a release opens a DRAFT (copy-on-write); later updates " +
                    "overwrite that same draft in place. Requires expected_hash — the body_hash you read from " +
                    "dashboards_get (or the previous update's result); a mismatch is a 409 conflict: re-read and " +
                    "rebase. The whole document is sent and validated in full. A dashboard is never renamed.",
            schema = DASHBOARD_DOCUMENT_SCHEMA_UPDATE,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        val expectedHash = args.requiredString("expected_hash")
        val document = reader.readOrThrow(ArtifactTools.document(args, DashboardReader.DOCUMENT_KEYS))
        return ArtifactTools.written(dashboards.write(workspaceId, id, document, expectedHash, ctx.principal.userId, WriteSurface.MCP))
    }
}

/** `dashboards_purge_draft` (§6.2.59) — the hash-guarded draft purge. Permission: `dashboard.version.manage`. */
class DashboardsPurgeDraftTool(
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "dashboards_purge_draft",
            description =
                "Hard-delete a dashboard's DRAFT by ID (versioning §5.4): the draft row is deleted, never restorable. " +
                    "Requires expected_hash — the draft's body_hash from dashboards_get. If the draft is the ONLY " +
                    "version, the dashboard goes with it. A RELEASED version is never touched here. This is a write: " +
                    "it is audited as one.",
            schema = ArtifactTools.idSchema("dashboard", withHash = true),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        dashboards.findWorking(workspaceId, lens.viewFor(ctx.principal).dashboards, id) ?: throw notFound(id)
        dashboards.purgeDraft(workspaceId, id, args.requiredString("expected_hash"))
        return mapOf("id" to id.toString(), "purged" to true)
    }
}

/**
 * `dashboards_validate` (§6.2.60) — §3.2's rules against the dependencies' CURRENT state for the WORKING version, no
 * write: the agent's loop before a human releases. The verdict is the answer (`valid` and every failure), not an
 * error. Permission: `dashboard.update` — an author verb (owner ruling 2026-09-29): the validator reads pin statuses
 * unlensed, so a promoter's key is refused by the dispatcher before this runs (the L1a security pass's O5).
 */
class DashboardsValidateTool(
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "dashboards_validate",
            description =
                "Validate a dashboard by ID against its dependencies as they are NOW, without writing anything: the " +
                    "WORKING version (the draft, else the current release) is checked in full, and the answer is the " +
                    "verdict — valid, and every failure with its code, path and message. Run it after the pipelines, " +
                    "the parameter set or the visualizations a draft pins have changed, before asking a person to " +
                    "release. An authoring verb: a promoter's key is refused.",
            schema = ArtifactTools.idSchema("dashboard", withHash = false),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        val loaded = dashboards.findWorking(workspaceId, lens.viewFor(ctx.principal).dashboards, id) ?: throw notFound(id)
        val result =
            when (val validation = dashboards.validate(workspaceId, DashboardDocument(loaded.record.name, loaded.body))) {
                is ArtifactValidation.Valid -> ValidationResult.VALID
                is ArtifactValidation.Invalid -> validation.result
            }
        return mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "version" to loaded.detail.version,
            "body_hash" to loaded.detail.bodyHash,
            "valid" to result.isValid,
            "failures" to
                result.failures.map { mapOf("code" to it.code, "path" to it.path, "message" to it.message, "details" to it.details) },
        )
    }
}

private fun notFound(id: UUID): DatapipelinesException = ArtifactTools.notFound(DashboardErrorCodes.NOT_FOUND, "dashboard", id)

private const val DASHBOARD_DOCUMENT_PROPERTIES =
    """
    "name": {"type": "string", "description": "Folder-path name (2-10 segments, lower-case): acme/dashboards/revenue_overview. Never renamed."},
    "display_name": {"type": "string", "description": "Human label, 1-120 characters."},
    "description": {"type": "string", "description": "Optional; 2000 characters max."},
    "parameter_set": {"type": "object", "description": "Optional: {name, version} — the parameter set whose controls the dashboard shows."},
    "sources": {"type": "array", "items": {"type": "object"}, "description": "[{name, pipeline: {name, version}, parameters: {<pipeline parameter>: {parameter: <set parameter>} or {value: <literal>}}}] — each pinned release RELEASED and read-only; every required pipeline parameter bound."},
    "visualizations": {"type": "array", "items": {"type": "object"}, "description": "[{name, type: \"visualization\", visualization: {name, version}, inputs: {<visualization input>: {source: <source name>}}, timeout_seconds}] — at most 50; every named input of the pinned visualization mapped."},
    "groups": {"type": "array", "items": {"type": "object"}, "description": "Optional: [{name, type: \"group\", members: [<object or parameter names>]}]."},
    "actions": {"type": "array", "items": {"type": "object"}, "description": "Optional: [{name, type: \"refresh\", scope: all or targets, targets: [<visualization occurrence names>] (iff scope is targets), initial}]."},
    "action_controls": {"type": "array", "items": {"type": "object"}, "description": "Optional: [{name, type: \"action_control\", action: <action name>, label, parameter}] — parameter binds the action to a LEAF parameter's control."},
    "parameter_scopes": {"type": "object", "description": "Optional: {<set parameter>: [<group names>]} — a scope may not omit a group that consumes the parameter."},
    "parameter_state": {"type": "object", "description": "Optional: {dashboard: {visible, enabled}, parameters: {<name>: {visible, enabled}}}; each inherit, force_true or force_false."},
    "outgoing_overrides": {"type": "object", "description": "Optional: {<source name>: {<pipeline parameter>: {value: <literal>}}} — the value the pipeline receives whatever the control showed."},
    "layout": {"type": "object", "description": "{grid: [{name, x, y, w, h}], columns: 12, breakpoint_px, parameter_set: {position}, parameter_placements: {<parameter>: {group}}} — every occurrence and control placed exactly once (a group at most once)."},
    "timeouts": {"type": "object", "description": "Optional: {refresh_seconds} — at most 900."}
    """

private val DASHBOARD_DOCUMENT_SCHEMA_CREATE =
    """
    {
      "type": "object",
      "required": ["name", "display_name", "visualizations", "layout"],
      "properties": {
        $DASHBOARD_DOCUMENT_PROPERTIES,
        "confirm_new_root": {"type": "boolean", "description": "Set true ONLY after a person has agreed to a new top-level folder; the refusal names the roots that exist. 'test/' never needs it."}
      },
      "additionalProperties": false
    }
    """.trimIndent()

private val DASHBOARD_DOCUMENT_SCHEMA_UPDATE =
    """
    {
      "type": "object",
      "required": ["id", "expected_hash", "name", "display_name", "visualizations", "layout"],
      "properties": {
        "id": {"type": "string", "format": "uuid"},
        "expected_hash": {"type": "string", "description": "The body_hash of the version this edit is based on. A mismatch is a 409 conflict; re-read and rebase."},
        $DASHBOARD_DOCUMENT_PROPERTIES
      },
      "additionalProperties": false
    }
    """.trimIndent()

/** The dashboard tools in §6.1's order. */
internal fun dashboardTools(
    dashboards: DashboardService,
    visualizations: VisualizationService,
    pipelines: PipelineReleaseFacts,
    reader: DashboardReader,
    lens: PromoterLens,
): List<McpTool> =
    listOf(
        DashboardsListTool(dashboards, lens),
        DashboardsGetTool(dashboards, visualizations, pipelines, lens),
        DashboardsCreateTool(dashboards, reader, lens),
        DashboardsUpdateTool(dashboards, reader),
        DashboardsPurgeDraftTool(dashboards, lens),
        DashboardsValidateTool(dashboards, lens),
    )
