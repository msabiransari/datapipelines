package co.datapipelines.mcp

import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.application.datasources.toWireMap
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutorJson
import io.modelcontextprotocol.spec.McpSchema

/*
 * The dp-lake catalog tools (mcp-server.md §6.2.29–31, metadata-db §4.15, round 089 §A) —
 * thin adapters over [LakeTableRegistryService], the SAME application service the REST
 * `/api/v1/datasources/{name}/tables` endpoints call (049's rule: two entry points, one
 * validated path). Nothing about registration is re-implemented here; what lives on this side
 * of the boundary is each tool's schema, its argument-to-body assembly (the
 * [DatasourcesCreateTool] precedent) and its result shape.
 *
 * Scope: `author` on all three (auth.md §7.6) — the datasource-mutation floor. Mutating a
 * GLOBAL datasource's registry additionally requires admin, a D8 rule the service's injected
 * gate enforces rather than a scope. Every tool first passes the §5.3 visibility gate
 * ([DatasourceRegistry.requireVisible]): a datasource bound to another workspace resolves as
 * not-found BEFORE anything is validated or written, uniformly with the other datasource
 * tools. All three are declared `mutating` in [McpToolCatalog], so every call writes
 * `mcp.tool.called` AND `mcp.tool.write` at the dispatcher's single audit choke point.
 */

/**
 * `lake_tables_register` (mcp-server.md §6.2.29) — register one table on a LAKE datasource.
 * Scope: `author`. Mutating.
 */
class LakeTablesRegisterTool(
    private val datasources: DatasourceRegistry,
    private val lakeTables: LakeTableRegistryService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "lake_tables_register",
            description =
                "Register one table in a LAKE datasource's catalog (the dp-lake registry). Mirrors " +
                    "POST /api/v1/datasources/{name}/tables: namespace (array of segments or the dotted " +
                    "'nyc.mobility' shorthand), table, format (parquet | iceberg) and location are required; " +
                    "partition_column is optional. The location is s3://bucket/prefix/ (parquet: a directory or " +
                    "glob; iceberg: the table root holding metadata/) or a file:// path — no other scheme, and " +
                    "no quotes, backslashes, whitespace or control characters (it is interpolated into the " +
                    "engine's CREATE VIEW, so the refusal is total). Segments follow the pipeline/template " +
                    "segment grammar without dots. Registering an already-registered (namespace, table) is the " +
                    "409 datasource.lake_table_duplicate; a non-LAKE datasource is refused. Mutating.",
            schema = REGISTER_SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val gated = datasources.requireVisible(args.requiredString("name"), ctx)
        // The REST body, assembled server-side from the declared arguments — the same shape the
        // REST surface binds, so the two entry points cannot diverge on what a field means.
        val body =
            buildMap<String, Any?> {
                put(
                    "namespace",
                    args.rawMap()["namespace"] ?: throw McpArguments.invalidParams("Missing required argument 'namespace'."),
                )
                put("name", args.requiredString("table"))
                put("format", args.requiredString("format"))
                put("location", args.requiredString("location"))
                args.string("partition_column")?.let { put("partition_column", it) }
            }
        return lakeTables.register(gated, McpTools.readTree(ExecutorJson.write(body)), ctx.principal).toWireMap()
    }

    private companion object {
        val REGISTER_SCHEMA =
            """
            {
              "type": "object",
              "required": ["name", "namespace", "table", "format", "location"],
              "additionalProperties": false,
              "properties": {
                "name": {"type": "string", "description": "Datasource name. A LAKE datasource visible in the key's pinned workspace."},
                "namespace": {
                  "description": "Namespace — a segments array or the dotted shorthand. 1-9 segments, the segment grammar without dots.",
                  "anyOf": [
                    {"type": "array", "items": {"type": "string"}},
                    {"type": "string"}
                  ]
                },
                "table": {"type": "string", "description": "The table's name — one segment of the same grammar, e.g. hvfhv_zone_day."},
                "format": {"type": "string", "enum": ["parquet", "iceberg"]},
                "location": {
                  "type": "string",
                  "description": "s3://bucket/prefix/ (parquet dir/glob; iceberg root) or file:// path. Nothing else; no injection chars."
                },
                "partition_column": {"type": "string", "description": "Optional. The hive-style partition column, e.g. pickup_date."}
              }
            }
            """.trimIndent()
    }
}

/**
 * `lake_tables_import` (mcp-server.md §6.2.30) — bulk-register from a 088-style manifest
 * `tables[]` block, inline or fetched from the datasource's own endpoint/bucket. Scope:
 * `author`. Mutating.
 */
