package co.datapipelines.lake.maintenance.plan

import org.apache.iceberg.io.FileIO
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One attempt's in-process state (brief 959 D2): `running` → `publishing` or `abandoned`, by one compare-and-set. */
enum class AttemptState { RUNNING, PUBLISHING, ABANDONED }

/**
 * Seams at the two compare-and-sets, for the race test (N3b) only: production passes [NONE]. Each is called on the
 * thread that is about to try (or has just tried) its compare-and-set.
 */
interface PlanRaceHooks {
    fun beforeReaderSet(tableId: UUID) = Unit

    fun afterReaderSet(tableId: UUID) = Unit

    fun beforePassSet(tableId: UUID) = Unit

    fun afterPassSet(tableId: UUID) = Unit

    companion object {
        val NONE = object : PlanRaceHooks {}
    }
}

/**
 * The planner's own readers (brief 959 D2), not the settlement reader (which refuses a successor while a read is stuck).
 * A reader is one daemon thread with the object-store client it built on that thread. A measurement that ends in time
 * leaves its reader for the next table; a read still running at its bound is ABANDONED with its reader: the reader is
 * retired (never reused, never interrupted into a second use), and the next table gets a new one. When the abandoned
 * read ends, its client is closed on its own thread and the thread ends.
 *
 * Used by one pass at a time (the planner's recurring task runs on one instance at a time); [Reader.ended] is the only
 * field another thread writes.
 */
class PlanReaders(
    private val ioFactory: () -> FileIO,
    private val threadName: String = THREAD_NAME,
) : AutoCloseable {
    private val numbers = AtomicLong()
    private var live: Reader? = null
    private val abandoned = mutableListOf<Reader>()

    /** A reader: its thread, and the client built on that thread for its first measurement. */
    inner class Reader {
        private val executor: ExecutorService =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "$threadName-${numbers.incrementAndGet()}").apply { isDaemon = true }
            }
        private var io: FileIO? = null

        /** The table whose read was abandoned on this reader; null while it is live. */
        var tableId: UUID? = null
            internal set

        /** Set on the reader's own thread once an abandoned read has ended and its client is closed. */
        @Volatile
        var ended: Boolean = false
            private set

        /** Runs [task] on the reader's thread with its client (built there, on first use). */
        fun <T> submit(task: (FileIO) -> T): Future<T> = executor.submit<T> { task(io ?: ioFactory().also { io = it }) }

        /** On the reader's own thread, after an abandoned read: closes its client and lets the thread end. */
        internal fun endAbandoned() {
            try {
                io?.close()
            } finally {
                io = null
                ended = true
            }
        }

        internal fun retire() = executor.shutdown()

        internal fun shutdownLive() {
            executor.submit { io?.close() }
            executor.shutdown()
        }
    }

    /** The live reader, created when there is none. */
    fun reader(): Reader = live ?: Reader().also { live = it }

    /** Retires [reader] with its abandoned read of [tableId]: the next [reader] call builds a new one. */
    fun retire(
        reader: Reader,
        tableId: UUID,
    ) {
        if (live === reader) live = null
        reader.tableId = tableId
        abandoned += reader
        reader.retire()
    }

    /** Abandoned reads still running; one that has ended frees its place here. */
    fun outstanding(): Int {
        abandoned.removeAll { it.ended }
        return abandoned.size
    }

    /** The tables with an abandoned read still running: no second read is started for them. */
    fun outstandingTables(): Set<UUID> {
        abandoned.removeAll { it.ended }
        return abandoned.mapNotNull { it.tableId }.toSet()
    }

    /** Stops the live reader (its client closed on its thread); an abandoned read dies with its daemon thread. */
    override fun close() {
        live?.shutdownLive()
        live = null
    }

    companion object {
        const val THREAD_NAME = "lake-maintenance-plan-reader"
    }
}

/** One attempt: its id and its state, shared by the pass and the reader. */
class PlanAttempt(
    val tableId: UUID,
    val id: UUID,
) {
    val state = AtomicReference(AttemptState.RUNNING)
}
