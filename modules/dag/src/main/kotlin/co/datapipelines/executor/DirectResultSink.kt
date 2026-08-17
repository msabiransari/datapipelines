package co.datapipelines.executor

import co.datapipelines.typesystem.ColumnSchema

/**
 * Receives a caller-node result streamed directly to an in-process consumer (design §4.2 — the
 * `direct` delivery mode).
 *
 * A child execution spawned by a PIPELINE node carries one of these on its
 * [ExecuteRequest.directSink]; its caller node's ResultSet is then streamed here instead of being
 * materialized into the [ResultStore]: nothing is written to Redis, there is no cursor, and the
 * result is not re-fetchable after consumption — re-running the child is the recovery path. The
 * mode exists for internal invocation only; REST/MCP requests already have SSE + the cursor.
 */
fun interface DirectResultSink {
    /**
     * Called at most once, on the execution's caller node. [rows] is lazy over the still-open
     * JDBC cursor, so it must be consumed **inside** this call — it reads nothing once the node's
     * connection has closed.
     */
    suspend fun accept(
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    )
}
