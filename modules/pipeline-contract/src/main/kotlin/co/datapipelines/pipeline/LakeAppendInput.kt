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

    fun scan(consume: (ResultSet) -> Unit)
}

/** Native query metadata captured before CTAS, without executing the query or retaining its statement. */
data class LakeAppendColumn(
    val name: String,
    val nativeType: String,
    val precision: Int,
    val scale: Int,
)
