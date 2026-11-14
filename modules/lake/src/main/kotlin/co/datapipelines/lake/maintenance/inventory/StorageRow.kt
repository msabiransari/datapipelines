package co.datapipelines.lake.maintenance.inventory

/**
 * The six rows of a table's storage breakdown (record §11b call 46, "The storage breakdown"; brief 964 D2), in their
 * first-fit order: an object falls in the FIRST row that fits it, so every listed object is in exactly one. [wire] is
 * `lake_storage_inventory.row_name` (V64's CHECK) and the `docs/enums.md` spelling.
 */
enum class StorageRow(
    val wire: String,
) {
    /** Every object under `<location>/dp/`: the recipes and the remover's clock markers. */
    DP_RECORDS("dp_records"),

    /** Metadata files, manifest lists, manifests and statistics files reachable from a protected revision. */
    TABLE_METADATA("table_metadata"),

    /** Data and delete files the current snapshot names. */
    CURRENT("current"),

    /** Data and delete files, not current, that a history snapshot reaches. */
    HISTORY("history"),

    /** Data and delete files that ONLY keep-held snapshots reach ("held by keep rules"). */
    KEEP_HELD("keep_held"),

    /** Every other object: files awaiting removal after grace, and files a run in flight has written, not committed. */
    NOT_REFERENCED("not_referenced"),
    ;

    companion object {
        fun ofWire(wire: String): StorageRow = requireNotNull(entries.firstOrNull { it.wire == wire }) { "not a storage row: $wire" }
    }
}

/** One row's figures: how many listed objects fall in it and their summed size in bytes. */
data class RowFigure(
    val objects: Long,
    val bytes: Long,
) {
    init {
        require(objects >= 0 && bytes >= 0) { "a row's figures are never negative" }
    }

    companion object {
        val ZERO = RowFigure(0, 0)
    }
}
