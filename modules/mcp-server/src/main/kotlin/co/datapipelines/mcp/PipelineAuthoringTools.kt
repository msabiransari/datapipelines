package co.datapipelines.mcp

import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/**
 * The save path shared by `pipelines_create` and `pipelines_update` (§6.2.4, §6.2.5).
 *
 * Both run the **universal save-time validation** (pipeline-contract §2.8): deserialize → §12
 * validate → store. Nothing invalid ever reaches the database, and a validation failure comes
 * back as a tool result with `isError: true` carrying the validation code (§9.2) — the agent
 * fixes and retries, no partial creation is possible.
 */
internal class PipelineSaveSupport(
    private val deserializer: PipelineDeserializer,
    private val validator: PipelineValidator,
    private val serializer: PipelineSerializer,
) {
    /** Args → validated [Pipeline] + its canonical body JSON. Validates within [workspaceId]. */
    fun validated(
        args: McpArguments,
        workspaceId: UUID,
    ): Pair<Pipeline, String> {
        val pipeline = validator.validateOrThrow(deserializer.readOrThrow(bodyJson(args)), workspaceId)
        return pipeline to serializer.write(pipeline)
    }

    /**
     * Assembles the §3 body from the tool arguments.
     *
     * `schema_version` is supplied by the server rather than taken from the agent: §6.2.4's input
     * schema has no such property (and is `additionalProperties: false`), so v1 is the only
     * version an MCP-authored pipeline can be.
     */
    private fun bodyJson(args: McpArguments): String {
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
        version: co.datapipelines.pipeline.PipelineVersionDetail? = null,
        draft: co.datapipelines.pipeline.PipelineVersionDetail? = null,
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

    companion object {
        /** The §6.2.4 `nodes` description, restated verbatim by both tools. */
        const val NODES_DESCRIPTION: String =
            "Pipeline nodes. Each node has type (DQL/DML/DDL/PIPELINE), source, template ref, depends_on array, and — for DQL " +
                "only — an optional output block. Omitting output on a DQL node means output.target='caller'; at most " +
                "one node per pipeline may resolve to 'caller'. A node whose data downstream nodes query must declare " +
                "output.target='tempdb' with a table name explicitly. A PIPELINE node instead carries a pipeline ref " +
                "{name, version} pinning an existing pipeline version to execute as a child execution, an optional " +
                "parameters map (typed literals, or '\${parent_param}' to pass a parent parameter through), and an " +
                "optional output block allowed only when the pinned child has a caller node; it declares neither source " +
                "nor template."

        /** The §6.2.4 `parameters` description, restated verbatim by both tools. */
        const val PARAMETERS_DESCRIPTION: String =
            "Declared pipeline parameters (name -> {type, required, default, description}). This is the ONLY parameter " +
                "declaration point: the full parameter map, defaults applied, is the render context for every template " +
                "the pipeline references."
    }
}

/** `pipelines_create` (mcp-server.md §6.2.4). Scope: `author`. */
class PipelinesCreateTool(
    private val pipelines: PipelineRepository,
    deserializer: PipelineDeserializer,
    validator: PipelineValidator,
    serializer: PipelineSerializer = PipelineSerializer(),
) : McpTool {
    private val support = PipelineSaveSupport(deserializer, validator, serializer)

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
        val workspaceId = ctx.principal.requireWorkspace().id
        val (pipeline, body) = support.validated(args, workspaceId)
        val record = pipelines.create(workspaceId, NewPipeline.from(pipeline, ownerId = ctx.principal.userId), body, ctx.principal.userId)
        // Creation is not modification (§3.2): v1 lands RELEASED and immediately executable —
        // read back the row the database stored so the response carries its real hash.
        val version = pipelines.findCurrentVersionDetail(workspaceId, record.id)
        return support.response(record, body, version)
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
                "parameters": {"type": "object", "description": "${PipelineSaveSupport.PARAMETERS_DESCRIPTION}"},
                "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
                "nodes": {"type": "array", "description": "${PipelineSaveSupport.NODES_DESCRIPTION}"}
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
    private val pipelines: PipelineRepository,
    private val drafts: co.datapipelines.pipeline.PipelineDraftService,
    deserializer: PipelineDeserializer,
    validator: PipelineValidator,
    serializer: PipelineSerializer = PipelineSerializer(),
) : McpTool {
    private val support = PipelineSaveSupport(deserializer, validator, serializer)

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
        val workspaceId = ctx.principal.requireWorkspace().id
        val id: UUID = args.requiredUuid("id")
        val expectedHash = args.requiredString("expected_hash")
        val (pipeline, body) = support.validated(args, workspaceId)
        val written =
            drafts.write(
                workspaceId = workspaceId,
                pipelineId = id,
                pipeline = pipeline,
                canonical = body,
                expectedHash = expectedHash,
                actor = ctx.principal.userId,
            )
        return support.response(written.record, body, written.version, written.version)
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
                "parameters": {"type": "object", "description": "${PipelineSaveSupport.PARAMETERS_DESCRIPTION}"},
                "settings": {"type": "object", "description": "Pipeline-level execution settings (e.g., tempdb engine)."},
                "nodes": {"type": "array", "description": "${PipelineSaveSupport.NODES_DESCRIPTION}"}
              },
              "additionalProperties": false
            }
            """.trimIndent()
    }
}
