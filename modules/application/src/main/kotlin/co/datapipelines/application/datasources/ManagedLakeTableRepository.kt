package co.datapipelines.application.datasources

import co.datapipelines.datasources.ManagedLakeTable
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * #818 (dp-lake P5, design record §10 "One snapshot per generation"): the managed read. The managed
 * datasource's `lake_tables` rows (`origin = 'managed'`) joined to the Iceberg catalog's row (V53
 * `iceberg_tables`, the row the compare-and-swap writes) and to their lifecycle rows (V54
 * `lake_tables_managed`, `lake_namespaces`).
 *
 * The catalog join is a LEFT join on purpose. A managed row with no catalog row still comes back,
 * with `metadata_location` null, so the planner can omit it AND the freshness tick can see it
 * (record §10: "a managed row with no catalog row is omitted (unavailable)"). Nothing here ever
 * substitutes `lake_tables.location`. That column is the display projection and no read consumes it.
 *
 * The join keys follow 798's spellings: `iceberg_tables.catalog_name` is the lake's name, and
 * `table_namespace` is the single-level Iceberg namespace, the second segment of the registry row's
 * `[<lake>, <namespace>]`. A sibling of [LakeTableRepository] rather than a method on it, to keep
 * that class within the house size.
 */
class ManagedLakeTableRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Every managed table of [lake], in tree order. One statement; throws on a failed read (the port's contract). */
    fun managedTables(lake: String): List<ManagedLakeTable> = jdbc.query(MANAGED_READ, mapOf("lake" to lake), MAPPER)

    private companion object {
        /** `:lake` is the only input, and it is bound. */
        const val MANAGED_READ =
            """
            SELECT t.namespace, t.name, t.format, t.partition_column, t.last_error,
                   i.metadata_location,
                   m.status AS table_status, m.deprecated_at AS table_deprecated_at,
                   rn.name || '.' || rm.name AS table_replacement,
                   n.status AS namespace_status, n.deprecated_at AS namespace_deprecated_at,
                   nr.name AS namespace_replacement
              FROM lake_tables t
              LEFT JOIN iceberg_tables i
                     ON i.catalog_name = :lake AND i.table_namespace = t.namespace[2] AND i.table_name = t.name
                    AND (i.iceberg_type IS NULL OR i.iceberg_type = 'TABLE')
              LEFT JOIN lake_namespaces n ON n.name = t.namespace[2]
              LEFT JOIN lake_tables_managed m ON m.namespace_id = n.id AND m.name = t.name
              LEFT JOIN lake_tables_managed rm ON rm.id = m.replacement_id
              LEFT JOIN lake_namespaces rn ON rn.id = rm.namespace_id
              LEFT JOIN lake_namespaces nr ON nr.id = n.replacement_id
             WHERE t.datasource_id = :lake AND t.origin = 'managed'
             ORDER BY t.namespace, t.name
            """

        /** The strictness order of the lifecycle statuses: the stricter of table and namespace wins. */
        val STRICTNESS = listOf("active", "deprecated", "archived", "purged")

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                val tableStatus = rs.getString("table_status")
                val namespaceStatus = rs.getString("namespace_status")
                ManagedLakeTable(
                    namespace = (rs.getArray("namespace").array as Array<*>).map { it.toString() },
                    name = rs.getString("name"),
                    format = rs.getString("format"),
                    partitionColumn = rs.getString("partition_column"),
                    lastError = rs.getString("last_error"),
                    metadataLocation = rs.getString("metadata_location"),
                    status = tableStatus?.let { listOfNotNull(it, namespaceStatus).maxBy(STRICTNESS::indexOf) },
                    deprecationMessage = deprecationMessage(rs, tableStatus, namespaceStatus),
                )
            }

        /**
         * The notice introspection shows. V54 has no message column, so it is derived from the stamps
         * and the replacement pointers: "Deprecated since 2026-11-04; use sales.orders_v2 instead."
         */
        fun deprecationMessage(
            rs: ResultSet,
            tableStatus: String?,
            namespaceStatus: String?,
        ): String? =
            when {
                tableStatus == "deprecated" -> {
                    notice("This table", rs.getObject("table_deprecated_at", OffsetDateTime::class.java), rs.getString("table_replacement"))
                }

                namespaceStatus == "deprecated" -> {
                    notice(
                        "Its namespace",
                        rs.getObject("namespace_deprecated_at", OffsetDateTime::class.java),
                        rs.getString("namespace_replacement"),
                    )
                }

                else -> {
                    null
                }
            }

        private fun notice(
            subject: String,
            since: OffsetDateTime?,
            replacement: String?,
        ): String =
            buildString {
                append("$subject is deprecated")
                since?.let { append(" since ").append(it.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()) }
                append(".")
                replacement?.let { append(" Use $it instead.") }
            }
    }
}
