package co.datapipelines.mcp

import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionDetail
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * The **wire shapes** `pipelines_create` and `pipelines_update` share (§6.2.4, §6.2.5): the §3
 * body assembled from the tool arguments, the result payload, and the two long property
 * descriptions both input schemas restate verbatim.
 *
 * This is what is left of `PipelineSaveSupport`, which 056 deleted. Its `validated()` — the
 * deserialize → §12 validate → canonical triple — was the MCP copy of the identical triple
 * `PipelinesController` carried (ARCH-AUDIT S2/D1); that rule is [PipelineService.validate] now,
 * and both surfaces call it. What remained here is genuinely MCP-shaped and belongs on this side
 * of the boundary: a service must not know what a tool result looks like.
 */
internal object PipelineToolPayloads {
    /**
     * Assembles the §3 body from the tool arguments.
     *
     * `schema_version` is supplied by the server rather than taken from the agent: §6.2.4's input
     * schema has no such property (and is `additionalProperties: false`), so v1 is the only
     * version an MCP-authored pipeline can be.
     */
    fun bodyJson(args: McpArguments): String {
        val body =
            buildMap<String, Any?> {
                put("schema_version", Pipeline.SUPPORTED_SCHEMA_VERSION)
                put("name", args.requiredString("name"))
                put("display_name", args.requiredString("display_name"))
                put("description", args.string("description") ?: "")
                args.objectArg("parameters")?.let { put("parameters", it) }
                args.objectArg("settings")?.let { put("settings", it) }
                put("nodes", args.requiredList("nodes"))
            }
        return ExecutorJson.write(body)
    }

    /**
     * The create/update response: server-owned metadata plus the stored body (§6.2.4), with
     * the version's lifecycle state since the draft round — an agent must be able to SEE
     * that its update landed as a DRAFT and read the hash to carry into its next write
     * (versioning §7/§12).
     */
    fun response(
        record: PipelineRecord,
        body: String,
        version: PipelineVersionDetail? = null,
        draft: PipelineVersionDetail? = null,
    ): Map<String, Any?> =
        buildMap<String, Any?> {
            put("id", record.id.toString())
            put("name", record.name)
            put("display_name", record.displayName)
            put("description", record.description)
            put("owner_id", record.ownerId.toString())
            put("version", version?.version ?: record.currentVersion)
            put("current_version", record.currentVersion)
            put("status", version?.status?.name ?: "RELEASED")
            put("body_hash", version?.bodyHash ?: "")
            put("created_at", record.createdAt)
            put("updated_at", record.updatedAt)
            put("body", McpTools.readTree(body))
            draft?.let {
                put(
                    "draft",
                    mapOf(
                        "version" to it.version,
                        "body_hash" to it.bodyHash,
                        "updated_at" to (it.updatedAt?.toString() ?: ""),
                    ),
                )
            }
        }

    /** The §6.2.4 `nodes` description, restated verbatim by both tools. */
    const val NODES_DESCRIPTION: String =
        "Pipeline nodes. Each node has type (DQL/DML/DDL/PIPELINE), source, template ref, depends_on array, and — for DQL " +
            "only — an optional output block. Omitting output on a DQL node means output.target='caller'; at most " +
            "one node per pipeline may resolve to 'caller'. A node whose data downstream nodes query must declare " +
            "output.target='tempdb' with a table name explicitly. A PIPELINE node instead carries a pipeline ref " +
            "{name, version} pinning an existing pipeline version to execute as a child execution, an optional " +
            "parameters map (typed literals, or '${'$'}{parent_param}' to pass a parent parameter through), and an " +
            "optional output block allowed only when the pinned child has a caller node; it declares neither source " +
            "nor template."

    /** The §6.2.4 `parameters` description, restated verbatim by both tools. */
    const val PARAMETERS_DESCRIPTION: String =
        "Declared pipeline parameters (name -> {type, required, default, description}). This is the ONLY parameter " +
            "declaration point: the full parameter map, defaults applied, is the render context for every template " +
            "the pipeline references."
}

