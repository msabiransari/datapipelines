package co.datapipelines.lake.maintenance.manifests

import co.datapipelines.lake.maintenance.MaintenanceTable
import org.apache.iceberg.Snapshot
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** A table's current snapshot as its manifest list describes it, read with no lease. */
data class ManifestListView(
    val snapshotId: Long,
    val manifests: List<ManifestFact>,
    /** The manifest target size core would roll at ([ManifestFold.targetBytes]). */
    val targetBytes: Long,
) {
    /** The record's manifest count: every manifest of the snapshot, data and delete (959's measurement counts the same). */
    val count: Int get() = manifests.size
}

/**
 * Reads a table's manifest list with no lease (brief 963 D2, D3): the catalog row's metadata location, the metadata
 * file, then the current snapshot's manifest list through a client from [io] (`LakeCatalogs.metadataFileIo()`, which
 * refuses deletes), closed on this thread. No manifest is opened and nothing is written. The table's name comes from
 * its metadata row, never from a request.
 */
class ManifestListReader(
    private val jdbc: NamedParameterJdbcTemplate,
    private val catalogName: String,
    private val io: () -> FileIO,
) {
    /** The current snapshot's manifest list; null for a table with no catalog row or no snapshot. */
    fun current(table: MaintenanceTable): ManifestListView? =
        read(table) { metadata, client -> metadata.currentSnapshot()?.let { view(metadata, it, client) } }

    /**
     * The manifest list of the snapshot whose parent is [parentId] (the one a commit on [parentId] made: the commit was a
     * compare-and-swap on it, so no other snapshot has that parent); null when there is none.
     */
    fun childOf(
        table: MaintenanceTable,
        parentId: Long,
    ): ManifestListView? =
        read(table) { metadata, client ->
            metadata.snapshots().singleOrNull { it.parentId() == parentId }?.let { view(metadata, it, client) }
        }

    private fun <T> read(
        table: MaintenanceTable,
        block: (TableMetadata, FileIO) -> T?,
    ): T? {
        val location = metadataLocation(table) ?: return null
        return io().use { client -> block(TableMetadataParser.read(client, location), client) }
    }

    private fun view(
        metadata: TableMetadata,
        snapshot: Snapshot,
        client: FileIO,
    ): ManifestListView =
        ManifestListView(
            snapshot.snapshotId(),
            snapshot.allManifests(client).map(ManifestFact::of),
            ManifestFold.targetBytes(metadata),
        )

    private fun metadataLocation(table: MaintenanceTable): String? =
        jdbc
            .queryForList(
                "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = :catalog AND table_namespace = :namespace " +
                    "AND table_name = :name",
                mapOf("catalog" to catalogName, "namespace" to table.namespace, "name" to table.name),
                String::class.java,
            ).singleOrNull()
}
