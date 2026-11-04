package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalTypeMapping
import co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate
import co.datapipelines.typesystem.jdbc.guardedForAuthorSql
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * The DuckDB implementation of [Staging] (staging.md §8.5): a file-backed database per execution,
 * opened, bounded and locked by [DuckDbStagingFactory].
 *
 * The contract is H2's (§10): [stage]/[stageRows] drain their source in batches holding no lease,
 * a name is reserved before its bare `CREATE TABLE` and a failure after it drops the partial table
 * (§4.5), [withQuery] holds its lease for the whole cursor, and [close] never throws (§3.4). What
 * differs is the engine underneath:
 *
 *  - **Author leases are gated.** [withConnection], [withQuery] and [execute] hand out a
 *    [Connection] wrapped by [guardedForAuthorSql]: every SQL text passes [DuckDbAuthorSqlGate] (TEMPORARY, owner ruling
 *    2026-11-03) on a connection whose configuration is already locked. DP's own statements run on
 *    an ungated lease.
 *  - **The memory bound is the engine's.** `memory_limit` bounds DuckDB's buffer manager; an
 *    allocation past it fails inside the engine and surfaces as `memory_limit_exceeded` with the
 *    engine's reading ([DuckDbEngineFailures]). [StagingStats.memoryUsedBytes] is the sum of
 *    `duckdb_memory()`. The limit does not bound the JVM heap or every native allocation
 *    (dp-lake design §11). This class supplies no combined JVM/native process-memory bound.
 *  - **The disk bound is the reservation.** A spill past `max_temp_directory_size` fails inside the
 *    engine; the database file and WAL have no engine-side bound at all, so the file, WAL and spill
 *    directory are measured after every write and a total past the reservation fails as
 *    `scratch_limit_exceeded`. That check runs after the statement: one statement can overshoot
 *    the reservation before it is caught (staging.md §8.5 states the limit).
 *  - **Values are appended, not bound.** Inserts go through the driver's appender with each value
 *    converted to its column's exact Java type ([DuckDbValueBinder]).
 *  - **[close] drops nothing table by table**: the file dies. It closes the root connection and
 *    deletes the file, WAL and spill directory, then releases the reservation — at once when no
 *    lease is outstanding, otherwise when the last one returns.
 *  - **Author statements this class creates carry the configured timeout**: [withQuery] and
 *    [execute] set it, and [close] cancels statements still running before it closes the pool,
 *    so the last lease comes back without waiting the timeout out. Two limits, both left to the
 *    query timeout: a `withConnection` statement is the caller's to bound (the executor sets the
 *    timeout and cancels it with the node), and the driver's cancel does nothing to a statement
 *    that has not yet entered its execute, so a [close] landing in those microseconds misses it.
 *    A zero query timeout disables the timeout bound, including that cancellation window.
 */
