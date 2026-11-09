package co.datapipelines.lake.settle

import co.datapipelines.lake.LakeTableProperties
import co.datapipelines.persistence.FailureShape
import com.fasterxml.jackson.databind.JsonNode
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.exceptions.RuntimeIOException
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.util.JsonUtil
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

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
 * abandoned: it is logged and counted, holds no lease and no database connection, and refuses every successor until it
 * ends, so there are never two. An abandoned read ends at its next chunk (#851): the read checks its stop between every
 * chunk the store returns, then closes its stream on its own thread and ends as [MetadataReadAbandoned]. A read blocked
 * on a store that has stopped answering sees the stop only when the client gives up: its conditional network-wait envelope is
 * `SettlementObjectReadEnd.stalledReadEndByMillis`, read from the same effective properties this client is built from
 * (`LakeCatalogs.catalogProperties()`), 420 400 ms under the stalled-store model in docs/lake.md §8, excluding DNS,
 * credentials, writes, TLS setup and local scheduling/processing; there is no unconditional end-to-end timer. The file is capped at
 * [METADATA_MAX_BYTES] of JSON after decompression. The result of an abandoned read is discarded; the row is verified
 * by a later tick. There is no retry loop here, no table operations and no catalog session.
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
        val stop = ReadStop()
        val future = thread.submit<Int?> { readVersion(location, stop) }
        return try {
            LakeMetadataRead.Version(future.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS))
        } catch (_: TimeoutException) {
            abandon(tableId, started, future, stop)
            LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon(tableId, started, future, stop)
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

    /**
     * `TableMetadataParser.read(io, location)` with its two steps kept (the codec by the file's name, the JSON parsed by
     * the library) and the stream guarded: [stop] is checked around every chunk, before and after the store returns it,
     * and the inflated bytes are counted against [METADATA_MAX_BYTES]. The stream is closed here, on the read's thread.
     */
    private fun readVersion(
        location: String,
        stop: ReadStop,
    ): Int? =
        try {
            val node =
                open(location, stop).use { stream -> JsonUtil.mapper().readValue(stream, JsonNode::class.java) }
            TableMetadataParser
                .fromJson(location, node)
                .properties()[LakeTableProperties.DEFINITION_VERSION]
                ?.toIntOrNull()
        } catch (failure: IOException) {
            throw ownFailure(failure) ?: RuntimeIOException(failure, "Failed to read file: %s", location)
        } finally {
            lastReadEnded = System.nanoTime()
        }

    private fun open(
        location: String,
        stop: ReadStop,
    ): InputStream {
        val raw = GuardedStream(io.value.newInputFile(location).newStream(), stop, location, cap = null)
        val inflated =
            try {
                if (TableMetadataParser.Codec.fromFileName(location) == TableMetadataParser.Codec.GZIP) GZIPInputStream(raw) else raw
            } catch (failure: IOException) {
                raw.close()
                throw failure
            }
        return GuardedStream(inflated, stop, location, METADATA_MAX_BYTES)
    }

    private fun abandon(
        tableId: UUID,
        started: Long,
        future: Future<Int?>,
        stop: ReadStop,
    ) {
        stop.requested = true
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
            when (failure) {
                null -> "none"
                is MetadataReadAbandoned -> ABANDONED_CAUSE
                else -> FailureShape.cause(failure)
            },
            failure?.let(FailureShape::sqlState) ?: "none",
        )
    }

    /** One read's stop, set by the tick's thread when it stops waiting and read by the read's own thread. */
    private class ReadStop {
        @Volatile
        var requested = false
    }

    /**
     * Checks [stop] before and after every chunk it passes on, and counts the bytes it passed against [cap] (none for the
     * stored bytes of a compressed file, whose inflated bytes are capped instead). Closes its stream once: the parser
     * closes the source it was given, and the read closes it again on the way out.
     */
    private class GuardedStream(
        delegate: InputStream,
        private val stop: ReadStop,
        private val location: String,
        private val cap: Long?,
    ) : FilterInputStream(delegate) {
        private var passed = 0L
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            super.close()
        }

        override fun read(): Int {
            checkStop()
            val byte = `in`.read()
            checkStop()
            if (byte >= 0) count(1)
            return byte
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            checkStop()
            val read = `in`.read(b, off, len)
            checkStop()
            if (read > 0) count(read.toLong())
            return read
        }

        override fun skip(n: Long): Long {
            checkStop()
            val skipped = `in`.skip(n)
            checkStop()
            if (skipped > 0) count(skipped)
            return skipped
        }

        private fun checkStop() {
            if (stop.requested) throw MetadataReadAbandoned(location)
        }

        private fun count(bytes: Long) {
            passed += bytes
            if (cap != null && passed > cap) throw MetadataFileTooLarge(location, cap)
        }
    }

    private class AbandonedRead(
        val tableId: UUID,
        val started: Long,
        val future: Future<Int?>,
    )

    companion object {
        /** The read's one thread; its name is what an operator sees in a thread dump. */
        const val THREAD_NAME = "dp-lake-metadata"

        /**
         * The most metadata JSON one read parses, after decompression (#851 D3; docs/lake.md §8): 64 MiB, 67 108 864 bytes.
         * A table's metadata file grows with its snapshot log, and the whole file is parsed into one tree; past this the
         * read fails.
         */
        const val METADATA_MAX_BYTES = 67_108_864L

        /** `lake.settle.metadata_read_ended`'s cause for a read that stopped itself after the tick abandoned it. */
        const val ABANDONED_CAUSE = "abandoned"

        private val log = LoggerFactory.getLogger(LakeMetadataReads::class.java)

        private const val MAX_CHAIN = 16

        /** This file's own failure in [failure]'s cause chain, whatever wrapped it on the way out of the parser. */
        private fun ownFailure(failure: Throwable): Throwable? =
            generateSequence(failure) { it.cause }
                .take(MAX_CHAIN)
                .firstOrNull { it is MetadataReadAbandoned || it is MetadataFileTooLarge }
    }
}

/** An abandoned read that stopped itself at its next chunk; carried by its future, logged as `cause=abandoned`. */
internal class MetadataReadAbandoned(
    location: String,
) : IOException("the settlement tick abandoned the read of $location")

/** A metadata file past [LakeMetadataReads.METADATA_MAX_BYTES] of JSON; the read fails at the cap. */
internal class MetadataFileTooLarge(
    location: String,
    cap: Long,
) : IOException("the metadata file $location is larger than the read's cap of $cap bytes")
