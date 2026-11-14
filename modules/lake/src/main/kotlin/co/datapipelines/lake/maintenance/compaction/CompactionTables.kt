package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.profiles.LakeProfile
import co.datapipelines.lake.profiles.LakeProfileDocument
import org.apache.iceberg.BaseTable
import org.apache.iceberg.DataFile
import org.apache.iceberg.LocationProviders
import org.apache.iceberg.StaticTableOperations
import org.apache.iceberg.Table
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** A managed table's row and its profile as the declaration reads them (D4): status, the profile pair, its values. */
data class CompactionTableRow(
    val status: String,
    val profileId: UUID,
    val profileUpdatedAt: Instant,
    val profile: LakeProfile,
)

/**
 * The table pinned at the snapshot its groups are planned from (D7): no lease is held. [table] is a handle over the
 * metadata file the catalog's row named when it was read (it never commits: the commit loads the table inside the
 * lease); [files] are that snapshot's data files; [filesWithDeletes] how many of them carry a delete file.
 */
class PinnedTable(
    val table: Table,
    val snapshotId: Long,
    val files: List<DataFile>,
    val filesWithDeletes: Int,
)

/** A data file of the snapshot has a delete file (D8): compaction never rewrites such a table; the part fails. */
class CompactionDeleteFilesFound(
    tableId: UUID,
) : IllegalStateException("table $tableId has delete files; compaction rewrites no table with row-level deletes")

/**
 * The compaction part's reads (brief 969 D4, D7): the table row with its profile (one statement), and the table pinned
 * at its current snapshot through the catalog row's metadata location (as 959's `TableMeasurer` reads it), with no
 * lease. Every statement is parameterised; the names come from the run's own table row.
 */
class CompactionTables(
    private val jdbc: NamedParameterJdbcTemplate,
    private val catalogName: String,
) {
    fun row(tableId: UUID): CompactionTableRow? =
        jdbc
            .query(
                "SELECT t.status, t.profile_id, p.updated_at, p.values_json::text AS values_json FROM lake_tables_managed t " +
                    "JOIN lake_profiles p ON p.id = t.profile_id WHERE t.id = :table",
                mapOf("table" to tableId),
            ) { rs, _ ->
                CompactionTableRow(
                    status = rs.getString("status"),
                    profileId = rs.getObject("profile_id", UUID::class.java),
                    profileUpdatedAt = rs.getObject("updated_at", Timestamp::class.java).toInstant(),
                    profile = LakeProfileDocument.parse(rs.getString("values_json")),
                )
            }.singleOrNull()

    /** [table] at its current snapshot, read through [io]; null when the catalog has no row for it or no snapshot. */
    fun pinned(
        table: MaintenanceTable,
        io: FileIO,
    ): PinnedTable? {
        val location = metadataLocation(table) ?: return null
        // The location provider the catalog's own handle has (BaseMetastoreTableOperations builds it the same way), so the
        // outputs written from this handle land where the table's appends do; a bare static handle has none.
        val metadata = TableMetadataParser.read(io, location)
        val locations = LocationProviders.locationsFor(metadata.location(), metadata.properties())
        val handle = BaseTable(StaticTableOperations(metadata, io, locations), "${table.namespace}.${table.name}")
        val snapshot = handle.currentSnapshot() ?: return null
        val tasks = handle.newScan().useSnapshot(snapshot.snapshotId()).planFiles().use { it.toList() }
        return PinnedTable(handle, snapshot.snapshotId(), tasks.map { it.file() }, tasks.count { it.deletes().isNotEmpty() })
    }

    private fun metadataLocation(table: MaintenanceTable): String? =
        jdbc
            .queryForList(
                "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = :catalog AND table_namespace = :namespace " +
                    "AND table_name = :name",
                mapOf("catalog" to catalogName, "namespace" to table.namespace, "name" to table.name),
                String::class.java,
            ).singleOrNull()
}
