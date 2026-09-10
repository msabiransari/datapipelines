package co.datapipelines.application.datasources

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `lake_tables` (metadata-db §4.15, round 089 §A) — the dp-lake catalog's rows.
 *
 * A plain JDBC repository in the house shape ([co.datapipelines.application.endpoints.PublishedEndpointRepository]
 * is the sibling exemplar): NamedParameterJdbcTemplate, one RowMapper, no JPA.
 *
 * The `namespace` column is a Postgres `TEXT[]`, so writes bind a real `java.sql.Array` built
 * from the driver's own connection (`createArrayOf`) — never a hand-assembled `{a,b}` literal,
 * which would be a string-interpolation boundary one field away from the injection grammar this
 * table exists behind. Array equality (`namespace = :namespace`) is Postgres's own, so the
 * delete and the conflict clause match exactly the triple the UNIQUE constraint guards.
 */
class LakeTableRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** The datasource's registered tables, in tree order — the registry's hot read. */
    fun findByDatasource(datasourceId: String): List<LakeTable> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE datasource_id = :datasourceId ORDER BY namespace, name",
            mapOf("datasourceId" to datasourceId),
            MAPPER,
        )

    /**
     * Registers one table.
     *
     * @throws DuplicateKeyException when the (datasource, namespace, name) triple is taken —
     *   `uq_lake_tables_datasource_namespace_name`; the SERVICE maps it to the catalogued
     *   `datasource.lake_table_duplicate`, so the mapping has exactly one home.
     */
    fun insert(
        datasourceId: String,
        registration: LakeTableRegistration,
        actor: UUID,
    ): LakeTable =
        withTextArray(registration.namespace) { namespaceArray ->
            jdbc
                .query(
                    """
                    INSERT INTO lake_tables (datasource_id, namespace, name, format, location, partition_column, registered_by)
                    VALUES (:datasourceId, :namespace, :name, :format, :location, :partitionColumn, :registeredBy)
                    $RETURNING
                    """.trimIndent(),
                    params(datasourceId, registration, actor, namespaceArray),
                    MAPPER,
                ).single()
        }

    /**
     * The import path's insert: same row, `ON CONFLICT DO NOTHING` — re-importing a manifest is
     * idempotent by design (bootstrap re-runs it on every boot), so an already-registered triple
     * is reported, not an error. Returns the inserted row, or null when the triple was taken.
     */
    fun insertIfAbsent(
        datasourceId: String,
        registration: LakeTableRegistration,
        actor: UUID,
    ): LakeTable? =
        withTextArray(registration.namespace) { namespaceArray ->
            jdbc
                .query(
                    """
                    INSERT INTO lake_tables (datasource_id, namespace, name, format, location, partition_column, registered_by)
                    VALUES (:datasourceId, :namespace, :name, :format, :location, :partitionColumn, :registeredBy)
                    ON CONFLICT (datasource_id, namespace, name) DO NOTHING
                    $RETURNING
                    """.trimIndent(),
                    params(datasourceId, registration, actor, namespaceArray),
                    MAPPER,
                ).singleOrNull()
        }

    /** Unregisters one table by its triple. Returns whether a row was removed. */
    fun delete(
        datasourceId: String,
        namespace: List<String>,
        name: String,
    ): Boolean =
        withTextArray(namespace) { namespaceArray ->
            jdbc.update(
                "DELETE FROM lake_tables WHERE datasource_id = :datasourceId AND namespace = :namespace AND name = :name",
                mapOf("datasourceId" to datasourceId, "namespace" to namespaceArray, "name" to name),
            ) > 0
        }

    /**
     * 109 §A — records one table's connect-time view-creation outcome: [error] null clears
     * `last_error`/`last_error_at` (the healthy spelling), non-null stores the bounded message
     * and stamps `last_error_at`. Callers invoke this on TRANSITIONS only (the pool's view
     * applier compares against the state the pool was built with), so this stays a rare write
     * rather than one per physical connection. Silent when the row vanished mid-pool — an
     * unregistered table has no outcome to carry. Returns whether a row was updated.
     */
    fun recordViewOutcome(
        datasourceId: String,
        namespace: List<String>,
        name: String,
        error: String?,
    ): Boolean =
        withTextArray(namespace) { namespaceArray ->
            jdbc.update(
                """
                UPDATE lake_tables
                   SET last_error = :error,
                       last_error_at = CASE WHEN CAST(:error AS text) IS NULL THEN NULL ELSE NOW() END
                 WHERE datasource_id = :datasourceId AND namespace = :namespace AND name = :name
                """.trimIndent(),
                mapOf(
                    "datasourceId" to datasourceId,
                    "namespace" to namespaceArray,
                    "name" to name,
                    "error" to error,
                ),
            ) > 0
        }

    /**
     * Runs [block] with a live `text[]` binding for [segments]. The array is built on a
     * connection held for the whole call: a `java.sql.Array` is only valid while its connection
     * is, so the create-and-use must share one borrow from the pool.
     */
    private fun <T> withTextArray(
        segments: List<String>,
        block: (java.sql.Array) -> T,
    ): T {
        val dataSource = requireNotNull(jdbc.jdbcTemplate.dataSource) { "lake_tables repository has no DataSource" }
        return dataSource.connection.use { connection ->
            block(connection.createArrayOf("text", segments.toTypedArray()))
        }
    }

    private fun params(
        datasourceId: String,
        registration: LakeTableRegistration,
        actor: UUID,
        namespaceArray: java.sql.Array,
    ): Map<String, Any?> =
        mapOf(
            "datasourceId" to datasourceId,
            "namespace" to namespaceArray,
            "name" to registration.name,
            "format" to registration.format.wire,
            "location" to registration.location,
            "partitionColumn" to registration.partitionColumn,
            "registeredBy" to actor,
        )

    private companion object {
        const val SELECT_COLUMNS =
            """
            SELECT id, datasource_id, namespace, name, format, location, partition_column, registered_by, registered_at,
                   last_error, last_error_at
              FROM lake_tables
            """

        const val RETURNING =
            """
            RETURNING id, datasource_id, namespace, name, format, location, partition_column, registered_by, registered_at,
                      last_error, last_error_at
            """

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                LakeTable(
                    id = rs.getObject("id", UUID::class.java),
                    datasourceId = rs.getString("datasource_id"),
                    namespace = (rs.getArray("namespace").array as Array<*>).map { it.toString() },
                    name = rs.getString("name"),
                    format =
                        LakeTableFormat.fromWireOrNull(rs.getString("format"))
                            // The CHECK makes this unreachable; a corrupt row fails loudly here
                            // rather than deserializing into a value that generates SQL later.
                            ?: error("lake_tables row ${rs.getObject("id")} holds an unknown format"),
                    location = rs.getString("location"),
                    partitionColumn = rs.getString("partition_column"),
                    registeredBy = rs.getObject("registered_by", UUID::class.java),
                    registeredAt = rs.getTimestamp("registered_at").toInstant(),
                    lastError = rs.getString("last_error"),
                    lastErrorAt = rs.getTimestamp("last_error_at")?.toInstant(),
                )
            }
    }
}
