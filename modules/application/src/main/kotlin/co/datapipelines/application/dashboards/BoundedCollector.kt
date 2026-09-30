package co.datapipelines.application.dashboards

import co.datapipelines.executor.DirectResultSink
import co.datapipelines.executor.ResultBytes
import co.datapipelines.typesystem.ColumnSchema
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A source's rows held in memory for one refresh: the typed table under the byte caps (spec §9.5). */
class CollectedTable(
    val columns: List<ColumnSchema>,
    val rows: List<List<Any?>>,
    /** The rows' cost in the result store's own accounting ([ResultBytes]). */
    val bytes: Long,
)

/**
 * The refresh-wide byte budget every collector of ONE refresh draws from (`max-bytes-per-refresh`). Atomic: sources
 * collect concurrently. A shared invocation collects once, so its bytes count once.
 */
class RefreshByteBudget(
    private val maxBytes: Long,
) {
    private val used = AtomicLong()

    /** Bytes held so far — the leak/accounting assertion surface. */
    val usedBytes: Long get() = used.get()

    /** Returns [bytes] a collector took and then dropped (its source failed): the rows are gone, the budget is theirs again. */
    fun give(bytes: Long) {
        used.addAndGet(-bytes)
    }

    /** Takes [bytes] if they fit; false (and nothing taken) when they would cross the cap. */
    fun tryTake(bytes: Long): Boolean {
        while (true) {
            val current = used.get()
            if (current + bytes > maxBytes) return false
            if (used.compareAndSet(current, current + bytes)) return true
        }
    }
}

/** Which cap a collector stopped at. */
enum class Overflow {
    /** The source's own cap (`max-bytes-per-source`): that source fails `result_too_large`. */
    SOURCE,

    /** The refresh's cap (`max-bytes-per-refresh`): the refresh ends PARTIAL. */
    REFRESH,
}

/**
 * The [DirectResultSink] a dashboard source streams into (spec §9 step 4, §9.5): copies rows into a typed in-memory
 * table, counting each row with [ResultBytes] — the accounting the result store uses — BEFORE keeping it, and STOPS at
 * the first row that would cross a cap: it never buffers past the cap. Stopping is a thrown [ResultTooLarge], which
 * ends the caller node's iteration (the open cursor is closed by its owner) and fails the execution; the runtime reads
 * [overflow] to tell "too large" from any other failure.
 *
 * One collector per invocation; [table] is set only when the whole result fit. The rows are released by the runtime
 * when the last consumer finished (dropping the reference is the release — a dashboard run never writes a store).
 */
class BoundedCollector(
    private val maxBytes: Long,
    private val budget: RefreshByteBudget,
) : DirectResultSink {
    private val collected = AtomicReference<CollectedTable?>()
    private val stoppedAt = AtomicReference<Overflow?>()

    /** The complete table, or null while running / after a failure or an overflow. */
    val table: CollectedTable? get() = collected.get()

    /** The cap this collector stopped at, or null. */
    val overflow: Overflow? get() = stoppedAt.get()

    override suspend fun accept(
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    ) {
        val kept = ArrayList<List<Any?>>()
        var bytes = 0L
        for (row in rows) {
            val cost = ResultBytes.rowBytes(row, schema)
            if (bytes + cost > maxBytes) stop(Overflow.SOURCE, bytes)
            if (!budget.tryTake(cost)) stop(Overflow.REFRESH, bytes)
            bytes += cost
            kept += row
        }
        collected.set(CollectedTable(schema, kept, bytes))
    }

    private fun stop(
        cap: Overflow,
        bytes: Long,
    ): Nothing {
        stoppedAt.set(cap)
        budget.give(bytes) // the partial table is dropped with the failure; do not keep charging the refresh for it
        throw ResultTooLarge(cap, bytes)
    }
}

/** Thrown by [BoundedCollector] at the first row over a cap; carries no row content. */
class ResultTooLarge(
    val cap: Overflow,
    val bytesBeforeStop: Long,
) : RuntimeException("a dashboard source's result crossed the ${cap.name.lowercase()} byte cap")
