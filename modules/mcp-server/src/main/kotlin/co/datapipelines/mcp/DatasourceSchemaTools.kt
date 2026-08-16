package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.toWireMap
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/*
 * The schema-introspection tools (mcp-server.md §6.2.16–18, datasources.md §7A) — thin adapters
 * over [SchemaIntrospector], the same service the REST endpoints use. Together they form the
 * ONE introspection flow: get_schemas → get_tables(schema) → get_columns for only the tables
 * the SQL needs.
 *
 * Scope: `author` on all three (auth.md §7.6) — each opens a live connection against the
 * datasource, matching the `datasources_test` precedent. Payloads are the shared §7A wire maps
 * (`toWireMap`, in `modules/datasources` beside the data classes — the same projections the REST
 * endpoints use, so the two surfaces cannot drift); credentials are not part of schema metadata
 * at all, and the field-by-field maps keep it that way by construction.
 *
 * A connection failure arrives as the introspector's [DatasourceUnreachableException] — its
 * lease boundary translates BOTH the SQLException family and the RuntimeException pool-build
 * family (`HikariPool.PoolInitializationException` on a down database) — and is mapped here to
 * the catalogued `pipeline.execution.datasource_unreachable` and thrown as a
 * [DatapipelinesException], so the dispatcher envelopes it as an `isError` tool result (§9.2),
 * never a JSON-RPC -32603. The driver's message stays off the wire (§13 forbids internal
 * topology in error messages). The catch cannot live in a shared home: the code belongs to
 * `pipeline-contract`, a sibling of `datasources`, so each surface keeps its own three-line
 * translation (accepted in the round-2 hardening review).
 */

/**
 * `datasources_get_schemas` (mcp-server.md §6.2.16) — the flow's entry point. Scope: `author`.
 */
class DatasourcesGetSchemasTool(
    private val introspector: SchemaIntrospector,
) : McpTool {
    override val definition =
        McpTools.tool(
            name = "datasources_get_schemas",
            description =
                "List the schemas of a registered datasource by reading its live JDBC metadata, excluding the " +
                    "engine's own system schemas. The entry point of schema discovery: call this first, then " +
                    "get_tables(schema), then get_columns for only the tables the SQL needs. An empty list on a " +
                    "schemaless datasource is a valid answer. Read-only, for pipeline authoring.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string", "description": "Datasource name."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        return introspecting(name) { introspector.schemas(name).toWireMap() }
    }
}

/** `datasources_get_tables` (mcp-server.md §6.2.17). Scope: `author`. */
class DatasourcesGetTablesTool(
    private val introspector: SchemaIntrospector,
) : McpTool {
    override val definition =
        McpTools.tool(
            name = "datasources_get_tables",
            description =
                "List the tables and views of a registered datasource by reading its live JDBC metadata. " +
                    "The listing spans schemas — pass each table's reported schema to datasources_get_columns. " +
                    "Without a schema argument the listing fails on a datasource that reports no current schema " +
                    "(call datasources_get_schemas and pass one). Read-only, for pipeline authoring.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name"],
                  "properties": {
                    "name": {"type": "string", "description": "Datasource name."},
                    "schema": {"type": "string", "description": "Optional schema filter. An unknown schema matches nothing."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        return introspecting(name) { introspector.tables(name, args.string("schema")).toWireMap() }
    }
}

/** `datasources_get_columns` (mcp-server.md §6.2.18). Scope: `author`. */
class DatasourcesGetColumnsTool(
    private val introspector: SchemaIntrospector,
) : McpTool {
    override val definition =
        McpTools.tool(
            name = "datasources_get_columns",
            description =
                "List one table's columns with canonical types, read from the datasource's live JDBC metadata. " +
                    "Pass the table name exactly as datasources_get_tables returned it. Without a schema argument " +
                    "only the connection's current schema is read; if the datasource reports no current schema, " +
                    "an explicit schema is required (list them with datasources_get_schemas). " +
                    "Read-only, for pipeline authoring.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["name", "table"],
                  "properties": {
                    "name": {"type": "string", "description": "Datasource name."},
                    "table": {"type": "string", "description": "Table name as returned by datasources_get_tables."},
                    "schema": {"type": "string", "description": "Optional schema filter. An unknown schema matches nothing."}
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
        return introspecting(name) { introspector.columns(name, table, args.string("schema")).map { it.toWireMap() } }
    }
}

/**
 * The §7A error boundaries shared by the three tools: the introspector's
 * [DatasourceUnreachableException] is the catalogued
 * `pipeline.execution.datasource_unreachable` thrown as a [DatapipelinesException], so the
 * dispatcher envelopes it (§9.2) instead of mapping it to JSON-RPC -32603; its
 * [co.datapipelines.datasources.CurrentSchemaUnknownException] is the catalogued
 * `pipeline.execution.parameter_required` — the closest §13.3 invalid-argument code, reused
 * per the additive-catalog rule — with a message naming the recovery tool. Messages are
 * static — driver text stays off the wire.
 */
private fun <T> introspecting(
    name: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DatasourceUnreachableException) {
        throw DatapipelinesException(
            code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
            message = "Datasource '$name' could not be reached for schema introspection.",
            details = mapOf("datasource" to name),
            cause = e,
        )
    } catch (e: co.datapipelines.datasources.CurrentSchemaUnknownException) {
        throw DatapipelinesException(
            code = PipelineErrorCodes.Execution.PARAMETER_REQUIRED,
            message =
                "Datasource '$name' reports no current schema, so an unqualified read could merge " +
                    "same-named tables across schemas. Pass an explicit schema (list them with " +
                    "datasources_get_schemas).",
            details = mapOf("datasource" to name),
            cause = e,
        )
    }
