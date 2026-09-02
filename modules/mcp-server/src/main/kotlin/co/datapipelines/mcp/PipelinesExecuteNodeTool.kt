package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SqlRunner
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.templates.NodeSqlResolution
import co.datapipelines.templates.NodeSqlResolver
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.util.UUID

/** §6.2.20 — the decoded-row cap for a node run, the D2 shape (50, fixed: no limit argument). */
private const val NODE_RUN_ROW_CAP = 50

/** The `elapsed_ms` conversion divisor. */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * `pipelines_execute_node` (mcp-server.md §6.2.20 — 037 E). Scope: `author`.
 *
 * The owner's definition (§A, 2026-09-01): *1. convert Freemarker into SQL, 2. pass parameters
 * needed in the SQL, 3. execute the SQL on the datasource the node is pointing to.* **A debug
 * query, not an execution**: no ancestors, no tempdb, no `pipeline_executions` row, no SSE, no
 * slots, no idempotency — steps 1–2 are [NodeSqlResolver] (032's resolution, extracted) and
 * step 3 is [SqlRunner] against the node's own datasource. It never touches
 * `PipelineExecutor`/`ExecutionLauncher`/the DAG.
 *
 * **DML/DDL nodes execute FOR REAL** against a real datasource, with no history and no trace —
 * that consequence is ratified (§A) and stated in the description's first sentence. The
 * readonly-datasource refusal still applies to them (E4).
 *
 * **Which version (E5)**: an absent `version` runs the DRAFT if one exists, else the current
 * released version — an agent debugging is almost always working on the draft it just wrote —
 * and the response ALWAYS states which `version` and `status` ran. Never make the caller
 * infer it.
 */
class PipelinesExecuteNodeTool(
    private val resolver: NodeSqlResolver,
    private val datasources: DatasourceRegistry,
    private val runner: SqlRunner,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_execute_node",
            description =
                "Runs ONE pipeline node's rendered SQL against its own datasource and returns up to 50 " +
                    "decoded rows — a debug query for testing a node in isolation, NOT a pipeline execution. " +
                    "DML and DDL nodes execute FOR REAL against the datasource, leaving no execution history " +
                    "or trace. No ancestors run and no tempdb exists: a node whose source is tempdb is refused. " +
                    "Parameters bind through the pipeline's declarations; unsupplied required parameters fall " +
                    "back to sample values and the response names them in sampled_parameters. Absent version " +
                    "runs the DRAFT if one exists, else the current released version; the response states which " +
                    "version and status ran.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["pipeline_id", "node_id"],
                  "properties": {
                    "pipeline_id": {"type": "string", "format": "uuid"},
                    "node_id": {"type": "string", "description": "Node id within the pipeline body."},
                    "version": {"type": "integer", "minimum": 1, "description": "Pipeline version to read. Omitted: the DRAFT if one exists, else the current released version."},
                    "parameters": {
                      "type": "object",
                      "description": "Values for the pipeline's declared parameters, keyed by name. Types follow the declarations (BIGINTEGER and BIGDECIMAL as strings).",
                      "additionalProperties": true
                    }
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val workspace = ctx.principal.requireWorkspace()
        val pipelineId = args.requiredUuid("pipeline_id")
        val nodeId = args.requiredString("node_id")
        val version = args.version()
        val inputs = args.objectArg("parameters")?.mapValues { (_, value) -> ExecutorJson.mapper.valueToTree<JsonNode>(value) }

        val resolution =
            try {
                resolver.resolve(workspace.id, pipelineId, nodeId, version, inputs)
            } catch (e: NoSuchElementException) {
                // Unknown pipeline or explicitly-requested unknown version — the resolver's
                // detail text already names which.
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = e.message ?: "Pipeline $pipelineId does not exist.",
                    details = mapOf("pipeline_id" to pipelineId.toString()),
                    cause = e,
                )
            }

        return when (resolution) {
            is NodeSqlResolution.NodeMissing ->
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Node.NOT_FOUND,
                    message = "Pipeline $pipelineId has no node '$nodeId' in version ${resolution.version.version}.",
                    details =
                        mapOf(
                            "pipeline_id" to pipelineId.toString(),
                            "node_id" to nodeId,
                            "version" to resolution.version.version,
                        ),
                )

            is NodeSqlResolution.ChildPipeline -> standaloneRefused(resolution.version, nodeId, "pipeline_node")

            is NodeSqlResolution.ParameterRejected ->
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    message = resolution.failures.joinToString(" ") { "${it.parameter}: ${it.message}" },
                    details =
                        mapOf(
                            "pipeline_id" to pipelineId.toString(),
                            "node_id" to nodeId,
                            "failures" to resolution.failures.map { mapOf("parameter" to it.parameter, "message" to it.message) },
                        ),
                )

            is NodeSqlResolution.TemplateMissing ->
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND,
                    message = "Template '${resolution.templateId}' version ${resolution.templateVersion} is missing.",
                    details = mapOf("template_id" to resolution.templateId, "template_version" to resolution.templateVersion),
                )

            is NodeSqlResolution.RenderFailed ->
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED,
                    message = resolution.message,
                    details = mapOf("pipeline_id" to pipelineId.toString(), "node_id" to nodeId),
                )

            is NodeSqlResolution.Rendered -> run(resolution, ctx)
        }
    }

    /** Step 3 — execute the rendered SQL on the datasource the node points to. */
    private fun run(
        resolution: NodeSqlResolution.Rendered,
        ctx: McpToolContext,
    ): Any {
        val node = resolution.node
        if (node.resolvedSource is NodeSource.Tempdb) {
            // §A: there is no tempdb outside a full execution, and manufacturing one would be a
            // different feature. Use pipelines_execute for the node that builds this table.
            standaloneRefused(resolution.version, node.id, "tempdb_source")
        }

        val datasourceName = node.source
        val gated = datasources.requireVisible(datasourceName, ctx)
        if ((node.type == NodeType.DML || node.type == NodeType.DDL) && gated.isReadonly) {
            // E4: the executor's live readonly backstop, applied to the debug query too.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.DATASOURCE_READONLY,
                message = "Datasource '$datasourceName' is readonly — its ${node.type.wire} use is forbidden.",
                details = mapOf("datasource" to datasourceName, "node_id" to node.id),
            )
        }

        val startedAt = System.nanoTime()
        return runningQuery(datasourceName) {
            when (node.type) {
                NodeType.DQL -> {
                    val page = runner.select(gated, resolution.positionalSql, resolution.bindValues, limit = NODE_RUN_ROW_CAP)
                    payload(resolution, datasourceName, startedAt) {
                        put("columns", page.schema.columns.map { mapOf("name" to it.name, "type" to it.type.wire) })
                        put("rows", page.rows)
                        put("row_count", page.rows.size)
                        put("truncated", page.truncated)
                    }
                }

                NodeType.DML, NodeType.DDL -> {
                    val affected = runner.executeUpdate(gated, resolution.positionalSql, resolution.bindValues)
                    payload(resolution, datasourceName, startedAt) { put("affected_rows", affected) }
                }

                NodeType.PIPELINE -> standaloneRefused(resolution.version, node.id, "pipeline_node")
            }
        }
    }

    private fun payload(
        resolution: NodeSqlResolution.Rendered,
        datasourceName: String,
        startedAt: Long,
        extra: MutableMap<String, Any?>.() -> Unit,
    ): Map<String, Any?> =
        buildMap {
            put("node_id", resolution.node.id)
            put("node_type", resolution.node.type.wire)
            put("datasource", datasourceName)
            put("version", resolution.version.version)
            put("status", resolution.version.status.name)
            put("sql", resolution.sql)
            if (resolution.sampledParameters.isNotEmpty()) put("sampled_parameters", resolution.sampledParameters)
            extra()
            put("elapsed_ms", (System.nanoTime() - startedAt) / NANOS_PER_MILLI)
        }

    /** The §A/E2 catalogued refusal — one code, the reason names which shape fired. */
    private fun standaloneRefused(
        version: PipelineVersionDetail,
        nodeId: String,
        reason: String,
    ): Nothing =
        throw DatapipelinesException(
            code = PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED,
            message =
                when (reason) {
                    "tempdb_source" ->
                        "Node '$nodeId' reads from tempdb, which exists only inside a full execution — " +
                            "run pipelines_execute to build it."
                    else ->
                        "Node '$nodeId' is a PIPELINE node — it runs a child pipeline, not SQL."
                },
            details = mapOf("node_id" to nodeId, "reason" to reason, "version" to version.version),
        )
}
