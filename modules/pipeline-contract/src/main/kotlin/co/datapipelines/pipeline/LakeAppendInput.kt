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
     * One cursor over the stored relation (#968, dp-lake design record call 37). [orderBy] holds the 1-based positions,
     * in the stored relation, of the columns the rows are ordered by, each ascending with nulls first; empty means
     * arrival order. An implementation refuses, before any statement runs, a position outside the relation's columns,
     * a repeated position and more than [MAX_ORDER_POSITIONS] positions ([orderByClause] does all three).
     */
    fun scan(
        orderBy: List<Int> = emptyList(),
        consume: (ResultSet) -> Unit,
    )

    companion object {
        /** A table has at most four sort columns (record call 36 item 2). */
        const val MAX_ORDER_POSITIONS = 4

        /**
         * The text an implementation appends to its `SELECT * FROM <relation>`: empty for arrival order, else
         * ` ORDER BY 2 ASC NULLS FIRST, 5 ASC NULLS FIRST`. Only validated integers reach it, never a name.
         *
         * @throws IllegalArgumentException a position outside `1..columnCount`, a repeated position, or more than
         *   [MAX_ORDER_POSITIONS] positions.
         */
        fun orderByClause(
            orderBy: List<Int>,
            columnCount: Int,
        ): String {
            require(orderBy.size <= MAX_ORDER_POSITIONS) {
                "An append scan orders by at most $MAX_ORDER_POSITIONS columns, not ${orderBy.size}."
            }
            require(orderBy.all { it in 1..columnCount }) {
                "An append scan's order positions $orderBy are outside the relation's $columnCount columns."
            }
            require(orderBy.toSet().size == orderBy.size) { "An append scan's order positions $orderBy repeat a position." }
            return if (orderBy.isEmpty()) "" else orderBy.joinToString(", ", prefix = " ORDER BY ") { "$it ASC NULLS FIRST" }
        }
    }
}

/** Native query metadata captured before CTAS, without executing the query or retaining its statement. */
data class LakeAppendColumn(
    val name: String,
    val nativeType: String,
    val precision: Int,
    val scale: Int,
)
