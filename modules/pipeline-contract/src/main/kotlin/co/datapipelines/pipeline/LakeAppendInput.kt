package co.datapipelines.pipeline

import java.sql.ResultSet

/**
 * A stable, already materialized append result. Each scan invokes its consumer once with a fresh cursor
 * over the SAME rows, then closes it. The producer owns the connection, timeout and cancellation handle.
 * Implementations must not re-execute the author's query: it may contain nondeterministic expressions.
 * This contract lets the lake check every required value before opening its first data file.
 */
interface LakeAppendInput {
    /**
     * Original query metadata, when materialization renamed duplicate labels or assigned a type to SQL NULL.
     * Null uses the scan's own metadata. Positions and column count must match the stored relation exactly.
     */
    val originalColumns: List<LakeAppendColumn>? get() = null

    /**
     * One cursor over the stored relation (#968, dp-lake design record call 37). [orderBy] holds the columns the rows are
     * ordered by, most significant first, each ascending with nulls first ([LakeOrderKey]); empty means arrival order.
     * An implementation refuses, before any statement runs, a position outside the relation's columns, a repeated
     * position and more than [MAX_ORDER_POSITIONS] keys ([orderByClause] does all three).
     */
    fun scan(
        orderBy: List<LakeOrderKey> = emptyList(),
        consume: (ResultSet) -> Unit,
    )

    companion object {
        /** A table has at most four sort columns (record call 36 item 2). */
        const val MAX_ORDER_POSITIONS = 4

        /**
         * The text an implementation appends to its `SELECT * FROM <relation>`: empty for arrival order, else
         * ` ORDER BY 2 ASC NULLS FIRST, #5 COLLATE "binary" ASC NULLS FIRST` (the second key a [LakeOrderKey.binaryText]
         * one). It is built from validated integers and this function's own fixed text only, never a name, a type or
         * any caller text.
         *
         * @throws IllegalArgumentException a position outside `1..columnCount`, a repeated position, or more than
         *   [MAX_ORDER_POSITIONS] keys.
         */
        fun orderByClause(
            orderBy: List<LakeOrderKey>,
            columnCount: Int,
        ): String {
            require(orderBy.size <= MAX_ORDER_POSITIONS) {
                "An append scan orders by at most $MAX_ORDER_POSITIONS columns, not ${orderBy.size}."
            }
            val positions = orderBy.map { it.position }
            require(positions.all { it in 1..columnCount }) {
                "An append scan's order positions $positions are outside the relation's $columnCount columns."
            }
            require(positions.toSet().size == positions.size) { "An append scan's order positions $positions repeat a position." }
            return if (orderBy.isEmpty()) "" else orderBy.joinToString(", ", prefix = " ORDER BY ") { it.term() + ASCENDING_NULLS_FIRST }
        }

        private const val ASCENDING_NULLS_FIRST = " ASC NULLS FIRST"

        /**
         * DuckDB's positional column reference (`#n`, the n-th column of `SELECT *`) with its built-in binary collation:
         * an explicit `COLLATE` overrides the column's own collation and the connection's `default_collation`, and
         * compares the UTF-8 bytes. `binary` is a keyword there, so it is quoted. A bare integer cannot carry a collation:
         * `ORDER BY 2 COLLATE ...` is not a positional reference.
         */
        private fun LakeOrderKey.term(): String = if (binaryText) "#$position COLLATE \"binary\"" else position.toString()
    }
}

/**
 * One column an append scan orders by (#968 D1; round 2 correction C1), ascending with nulls first. [position] is its
 * 1-based position in the stored relation. [binaryText] marks a string column: it is compared by Unicode code points
 * (UTF-8 byte order, which is Iceberg's string order), whatever collation the stored column carries. An author's
 * `COLLATE NOCASE` survives materialization while JDBC still reports `VARCHAR`, so a plain ordinal `ORDER BY` would
 * follow the author's collation instead. Only a string column may set it: the engine refuses to collate any other type,
 * so a wrong flag fails the scan loudly instead of misordering it.
 */
data class LakeOrderKey(
    val position: Int,
    val binaryText: Boolean = false,
)

/** Native query metadata captured before CTAS, without executing the query or retaining its statement. */
data class LakeAppendColumn(
    val name: String,
    val nativeType: String,
    val precision: Int,
    val scale: Int,
)