class LakeTablesImportTool(
    private val datasources: DatasourceRegistry,
    private val lakeTables: LakeTableRegistryService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "lake_tables_import",
            description =
                "Bulk-register lake tables from a manifest.json tables[] block (the sample-data-lake shape). " +
                    "Mirrors POST /api/v1/datasources/{name}/tables/import: pass EITHER tables (an array of " +
                    "{name, format, location|path, partition_column?, namespace?}, with publish_prefix for " +
                    "relative paths and an optional shared namespace) OR manifest_url. A manifest URL is fetched " +
                    "server-side ONLY from the datasource's own endpoint/bucket — derived from its declared " +
                    "dialect.endpoint / catalog.ref, or AWS S3 when neither is set; anything else is refused " +
                    "with datasource.validation.lake_manifest_url_forbidden (no arbitrary URL fetch — SSRF). " +
                    "Import is idempotent: already-registered tables are reported in already_registered, not " +
                    "errors. Mutating.",
            schema = IMPORT_SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val gated = datasources.requireVisible(args.requiredString("name"), ctx)
        // Every argument but the datasource name is forwarded verbatim — the service owns the
        // either/or shape rule, so it is stated once, for both surfaces.
        val body = args.rawMap().filterKeys { it != "name" }
        return lakeTables.importTables(gated, McpTools.readTree(ExecutorJson.write(body)), ctx.principal).toWireMap()
    }

    private companion object {
        val IMPORT_SCHEMA =
            """
            {
              "type": "object",
              "required": ["name"],
              "additionalProperties": false,
              "properties": {
                "name": {"type": "string", "description": "Datasource name. A LAKE datasource visible in the key's pinned workspace."},
                "tables": {
                  "type": "array",
                  "description": "Inline form: manifest entries {name, format, location|path, partition_column?, namespace?}.",
                  "items": {"type": "object"}
                },
                "namespace": {
                  "description": "Shared namespace applied to entries that carry none — an array of segments or the dotted shorthand.",
                  "anyOf": [
                    {"type": "array", "items": {"type": "string"}},
                    {"type": "string"}
                  ]
                },
                "publish_prefix": {"type": "string", "description": "Base URI resolving relative entry paths, e.g. s3://bucket/lake/v1."},
                "manifest_url": {
                  "type": "string",
                  "description": "URL of a manifest.json. Fetched ONLY from the datasource's own endpoint/bucket or AWS S3; else refused."
                }
              }
            }
            """.trimIndent()
    }
}

/**
 * `lake_tables_unregister` (mcp-server.md §6.2.31) — remove one table from the catalog. The
 * objects in the bucket are untouched; the table stops being queryable through the datasource.
 * Scope: `author`. Mutating.
 */
class LakeTablesUnregisterTool(
    private val datasources: DatasourceRegistry,
    private val lakeTables: LakeTableRegistryService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "lake_tables_unregister",
            description =
                "Unregister one table from a LAKE datasource's catalog. Mirrors " +
                    "DELETE /api/v1/datasources/{name}/tables/{namespace}/{table}: the objects in the bucket " +
                    "are untouched — the table stops being served by the datasource. Unregistering a table " +
                    "that is not registered is the 404 datasource.lake_table_not_found, never a silent no-op. " +
                    "Mutating.",
            schema = UNREGISTER_SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("name")
        val gated = datasources.requireVisible(name, ctx)
        val namespace =
            (args.rawMap()["namespace"] ?: throw McpArguments.invalidParams("Missing required argument 'namespace'."))
                .let { raw ->
                    when (raw) {
                        is String -> raw.split('.')
                        is List<*> -> raw.filterIsInstance<String>()
                        else -> throw McpArguments.invalidParams("'namespace' must be a string or an array of strings.")
                    }
                }.map { it.trim() }
                .filter { it.isNotEmpty() }
        val table = args.requiredString("table")
        lakeTables.unregister(gated, namespace, table, ctx.principal)
        return mapOf("datasource" to name, "table" to (namespace + table).joinToString("."), "deleted" to true)
    }

    private companion object {
        val UNREGISTER_SCHEMA =
            """
            {
              "type": "object",
              "required": ["name", "namespace", "table"],
              "additionalProperties": false,
              "properties": {
                "name": {"type": "string", "description": "Datasource name. A LAKE datasource visible in the key's pinned workspace."},
                "namespace": {
                  "description": "The table's namespace, outermost first — an array of segments or the dotted shorthand ('nyc.mobility').",
                  "anyOf": [
                    {"type": "array", "items": {"type": "string"}},
                    {"type": "string"}
                  ]
                },
                "table": {"type": "string", "description": "The table to unregister."}
              }
            }
            """.trimIndent()
    }
}

/** The three dp-lake catalog tools, in `tools/list` order — appended after the endpoint tools. */
object LakeTableTools {
    fun all(
        datasources: DatasourceRegistry,
        lakeTables: LakeTableRegistryService,
    ): List<McpTool> =
        listOf(
            LakeTablesRegisterTool(datasources, lakeTables),
            LakeTablesImportTool(datasources, lakeTables),
            LakeTablesUnregisterTool(datasources, lakeTables),
        )
}
