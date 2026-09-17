package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.H2EgressMapper
import co.datapipelines.typesystem.LogicalTypeMapping
import co.datapipelines.typesystem.MappedColumn
import co.datapipelines.typesystem.TypeMappers
import co.datapipelines.typesystem.TypeMappingWarning
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * The H2 implementation of [Staging] (staging.md §3, §4, §8, §9).
 *
 * One instance owns one [H2ConnectionPool] of restricted connections to a per-execution
 * in-memory H2 database (§9.1, #118). Every operation **leases** one physical connection for
 * exactly the span of its own statements and cursor, so independent nodes run their tempdb
 * SQL on different connections at the same time, bounded by `max-connections` (§9.2). The
 * pool is opened by [H2StagingFactory] with the bootstrap-handoff connection, grows on demand,
 * and is released by [close].
 *
 * ## What is still coordinated, and how narrowly
 *
 * Two things are shared across leases and guarded by one short `synchronized` section that
 * never covers JDBC, a callback, a suspension or a wait for a lease: the set of **table-name
 * reservations** (the deterministic `table_already_exists` guard, §4.5) and the **successful
 * staged-row total** [stats] reports (§8.2). Everything else is per lease.
 *
 * ## Name ownership across creation, insertion and failure
 *
 * A name is reserved before `CREATE TABLE`; the loser of a duplicate race is refused at the
 * reservation and never reaches the database, so it cannot drop the winner's table. A failure
 * after `CREATE TABLE` rolls the partial table back on a fresh lease and frees the reservation
 * **only when the drop succeeded** — a name whose partial table could not be removed stays
 * owned, so a retry meets `table_already_exists` rather than silently reusing dirty data.
 *
 * ## Source type mapping
 *
 * [stage] resolves each source column's canonical type through the **source dialect's** mapper
 * ([TypeMappers.forDialect]) — never H2's. A Postgres/Oracle/MySQL source's JDBC codes and type
 * names do not mean what H2's mean (Oracle `DATE` is a timestamp, MySQL `bit(n>1)` is binary),
 * so mapping source metadata through the H2 table picks the wrong storage type and loses data
 * *before* egress re-derivation can see it (§3.2). `mapColumn` (not `map`) is used so an
 * unknown source type's §8.2 warning can name its column; those warnings ride out on
 * [StageResult.warnings] and never fail the node.
 */
class H2Staging internal constructor(
    override val executionId: UUID,
    private val pool: H2ConnectionPool,
    private val config: H2StagingProperties,
) : Staging {
    private val bookkeeping = Any()

    /** Table names owned this execution — the deterministic guard for `table_already_exists`. */
    private val stagedTables = linkedSetOf<String>()

    /** Observability counter (§8.2): total rows staged, reported by [stats], never a limit input. */
    private var stagedRowTotal = 0L

    override suspend fun <T> withConnection(block: suspend (Connection) -> T): T = pool.lease(LeaseKind.AUTHOR, block)

    /**
     * ## The source cursor is drained holding NO lease
     *
     * The shape from 108 §B, now with connections instead of a lock: read a batch from the
     * source cursor holding nothing, lease a connection for the `INSERT`, close the statement,
     * return the lease, read the next batch. A network wait on somebody else's database never
     * holds one of this execution's few connections, and a statement never crosses a returned
     * lease — the next batch may land on a different physical connection (§9.2). The cost is
     * one materialised batch (`insert-batch-size` rows) and one prepare per batch, bounded by
     * construction.
     */
    override suspend fun stage(
        resultSet: ResultSet,
        tableName: String,
        sourceDialect: Dialect,
        observer: StageObserver,
    ): StageResult {
        // Metadata and type mapping read the SOURCE cursor and touch no staging connection.
        val metadata = resultSet.metaData
        val indices = 1..metadata.columnCount

        // Column labels come from user SQL — validate before they touch generated DDL (§4.5).
        val columnNames = StagingIdentifiers.validateColumnNames(indices.map { metadata.getColumnLabel(it) })
        val mapped = columnNames.mapIndexed { i, name -> mapSourceColumn(sourceDialect, name, metadata, i + 1) }
        val columns = mapped.map { it.column }
        // Flattened in column order, one per affected column; never fatal (§8.2).
        val warnings = mapped.flatMap { it.warnings }.map { it.withBoundedSourceType() }
        val mappings = columns.map { LogicalTypeMapping(it.type, it.precision, it.scale) }

        createStagedTable(tableName, columns, observer)
        val rowsStaged = guardingPartialTable(tableName, observer) { drainInto(tableName, columns, mappings, resultSet, observer) }
        recordStaged(rowsStaged)
        return StageResult(tableName, rowsStaged, columns, warnings)
    }

    override suspend fun stageRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        observer: StageObserver,
    ): StageResult {
        // §4.5, exactly as stage() applies it: these names have the same provenance one execution
        // removed — the CHILD's caller-node result labels, read off a `SELECT … AS "whatever the
        // author typed"`. `total` + `TOTAL` must fail here exactly as they fail through a DQL node.
        StagingIdentifiers.validateColumnNames(columns.map { it.name })
        createStagedTable(tableName, columns, observer)
        // The sequence is lazy over the CHILD's result, so it is pulled in batches holding no
        // lease, exactly like a source cursor — a child-side fault of any shape surfaces
        // mid-insert and rolls the partial table back like every other failure.
        val rowsStaged = guardingPartialTable(tableName, observer) { drainRows(tableName, columns, rows, observer) }
        recordStaged(rowsStaged)
        return StageResult(tableName, rowsStaged, columns)
    }

    override suspend fun <T> withQuery(
        sql: String,
        block: suspend (ResultSet) -> T,
    ): T =
        pool.lease(LeaseKind.AUTHOR) { connection ->
            connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
                stmt.queryTimeout = config.queryTimeoutSeconds
                stmt.fetchSize = config.resultBatchSize
                // A cursor closed inside the block takes its statement with it, and `use` closes
                // the statement regardless (§3.3) — before the lease returns.
                stmt.closeOnCompletion()
                block(stmt.executeQuery(sql))
            }
        }

    override suspend fun execute(sql: String): Long {
        val affected = pool.lease(LeaseKind.AUTHOR) { connection -> connection.createStatement().use { it.executeUpdate(sql) }.toLong() }
        // execute() may write to staging; measure the footprint after it (§8.2) — outside the
        // lease, since the reading is the JVM's and needs no connection.
        checkMemoryBudget()
        return affected
    }

    override suspend fun stats(): StagingStats {
        // Read from the catalog, not from `stagedTables`: §10 says "current tables", and SQL
        // nodes create tables through execute()/withConnection() that the reservation set never
        // sees. Each figure is read once, on its own; there is no transactionally frozen
        // snapshot of a database other nodes are writing to, and §10 does not promise one.
        val tableCount = pool.lease(LeaseKind.INTERNAL) { connection -> H2StagingSql.countTables(connection) }
        return StagingStats(
            tableCount = tableCount,
            totalRows = synchronized(bookkeeping) { stagedRowTotal },
            memoryUsedBytes = measureUsedHeapKb() * BYTES_PER_KB,
        )
    }

    /**
     * Destroys the staging database (§3.4): the enumerate-and-drop belt runs on one owned
     * connection when no lease is outstanding — or on the last late return when one is — and
     * every owned connection is closed; the last close destroys the in-memory database. Never
     * waits for a lease still inside the driver and does not throw for any SQL or runtime
     * cleanup failure (§3.4, §6); a JVM `Error` propagates only after every owned connection
     * was closed and the pool is terminal.
     */
    override fun close() {
        val outcome = pool.close { connection -> H2StagingSql.dropStagedTables(connection, executionId) }
        if (outcome.leasesOutstanding > 0) {
            log.warn(
                "tempdb closed with {} session(s) still owned (leases, opens in flight, guardians) for execution {}; " +
                    "each closes itself on return",
                outcome.leasesOutstanding,
                executionId,
            )
        }
    }

    /**
     * The pool's counters — for measurements and tests, never a decision input. Not part of
     * the engine-neutral [Staging] contract (it is H2-specific by nature), which is why it lives
     * on the implementation class only.
     */
    fun poolStats(): H2PoolStats =
        H2PoolStats(
            maxConnections = pool.maxConnections,
            peakActiveLeases = pool.peakActiveLeases,
            physicalOpened = pool.physicalOpened,
            leaseWaitNanos = pool.leaseWaitNanos,
        )

    // ------------------------------------------------------------ table ownership

    /**
     * Reserves [tableName] and creates it on a fresh lease; a refused create frees the reservation.
     *
     * The lease is reported to [observer] like an insert's (149): at capacity one, a node whose
     * sibling holds the pool's only connection waits HERE, before its first batch, and an
     * unobserved wait would read as a slow source query.
     */
    private suspend fun createStagedTable(
        tableName: String,
        columns: List<ColumnSchema>,
        observer: StageObserver,
    ) {
        synchronized(bookkeeping) { if (!stagedTables.add(tableName)) throw StagingTableAlreadyExistsException(tableName) }
        var created = false
        try {
            observer.connectionRequested()
            pool.lease(LeaseKind.INTERNAL) { connection ->
                observer.connectionAcquired()
                H2StagingSql.createTable(connection, tableName, columns)
            }
            created = true
        } finally {
            // Nothing was created (or it was someone else's table, §4.5): give the name back so the
            // failure is not double-counted as "staged" and a retry is not poisoned. `finally`
            // rather than a catch so a cancelled lease wait frees the name too.
            if (!created) synchronized(bookkeeping) { stagedTables.remove(tableName) }
        }
    }

    /**
     * Runs the post-`CREATE TABLE` part of a stage. Any failure past this point — the node's own
     * deadline (108 §A), a driver fault, `value_overflow`, `memory_limit_exceeded` — leaves a
     * partial table and an owned name behind; both are undone before the failure propagates.
     */
    private suspend inline fun guardingPartialTable(
        tableName: String,
        observer: StageObserver,
        body: () -> Long,
    ): Long =
        try {
            body()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Every shape alike — the node's own deadline (a cancelled scope skips every suspension
            // point before it starts, hence the NonCancellable rollback), a driver fault,
            // `value_overflow`, `memory_limit_exceeded`, or a child-side fault surfacing through the
            // lazy `stageRows` sequence. A partial table must survive none of them.
            rollbackAfterFailure(tableName, observer)
            throw e
        }

    /**
     * Drops the partial table on a fresh, bounded lease and frees the reservation only when the
     * drop succeeded. The lease is bounded so cleanup on an exhausted pool cannot outlast the
     * failure it is cleaning up after; a lease that never came leaves the name owned, which is
     * the safe outcome — nothing can reuse a dirty name.
     */
    private suspend fun rollbackAfterFailure(
        tableName: String,
        observer: StageObserver,
    ) {
        withContext(NonCancellable) {
            val dropped = withTimeoutOrNull(ROLLBACK_LEASE_TIMEOUT_MS) { dropPartialTable(tableName) }
            if (dropped == true) {
                synchronized(bookkeeping) { stagedTables.remove(tableName) }
                // Reported AFTER the lease returned (149 §4: never under a pool lock) — the
                // confirmed undo is what lets the terminal sample say "rolled back" rather than
                // leaving the partial write's fate unknown (R149-1).
                observer.partialTableDropped()
            } else {
                log.warn(
                    "tempdb partial table '{}' of execution {} could not be cleaned up; its name stays reserved",
                    tableName,
                    executionId,
                )
            }
        }
    }

    /**
     * A private METHOD rather than an inline `pool.lease` in the lambda above, and that is not a
     * style choice: a `private val` read from inside a lambda makes the Kotlin compiler emit a
     * **public static** `access$getPool$p` bridge, and `StagingConnectionAccessTest` — correctly —
     * reads that as a public member yielding the pool. A private method's bridge returns `Object`,
     * and the guard keeps its teeth.
     */
    private suspend fun dropPartialTable(tableName: String): Boolean =
        pool.lease(LeaseKind.INTERNAL) { connection -> H2StagingSql.dropPartialTable(connection, tableName, executionId) }

    /** Counted only once the whole operation succeeded — a failed stage must not inflate the total. */
    private fun recordStaged(rows: Long) {
        synchronized(bookkeeping) { stagedRowTotal += rows }
    }

    // ------------------------------------------------------------ drains

    private suspend fun drainInto(
        tableName: String,
        columns: List<ColumnSchema>,
        mappings: List<LogicalTypeMapping>,
        rs: ResultSet,
        observer: StageObserver,
    ): Long = drainBatches(tableName, columns, observer) { batchSize -> H2StagingSql.readBatch(rs, mappings, batchSize) }

    private suspend fun drainRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        observer: StageObserver,
    ): Long {
        val iterator = rows.iterator()
        return drainBatches(tableName, columns, observer) { batchSize ->
            buildList {
                while (size < batchSize && iterator.hasNext()) {
                    val row = iterator.next()
                    require(row.size == columns.size) { "Row has ${row.size} values for ${columns.size} columns of table '$tableName'" }
                    add(row)
                }
            }
        }
    }

    /**
     * The batched drain: [nextBatch] runs holding no lease; the insert leases one connection per
     * batch. A source with zero rows still issues one `executeBatch` — the shape the pre-108 code
     * guaranteed, and what several drivers need to consider the statement used.
     */
    private suspend fun drainBatches(
        tableName: String,
        columns: List<ColumnSchema>,
        observer: StageObserver,
        nextBatch: (Int) -> List<List<Any?>>,
    ): Long {
        val sqlTypes = columns.map { H2EgressMapper.h2SqlType(it) }
        val batchSize = config.insertBatchSize
        var rowCount = 0L
        var batchIndex = 0L
        var lastBudgetCheckMs = 0L
        while (true) {
            // A cancelled node stops at the next batch boundary: neither the semaphore's fast path
            // nor a blocking driver call checks for cancellation, so the drain checks itself.
            coroutineContext.ensureActive()
            // 149 §3: the fetch boundary — holding no lease, so a slow source is measured as
            // fetching, never as writing.
            observer.fetchStarted()
            val batch = nextBatch(batchSize)
            observer.fetchFinished(batch.size)
            val exhausted = batch.size < batchSize
            if (batch.isNotEmpty() || rowCount == 0L) {
                // The request and the acquisition are two boundaries (requesting ≠ holding):
                // admission may suspend on the cap, checkout may open a physical connection.
                observer.connectionRequested()
                pool.lease(LeaseKind.INTERNAL) { connection ->
                    observer.connectionAcquired()
                    H2StagingSql.insertBatch(connection, tableName, columns, sqlTypes, batch)
                }
                rowCount += batch.size
                // Reported AFTER the lease returned: the caller's sink is not this class's to
                // trust with a lease (108 §B), and it never runs under the pool's lock.
                observer.batchWritten(batch.size, rowCount)
                lastBudgetCheckMs = checkBudgetIfDue(batchIndex, lastBudgetCheckMs)
            }
            batchIndex++
            if (exhausted) break
        }
        // The closing check is unconditional, whatever the throttle decided along the way: §8.2's
        // guarantee is about the footprint a completed stage leaves behind.
        checkMemoryBudget()
        return rowCount
    }

    // ------------------------------------------------------------ memory guard (§8.2)

    /**
     * The §8.2 budget check on the drain path: the FIRST batch, then at most once per
     * [BUDGET_CHECK_INTERVAL_MS] — and **without forcing a collection unless the cheap reading
     * says it might matter**.
     *
     * [measureUsedHeapKb] calls `System.gc()` to match H2's own `MEMORY_USED()`; at the default
     * batch size a 2M-row stage is 2 000 batches, and several drains run concurrently, so the
     * common path reads used heap WITHOUT collecting. That reading includes garbage, so it can
     * only ever be an OVER-estimate — a false alarm, never a miss; only then does the accurate,
     * collecting check decide. The frequency is per drain, not per connection: the pool changes
     * nothing here (#118).
     *
     * @return the timestamp of the check that ran, or [lastCheckMs] when none was due.
     */
    private fun checkBudgetIfDue(
        batchIndex: Long,
        lastCheckMs: Long,
    ): Long {
        val now = System.currentTimeMillis()
        if (batchIndex > 0 && now - lastCheckMs < BUDGET_CHECK_INTERVAL_MS) return lastCheckMs
        if (usedHeapKbWithoutCollecting() > config.maxMemoryMb * KB_PER_MB) checkMemoryBudget()
        return now
    }

    /** Used heap as the JVM reports it right now — garbage included, so only ever an over-estimate. */
    private fun usedHeapKbWithoutCollecting(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB
    }

    /**
     * Compares the **measured** footprint against the budget (§8.2) with the accurate,
     * post-GC reading. Called after each [execute] and unconditionally at drain completion;
     * mid-drain it runs only when [checkBudgetIfDue]'s cheap non-collecting reading says the
     * budget might be blown — never per batch, because THIS reading forces a `System.gc()`.
     * The reading is kilobytes; the budget is megabytes.
     */
    private fun checkMemoryBudget() {
        val usedKb = measureUsedHeapKb()
        if (usedKb > config.maxMemoryMb * KB_PER_MB) {
            throw StagingMemoryLimitException(memoryUsedBytes = usedKb * BYTES_PER_KB, maxMemoryMb = config.maxMemoryMb)
        }
    }

    /**
     * The measured footprint (§8.2): the JVM's used heap, read **in-process**.
     *
     * H2's `SELECT MEMORY_USED()` is deliberately not used — it requires admin rights (SQLState
     * 90040) in the pinned driver, and every operational connection is a non-admin user by §9.5.
     * Nothing is lost by the substitution: `MEMORY_USED()` is itself "collect garbage, then
     * report used heap", verified against 2.3.232 (both readings 14271 KB at the same instant).
     *
     * Known limit, stated in §8.2: this is heap for the whole JVM, so with concurrent executions
     * every instance reads the same number and `max_memory_mb` acts as a shared ceiling rather
     * than an isolated per-execution budget. The pool's connections share one database and one
     * set of staged tables, so the reading is not multiplied by the cap either (#118).
     */
    private fun measureUsedHeapKb(): Long {
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB
    }

    // ------------------------------------------------------------ mapping

    /**
     * Resolves one source column through [sourceDialect]'s mapper (§3.2). `mapColumn` carries
     * the column name so an unknown source type's §8.2 warning can name it — `map` cannot.
     */
    private fun mapSourceColumn(
        sourceDialect: Dialect,
        name: String,
        metadata: ResultSetMetaData,
        index: Int,
    ): MappedColumn =
        TypeMappers.forDialect(sourceDialect).mapColumn(
            name = name,
            sqlType = metadata.getColumnType(index),
            precision = metadata.getPrecision(index),
            scale = metadata.getScale(index),
            typeName = metadata.getColumnTypeName(index),
        )

    /**
     * Bounds the reflected source-DB type name carried by a §8.2 warning (ST-SEC-2). The value
     * is unvalidated text from a foreign driver's metadata and rides to the UI; an unbounded
     * one is a payload budget nobody set. Applied here, at staging's own boundary, because the
     * mapper that builds the warning lives in `typesystem` — outside this module.
     */
    private fun TypeMappingWarning.withBoundedSourceType(): TypeMappingWarning {
        val raw = sourceType ?: return this
        if (raw.length <= MAX_SOURCE_TYPE_CHARS) return this
        return copy(sourceType = raw.take(MAX_SOURCE_TYPE_CHARS) + "…")
    }

    private companion object {
        val log = LoggerFactory.getLogger(H2Staging::class.java)
        const val KB_PER_MB = 1024L
        const val BYTES_PER_KB = 1024L

        /**
         * How often the §8.2 budget may be re-measured mid-drain (108 §B). One second, not 250 ms:
         * the interval is paid once per CONCURRENT drain, and concurrency is what §B added.
         */
        const val BUDGET_CHECK_INTERVAL_MS = 1_000L

        /**
         * How long the partial-table rollback may wait for a lease (§6). Generous against the
         * longest batch a healthy pool can be busy with, short against a node deadline, and a
         * miss is safe: the name stays reserved and the pool's close drops the table anyway.
         */
        const val ROLLBACK_LEASE_TIMEOUT_MS = 30_000L

        /** Ceiling for a reflected source type name in a warning (ST-SEC-2). */
        const val MAX_SOURCE_TYPE_CHARS = 64
    }
}

/**
 * A point-in-time reading of one execution's connection pool (staging.md §9): the configured
 * cap, the most leases ever inside their callbacks at once, how many physical connections were
 * opened over the execution, and the total time callers spent suspended waiting for a lease.
 */
data class H2PoolStats(
    val maxConnections: Int,
    val peakActiveLeases: Int,
    val physicalOpened: Int,
    val leaseWaitNanos: Long,
)
