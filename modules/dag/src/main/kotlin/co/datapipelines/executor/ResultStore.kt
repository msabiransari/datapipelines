package co.datapipelines.executor

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.TypeMappingWarning
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * The Redis-backed store for the caller node's result (dag-executor.md §6.4.2, D9).
 *
 * There is **one** delivery model for surface callers: every caller result is fully materialized
 * here before the source connection closes, and every read — the `data_ready` inline first page,
 * the REST cursor, the MCP tool — pages the stored result. No inline-vs-claim-check split, no
 * live `ResultSet` outliving a node ([REST API §7](../../../../../../../docs/rest-api.md)).
 *
 * The single, deliberate exception is internal: a child execution spawned by a PIPELINE node can
 * carry a [DirectResultSink] (design §4.2's `direct` mode), in which case its caller result
 * streams to the parent executor and never passes through this store at all.
 */
interface ResultStore {
    /**
     * Drains [resultSet] into the store, checking the size cap **as it goes**, and returns the
     * reference the executor carries onward (§6.4.2).
     *
     * @param sourceDialect the dialect of the database the cursor came from. Column metadata is
     *   mapped through **that** dialect's mapper, never H2's — the same rule staging §3.2 states
     *   for ingress, and for the same reason: a source dialect's JDBC codes and type names do not
     *   mean what another's mean, so mapping them through the wrong table mislabels the wire
     *   schema. (§6.4.2 step 1 says "convert to canonical column descriptors" without naming the
     *   mapper; this parameter is how that is done correctly — reported as a spec clarification.)
     * @param ttlSeconds the already-clamped effective TTL (§6.4.2 step 4, REST API §7.4).
     * @throws co.datapipelines.typesystem.DatapipelinesException `result.too_large` when the cap
     *   is crossed (the partial result is discarded first), or `result.storage_unavailable` when
     *   Redis rejects a write.
     */
    suspend fun materialize(
        executionId: UUID,
        resultSet: ResultSet,
        sourceDialect: Dialect,
        ttlSeconds: Long,
    ): StoredResult

    /**
     * Stores an **already-decoded** result — canonical [schema] plus [rows] — under exactly the
     * contract [materialize] fulfils: same key layout, same during-drain size cap, same expiry,
     * same `describe`/`page` readability.
     *
     * This is the re-publication half of composition (design §4.2): a parent PIPELINE node whose
     * own `output.target` is `"caller"` lands the child's `direct`-streamed rows here so the
     * parent's caller gets an ordinary cursor result. The rows carry no ResultSet and no dialect
     * — decoding and dialect mapping happened upstream, in the child's executor.
     *
     * @param ttlSeconds the already-clamped effective TTL, as for [materialize].
     * @throws co.datapipelines.typesystem.DatapipelinesException `result.too_large` /
     *   `result.storage_unavailable`, under the same rules as [materialize].
     */
    suspend fun materializeRows(
        executionId: UUID,
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        ttlSeconds: Long,
    ): StoredResult

    /**
     * Everything `data_ready` needs, read back **from the stored result** rather than from the
     * ResultSet (§6.4.2): schema, the inline first page, totals, and the expiry.
     *
     * @return null when the key is unknown or its TTL has elapsed.
     */
    fun describe(key: String): StoredResultView?

    /**
     * The store key for [executionId] — the same string [materialize] returns in
     * [StoredResult.key], and the one [describe] and [page] take.
     *
     * A surface that holds only an execution id (the REST cursor
     * `GET /api/v1/executions/{id}/result`, rest-api §7.2; the MCP result tool) needs that key to
     * read a result it did not materialize itself. Publishing the mapping here is what keeps it
     * from being re-spelled — a hardcoded literal at each surface is a second definition of the
     * key layout, and the layout is the store's to own.
     *
     * The mapping is per-implementation on purpose: it is a store's own keyspace, not a shared
     * constant. It is total and side-effect free — it says nothing about whether a result for
     * [executionId] exists or has expired; [describe] answers that.
     */
    fun keyFor(executionId: UUID): String

    /** One page of the stored result, or null when the key is unknown or expired (REST §7.2). */
    fun page(
        key: String,
        offset: Long,
        limit: Int,
    ): ResultPage?

    /** Drops a stored result — used to discard a partial result after an aborted drain. */
    fun discard(key: String)
}

/**
 * What [ResultStore.materialize] returns (§6.4.2 step 5).
 *
 * Only [key] travels onward, in `NodeResult.callerResultRef`; the rest is stats and the
 * `data_ready` payload's raw material.
 */
data class StoredResult(
    val key: String,
    val totalRows: Long,
    val bytes: Long,
    val expiresAt: Instant,
    /** Type-mapping warnings raised for the result's columns; never fatal (type-system §8.2). */
    val warnings: List<TypeMappingWarning> = emptyList(),
)

/** The stored result's header plus its inline first page — the source of `data_ready`. */
data class StoredResultView(
    val key: String,
    val executionId: UUID,
    val schema: List<ColumnSchema>,
    val firstPage: List<List<Any?>>,
    val totalRows: Long,
    val bytes: Long,
    val expiresAt: Instant,
    val warnings: List<TypeMappingWarning> = emptyList(),
) {
    /** True when the stored result holds more rows than the inline first page carries. */
    val hasMore: Boolean get() = totalRows > firstPage.size
}

/** One cursor page (REST API §7.3). Row order is stable because the result is fully materialized. */
data class ResultPage(
    val executionId: UUID,
    val schema: List<ColumnSchema>,
    val rows: List<List<Any?>>,
    val offset: Long,
    val limit: Int,
    val totalRows: Long,
    val expiresAt: Instant,
) {
    val hasMore: Boolean get() = offset + rows.size < totalRows
}