/** `pipelines_create` (mcp-server.md §6.2.4). Scope: `author`. */
class PipelinesCreateTool(
    private val pipelines: PipelineService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_create",
            description =
                "Create a new pipeline. The body must satisfy the Pipeline Contract: nodes must form a DAG; at most one " +
                    "DQL node may resolve to output.target='caller' (a node that omits its output block resolves to " +
                    "'caller' by default); zero caller nodes is legal for pure write-back pipelines; all datasource " +
                    "references must exist in this environment; all template references must exist and dry-render " +
                    "against the declared parameters. Returns the created pipeline with server-assigned id and version 1.",
            schema = SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        // The authoring capability check (versioning §5.5), the §12 validation and the write are
        // all PipelineService.create's — the same call PUT /pipelines makes.
        val saved =
            pipelines.create(
                ctx.principal.requireWorkspace().id,
                PipelineToolPayloads.bodyJson(args),
                ctx.principal.userId,
            )
        return PipelineToolPayloads.response(saved.record, saved.bodyJson, saved.version)
    }

    private companion object {
        val SCHEMA =
            """
            {
              "type": "object",
              "required": ["name", "display_name", "nodes"],
              "properties": {
                "name": {"type": "string", "pattern": "^[a-z0-9_]+${'$'}"},
                "display_name": {"type": "string"},
                "description": {"type": "string"},
                "parameters": {"type": "object", "description": "${PipelineToolPayloads.PARAMETERS_DESCRIPTION}"},
                "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
                "nodes": {"type": "array", "description": "${PipelineToolPayloads.NODES_DESCRIPTION}"}
              },
              "additionalProperties": false
            }
            """.trimIndent()
    }
}

/**
 * `pipelines_update` (mcp-server.md §6.2.5). Scope: `author`.
 *
 * "Same input as `pipelines_create` plus required `id` and `expected_hash`. Returns the new version. Same save-time
 * validation applies."
 *
 * Since the draft round (versioning §3.2/§7) an update always writes the DRAFT branch: the
 * first write after a release copies the released version to a draft, later writes
 * overwrite that one draft in place — an agent iterating produces ONE draft row it keeps
 * overwriting, not a version per save. **The agent never releases** (D4): leave the draft
 * for a human to review and release from the UI. `expected_hash` is the `body_hash` the
 * caller read (from `pipelines_get` or a previous update's result) — the precondition that
 * makes two writers (two agents, an agent and a human, two tabs) never silently overwrite
 * each other; on `pipeline.version.conflict`, re-read and rebase, never retry blindly.
 */
class PipelinesUpdateTool(
    private val pipelines: PipelineService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_update",
            description =
                "Update an existing pipeline by writing its DRAFT — the first update after a release creates the draft " +
                    "(copy-on-write); later updates overwrite that same draft in place. Requires expected_hash: the " +
                    "body_hash you read (pipelines_get, or a previous update's result) for the version you based your " +
                    "edit on. The result carries status='DRAFT' — your work is NOT released; a human releases it from " +
                    "the UI. On pipeline.version.conflict someone modified it after you loaded it: re-read, rebase, " +
                    "retry; never retry blindly.",
            schema = SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val id: UUID = args.requiredUuid("id")
        val saved =
            pipelines.update(
                workspaceId = ctx.principal.requireWorkspace().id,
                pipelineId = id,
                bodyJson = PipelineToolPayloads.bodyJson(args),
                expectedHash = args.requiredString("expected_hash"),
                actor = ctx.principal.userId,
            )
        // A no-op update (versioning §5.1) reports status RELEASED and carries NO draft
        // pointer — the body already equals the released one, nothing was opened. That branch
        // is the service's now; PUT /pipelines/{id} spelled it identically before 056.
        return PipelineToolPayloads.response(saved.record, saved.bodyJson, saved.version, saved.draft)
    }

    private companion object {
        val SCHEMA =
            """
            {
              "type": "object",
              "required": ["id", "expected_hash", "name", "display_name", "nodes"],
              "properties": {
                "id": {"type": "string", "format": "uuid", "description": "Pipeline to update."},
                "expected_hash": {"type": "string", "description": "The body_hash of the version this edit is based on — pipelines_get or the previous update's result. A mismatch is a 409 conflict; re-read and rebase."},
                "name": {"type": "string", "pattern": "^[a-z0-9_]+${'$'}"},
                "display_name": {"type": "string"},
                "description": {"type": "string"},
                "parameters": {"type": "object", "description": "${PipelineToolPayloads.PARAMETERS_DESCRIPTION}"},
                "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
                "nodes": {"type": "array", "description": "${PipelineToolPayloads.NODES_DESCRIPTION}"}
              },
              "additionalProperties": false
            }
            """.trimIndent()
    }
}
