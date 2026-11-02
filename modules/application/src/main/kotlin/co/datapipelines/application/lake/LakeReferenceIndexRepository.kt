package co.datapipelines.application.lake

import co.datapipelines.pipeline.release.ReleaseFamily
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for the lake reference index — `lake_reference_scans` and `lake_table_references` (metadata-db
 * §4.41–§4.42, V52; #778). The house shape ([co.datapipelines.application.datasources.LakeTableRepository] is the
 * sibling): NamedParameterJdbcTemplate, every value a bound parameter, no JPA.
 *
 * Every statement runs on the CALLER's transaction — the template enlists in whatever metadata transaction is open on
 * the thread (a release's, an import's, the backfill's own) — so a scan commits or rolls back with the write it
 * describes. The `namespace TEXT[]` is bound as a JSON array and expanded by Postgres
 * (`jsonb_array_elements_text … WITH ORDINALITY`), so no second connection is borrowed for a `java.sql.Array` while the
 * transaction holds one.
 */
class LakeReferenceIndexRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    /** Replaces [scan]'s rows: the old scan row goes (its references cascade), the new one and its references land. */
    fun replace(scan: LakeReferenceScan) {
        val key = keyParams(scan.version)
        jdbc.update(
            "DELETE FROM lake_reference_scans WHERE workspace_id = :workspaceId AND family = :family AND entity_id = :entityId " +
                "AND version = :version",
            key,
        )
        jdbc.update(
            """
            INSERT INTO lake_reference_scans (workspace_id, family, entity_id, version, name, status, reasons_json, scanner_version)
            VALUES (:workspaceId, :family, :entityId, :version, :name, :status, CAST(:reasons AS jsonb), :scannerVersion)
            """.trimIndent(),
            MapSqlParameterSource(key.values)
                .addValue("name", scan.name)
                .addValue("status", scan.status.wire)
                .addValue("reasons", mapper.writeValueAsString(scan.reasons.map(::reasonJson)))
                .addValue("scannerVersion", scan.scannerVersion),
        )
        if (scan.references.isEmpty()) return
        jdbc.batchUpdate(
            INSERT_REFERENCE,
            scan.references
                .map { reference ->
                    MapSqlParameterSource(key.values)
                        .addValue("ordinal", reference.ordinal)
                        .addValue("nodeId", reference.nodeId)
                        .addValue("selector", reference.selector)
                        .addValue("templateId", reference.templateId)
                        .addValue("templateVersion", reference.templateVersion)
                        .addValue("datasourceName", reference.datasourceName)
                        .addValue("writtenCatalog", reference.writtenCatalog)
                        .addValue("writtenSchema", reference.writtenSchema)
                        .addValue("writtenTable", reference.writtenTable)
                        .addValue("resolved", reference.resolved != null)
                        .addValue("namespace", reference.resolved?.let { mapper.writeValueAsString(it.namespace) })
                        .addValue("tableName", reference.resolved?.table)
                        .addValue("timeTravel", reference.timeTravel)
                        .addValue("statementIndex", reference.statementIndex)
                }.toTypedArray(),
        )
    }

    /** The scan row of [version], or null when it was never scanned. */
    fun findScan(version: ScannedVersion): StoredScan? =
        jdbc
            .query(
                "SELECT name, status, scanner_version FROM lake_reference_scans WHERE workspace_id = :workspaceId " +
                    "AND family = :family AND entity_id = :entityId AND version = :version",
                keyParams(version),
            ) { rs, _ -> StoredScan(rs.getString("name"), LakeScanStatus.fromWire(rs.getString("status")), rs.getInt("scanner_version")) }
            .singleOrNull()

    /** [version]'s references, in document order. */
    fun references(version: ScannedVersion): List<LakeTableReference> =
        jdbc.query(
            "$SELECT_REFERENCE WHERE workspace_id = :workspaceId AND family = :family AND entity_id = :entityId " +
                "AND version = :version ORDER BY ordinal",
            keyParams(version),
            REFERENCE_MAPPER,
        )

    /**
     * The scanned versions with a RESOLVED position naming [table] of [datasourceName] — the lookup the
     * `idx_lake_table_references_table` index serves (dp-lake P8 reads it; slice D-C). The identity is compared as
     * stored: the scanner writes the registry's own spelling whenever the registry holds the table.
     */
    fun versionsReading(
        datasourceName: String,
        table: LakeTableIdentity,
    ): List<ScannedVersion> =
        jdbc.query(
            "SELECT DISTINCT workspace_id, family, entity_id, version FROM lake_table_references " +
                "WHERE datasource_name = :datasourceName AND namespace = ${namespaceArray("namespace")} AND table_name = :tableName " +
                "ORDER BY family, entity_id, version",
            MapSqlParameterSource()
                .addValue("datasourceName", datasourceName)
                .addValue("namespace", mapper.writeValueAsString(table.namespace))
                .addValue("tableName", table.table),
        ) { rs, _ ->
            ScannedVersion(
                rs.getObject("workspace_id", UUID::class.java),
                familyOf(rs.getString("family")),
                rs.getObject("entity_id", UUID::class.java),
                rs.getInt("version"),
            )
        }

    /**
     * Up to [limit] RELEASED pipeline and parameter-set versions with no scan row, or a row an older scanner wrote — the
     * boot backfill's worklist (slice D-D (3)). Ordered so successive pages make progress deterministically.
     */
    fun unscannedReleased(
        scannerVersion: Int,
        limit: Int,
    ): List<ScannedVersion> =
        jdbc.query(
            """
            SELECT workspace_id, family, entity_id, version FROM (
                SELECT p.workspace_id, 'pipeline' AS family, v.pipeline_id AS entity_id, v.version
                  FROM pipeline_versions v
                  JOIN pipelines p ON p.id = v.pipeline_id
                 WHERE v.status = 'RELEASED'
                UNION ALL
                SELECT s.workspace_id, 'parameter_set' AS family, v.parameter_set_id AS entity_id, v.version
                  FROM parameter_set_versions v
                  JOIN parameter_sets s ON s.id = v.parameter_set_id
                 WHERE v.status = 'RELEASED'
            ) released
             WHERE NOT EXISTS (
                SELECT 1 FROM lake_reference_scans scan
                 WHERE scan.workspace_id = released.workspace_id AND scan.family = released.family
                   AND scan.entity_id = released.entity_id AND scan.version = released.version
                   AND scan.scanner_version >= :scannerVersion)
             ORDER BY family, entity_id, version
             LIMIT :limit
            """.trimIndent(),
            mapOf("scannerVersion" to scannerVersion, "limit" to limit),
        ) { rs, _ ->
            ScannedVersion(
                rs.getObject("workspace_id", UUID::class.java),
                familyOf(rs.getString("family")),
                rs.getObject("entity_id", UUID::class.java),
                rs.getInt("version"),
            )
        }

    /** A stored scan row's verdict, without its references. */
    data class StoredScan(
        val name: String,
        val status: LakeScanStatus,
        val scannerVersion: Int,
    )

    private fun reasonJson(reason: LakeScanReason): Map<String, String> =
        mapOf("site" to reason.site, "kind" to reason.kind, "detail" to reason.detail)

    private fun keyParams(version: ScannedVersion): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("workspaceId", version.workspaceId)
            .addValue("family", version.family.wire)
            .addValue("entityId", version.entityId)
            .addValue("version", version.version)

    private companion object {
        /** The `TEXT[]` a JSON-array parameter [name] expands to; a NULL parameter stays NULL (an unresolved row). */
        fun namespaceArray(name: String): String =
            "CASE WHEN CAST(:$name AS jsonb) IS NULL THEN NULL ELSE ARRAY(SELECT segment FROM " +
                "jsonb_array_elements_text(CAST(:$name AS jsonb)) WITH ORDINALITY AS t(segment, position) ORDER BY position) END"

        val INSERT_REFERENCE =
            """
            INSERT INTO lake_table_references (workspace_id, family, entity_id, version, ordinal, node_id, selector, template_id,
                template_version, datasource_name, written_catalog, written_schema, written_table, resolved, namespace, table_name,
                time_travel, statement_index)
            VALUES (:workspaceId, :family, :entityId, :version, :ordinal, :nodeId, :selector, :templateId, :templateVersion,
                :datasourceName, :writtenCatalog, :writtenSchema, :writtenTable, :resolved, ${namespaceArray("namespace")}, :tableName,
                :timeTravel, :statementIndex)
            """.trimIndent()

        const val SELECT_REFERENCE =
            "SELECT ordinal, node_id, selector, template_id, template_version, datasource_name, written_catalog, written_schema, " +
                "written_table, namespace, table_name, time_travel, statement_index FROM lake_table_references"

        val REFERENCE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                val namespace = rs.getArray("namespace")?.let { array -> (array.array as Array<*>).map { it.toString() } }
                LakeTableReference(
                    ordinal = rs.getInt("ordinal"),
                    nodeId = rs.getString("node_id"),
                    selector = rs.getString("selector"),
                    templateId = rs.getString("template_id"),
                    templateVersion = rs.getInt("template_version"),
                    datasourceName = rs.getString("datasource_name"),
                    writtenCatalog = rs.getString("written_catalog"),
                    writtenSchema = rs.getString("written_schema"),
                    writtenTable = rs.getString("written_table"),
                    resolved = namespace?.let { LakeTableIdentity(it, rs.getString("table_name")) },
                    timeTravel = rs.getBoolean("time_travel"),
                    statementIndex = rs.getInt("statement_index"),
                )
            }

        fun familyOf(wire: String): ReleaseFamily =
            SCANNED_FAMILIES.firstOrNull { it.wire == wire } ?: error("lake_reference_scans holds an unknown family '$wire'")
    }
}
