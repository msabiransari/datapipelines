package co.datapipelines.lake.maintenance.inventory

import co.datapipelines.lake.maintenance.remove.ListedObject

/**
 * The six rows' figures of one inventory, every row present and in [StorageRow]'s order, zero rows included (brief 964
 * D2, D5). Built only by [of], which refuses placements that do not add up to the listing.
 */
class StorageBreakdown private constructor(
    val rows: Map<StorageRow, RowFigure>,
) {
    val objects: Long get() = rows.values.sumOf { it.objects }
    val bytes: Long get() = rows.values.sumOf { it.bytes }

    fun row(row: StorageRow): RowFigure = rows.getValue(row)

    override fun equals(other: Any?): Boolean = other is StorageBreakdown && other.rows == rows

    override fun hashCode(): Int = rows.hashCode()

    override fun toString(): String =
        rows.entries.joinToString(prefix = "StorageBreakdown(", postfix = ")") { (r, f) -> "${r.wire}=${f.objects}/${f.bytes}" }

    companion object {
        /**
         * D5: the rows add up by construction, and this asserts it — the six counts and byte sums must equal the
         * [listing]'s totals, else [InventoryTotalsMismatch] is thrown and nothing is built (so nothing is stored).
         */
        fun of(
            listing: List<ListedObject>,
            placements: List<Placement>,
        ): StorageBreakdown {
            val byRow = placements.groupBy { it.row }
            val rows =
                StorageRow.entries.associateWith { row ->
                    byRow[row]?.let { placed -> RowFigure(placed.size.toLong(), placed.sumOf { it.listed.size }) } ?: RowFigure.ZERO
                }
            val breakdown = StorageBreakdown(rows)
            val listedObjects = listing.size.toLong()
            val listedBytes = listing.sumOf { it.size }
            if (breakdown.objects != listedObjects || breakdown.bytes != listedBytes) {
                throw InventoryTotalsMismatch(listedObjects, listedBytes, breakdown.objects, breakdown.bytes)
            }
            return breakdown
        }

        /** Rows read back from the table (every one present), for [StorageInventory.Measured]. */
        internal fun stored(rows: Map<StorageRow, RowFigure>): StorageBreakdown {
            check(rows.keys == StorageRow.entries.toSet()) { "a stored inventory has all six rows" }
            return StorageBreakdown(StorageRow.entries.associateWith { rows.getValue(it) })
        }
    }
}

/**
 * The six rows did not add up to the listing (D5): thrown BEFORE anything is written, so the table's previous rows stay;
 * the cleanup records `observer: failed`. The message carries the two totals, counts and bytes only — never a key.
 */
class InventoryTotalsMismatch(
    val listedObjects: Long,
    val listedBytes: Long,
    val rowObjects: Long,
    val rowBytes: Long,
) : IllegalStateException(
        "the storage rows do not add up to the listing: listed $listedObjects objects / $listedBytes bytes, " +
            "rows $rowObjects objects / $rowBytes bytes",
    )
