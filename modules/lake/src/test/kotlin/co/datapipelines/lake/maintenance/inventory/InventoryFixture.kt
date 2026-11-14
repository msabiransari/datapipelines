package co.datapipelines.lake.maintenance.inventory

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.cleanup.CleanupHarness
import co.datapipelines.lake.maintenance.cleanup.CleanupObservation
import co.datapipelines.lake.maintenance.cleanup.CleanupObserver
import co.datapipelines.lake.maintenance.remove.ListedObject
import org.apache.iceberg.DataFile
import org.apache.iceberg.DataFiles
import org.apache.iceberg.FileFormat
import org.apache.iceberg.Table
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #964 — the storage breakdown over 962's [CleanupHarness] (Postgres, MinIO, the production cleanup and remover): data
 * files whose OBJECT size is chosen by the case and whose manifest entry declares that same size (so the planner's
 * figure and the inventory's agree until the table changes), appends, Iceberg's own `RewriteFiles` for rewrites, tags,
 * and the production observer over the production repository, with every observation recorded.
 *
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal class InventoryFixture(
    val h: CleanupHarness = CleanupHarness(),
) {
    val repository =
        StorageInventoryRepository(LakeTestDb.jdbc, TransactionTemplate(DataSourceTransactionManager(LakeTestDb.dataSource)))
    val observations: MutableList<CleanupObservation> = CopyOnWriteArrayList()
    private val files = ConcurrentHashMap<String, DataFile>()

    /** The production observer (or one over [classification]), recording what it was handed first. */
    fun observer(classification: StorageClassification = StorageClassifier): CleanupObserver {
        val real = StorageInventoryObserver(repository, classification)
        return CleanupObserver { observations += it; real.observed(it) }
    }

    /** One data file of [size] bytes appended in one commit; answers the new snapshot id and the file's key. */
    fun add(
        table: MaintenanceTable,
        size: Int,
    ): Pair<Long, String> {
        var key = ""
        h.p.commit(table) { t ->
            val file = file(t, table, size)
            key = keyOf(table, file)
            t.newAppend().appendFile(file).commit()
        }
        return h.metadata(table).currentSnapshot().snapshotId() to key
    }

    /** Iceberg's `RewriteFiles`: [old] replaced by a new file of [size] bytes in one commit; answers the snapshot and new key. */
    fun rewrite(
        table: MaintenanceTable,
        old: String,
        size: Int,
    ): Pair<Long, String> {
        var key = ""
        h.p.commit(table) { t ->
            val file = file(t, table, size)
            key = keyOf(table, file)
            t
                .newRewrite()
                .deleteFile(files.getValue(old))
                .addFile(file)
                .commit()
        }
        return h.metadata(table).currentSnapshot().snapshotId() to key
    }

    fun tag(
        table: MaintenanceTable,
        name: String,
        snapshotId: Long,
    ) = h.p.commit(table) { t -> t.manageSnapshots().createTag(name, snapshotId).commit() }

    /** An object of [size] bytes at [relative] under the table's location, never committed; answers its key. */
    fun objectAt(
        table: MaintenanceTable,
        relative: String,
        size: Int,
    ): String = (h.target(table).keyPrefix + relative).also { h.put(it, size) }

    /** The test's OWN listing (never the remover's) as row figures for the keys [select] picks. */
    fun figureOf(
        listing: List<ListedObject>,
        select: (String) -> Boolean,
    ): RowFigure = listing.filter { select(it.key) }.let { RowFigure(it.size.toLong(), it.sumOf { o -> o.size }) }

    fun measured(table: MaintenanceTable): StorageInventory.Measured =
        repository.forTable(table.id) as? StorageInventory.Measured ?: error("${table.name} has no stored inventory")

    private fun file(
        t: Table,
        table: MaintenanceTable,
        size: Int,
    ): DataFile {
        val location = "${t.location()}/data/${UUID.randomUUID()}.parquet"
        val file =
            DataFiles
                .builder(t.spec())
                .withPath(location)
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(size.toLong())
                .withRecordCount(1)
                .withPartitionPath("${t.spec().fields().single().name()}=2026-11-01")
                .build()
        h.put(keyOf(table, file), size)
        files[keyOf(table, file)] = file
        return file
    }

    private fun keyOf(
        table: MaintenanceTable,
        file: DataFile,
    ): String = requireNotNull(h.target(table).keyOf(file.location()))
}
