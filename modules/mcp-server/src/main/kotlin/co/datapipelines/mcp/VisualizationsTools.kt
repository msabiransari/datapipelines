package co.datapipelines.mcp

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactFolder
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * The shape both artifact families' tools share (mcp-server.md §6.2.50–.60; the `ParameterSetsTools` mould): the
 * document assembled from the call's arguments (only the READER's own document keys — the reader then judges every
 * key, type and bound, exactly as REST's does), the family's not-found, the list page's cap, a row's lifecycle state.
 */
internal object ArtifactTools {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    /** The document the call describes: the arguments whose names are the reader's document keys, as a JSON tree. */
    fun document(
        args: McpArguments,
        documentKeys: Set<String>,
    ): JsonNode = ArtifactJson.mapper.valueToTree(args.rawMap().filterKeys { it in documentKeys })

    /** An absent, foreign or lens-hidden artifact — the family's `*.not_found`, never a permission error (§11A.1). */
    fun notFound(
        code: String,
        noun: String,
        id: UUID,
    ): DatapipelinesException =
        DatapipelinesException(code, "${noun.replaceFirstChar { it.uppercase() }} $id does not exist.", mapOf("id" to id.toString()))

    /** What a write answers: the row it wrote. */
    fun <B : Any> written(loaded: ArtifactVersion<B>): Map<String, Any?> =
        mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "version" to loaded.detail.version,
            "status" to loaded.detail.status.name,
            "body_hash" to loaded.detail.bodyHash,
        )

    /** A read's lifecycle state and document; the draft pointer only under the whole view (a promoter never sees one). */
    fun <B : Any> full(
        loaded: ArtifactVersion<B>,
        wholeView: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "display_name" to loaded.record.displayName,
            "description" to loaded.record.description,
            "version" to loaded.detail.version,
            "status" to loaded.detail.status.name,
            "body_hash" to loaded.detail.bodyHash,
            "current_version" to loaded.record.currentVersion,
            "draft" to
                loaded.detail
                    .takeIf { wholeView && it.status == PipelineVersionStatus.DRAFT }
                    ?.let { mapOf("version" to it.version, "body_hash" to it.bodyHash, "updated_at" to (it.updatedAt?.toString() ?: "")) },
            "document" to ArtifactJson.mapper.valueToTree<JsonNode>(loaded.body),
        )

    /** One listing row: metadata, never the body. */
    fun <B : Any> row(loaded: ArtifactVersion<B>): Map<String, Any?> =
        mapOf(
            "id" to loaded.record.id.toString(),
            "name" to loaded.record.name,
            "display_name" to loaded.record.displayName,
            "version" to loaded.detail.version,
            "status" to loaded.detail.status.name,
            "current_version" to loaded.record.currentVersion,
        )

    fun folder(
        folder: ArtifactFolder,
        countKey: String,
    ): Map<String, Any?> = mapOf("path" to folder.path, "segment" to folder.segment, countKey to folder.count)

    /** The list tools' shared schema: one level of the name tree, capped like the set tools. */
    fun listSchema(noun: String): String =
        """
        {
          "type": "object",
          "properties": {
            "prefix": {"type": "string", "description": "Browse ONE level of the $noun name tree at this prefix. Empty string (or absent) browses the roots."},
            "limit": {"type": "integer", "default": $DEFAULT_LIMIT, "maximum": $MAX_LIMIT}
          }
        }
        """.trimIndent()

    /** A get, validate or purge schema: the id (and, for the purge, the hash). */
    fun idSchema(
        noun: String,
        withHash: Boolean,
    ): String {
        val id = """"id": {"type": "string", "format": "uuid", "description": "The $noun's id (${noun}s_list returns it)."}"""
        val properties = if (withHash) "$id, $EXPECTED_HASH_PROPERTY" else id
        val required = if (withHash) """["id", "expected_hash"]""" else """["id"]"""
        return """{"type": "object", "required": $required, "properties": {$properties}, "additionalProperties": false}"""
    }

    private const val EXPECTED_HASH_PROPERTY =
        """"expected_hash": {"type": "string", "description": "The draft's body_hash. A mismatch is a 409 conflict; re-read first."}"""
}

/**
 * `visualizations_list` (mcp-server.md §6.2.50) — browse one level of the visualization tree, the
 * `parameter_sets_list` shape, each row carrying `used_by`: the dashboards that pin it (D30) — through the caller's
 * lens, so a promoter sees only her admitted dashboards. Permission: `visualization.read`.
 */
