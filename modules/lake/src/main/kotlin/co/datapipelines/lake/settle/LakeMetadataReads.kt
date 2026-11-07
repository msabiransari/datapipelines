package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeTableProperties
import co.datapipelines.persistence.FailureShape
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.io.FileIO
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** What a bounded metadata read answered: the table's definition version, if it names one, or why there is no answer. */
sealed interface LakeMetadataRead {
    data class Version(
        val version: Int?,
    ) : LakeMetadataRead

    data class Deferred(
        val cause: SettleDeferCause,
    ) : LakeMetadataRead
}

/**
 * The settlement tick's metadata read (record §8b, D4): the tick's thread never waits on the object store past its budget.
 * The catalog row's location is read through the tick's own bounded template, then the metadata JSON is read on one
 * dedicated daemon thread, with the tick waiting at most what is left of its budget. A read that outlives the wait is
 * abandoned: it is logged and counted, holds no lease and no database connection, leaves one thread on one socket, and
 * refuses every successor until it ends, so there are never two. Its result is discarded; the row is verified by a later
 * tick. There is no retry loop here, no table operations and no catalog session; the store client's own bounds are
 * `LakeCatalogs.catalogProperties()`.
 *
 * Called by one tick at a time; the object-store client is built on the read's own thread, on the first read.
 */
class LakeMetadataReads(
    private val jdbc: NamedParameterJdbcTemplate,
    private val catalogName: String,
    ioFactory: () -> FileIO,
) : AutoCloseable {
    private val io = lazy(ioFactory)
    private val thread =
        Executors.newSingleThreadExecutor { task -> Thread(task, THREAD_NAME).apply { isDaemon = true } }
    private val stuckTotal = AtomicLong()

    @Volatile
    private var abandoned: AbandonedRead? = null

    /** Monotonic time the latest read ended, written on the read's own thread: an abandoned read's true duration. */
    @Volatile
    private var lastReadEnded = 0L

    /** True while an abandoned read is still running. Costs no statement; notes the end of one that has finished. */
    fun stuck(): Boolean {
        val read = abandoned ?: return false
        if (!read.future.isDone) return true
        abandoned = null
        logEnded(read)
        return false
    }

    /**
     * The definition version `<namespace>.<table>`'s current metadata file names, or why it could not be had within
     * [budget]. A missing catalog row or a read that fails propagates, as the catalog's own load did.
     */
    @Suppress("ReturnCount") // Each check exits before the group it guards.
    fun definitionVersion(
        tableId: UUID,
        namespace: String,
        table: String,
        budget: SettleBudget,
    ): LakeMetadataRead {
        if (stuck()) return LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)
        if (budget.expired()) return LakeMetadataRead.Deferred(SettleDeferCause.BUDGET)
        val location = metadataLocation(namespace, table)
        if (budget.expired()) return LakeMetadataRead.Deferred(SettleDeferCause.BUDGET)
        val started = System.nanoTime()
        val future = thread.submit<Int?> { readVersion(location) }
        return try {
            LakeMetadataRead.Version(future.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS))
        } catch (_: TimeoutException) {
            abandon(tableId, started, future)
            LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon(tableId, started, future)
            LakeMetadataRead.Deferred(SettleDeferCause.BUDGET)
        }
    }

    /** Stops the thread and closes its client. A read stuck on its socket ignores the interrupt and dies with its daemon thread. */
    override fun close() {
        thread.shutdownNow()
        if (io.isInitialized()) io.value.close()
    }

    private fun metadataLocation(
        namespace: String,
        table: String,
    ): String =
        checkNotNull(
            jdbc
                .queryForList(
                    "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = :catalog AND table_namespace = :namespace " +
                        "AND table_name = :table AND (iceberg_type = 'TABLE' OR iceberg_type IS NULL)",
                    mapOf("catalog" to catalogName, "namespace" to namespace, "table" to table),
                    String::class.java,
                ).singleOrNull(),
        ) { "the catalog has no table $namespace.$table" }

    private fun readVersion(location: String): Int? =
        try {
            TableMetadataParser
                .read(io.value, location)
                .properties()[LakeTableProperties.DEFINITION_VERSION]
                ?.toIntOrNull()
        } finally {
            lastReadEnded = System.nanoTime()
        }

    private fun abandon(
        tableId: UUID,
        started: Long,
        future: Future<Int?>,
    ) {
        abandoned = AbandonedRead(tableId, started, future)
        log.warn(
            "event=lake.settle.metadata_read_stuck table_id={} elapsed_ms={} stuck_total={} cause=none sql_state=none",
            tableId,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            stuckTotal.incrementAndGet(),
        )
    }

    private fun logEnded(read: AbandonedRead) {
        val failure = runCatching { read.future.get() }.exceptionOrNull()?.let { it.cause ?: it }
        log.info(
            "event=lake.settle.metadata_read_ended table_id={} elapsed_ms={} cause={} sql_state={}",
            read.tableId,
            TimeUnit.NANOSECONDS.toMillis(lastReadEnded - read.started),
            failure?.let(FailureShape::cause) ?: "none",
            failure?.let(FailureShape::sqlState) ?: "none",
        )
    }

    private class AbandonedRead(
        val tableId: UUID,
        val started: Long,
        val future: Future<Int?>,
    )

    companion object {
        /** The read's one thread; its name is what an operator sees in a thread dump. */
        const val THREAD_NAME = "dp-lake-metadata"

        private val log = LoggerFactory.getLogger(LakeMetadataReads::class.java)
    }
}
