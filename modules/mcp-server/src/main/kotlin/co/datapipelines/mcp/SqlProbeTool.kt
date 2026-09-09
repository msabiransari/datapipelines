package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.SqlProbeExecutionException
import co.datapipelines.datasources.SqlProbeParameter
import co.datapipelines.datasources.SqlProbeParameterException
import co.datapipelines.datasources.SqlProbeRefusalException
import co.datapipelines.datasources.SqlProbeTimeoutException
import co.datapipelines.datasources.toWireMap
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.modelcontextprotocol.spec.McpSchema

/**
 * The parameter `type` enum, restated in the schema below — DERIVED from [LogicalType], never
 * typed (the [DIALECT_ENUM_JSON] precedent). [LogicalType.NULL] is excluded: it is not a
 * declarable parameter type (pipeline-contract §6.2) and the probe refuses it at bind time.
 */
internal val PROBE_TYPE_ENUM_JSON: String =
    LogicalType.entries.filter { it != LogicalType.NULL }.joinToString(prefix = "[", postfix = "]") { "\"${it.wire}\"" }

/**
 * `sql_probe` (datasources.md §7D — 107). Scope: `author`, like every tool that returns live
 * row data from a datasource connection (037 F).
 *
 * The bounded, read-only free-SQL probe: ONE classified SELECT/WITH, row-capped and timeboxed,
 * answering rows + canonical schema + the EXPLAIN plan captured BEFORE the query ran (so the
 * plan survives the timeout it explains). The gate, the plan-first read and the timebox all
 * live in [SqlProbe]; this tool is the translation layer: argument binding, the §5.3 visibility
 * gate, the `tempdb` refusal, and the error mapping below.
 *
 * ## Error surface
 *
 * - A statement that is not a single read-only SELECT/WITH ([SqlProbeRefusalException]) and a
 *   parameter that is missing or mistyped ([SqlProbeParameterException]) are ARGUMENT faults —
 *   JSON-RPC `-32602` ([McpArguments.invalidParams]), exactly like a schema violation: nothing
 *   was leased, nothing ran. Both exception messages are static by construction (the denylist
 *   keyword comes from a fixed set; the parameter NAME travels, the never-trusted value text
 *   does not).
 * - A timeout ([SqlProbeTimeoutException]) and a driver refusal ([SqlProbeExecutionException])
 *   are RUN failures — `isError` envelopes carrying the catalogued codes: the timeout is
 *   `pipeline.node.query_timeout` (T202 — "too slow for the budget" is not "wrong SQL"), the
 *   driver refusal `pipeline.node.query_execution_failed`, the same surface
 *   `datasources_preview_rows`'s [runningQuery] boundary uses for bad SQL. The timeout's
 *   details carry `wall_ms` and the pre-captured plan; the driver message stays bounded one
 *   level down (B1).
 */
