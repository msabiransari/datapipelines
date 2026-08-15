package co.datapipelines.mcp

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SchemaSnapshot
import co.datapipelines.datasources.TableInfo

/*
 * The schema-introspection tools (mcp-server.md §6.2.16–18, datasources.md §7A) — thin adapters
 * over [SchemaIntrospector], the same service the REST endpoints use.
 *
 * Scope: `author` on all three (auth.md §7.6) — each opens a live connection against the
 * datasource, matching the `datasources_test` precedent. Payloads are hand-built snake_case maps
 * (the §6.2.16–18 shapes), never a serialized entity; credentials are not part of schema metadata
 * at all, and building the maps field-by-field keeps it that way by construction.
 */

/** `datasources_get_schema` (mcp-server.md §6.2.16). Scope: `author`. */
class DatasourcesGetSchemaTool(
    private val introspector: SchemaIntrospector,
) : McpTool {
    override val definition =
        McpTools.tool(
            name = "datasources_get_schema",
            description =
                "Read a datasource's whole schema — its tables with their columns — in one payload, capped at 200 " +
                    "tables (truncated: true when tables were dropped). Read-only, for pipeline authoring.",
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
    ): Any = introspector.snapshot(args.requiredString("name")).toMcpSnapshot()
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
                    "Read-only, for pipeline authoring.",
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
    ): Any = introspector.tables(args.requiredString("name"), args.string("schema")).map { it.toMcpTable() }
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
                    "Pass the table name exactly as datasources_get_tables returned it. Read-only, for pipeline authoring.",
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
        return introspector.columns(name, table, args.string("schema")).map { it.toMcpColumn() }
    }
}

/** The §6.2.17 table descriptor. */
internal fun TableInfo.toMcpTable(): Map<String, Any?> =
    mapOf(
        "schema" to schema,
        "name" to name,
        "type" to type,
    )

/**
 * The §6.2.18 column descriptor. Omitted-when-null follows the envelope convention
 * (type-system §7.3): a missing `precision`/`scale`/`nullable` key carries its documented
 * meaning; `"nullable": null` would assert a fact nobody reported.
 */
internal fun ColumnInfo.toMcpColumn(): Map<String, Any?> =
    buildMap {
        put("name", column.name)
        put("type", column.type.wire)
        column.precision?.let { put("precision", it) }
        column.scale?.let { put("scale", it) }
        column.nullable?.let { put("nullable", it) }
        put("source_type", sourceTypeName)
    }

/** The §6.2.16 whole-schema snapshot. */
internal fun SchemaSnapshot.toMcpSnapshot(): Map<String, Any?> =
    mapOf(
        "datasource" to datasource,
        "dialect" to dialect,
        "truncated" to truncated,
        "tables" to tables.map { (table, columns) -> mapOf("table" to table.toMcpTable(), "columns" to columns.map { it.toMcpColumn() }) },
    )