class VisualizationsListTool(
    private val visualizations: VisualizationService,
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_list",
            description =
                "List the visualizations of the key's pinned workspace by BROWSING one level of the name tree. A " +
                    "visualization is a versioned chart, table or KPI bound to named inputs, reusable across dashboards; " +
                    "every row carries the id the other visualizations_* tools take, and used_by — the dashboards that " +
                    "pin it (name@version). Names are FOLDER PATHS (acme/visualizations/monthly_revenue): prefix:\"\" " +
                    "lists the roots, prefix:\"acme\" what is directly under acme; folders and visualizations are " +
                    "answered apart. A promoter's key sees only the visualizations a released dashboard its lens admits pins.",
            schema = ArtifactTools.listSchema("visualization"),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val view = lens.viewFor(ctx.principal)
        val limit = args.int("limit", default = ArtifactTools.DEFAULT_LIMIT, min = 1, max = ArtifactTools.MAX_LIMIT)
        val prefix = args.string("prefix")
        val folders = visualizations.listChildFolders(workspaceId, view.visualizations, prefix)
        val loaded = visualizations.listChildren(workspaceId, view.visualizations, prefix, 0, limit)
        // #331 — used_by for the WHOLE page in ONE call: the batched pins answer, never a containment
        // scan per row. The map lookup answers absent names with the empty list the per-row read gave.
        val usedBy = dashboards.pinnedByAll(workspaceId, view.dashboards, loaded.map { it.record.name })
        return mapOf(
            "prefix" to (prefix ?: ""),
            "folders" to folders.map { ArtifactTools.folder(it, "visualization_count") },
            "visualizations" to
                loaded.map {
                    ArtifactTools.row(it) + ("used_by" to usedBy.getOrDefault(it.record.name, emptyList()))
                },
            "returned" to (folders.size + loaded.size),
        )
    }
}

/**
 * `visualizations_get` (§6.2.51) — the WORKING version by id: the document, the lifecycle state (the draft pointer
 * under the whole view only) and `used_by`. Permission: `visualization.read`; a foreign or lens-hidden one is
 * `visualization.not_found`.
 */
class VisualizationsGetTool(
    private val visualizations: VisualizationService,
    private val dashboards: DashboardService,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_get",
            description =
                "Read one visualization by ID: the WORKING version's full document (renderer, inputs, transform, " +
                    "config, bindings, presentation, tests) and its lifecycle state — version and status name the " +
                    "returned row (the DRAFT when one exists, else the current release), current_version the latest " +
                    "release, body_hash what an update's expected_hash carries — plus used_by, the dashboards that pin " +
                    "it. A visualization of another workspace, or one the promoter lens hides, answers not-found.",
            schema = ArtifactTools.idSchema("visualization", withHash = false),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        val view = lens.viewFor(ctx.principal)
        val loaded = visualizations.findWorking(workspaceId, view.visualizations, id) ?: throw notFound(id)
        return ArtifactTools.full(loaded, view.visualizations.isEverything) +
            ("used_by" to dashboards.pinnedBy(workspaceId, view.dashboards, loaded.record.name))
    }
}

/**
 * `visualizations_create` (§6.2.52) — version 1 lands as a DRAFT, validated in full; the 094 new-root confirmation.
 * Permission: `visualization.create`.
 */
class VisualizationsCreateTool(
    private val visualizations: VisualizationService,
    private val reader: VisualizationReader,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_create",
            description =
                "Create a visualization: version 1 lands as a DRAFT (a human releases it; no tool releases anything). " +
                    "The document is validated in FULL at save — the renderer's configuration schema, every binding " +
                    "path (it must already exist in config) and column, the transform pin's contract against the " +
                    "inputs, the test cases — and a refusal names every failing path. A NEW top-level folder is refused " +
                    "until you confirm it: reuse an existing root, or ask the person first and then pass " +
                    "confirm_new_root: true. Returns the id the other tools take and the body_hash an update needs.",
            schema = VISUALIZATION_DOCUMENT_SCHEMA_CREATE,
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
            code = VisualizationErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION,
        ) { visualizations.listChildFolders(workspaceId, view.visualizations, null).map { it.segment } }
        val document = reader.readOrThrow(ArtifactTools.document(args, VisualizationReader.DOCUMENT_KEYS))
        return ArtifactTools.written(visualizations.create(workspaceId, document, ctx.principal.userId, WriteSurface.MCP))
    }
}

/**
 * `visualizations_update` (§6.2.53) — the hash-preconditioned draft write, the `parameter_sets_update` twin.
 * Permission: `visualization.update`.
 */
class VisualizationsUpdateTool(
    private val visualizations: VisualizationService,
    private val reader: VisualizationReader,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_update",
            description =
                "Edit a visualization by ID: the first change after a release opens a DRAFT (copy-on-write); later " +
                    "updates overwrite that same draft in place. Requires expected_hash — the body_hash you read from " +
                    "visualizations_get (or the previous update's result) for the version this edit is based on; a " +
                    "mismatch is a 409 conflict: re-read and rebase. The whole document is sent and validated in full. " +
                    "A visualization is never renamed: a document naming another is refused.",
            schema = VISUALIZATION_DOCUMENT_SCHEMA_UPDATE,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        val expectedHash = args.requiredString("expected_hash")
        val document = reader.readOrThrow(ArtifactTools.document(args, VisualizationReader.DOCUMENT_KEYS))
        return ArtifactTools.written(visualizations.write(workspaceId, id, document, expectedHash, ctx.principal.userId, WriteSurface.MCP))
    }
}