class DuckDbStaging internal constructor(
    override val executionId: UUID,
    private val pool: DuckDbLeasePool,
    private val config: DuckDbStagingProperties,
    private val files: DuckDbStagingFiles,
    private val reservation: ScratchReservation,
) : Staging {
    private val bookkeeping = Any()
    private val stagedTables = linkedSetOf<String>()
    private var stagedRowTotal = 0L
    private val running: MutableSet<Statement> = Collections.newSetFromMap(IdentityHashMap())
    private var closed = false
    private val failures = DuckDbEngineFailures(executionId, config.memoryLimitBytes, reservation.bytes)

    override suspend fun <T> withConnection(block: suspend (Connection) -> T): T = authorLease(block).also { checkScratch() }

    override suspend fun stage(
        resultSet: ResultSet,
        tableName: String,
        sourceDialect: Dialect,
        observer: StageObserver,
    ): StageResult {
        val metadata = resultSet.metaData
        val indices = 1..metadata.columnCount
        val columnNames = StagingIdentifiers.validateColumnNames(indices.map { metadata.getColumnLabel(it) })
        val mapped = columnNames.mapIndexed { i, name -> DuckDbStagingSql.mapSourceColumn(sourceDialect, name, metadata, i + 1) }
        val columns = mapped.map { it.column }
        val warnings = mapped.flatMap { it.warnings }.map { DuckDbStagingSql.boundedSourceType(it) }
        val mappings = columns.map { LogicalTypeMapping(it.type, it.precision, it.scale) }

        createStagedTable(tableName, columns, observer)
        val rowsStaged =
            guardingPartialTable(tableName, observer) {
                drainBatches(tableName, columns, observer) { batchSize -> H2StagingSql.readBatch(resultSet, mappings, batchSize) }
            }
        recordStaged(rowsStaged)
        return StageResult(tableName, rowsStaged, columns, warnings)
    }

    override suspend fun stageRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        observer: StageObserver,
    ): StageResult {
        StagingIdentifiers.validateColumnNames(columns.map { it.name })
        createStagedTable(tableName, columns, observer)
        val iterator = rows.iterator()
        val rowsStaged =
            guardingPartialTable(tableName, observer) {
                drainBatches(tableName, columns, observer) { batchSize ->
                    buildList {
                        while (size < batchSize && iterator.hasNext()) {
                            val row = iterator.next()
                            require(row.size == columns.size) {
                                "Row has ${row.size} values for ${columns.size} columns of table '$tableName'"
                            }
                            add(row)
                        }
                    }
                }
            }
        recordStaged(rowsStaged)
        return StageResult(tableName, rowsStaged, columns)
    }

    override suspend fun <T> withQuery(
        sql: String,
        block: suspend (ResultSet) -> T,
    ): T =
        authorLease { connection ->
            connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
                stmt.queryTimeout = config.queryTimeoutSeconds
                stmt.fetchSize = config.resultBatchSize
                stmt.closeOnCompletion()
                inFlight(stmt) { block(stmt.executeQuery(sql)) }
            }
        }

    /**
     * Returns the affected row count; DDL reports 0 (JDBC's contract — the pinned driver returns −1
     * for a `CREATE TABLE … AS`, lane #795 probe 16). Bounded as [withQuery] is: the configured
     * query timeout, and [close] cancels it.
     */
    override suspend fun execute(sql: String): Long {
        val affected =
            authorLease { connection ->
                connection.createStatement().use { stmt ->
                    stmt.queryTimeout = config.queryTimeoutSeconds
                    inFlight(stmt) { stmt.executeUpdate(sql) }.toLong()
                }
            }
        checkScratch()
        return affected.coerceAtLeast(0L)
    }

    override suspend fun stats(): StagingStats {
        val (tableCount, memoryUsed) =
            internalLease { connection -> DuckDbStagingSql.countTables(connection) to DuckDbStagingSql.memoryUsedBytes(connection) }
        return StagingStats(tableCount = tableCount, totalRows = synchronized(bookkeeping) { stagedRowTotal }, memoryUsedBytes = memoryUsed)
    }

    override fun close() {
        val toCancel =
            synchronized(bookkeeping) {
                closed = true
                running.toList()
            }
        toCancel.forEach(::cancel)
        val outstanding = pool.close()
        if (outstanding > 0) {
            log.warn(
                "tempdb closed with {} lease(s) still out for execution {}; its running statements were cancelled " +
                    "and the last lease to return deletes the database",
                outstanding,
                executionId,
            )
        }
    }

    /** Bytes on disk now (file + WAL + spill) — for measurements and tests, never part of [Staging]. */
    fun diskBytes(): Long = files.diskBytes()

    /** The scratch bytes this database reserved. */
    val reservationBytes: Long get() = reservation.bytes

    /** The most leases ever out at once — a measurement for tests. */
    val peakActiveLeases: Int get() = pool.peakActiveLeases

    /** Statements [withQuery] and [execute] have running now — a measurement for tests. */
    val inFlightStatements: Int get() = synchronized(bookkeeping) { running.size }

    // ------------------------------------------------------------ leases

    private suspend fun <T> authorLease(block: suspend (Connection) -> T): T =
        classifying { pool.lease({ it.guardedForAuthorSql() }, block) }

    private suspend fun <T> internalLease(block: suspend (Connection) -> T): T = classifying { pool.lease({ it }, block) }

    /** An engine failure leaving a lease becomes its catalogued code; any other passes unchanged. */
    private suspend inline fun <T> classifying(body: () -> T): T =
        try {
            body()
        } catch (e: SQLException) {
            throw failures.classify(e)
        }

    /**
     * Runs [body] with [statement] registered, so [close] can cancel it: around the execute and, for
     * a cursor, its reading. Refused once [close] ran, so no statement starts after its cancel pass.
     */
    private suspend fun <T> inFlight(
        statement: Statement,
        body: suspend () -> T,
    ): T {
        synchronized(bookkeeping) {
            check(!closed) { "staging database for execution $executionId is closed" }
            running += statement
        }
        try {
            return body()
        } finally {
            synchronized(bookkeeping) { running -= statement }
        }
    }

    /** The driver refuses `cancel()` on a statement that closed meanwhile: it finished, nothing to stop. */
    private fun cancel(statement: Statement) {
        try {
            statement.cancel()
        } catch (e: SQLException) {
            log.debug("tempdb statement of execution {} had already closed when close() cancelled it: {}", executionId, e.message)
        }
    }

    /** The disk bound (class KDoc): measured after a write, refused past the reservation. */
    private fun checkScratch() {
        val measured = files.diskBytes()
        if (measured > reservation.bytes) {
            throw StagingScratchLimitException(executionId, reservation.bytes, measuredBytes = measured, engineReading = null)
        }
    }

    // ------------------------------------------------------------ table ownership (as H2Staging)

    private suspend fun createStagedTable(
        tableName: String,
        columns: List<ColumnSchema>,
        observer: StageObserver,
    ) {
        synchronized(bookkeeping) { if (!stagedTables.add(tableName)) throw StagingTableAlreadyExistsException(tableName) }
        var created = false
        try {
            observer.connectionRequested()
            pool.lease({ it }) { connection ->
                observer.connectionAcquired()
                try {
                    DuckDbStagingSql.createTable(connection, tableName, columns)
                } catch (e: SQLException) {
                    throw failures.classifyCreate(e, tableName)
                }
            }
            created = true
        } finally {
            if (!created) synchronized(bookkeeping) { stagedTables.remove(tableName) }
        }
    }

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
            rollbackAfterFailure(tableName, observer)
            throw e
        }

    private suspend fun rollbackAfterFailure(
        tableName: String,
        observer: StageObserver,
    ) {
        withContext(NonCancellable) {
            val dropped = withTimeoutOrNull(ROLLBACK_LEASE_TIMEOUT_MS) { dropPartialTable(tableName) }
            if (dropped == true) {
                synchronized(bookkeeping) { stagedTables.remove(tableName) }
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

    /** A private method, not an inline lease in the lambda above: see H2Staging's `dropPartialTable`. */
    private suspend fun dropPartialTable(tableName: String): Boolean =
        pool.lease({ it }) { connection -> DuckDbStagingSql.dropPartialTable(connection, tableName, executionId) }

    private fun recordStaged(rows: Long) {
        synchronized(bookkeeping) { stagedRowTotal += rows }
    }

    // ------------------------------------------------------------ drain

    /**
     * The batched drain, H2's shape: [nextBatch] runs holding no lease, each batch is appended on a
     * lease of its own and the disk bound is measured after it. A source with no rows still takes
     * one (empty) lease, so the observer sees the same boundaries as on H2.
     */
    private suspend fun drainBatches(
        tableName: String,
        columns: List<ColumnSchema>,
        observer: StageObserver,
        nextBatch: (Int) -> List<List<Any?>>,
    ): Long {
        val binders = columns.map { DuckDbValueBinder(tableName, it) }
        val batchSize = config.insertBatchSize
        var rowCount = 0L
        while (true) {
            coroutineContext.ensureActive()
            observer.fetchStarted()
            val batch = nextBatch(batchSize)
            observer.fetchFinished(batch.size)
            val exhausted = batch.size < batchSize
            if (batch.isNotEmpty() || rowCount == 0L) {
                observer.connectionRequested()
                internalLease { connection ->
                    observer.connectionAcquired()
                    DuckDbStagingSql.appendBatch(connection, tableName, binders, batch)
                }
                rowCount += batch.size
                observer.batchWritten(batch.size, rowCount)
                checkScratch()
            }
            if (exhausted) break
        }
        return rowCount
    }

    private companion object {
        val log = LoggerFactory.getLogger(DuckDbStaging::class.java)

        /** As H2Staging: how long the partial-table rollback may wait for a lease (§6). */
        const val ROLLBACK_LEASE_TIMEOUT_MS = 30_000L
    }
}
