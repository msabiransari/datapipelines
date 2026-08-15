package co.datapipelines.datasources

/**
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

/** The §7A table descriptor (`datasources_get_tables`'s element shape). */
fun TableInfo.toWireMap(): Map<String, Any?> =
    mapOf(
        "schema" to schema,
        "name" to name,
        "type" to type,
    )

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
    }

/** The §7A whole-schema snapshot (`datasources_get_schema` / `GET .../schema`). */
fun SchemaSnapshot.toWireMap(): Map<String, Any?> =
    mapOf(
        "datasource" to datasource,
        "dialect" to dialect,
        "truncated" to truncated,
        "tables" to tables.map { (table, columns) -> mapOf("table" to table.toWireMap(), "columns" to columns.map { it.toWireMap() }) },
    )
