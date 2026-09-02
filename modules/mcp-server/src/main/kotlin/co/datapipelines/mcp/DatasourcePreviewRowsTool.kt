package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.OrderByTerm
import co.datapipelines.datasources.SqlExecutionException
import co.datapipelines.datasources.SqlRunner
import co.datapipelines.pipeline.PipelineErrorCodes
import io.modelcontextprotocol.spec.McpSchema

/** §6.2.19 — the `limit` bounds, kept beside the tool that owns them. */
private const val LIMIT_DEFAULT = 50

/**
 * `datasources_preview_rows` (mcp-server.md §6.2.19, datasources.md §7B — 037 D). Scope:
 * `author`, like every tool that returns live data from a datasource connection.
 *
 * The agent's blindness this closes: *"there is no way agent can see the sample data from the
 * sample table."* `datasources_get_columns` shows the shape; this shows the DATA — up to 50
 * wire-encoded rows of one table, optionally ordered both ways (`direction: "DESC"` is the
 * other end of the data, the stated point of [order_by]'s object form).
 *
 * The agent supplies **identifiers only** — the service builds the ENTIRE statement and quotes
 * every identifier with the dialect's quote character doubled (D3: there is no SQL-validation
 * utility in this repo and this round does not build one; the quoting IS the boundary). An
 * `order_by` entry is a `{column, direction}` object, never a free `"col DESC"` string (D1) —
 * a free string is an injection vector the quoting would then have to untangle.
 */
class DatasourcesPreviewRowsTool(
    private val datasources: DatasourceRegistry,
    private val runner: SqlRunner,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "datasources_preview_rows",
            description =
                "Preview up to `limit` rows of one table's data, read live from the datasource. " +
                    "The counterpart to datasources_get_columns: this shows the DATA, that shows the shape. " +
                    "Without order_by the top-N is engine-arbitrary; pass order_by to see a chosen end of the " +
                    "data, e.g. direction DESC for the newest or largest rows. Read-only (SELECT); readonly " +
                    "datasources are valid targets. Values arrive wire-encoded: BIGINTEGER and BIGDECIMAL as " +
                    "strings, temporal as fixed-width ISO forms.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name", "table"],
                  "properties": {
                    "name": {"type": "string", "description": "Datasource name."},
                    "table": {"type": "string", "description": "Table name exactly as datasources_get_tables returned it."},
                    "schema": {"type": "string", "description": "Optional schema qualifier. Omitted means the connection's current schema."},
                    "order_by": {
                      "type": "array",
                      "description": "Sort terms applied in order. Each is an object with a column and a direction, never a free SQL string.",
                      "items": {
                        "type": "object",
                        "required": ["column"],
                        "properties": {
                          "column": {"type": "string", "description": "Column name exactly as datasources_get_columns returned it."},
                          "direction": {"type": "string", "enum": ["ASC", "DESC"], "default": "ASC"}
                        }
                      }
                    },
                    "limit": {"type": "integer", "default": 50, "minimum": 1, "maximum": 50}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        val table = args.requiredString("table")
        val orderBy = parseOrderBy(args)
        val limit = args.int("limit", default = LIMIT_DEFAULT, min = 1, max = LIMIT_DEFAULT)
        val gated = datasources.requireVisible(name, ctx)
        return runningQuery(name) {
            runner
                .previewTable(gated, table, args.string("schema"), orderBy, limit)
                .let { page ->
                    buildMap {
                        put("datasource", name)
                        put("table", table)
                        args.string("schema")?.let { put("schema", it) }
                        put(
                            "columns",
                            page.schema.columns.map { column ->
                                mapOf("name" to column.name, "type" to column.type.wire)
                            },
                        )
                        put("rows", page.rows)
                        put("row_count", page.rows.size)
                        put("truncated", page.truncated)
                    }
                }
        }
    }

    /**
     * D1's object form: `{column, direction}` — column required and non-blank, direction
     * strictly `ASC`/`DESC` (anything else is `-32602`, not a guess). A blank column is
     * `invalidParams`, the D3 blank-identifier rule.
     */
    private fun parseOrderBy(args: McpArguments): List<OrderByTerm> =
        args
            .listArg("order_by")
            ?.mapIndexed { index, entry ->
                val map =
                    entry as? Map<*, *> ?: throw McpArguments.invalidParams("order_by[$index] must be an object.")
                val column =
                    (map["column"] as? String)?.takeUnless { it.isBlank() }
                        ?: throw McpArguments.invalidParams("order_by[$index].column must be a non-empty string.")
                val direction =
                    map["direction"]?.let {
                        when (it) {
                            "ASC" -> false
                            "DESC" -> true
                            else -> throw McpArguments.invalidParams("order_by[$index].direction must be ASC or DESC.")
                        }
                    } ?: false
                OrderByTerm(column, direction)
            }
            ?: emptyList()
}

/**
 * The §7B error boundary both query tools share: unreachable is the catalogued
 * `pipeline.execution.datasource_unreachable`, a refused statement is the catalogued
 * `pipeline.node.query_execution_failed` carrying the bounded driver message (rest-api §4.2
 * sanctions driver text; the executor's own B1 bound applies). The `introspecting` precedent in
 * [DatasourceSchemaTools] catches only the first — previewing DATA can also fail AT the
 * statement, and that failure must be an `isError` envelope, never a JSON-RPC -32603.
 */
internal fun <T> runningQuery(
    name: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DatasourceUnreachableException) {
        throw co.datapipelines.typesystem.DatapipelinesException(
            code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
            message = "Datasource '$name' could not be reached for the query.",
            details = mapOf("datasource" to name),
            cause = e,
        )
    } catch (e: SqlExecutionException) {
        throw co.datapipelines.typesystem.DatapipelinesException(
            code = PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
            message = "The database refused the statement: ${e.message}",
            details = mapOf("datasource" to name),
            cause = e,
        )
    }
