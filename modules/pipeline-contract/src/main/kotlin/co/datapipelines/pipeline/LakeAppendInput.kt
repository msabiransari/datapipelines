package co.datapipelines.pipeline

import java.sql.ResultSet

/**
 * A stable, already materialized append result. Each scan invokes its consumer once with a fresh cursor
 * over the SAME rows, then closes it. The producer owns the connection, timeout and cancellation handle.
 * Implementations must not re-execute the author's query: it may contain nondeterministic expressions.
 * This contract lets the lake check every required value before opening its first data file.
 */
interface LakeAppendInput {
    fun scan(consume: (ResultSet) -> Unit)
}