/**
 * `visualizations_purge_draft` (§6.2.54) — the bounded 107-style self-service verb (versioning §5.4): hash-guarded,
 * drafts only, never a version a live dashboard pins. Permission: `visualization.version.manage`.
 */
class VisualizationsPurgeDraftTool(
    private val visualizations: VisualizationService,
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_purge_draft",
            description =
                "Hard-delete a visualization's DRAFT by ID (versioning §5.4): the draft row is deleted, never " +
                    "restorable. Requires expected_hash — the draft's body_hash from visualizations_get — so you purge " +
                    "the draft you looked at. If the draft is the ONLY version, the visualization goes with it. A draft " +
                    "a live dashboard pins is refused (visualization.version.pinned, naming the dashboards). A RELEASED " +
                    "version is never touched here. This is a write: it is audited as one.",
            schema = ArtifactTools.idSchema("visualization", withHash = true),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredUuid("id")
        visualizations.findWorking(workspaceId, lens.viewFor(ctx.principal).visualizations, id) ?: throw notFound(id)
        visualizations.purgeDraft(workspaceId, id, args.requiredString("expected_hash"))
        return mapOf("id" to id.toString(), "purged" to true)
    }
}

private fun notFound(id: UUID): DatapipelinesException = ArtifactTools.notFound(VisualizationErrorCodes.NOT_FOUND, "visualization", id)

/** The document properties both write tools declare — the reader's DOCUMENT_KEYS, each described for an agent. */
private const val VISUALIZATION_DOCUMENT_PROPERTIES =
    """
    "name": {"type": "string", "description": "Folder-path name (2-10 segments, lower-case): acme/visualizations/monthly_revenue. Never renamed."},
    "display_name": {"type": "string", "description": "Human label, 1-120 characters."},
    "description": {"type": "string", "description": "Optional; 2000 characters max."},
    "renderer": {"type": "object", "description": "{kind, version}: kind is plotly, table or kpi (html and svg are reserved and refused); version is the renderer major the host must provide, as a string (\"4\")."},
    "inputs": {"type": "object", "description": "Named input contracts: {<input>: {columns: [{name, type, nullable}]}}; type is a LogicalType (STRING, INTEGER, DECIMAL, DATE, TIMESTAMP, BOOLEAN, ...). At most 8 inputs, 256 columns each."},
    "transform": {"type": "object", "description": "Optional: {template: {name, version}, inputs: {<contract input>: <visualization input>}} — a pinned TRANSFORM template whose declared input columns equal the named inputs' columns. Absent: the renderer binds one input directly."},
    "config": {"type": "object", "description": "The renderer's native configuration, stored verbatim and validated against its schema (plotly: a non-empty data array of supported traces). At most 262,144 bytes."},
    "bindings": {"type": "object", "description": "Optional: {<path into config, e.g. data[0].y>: <column of the transform's output, or of the single input>}. Each path must already exist in config; its value is replaced by the column's values at render."},
    "presentation": {"type": "object", "description": "Optional: {title, tokens} — tokens name theme tokens, never colours."},
    "tests": {"type": "object", "description": "{cases: [{name, fixtures: {<input>: [rows]}, assertions: [{kind}]}]}; kind is rendered, trace_count (with equals), no_console_errors, text_visible (with text), no_data or value_visible (with text). A release needs at least one case."}
    """

private val VISUALIZATION_DOCUMENT_SCHEMA_CREATE =
    """
    {
      "type": "object",
      "required": ["name", "display_name", "renderer", "inputs", "config"],
      "properties": {
        $VISUALIZATION_DOCUMENT_PROPERTIES,
        "confirm_new_root": {"type": "boolean", "description": "Set true ONLY after a person has agreed to a new top-level folder; the refusal names the roots that exist. 'test/' never needs it."}
      },
      "additionalProperties": false
    }
    """.trimIndent()

private val VISUALIZATION_DOCUMENT_SCHEMA_UPDATE =
    """
    {
      "type": "object",
      "required": ["id", "expected_hash", "name", "display_name", "renderer", "inputs", "config"],
      "properties": {
        "id": {"type": "string", "format": "uuid"},
        "expected_hash": {"type": "string", "description": "The body_hash of the version this edit is based on. A mismatch is a 409 conflict; re-read and rebase."},
        $VISUALIZATION_DOCUMENT_PROPERTIES
      },
      "additionalProperties": false
    }
    """.trimIndent()

/** The visualization tools in §6.1's order. */
internal fun visualizationTools(
    visualizations: VisualizationService,
    dashboards: DashboardService,
    reader: VisualizationReader,
    lens: PromoterLens,
): List<McpTool> =
    listOf(
        VisualizationsListTool(visualizations, dashboards, lens),
        VisualizationsGetTool(visualizations, dashboards, lens),
        VisualizationsCreateTool(visualizations, reader, lens),
        VisualizationsUpdateTool(visualizations, reader),
        VisualizationsPurgeDraftTool(visualizations, lens),
    )
