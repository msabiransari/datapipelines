package co.datapipelines.mcp

import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.PipelineService
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema

/** §6.2.42 — the `parameters` description, kept off the schema line for length. */
private const val PARAMETERS_DESC =
    "Object whose keys match the pipeline's declared parameters — the same binding pipelines_execute uses: undeclared " +
        "keys are ignored, defaults fill the execute way, and the calculator context is NOT available to a check. " +
        "Values must match the declared types (BIGINTEGER and BIGDECIMAL as strings, others as JSON native types)."

/**
 * `pipelines_run_checks` (mcp-server.md §6.2.42). Scope: `execute`.
 *
 * ## The one observed value there is
 *
 * A pipeline body's `checks[]` (pipeline-contract §3.3) is the author supplying the query and the
 * expectation — never an observed value. This tool is how an agent gets one: the SERVER runs every
 * check through [PipelineCheckRunner] — the ONE entry point REST (`POST …/checks/run`), the UI and
 * the release gate also ride — and PERSISTS one `pipeline_check_runs` row per check before
 * returning (metadata-db §4.20). There is deliberately **no `pipelines_record_check` tool**: no
 * tool records an observed value from a caller, because an observed value the caller supplied would
 * be a claim, not a run.
 *
 * ## A write-shaped read
 *
 * The tool is `execute`-scoped like the run it mirrors, and the catalog declares it **mutating**:
 * it writes run rows, so the dispatcher's generic `mcp.tool.write` row is the trace of who ran
 * them. Nothing per-tool is emitted — `McpToolDispatcher` audits every call itself.
 *
 * `verdict` is `pass` (the server's observed value satisfied the expectation), `fail` (a value was
 * produced and did NOT satisfy it) or `error` (no verdict could be formed — the datasource was
 * unresolvable or unreachable, the statement was refused, it returned a shape the expectation
 * cannot compare, or the parameters did not bind). An `error` is the truth recorded, never
 * silently a `fail`.
 */
class PipelineRunChecksTool(
    private val pipelines: PipelineService,
    private val checkRunner: PipelineCheckRunner,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "pipelines_run_checks",
            description =
                "Run a pipeline version's release checks (checks[]) NOW, against their own datasources. The SERVER " +
                    "runs every check and persists one pipeline_check_runs row per check before returning — only the " +
                    "server's own run produces `observed`, and there is deliberately no pipelines_record_check tool: " +
                    "no tool records an observed value from a caller. Each run's verdict is pass (observed satisfied " +
                    "expected), fail (a value was produced and did not satisfy it), or error (no verdict could be " +
                    "formed: the datasource was unresolvable or unreachable, the statement was refused or returned a " +
                    "shape the expectation cannot compare, or the parameters did not bind — the truth recorded, never " +
                    "silently a fail). With no version the WORKING version's checks run (the draft when one exists, " +
                    "else the latest released). Returns {version, runs: [{check_id, name, expected, observed, " +
                    "verdict, message, ran_at}]}; an empty checks[] returns an empty runs array.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "version": {"type": "integer", "description": "Specific version whose checks to run. Defaults to the WORKING version: the draft when one exists, else the latest released. Never clamped — an unknown version is refused, not rounded to the latest."},
                    "parameters": {
                      "type": "object",
                      "description": "$PARAMETERS_DESC",
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
        val id = args.requiredUuid("id")
        // The pipelines_execute resolution (B1/D55): never clamped, and the default is the
        // WORKING version — the draft when one exists, else the latest release.
        val record = pipelines.findRecord(workspace.id, id) ?: throw McpNotFound.pipeline(id)
        val version =
            args.version()
                ?: pipelines.workingVersion(workspace.id, record)
                ?: throw McpNotFound.pipelineVersion(id, 1)
        val outcomes =
            checkRunner.run(
                workspaceId = workspace.id,
                pipelineId = id,
                version = version,
                parameters = parameters(args),
                via = CheckRunVia.MCP,
                actor = ctx.principal.userId,
                correlationId = ctx.correlationId.toString(),
            ) ?: throw McpNotFound.pipelineVersion(id, version)
        return buildMap {
            put("version", version)
            put("runs", outcomes.map(::runPayload))
        }
    }

    /** Each declared parameter value as a `JsonNode`, which is what the runner binds from. */
    private fun parameters(args: McpArguments): Map<String, JsonNode> =
        args.objectArg("parameters")?.mapValues { (_, value) -> ExecutorJson.mapper.valueToTree(value) } ?: emptyMap()

    /**
     * The REST `checkRuns` shape (rest-api §5.16) as a Map payload: `expected` serialized from the
     * [CheckExpectation] model with its non-null members only, `observed`/`message`/`ran_at` null
     * exactly when the run has none (an `error` before a value could be read has no observed).
     */
    private fun runPayload(outcome: CheckRunOutcome): Map<String, Any?> =
        buildMap {
            put("check_id", outcome.checkId)
            put("name", outcome.name)
            put("expected", expectedPayload(outcome.expected))
            put("observed", outcome.observed)
            put("verdict", outcome.verdict.wire)
            put("message", outcome.message)
            put("ran_at", outcome.ranAt?.toString())
        }

    /** The wire shape of [CheckExpectation] (`NON_NULL` inclusion) without going through Jackson nodes. */
    private fun expectedPayload(expected: CheckExpectation): Map<String, Any?> =
        buildMap {
            put("kind", expected.kind)
            expected.value?.let { put("value", it) }
            expected.min?.let { put("min", it) }
            expected.max?.let { put("max", it) }
            expected.rows?.let { put("rows", it) }
            expected.tolerance?.let { put("tolerance", it) }
        }
}
