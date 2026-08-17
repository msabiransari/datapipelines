package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.TypeMappingWarning
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * The engine-agnostic staging abstraction (staging.md §10). One instance backs one
 * execution's in-memory scratch database; a future `DuckDbStaging` can be a drop-in
 * replacement without changing this contract (§10.1).
 *
 * ## Serialization is the implementation's job, not the caller's
 *
 * A single JDBC connection backs the instance and is **not** safe for concurrent callers
 * (§9.2). Every method that touches it — [stage], [withQuery], [execute], [stats] — takes an
 * internal `Mutex`, which is why they are `suspend`. Direct SQL access for SQL nodes goes
 * through [withConnection], which acquires that same mutex for the duration of the block:
 * the connection is never handed out unguarded, and the mutex itself is not reachable — or
 * even observable — from outside the implementation.
 *
 * Cursor consumption is enforced by construction, not convention: [withQuery] holds the same
 * lock for the entire lifetime of the cursor, including the caller node's suspending drain to
 * the result store (§3.3, §6.1). No method hands out a live `ResultSet`, so a downstream
 * `stage`/`execute` cannot interleave with an open cursor on the shared connection.
 *
 * [close] is deliberately **not** `suspend`: it is called from the executor's `finally` block
 * and must never throw (§3.4).
 */
interface Staging : AutoCloseable {
    /** The execution this staging database belongs to; also the JDBC URL discriminator (§3.1). */
    val executionId: UUID

    /**
     * Runs [block] against the single staging JDBC connection (§9.1) with the instance's
     * serialization lock **held for the whole block**, and returns its value.
     *
     * This is the only route to the raw [Connection]: SQL nodes that need to issue their own
     * DDL/DML get it here instead of from a property, so a caller can neither reach the
     * connection without the lock nor be trusted to take a lock it cannot see (§9.2).
     *
     * Two rules for [block]:
     *  - **Never re-enter.** The lock is not reentrant, so calling [stage], [withQuery], [execute],
     *    [stats], or [withConnection] from inside [block] deadlocks.
     *  - **Never let the [Connection] escape.** Anything derived from it (statements, cursors)
     *    must be consumed or closed before [block] returns; the guarantee ends with the block.
     */
    suspend fun <T> withConnection(block: suspend (Connection) -> T): T

    /**
     * Streams a source [ResultSet] into a new staged table named [tableName] (§3.2, §4).
     *
     * Column labels arrive from user-authored SQL and are validated and double-quoted before
     * reaching any generated DDL/DML (§4.5). Each source column's canonical type is resolved
     * through **[sourceDialect]'s** mapper — not H2's — because a source dialect's JDBC codes
     * and type names do not mean what H2's mean (Oracle `DATE` is a timestamp, MySQL `bit(n>1)`
     * is binary): mapping them through H2 silently picks the wrong storage type and loses data
     * before egress re-derivation can see it (§3.2). The table is then created via
     * `H2EgressMapper`, rows are inserted in batches, and the measured footprint is checked
     * against the memory budget after the last batch (§8.2).
     *
     * @param sourceDialect the dialect of the database the [ResultSet] came from; a
     *   `tempdb`→`tempdb` node passes [Dialect.H2] (§3.2). Non-fatal mapping warnings raised
     *   for its columns are returned on [StageResult.warnings] (§8.2), never thrown.
     * @throws StagingInvalidColumnNameException a source label is invalid or duplicated (§4.5).
     * @throws StagingTableAlreadyExistsException [tableName] is already staged this execution.
     * @throws StagingMemoryLimitException the staged footprint exceeds the budget (§8.2).
     */
    suspend fun stage(
        resultSet: ResultSet,
        tableName: String,
        sourceDialect: Dialect,
    ): StageResult

    /**
     * The already-decoded half of [stage]: streams [rows] — canonical values under the canonical
     * [columns] — into a new staged table named [tableName].
     *
     * This is the composition ingress path (design 2026-08-13-pipeline-node-type §4.2): a parent
     * PIPELINE node's `direct`-delivered child rows arrive decoded, with the source-dialect mapping
     * already applied by the child's executor, so there is no ResultSet and no dialect left to
     * consult. Everything else is [stage]'s contract unchanged: the same duplicate-name guard and
     * bare `CREATE TABLE` (§4.5), the same batched insert (§4.3), the same partial-table rollback —
     * a failure mid-stream leaves **no** table behind — and the same post-write memory-budget check
     * (§8.2). Because no source metadata is read, [StageResult.warnings] is always empty; the
     * child's own warnings rode its execution.
     *
     * @throws StagingTableAlreadyExistsException [tableName] is already staged this execution.
     * @throws StagingMemoryLimitException the staged footprint exceeds the budget (§8.2).
     */
    suspend fun stageRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    ): StageResult

    /**
     * Runs a read query and hands its cursor to [block] with the serialization lock held for
     * the **whole** consumption — statement creation, execution, and the caller's row-by-row
     * drain (§3.3, §9.2). The statement times out per `query-timeout-seconds`, fetches per
     * `result-batch-size`, and is closed when [block] returns.
     *
     * There is deliberately no API that returns a live `ResultSet` to be read after the lock
     * is released. That earlier shape relied on caller discipline the type system could not
     * enforce, and it contradicted §6.1: the caller node's drain to the result store is
     * suspending Redis I/O, so a concurrent `stage`/`execute` could execute a statement on the
     * shared connection while the cursor was still open — the exact state corruption §9.2
     * warns about. Holding the lock across [block] makes that unreachable by construction.
     *
     * [block] must fully consume (or abandon) the cursor before returning, and nothing derived
     * from it may escape. As with [withConnection], the lock is not reentrant: re-entering any
     * staging operation from inside [block] deadlocks.
     */
    suspend fun <T> withQuery(
        sql: String,
        block: suspend (ResultSet) -> T,
    ): T

    /** Runs an `INSERT`/`UPDATE`/`DELETE`/DDL against staging and returns the affected row count (§10). */
    suspend fun execute(sql: String): Long

    /** Current table count, staged-row total, and **measured** memory footprint (§8.2, §10). */
    suspend fun stats(): StagingStats

    /**
     * Destroys the staging database: drops every staged table (enumerated from the catalog,
     * since `DROP ALL OBJECTS` needs admin — §9.5) then closes the connection (§3.4).
     * Never throws — a failure is logged and surfaces as `pipeline.staging.cleanup_failed`.
     */
    override fun close()
}

/**
 * The outcome of one [Staging.stage] call (staging.md §10).
 *
 * [columns] is the canonical, wire-facing descriptor list (type-system.md §7.1) —
 * `LogicalTypeMapping`, the internal ingress artifact, never crosses this boundary.
 *
 * [warnings] carries the source→canonical mapping warnings raised for this result set
 * (type-system.md §8.2): one per affected column, flattened in column order. They are
 * **never fatal** — the executor rolls them into the execution result's `warnings` array.
 */
data class StageResult(
    val tableName: String,
    val rowsStaged: Long,
    val columns: List<ColumnSchema>,
    val warnings: List<TypeMappingWarning> = emptyList(),
)

/**
 * A point-in-time view of the staging database (staging.md §10).
 *
 * [memoryUsedBytes] is **measured** as in-process JVM heap (§8.2), never estimated from row
 * counts. [tableCount] and [totalRows] are plain observability counters (§8.2): reported,
 * never used to decide the memory limit.
 */
data class StagingStats(
    val tableCount: Int,
    val totalRows: Long,
    val memoryUsedBytes: Long,
)
