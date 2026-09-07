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
fun TableInfo.toWireMap(): Map<String, Any?> =
    buildMap {
        put("namespace", namespace)
        put("schema", schema)
        put("name", name)
        put("type", type)
        remarks?.let { put("remarks", it) }
    }

/**
 * The §7A column descriptor (`datasources_get_columns`'s element shape). `warnings` carries the
 * ingress type mapper's warning MESSAGES (type-system §8.2/§10.5) — an author sees why a column
 * fell back to STRING without parsing warning objects.
 */
fun ColumnInfo.toWireMap(): Map<String, Any?> =
    buildMap {
        put("name", column.name)
        put("type", column.type.wire)
        column.precision?.let { put("precision", it) }
        column.scale?.let { put("scale", it) }
        column.nullable?.let { put("nullable", it) }
        put("source_type", sourceTypeName)
        put("warnings", warnings.map { it.message })
        remarks?.let { put("remarks", it) }
    }

/** The §7A tables listing (`datasources_get_tables` / `GET .../tables`). */
fun TablesPage.toWireMap(): Map<String, Any?> =
    mapOf(
        "tables" to tables.map { it.toWireMap() },
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