class SqlProbeTool(
    private val datasources: DatasourceRegistry,
    private val probe: SqlProbe,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "sql_probe",
            description =
                "Run ONE read-only SELECT or WITH statement against a datasource and return up to `limit` " +
                    "wire-encoded rows, the canonical column schema, wall_ms of query time, and the EXPLAIN plan " +
                    "captured BEFORE the query ran — a bounded debug probe, not an export. The statement is " +
                    "classified before any connection opens: anything but a single SELECT/WITH, or a denylisted " +
                    "verb (INSERT, DROP, ATTACH, EXPLAIN, INTO, ...) anywhere in it, is refused without touching " +
                    "the datasource. Parameters bind as named :name placeholders through the same binder pipeline " +
                    "SQL uses; every referenced name must be supplied in `parameters` with its canonical type. " +
                    "`tempdb` is refused as a datasource — the staging database exists only inside a full " +
                    "execution (use pipelines_execute). On a timeout the error details carry wall_ms and the plan, " +
                    "so the plan that explains the timeout survives it. The sql text never reaches the audit log — " +
                    "only its SHA-256 and length are recorded.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name", "sql"],
                  "additionalProperties": false,
                  "properties": {
                    "name": {"type": "string", "description": "Datasource name. The reserved name tempdb is refused — it exists only inside a full execution (pipelines_execute)."},
                    "sql": {"type": "string", "description": "ONE SELECT or WITH statement. A second statement or a denylisted verb is refused before any connection opens."},
                    "parameters": {
                      "type": "object",
                      "description": "Bind values for the statement's :name placeholders, keyed by name. type is the canonical logical type; value is its wire string (BIGINTEGER/BIGDECIMAL as decimal text, temporal in ISO forms, BINARY as padded base64). A null value binds SQL NULL.",
                      "additionalProperties": {
                        "type": "object",
                        "required": ["type"],
                        "properties": {
                          "type": {"type": "string", "enum": $PROBE_TYPE_ENUM_JSON},
                          "value": {"type": ["string", "null"]}
                        }
                      }
                    },
                    "limit": {"type": "integer", "default": 50, "minimum": 1, "maximum": 500},
                    "timeout_seconds": {"type": "integer", "default": 10, "minimum": 1, "maximum": 30}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        if (name == TEMPDB) {
            // The pipelines_execute_node refusal (037 E2), restated for a free-SQL probe: tempdb
            // exists only inside a full execution, and manufacturing one would be a different
            // feature. Use pipelines_execute for the pipeline that builds the table.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED,
                message = "Datasource 'tempdb' exists only inside a full execution — run pipelines_execute to build it.",
                details = mapOf("datasource" to name, "reason" to "tempdb_source"),
            )
        }
        val sql = args.requiredString("sql")
        val limit = args.int("limit", default = SqlProbe.DEFAULT_LIMIT, min = 1, max = SqlProbe.MAX_LIMIT)
        val timeout = args.int("timeout_seconds", default = SqlProbe.DEFAULT_TIMEOUT_SECONDS, min = 1, max = SqlProbe.MAX_TIMEOUT_SECONDS)
        // Argument-shape faults refuse BEFORE the visibility gate: a malformed `parameters`
        // block is `-32602` regardless of what the caller may see, so the gate's answer cannot
        // depend on argument hygiene.
        val parameters = parametersOf(args)
        val gated = datasources.requireVisible(name, ctx)
        return probing(name) {
            probe.probe(gated, sql, parameters, limit, timeout).toWireMap()
        }
    }

    /**
     * The `parameters` object → typed [SqlProbeParameter]s. Shape faults are `-32602`; an
     * unknown `type` wire value is one too (the schema's enum is the contract).
     */
    private fun parametersOf(args: McpArguments): Map<String, SqlProbeParameter> =
        args.objectArg("parameters").orEmpty().mapValues { (key, value) ->
            val map = value as? Map<*, *> ?: throw McpArguments.invalidParams("parameters.$key must be an object with 'type' and 'value'.")
            val typeWire =
                map["type"] as? String ?: throw McpArguments.invalidParams("parameters.$key is missing 'type'.")
            val type =
                LogicalType.entries.firstOrNull { it != LogicalType.NULL && it.wire == typeWire }
                    ?: throw McpArguments.invalidParams("parameters.$key.type must be one of the canonical logical types.")
            val parameterValue =
                map["value"]?.let { it as? String ?: throw McpArguments.invalidParams("parameters.$key.value must be a string or null.") }
            SqlProbeParameter(type, parameterValue)
        }

    /**
     * The §7D error boundary: [runningQuery]'s two mappings (unreachable, driver-refused) plus
     * the probe's own three — the classifier/parameter refusals as `-32602` argument faults and
     * the timeout as the catalogued node-query code carrying `wall_ms` and the plan the timebox
     * was spent proving. The timeout's plan travels in `details` because the §6.3 error envelope
     * carries `details` verbatim; a success-shaped `status: "timeout"` payload was the
     * alternative and is rejected — a refused probe IS an error, and hiding it in a success
     * would teach agents to parse prose.
     *
     * The `SwallowedException` suppression: the two refusal catches translate into [McpError],
     * whose JSON-RPC shape has no cause channel — everything the caught exception carried (a
     * static message, the parameter NAME) is already in the new message.
     */
    @Suppress("SwallowedException")
    private fun <T> probing(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DatasourceUnreachableException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                message = "Datasource '$name' could not be reached for the probe.",
                details = mapOf("datasource" to name),
                cause = e,
            )
        } catch (e: SqlProbeRefusalException) {
            throw McpArguments.invalidParams(e.message ?: "The probe SQL is not a single read-only SELECT.")
        } catch (e: SqlProbeParameterException) {
            throw McpArguments.invalidParams("${e.message} Parameter: '${e.parameter}'.")
        } catch (e: SqlProbeTimeoutException) {
            // T202 (landed after this lane branched): a statement that outlived its timeout
            // reports the timeout code, not query_execution_failed — same rule as a node.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.QUERY_TIMEOUT,
                message =
                    "The probe exceeded its timeout against datasource '$name'; the EXPLAIN plan " +
                        "captured before the run is attached.",
                details =
                    buildMap {
                        put("datasource", name)
                        put("reason", "timeout")
                        put("wall_ms", e.wallMs)
                        e.plan?.let { put("plan", it.toWireMap()) }
                    },
                cause = e,
            )
        } catch (e: SqlProbeExecutionException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
                message = "The database refused the statement: ${e.driverMessage}",
                details = mapOf("datasource" to name),
                cause = e,
            )
        }

    private companion object {
        /** The reserved staging-datasource name (pipeline-contract §5.4). */
        const val TEMPDB = "tempdb"
    }
}
