package co.datapipelines.datasources

/*
 * The shared §7A wire projections — ONE definition per payload shape, used verbatim by BOTH
 * surfaces (the REST endpoints of `modules/web` and the MCP tools of `modules/mcp-server`), so
 * the two cannot drift apart and neither invents its own field names.
 *
 * Hand-built snake_case maps, never a serialized entity: credentials are not part of schema
 * metadata at all, and building the maps field-by-field keeps it that way by construction.
 * Omitted-when-null follows the envelope convention (type-system §7.3): a missing
 * `precision`/`scale`/`nullable` key carries its documented meaning, and `"nullable": null`
 * would assert a fact nobody reported.
 */

/**
 * The §7A table descriptor (`datasources_get_tables`'s element shape).
 *
 * `namespace` (087) is the ordered container path; `schema` is its last segment, kept for one
 * release so a pre-087 client keeps reading the field it knows. Both are always present — this is
 * an ADDITIVE change, and a client that reads `schema` sees exactly what it saw before.
 */
fun TableInfo.toWireMap(facts: List<Map<String, Any?>>? = null): Map<String, Any?> =
    buildMap {
        put("namespace", namespace)
        put("schema", schema)
        put("name", name)
        put("type", type)
        remarks?.let { put("remarks", it) }
        // 118 (design §7.2): the learned facts on this table, when the caller attached any —
        // omitted, not empty, when there are none (the envelope's omitted-when-null rule).
        facts?.takeIf { it.isNotEmpty() }?.let { put("facts", it) }
    }

/**
 * The §7A column descriptor (`datasources_get_columns`'s element shape). `warnings` carries the
 * ingress type mapper's warning MESSAGES (type-system §8.2/§10.5) — an author sees why a column
 * fell back to STRING without parsing warning objects.
 */
fun ColumnInfo.toWireMap(facts: List<Map<String, Any?>>? = null): Map<String, Any?> =
    buildMap {
        put("name", column.name)
        put("type", column.type.wire)
        column.precision?.let { put("precision", it) }
        column.scale?.let { put("scale", it) }
        column.nullable?.let { put("nullable", it) }
        put("source_type", sourceTypeName)
        put("warnings", warnings.map { it.message })
        remarks?.let { put("remarks", it) }
        // 118 (design §7.2): the learned facts on this column — omitted when none.
        facts?.takeIf { it.isNotEmpty() }?.let { put("facts", it) }
    }

/**
 * The §7A tables listing (`datasources_get_tables` / `GET .../tables`). [factsByTable] (118) is
 * the caller's per-table learned facts, keyed by table name; a table with none carries no key.
 */
fun TablesPage.toWireMap(factsByTable: Map<String, List<Map<String, Any?>>> = emptyMap()): Map<String, Any?> =
    mapOf(
        "tables" to tables.map { it.toWireMap(factsByTable[it.name]) },
        "truncated" to truncated,
    )

/**
 * The §7A schemas listing (`datasources_get_schemas` / `GET .../schemas`).
 *
 * Two projections of one list, on purpose (087). `schemas` is the pre-087 array of bare labels —
 * unchanged, still first, still what a client that has not been updated reads. `entries` is the
 * same rows as `{namespace, label}`, which is the only form that survives two same-named schemas
 * in different catalogs: `["sales", "sales"]` is not a listing anyone can act on, and
 * `[{namespace:["a1","sales"]}, {namespace:["a2","sales"]}]` is.
 *
 * Parallel arrays were the alternative and are rejected: index-aligned lists drift the first time
 * anyone filters one of them.
 */
fun SchemasPage.toWireMap(): Map<String, Any?> =
    mapOf(
        "schemas" to schemas,
        "entries" to entries.map { mapOf("namespace" to it.namespace, "label" to it.label) },
        "truncated" to truncated,
    )

/**
 * The §7C table-statistics payload (`datasources_get_table_stats`). `stats_source` names the
 * catalog the numbers came from — or `none`, a valid answer. The null stat fields follow the
 * same omitted-when-null rule as the §7A payloads: a missing `row_estimate`/`n_distinct`/`min`
 * key means "the catalog does not hold this", not "the value is zero".
 */
fun TableStats.toWireMap(): Map<String, Any?> =
    buildMap {
        rowEstimate?.let { put("row_estimate", it) }
        statsAsOf?.let { put("stats_as_of", it) }
        put("stats_source", statsSource)
        put("indexes", indexes.map { it.toWireMap() })
        put("columns", columns.map { it.toWireMap() })
    }

/** One index descriptor of the §7C payload — [IndexStats.columns] is key order. */
fun IndexStats.toWireMap(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "columns" to columns,
        "unique" to unique,
        "primary" to primary,
        "kind" to kind,
    )

/** One column's §7C statistics. [ColumnStats.distinctIsRatio] travels always. */
fun ColumnStats.toWireMap(): Map<String, Any?> =
    buildMap {
        put("name", name)
        nDistinct?.let { put("n_distinct", it) }
        put("distinct_is_ratio", distinctIsRatio)
        nullFraction?.let { put("null_fraction", it) }
        min?.let { put("min", it) }
        max?.let { put("max", it) }
    }

/**
 * The §7D probe payload (`sql_probe`). `columns` is the canonical schema the rows decode
 * against — the same [co.datapipelines.typesystem.ColumnSchema] projection §7A uses, without
 * `source_type` (a probe row has no catalog source to name). `warnings` carries the ingress
 * mapper's messages, like the §7A column payload. `row_count_returned` is `rows.size` named —
 * the caller must never count to learn the cap was hit; that is what `truncated` says. `plan`
 * is omitted-when-null (no EXPLAIN wrapper, or the plan read failed) per the envelope
 * convention.
 */
fun SqlProbeResult.toWireMap(): Map<String, Any?> =
    buildMap {
        put("columns", rows.schema.columns.map { it.toProbeWireMap() })
        put("warnings", rows.schema.warnings.map { it.message })
        put("rows", rows.rows)
        put("row_count_returned", rows.rows.size)
        put("truncated", rows.truncated)
        put("wall_ms", wallMs)
        plan?.let { put("plan", it.toWireMap()) }
    }

/** The §7D plan summary; every field but `raw` is omitted-when-null (best-effort extractions). */
fun ExplainPlanSummary.toWireMap(): Map<String, Any?> =
    buildMap {
        scan?.let { put("scan", it) }
        estimatedRows?.let { put("estimated_rows", it) }
        put("raw", raw)
        partitionsScanned?.let { put("partitions_scanned", it) }
        partitionsTotal?.let { put("partitions_total", it) }
    }

/** A canonical column as the §7D probe reports it — §7A's projection minus the catalog fields. */
private fun co.datapipelines.typesystem.ColumnSchema.toProbeWireMap(): Map<String, Any?> =
    buildMap {
        put("name", name)
        put("type", type.wire)
        precision?.let { put("precision", it) }
        scale?.let { put("scale", it) }
        nullable?.let { put("nullable", it) }
    }
