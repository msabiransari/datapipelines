package co.datapipelines.lake.maintenance.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * A table's latest storage breakdown as P8 reads it (brief 964 D6): the six rows with their one time, or
 * [NotMeasured] when the table has had no cleanup that stored one — a figure nobody measured is never zero. It carries
 * no planner figure and no sum with one (record call 46: two sources, two times).
 */
sealed interface StorageInventory {
    data object NotMeasured : StorageInventory

    data class Measured(
        /** The catalog's clock at the cleanup's listing: the one time of all six rows ("in the bucket at"). */
        val measuredAt: Instant,
        /** The maintenance run whose cleanup measured it. */
        val runId: UUID,
        val breakdown: StorageBreakdown,
    ) : StorageInventory
}

/**
 * `lake_storage_inventory` (V64; the cut's L12): the LATEST inventory per table, one row per table and [StorageRow].
 * [replace] swaps a table's six rows in one transaction; [forTable] is the one read. Every statement is parameterised;
 * the row names are [StorageRow.wire] values, never free text.
 */
class StorageInventoryRepository(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) {
    /** The table's six rows replaced by [breakdown], measured at [measuredAt] by [runId]'s cleanup — all or nothing. */
    fun replace(
        tableId: UUID,
        runId: UUID,
        measuredAt: Instant,
        breakdown: StorageBreakdown,
    ) {
        val at = Timestamp.from(measuredAt)
        tx.executeWithoutResult {
            jdbc.update("DELETE FROM lake_storage_inventory WHERE table_id = ?", tableId)
            jdbc.batchUpdate(
                "INSERT INTO lake_storage_inventory (table_id, row_name, object_count, bytes, measured_at, run_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                breakdown.rows.map { (row, figure) -> arrayOf<Any>(tableId, row.wire, figure.objects, figure.bytes, at, runId) },
            )
        }
    }

    /** D6: the six rows with their time, or [StorageInventory.NotMeasured]. */
    fun forTable(tableId: UUID): StorageInventory {
        val rows =
            jdbc.query(
                "SELECT row_name, object_count, bytes, measured_at, run_id FROM lake_storage_inventory WHERE table_id = ?",
                { rs, _ ->
                    StoredRow(
                        StorageRow.ofWire(rs.getString("row_name")),
                        RowFigure(rs.getLong("object_count"), rs.getLong("bytes")),
                        rs.getTimestamp("measured_at").toInstant(),
                        rs.getObject("run_id", UUID::class.java),
                    )
                },
                tableId,
            )
        if (rows.isEmpty()) return StorageInventory.NotMeasured
        val stamp = rows.map { it.measuredAt to it.runId }.distinct()
        check(stamp.size == 1) { "a stored inventory of $tableId has one time and one run" }
        val (measuredAt, runId) = stamp.single()
        return StorageInventory.Measured(measuredAt, runId, StorageBreakdown.stored(rows.associate { it.row to it.figure }))
    }

    private data class StoredRow(
        val row: StorageRow,
        val figure: RowFigure,
        val measuredAt: Instant,
        val runId: UUID,
    )
}
