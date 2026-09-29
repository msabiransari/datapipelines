package co.datapipelines.persistence

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An ordered, bounded group commit in front of one store (#266; dag-executor §10, auth §10).
 *
 * Callers [record] an item and get back only once it is durable — or definitively not — exactly as
 * they did when each of them wrote its own row. What changes is who writes and how often: items
 * go into one of [BatchingConfig.writers] FIFO partitions, chosen by the item's key, and each
 * partition's single writer thread commits whatever is queued as ONE batch. Under no load a lone
 * item is written the moment it arrives; under load, N callers waiting on one commit share it.
 *
 * ## The guarantees, and where each is enforced
 * - **Durable before the caller proceeds.** [record] returns [Outcome.Committed] only after the
 *   store accepted the item; nothing is acknowledged from memory. The only non-awaited entry
 *   point is [submit], whose items are lost if the process dies before they are written (the
 *   documented loss window) — it has no production caller yet (the dashboard refresh event, D27).
 * - **Per-key order.** One key, one partition, one writer, FIFO, one batch at a time; a failed
 *   batch is retried one item at a time IN ORDER.
 * - **A bad item costs itself only.** A batch that throws is retried as singles: the item the
 *   store refuses is skipped, counted and named in one WARN; every other item commits.
 * - **Bounded waits.** A full queue or a slow commit never holds a caller longer than
 *   [BatchingConfig.recordMaxWaitMillis]: the caller then takes its item BACK from the queue (a
 *   compare-and-set, so the writer can never also write it) and writes it itself. An item that is
 *   already inside a commit cannot be taken back; the blocking [record] then waits for that
 *   commit's outcome — which is what the caller's own direct write would have done — while the
 *   suspending [recordSuspending] waits one more bound and reports [Outcome.Indeterminate].
 * - **Bounded memory.** [BatchingConfig.queueMaxEvents] and [BatchingConfig.queueMaxBytes] count
 *   every item from admission until its outcome, including items inside a commit.
 * - **Bounded shutdown.** [stop] flushes for at most [BatchingConfig.shutdownDrainMillis], then
 *   fails what is left, counts it and says so — the only loss the writer itself can cause.
 *
 * The writer never inspects, logs or truncates an item: the [BatchSink] names it for log lines, and
 * a failure is named by its class and SQLState alone ([FailureShape]) — a store's exception message
 * can carry the very row it refused.
 */
class BatchingWriter<T : Any>(
    val name: String,
    private val config: BatchingConfig,
    private val sink: BatchSink<T>,
    /** Bound by the wiring (observability §4); swappable because the audit writer is built before the web metrics exist. */
    @Volatile var hooks: BatchingHooks = BatchingHooks.NONE,
    threadFactory: ThreadFactory = defaultThreadFactory(name),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(BatchingWriter::class.java)
    private val capacityLock = ReentrantLock()
    private val capacityFreed = capacityLock.newCondition()

    /** Completed and replaced whenever capacity is released — the suspending callers' wake-up. */
    private var capacitySignal = CompletableFuture<Unit>()
    private var admittedEvents = 0
    private var admittedBytes = 0L

    private val accepting = AtomicBoolean(true)
    private val saturationWarn = IntervalWarn(SATURATION_WARN_INTERVAL_NANOS)
    private val partitions = List(config.writers) { Partition(it) }
    private val threads = partitions.map { p -> threadFactory.newThread { p.run() }.also { it.start() } }

    /**
     * Writes [item] durably and returns once it is — or once it definitively is not. Blocks the
     * calling thread; the direct path (a full queue, a slow commit, a stopped writer) writes on it.
     * Never throws for a store failure: that is [Outcome.Failed].
     */
    fun record(item: T): Outcome =
        when (val admission = admitBlocking(item)) {
            is Admission.Refused -> direct(item, admission.reason)
            is Admission.Queued -> awaitOrReclaim(admission)
        }

    /**
     * [record] for a caller that must not block a thread and must not wait without bound — the
     * event emitter, whose terminal emit runs under `NonCancellable` (dag-executor §10). Waits at
     * most [BatchingConfig.recordMaxWaitMillis] for the batch, then at most as long again for the
     * direct write (run on [directExecutor], never on the caller's dispatcher) or for an item
     * already inside a commit; after that it returns [Outcome.Indeterminate] and the write, if it
     * is still running, may land later. A direct write abandoned before it started never runs.
     */
    suspend fun recordSuspending(
        item: T,
        directExecutor: Executor,
    ): Outcome =
        when (val admission = admitSuspending(item)) {
            is Admission.Refused -> directBounded(item, admission.reason, directExecutor)
            is Admission.Queued -> awaitOrReclaimSuspending(admission, directExecutor)
        }

    private fun admitBlocking(item: T): Admission<T> {
        if (!accepting.get()) return Admission.Refused(FallbackReason.STOPPED)
        val bytes = sink.sizeOf(item)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.recordMaxWaitMillis)
        if (!reserveBlocking(bytes, deadline)) return Admission.Refused(FallbackReason.SATURATED)
        return enqueue(item, bytes)?.let { Admission.Queued(it, deadline) } ?: Admission.Refused(FallbackReason.STOPPED)
    }

    private suspend fun admitSuspending(item: T): Admission<T> {
        if (!accepting.get()) return Admission.Refused(FallbackReason.STOPPED)
        val bytes = sink.sizeOf(item)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.recordMaxWaitMillis)
        if (!reserveSuspending(bytes, deadline)) return Admission.Refused(FallbackReason.SATURATED)
        return enqueue(item, bytes)?.let { Admission.Queued(it, deadline) } ?: Admission.Refused(FallbackReason.STOPPED)
    }

    /** Waited out the bound → take the item back and write it here; inside a commit → that commit's outcome. */
    private fun awaitOrReclaim(admission: Admission.Queued<T>): Outcome {
        val entry = admission.entry
        awaitBlocking(entry, admission.deadline - System.nanoTime())?.let { return it }
        if (!entry.claim()) {
            // Inside a commit: its outcome is the item's outcome, and this is exactly as long as the
            // caller's own INSERT would have taken against the same store.
            return entry.done.join()
        }
        release(entry)
        return direct(entry.item, FallbackReason.TIMEOUT)
    }

    private suspend fun awaitOrReclaimSuspending(
        admission: Admission.Queued<T>,
        directExecutor: Executor,
    ): Outcome {
        val entry = admission.entry
        val remaining = admission.deadline - System.nanoTime()
        if (remaining > 0) withTimeoutOrNull(nanosToMillisCeil(remaining)) { entry.done.copy().await() }?.let { return it }
        if (entry.claim()) {
            release(entry)
            return directBounded(entry.item, FallbackReason.TIMEOUT, directExecutor)
        }
        return withTimeoutOrNull(config.recordMaxWaitMillis) { entry.done.copy().await() }
            ?: indeterminate(listOf(entry.item))
    }

    /**
     * Queues [item] without waiting and without an acknowledgment: false when the queue is full or
     * the writer has stopped (counted as dropped). The item is lost if the process dies before it
     * is written — the loss window of the non-awaited path (D27). No production caller yet.
     */
    fun submit(item: T): Boolean {
        if (!accepting.get()) {
            hooks.onDropped()
            return false
        }
        val bytes = sink.sizeOf(item)
        if (!tryReserve(bytes)) {
            hooks.onDropped()
            saturated()
            return false
        }
        if (enqueue(item, bytes) == null) {
            hooks.onDropped()
            return false
        }
        return true
    }

    /** Items admitted and not yet finished — queued or inside a commit. */
    fun queueDepth(): Int = capacityLock.withLock { admittedEvents }

    /** [queueDepth] in bytes. */
    fun queuedBytes(): Long = capacityLock.withLock { admittedBytes }

    /**
     * Stops accepting (later [record]s write directly, [submit]s are refused), flushes what is
     * queued through the ordinary batch path for at most [BatchingConfig.shutdownDrainMillis], then
     * fails whatever is still queued. Idempotent; the second call reports nothing.
     */
    fun stop(): DrainReport {
        if (!accepting.compareAndSet(true, false)) return DrainReport(0, 0)
        partitions.forEach { it.wake() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownDrainMillis)
        threads.forEach { thread ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) thread.join(nanosToMillisCeil(remaining))
        }
        val lost = partitions.sumOf { it.abandonQueued() }
        val inFlight = queueDepth()
        if (lost > 0 || inFlight > 0) {
            if (lost > 0) hooks.onFailure(FailureKinds.DRAIN_LOST, lost)
            log.warn(
                "event=persistence.drain_incomplete writer={} lost={} in_flight={} drain_ms={} " +
                    "message=\"the store did not take the queue before the drain deadline; lost items were never written, " +
                    "in-flight items may or may not have been\"",
                name,
                lost,
                inFlight,
                config.shutdownDrainMillis,
            )
        } else {
            log.info("event=persistence.drained writer={}", name)
        }
        return DrainReport(lost, inFlight)
    }

    override fun close() {
        stop()
    }

    /** Whether partition [index]'s writer thread is still running — the observable half of "a writer died". */
    fun isWriterAlive(index: Int): Boolean = threads[index].isAlive

    // ------------------------------------------------------------------ capacity

    private fun admits(bytes: Int): Boolean =
        admittedEvents < config.queueMaxEvents && (admittedBytes + bytes <= config.queueMaxBytes || admittedEvents == 0)

    /** Null when [bytes] were reserved; otherwise the signal the next release completes. */
    private fun reserveOrSignal(bytes: Int): CompletableFuture<Unit>? =
        capacityLock.withLock {
            if (!admits(bytes)) return capacitySignal
            admittedEvents++
            admittedBytes += bytes
            null
        }

    private fun tryReserve(bytes: Int): Boolean =
        capacityLock.withLock {
            if (!admits(bytes)) return false
            admittedEvents++
            admittedBytes += bytes
            true
        }

    private fun reserveBlocking(
        bytes: Int,
        deadline: Long,
    ): Boolean {
        capacityLock.withLock {
            while (!admits(bytes)) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    saturated()
                    return false
                }
                capacityFreed.awaitNanos(remaining)
            }
            admittedEvents++
            admittedBytes += bytes
            return true
        }
    }

    /**
     * [reserveBlocking] without a thread: reserve-or-signal under ONE lock hold (a release between a
     * failed reserve and the capture of the signal would otherwise be slept through), then await the
     * next release until [deadline].
     */
    private suspend fun reserveSuspending(
        bytes: Int,
        deadline: Long,
    ): Boolean {
        var signal = reserveOrSignal(bytes)
        while (signal != null) {
            val remaining = deadline - System.nanoTime()
            val pending = signal
            val woken = remaining > 0 && withTimeoutOrNull(nanosToMillisCeil(remaining)) { pending.copy().await() } != null
            signal = reserveOrSignal(bytes)
            if (!woken && signal != null) {
                saturated()
                return false
            }
        }
        return true
    }

    private fun release(entry: Entry<T>) {
        capacityLock.withLock {
            admittedEvents--
            admittedBytes -= entry.bytes
            capacityFreed.signalAll()
            val signal = capacitySignal
            capacitySignal = CompletableFuture()
            signal.complete(Unit)
        }
    }

    private fun saturated() {
        saturationWarn.maybe {
            log.warn(
                "event=persistence.saturated writer={} queued={} max_events={} queued_bytes={} max_bytes={} " +
                    "message=\"the queue is full; callers are writing directly and submissions are refused\"",
                name,
                queueDepth(),
                config.queueMaxEvents,
                queuedBytes(),
                config.queueMaxBytes,
            )
        }
    }

    // ------------------------------------------------------------------ outcomes

    private fun enqueue(
        item: T,
        bytes: Int,
    ): Entry<T>? {
        val entry = Entry(item, bytes, System.nanoTime())
        val partition = partitions[Math.floorMod(sink.partitionKey(item).hashCode(), partitions.size)]
        if (!partition.offer(entry)) {
            release(entry)
            return null
        }
        return entry
    }

    private fun awaitBlocking(
        entry: Entry<T>,
        remainingNanos: Long,
    ): Outcome? {
        if (remainingNanos <= 0) return null
        return try {
            entry.done.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun direct(
        item: T,
        reason: FallbackReason,
    ): Outcome {
        hooks.onFallback(reason)
        return writeDirect(item)
    }

    @Suppress("TooGenericExceptionCaught") // the store's failure, of whatever type, is the item's outcome — never the caller's exception
    private fun writeDirect(item: T): Outcome =
        try {
            sink.writeOne(item)
            Outcome.Committed
        } catch (e: Exception) {
            val kind = sink.classify(e)
            hooks.onFailure(kind, 1)
            // The class and the SQLState, never the Throwable: a store's message can carry the row (FailureShape).
            log.warn(
                "event=persistence.direct_write_failed writer={} kind={} item={} cause={} sql_state={}",
                name,
                kind,
                sink.describe(item),
                FailureShape.cause(e),
                FailureShape.sqlState(e),
            )
            Outcome.Failed(kind, e)
        }

    private suspend fun directBounded(
        item: T,
        reason: FallbackReason,
        executor: Executor,
    ): Outcome {
        hooks.onFallback(reason)
        val started = AtomicBoolean(false)
        val write =
            CompletableFuture.supplyAsync({
                started.set(true)
                writeDirect(item)
            }, executor)
        // Awaiting the future cancels it on timeout: a write that has not started never will.
        val outcome = withTimeoutOrNull(config.recordMaxWaitMillis) { write.copy().await() }
        // Not started yet → it never will: a cancelled supplyAsync skips its supplier.
        if (outcome == null) write.cancel(false)
        return when {
            outcome != null -> {
                outcome
            }

            started.get() -> {
                indeterminate(listOf(item))
            }

            else -> {
                hooks.onFailure(FailureKinds.ABANDONED, 1)
                log.warn("event=persistence.direct_write_abandoned writer={} item={}", name, sink.describe(item))
                Outcome.Failed(FailureKinds.ABANDONED, null)
            }
        }
    }

    private fun indeterminate(items: List<T>): Outcome {
        hooks.onFailure(FailureKinds.INDETERMINATE, items.size)
        log.warn(
            "event=persistence.indeterminate writer={} items={} wait_ms={} " +
                "message=\"the store did not answer in time; the write may still land\"",
            name,
            items.joinToString(",") { sink.describe(it) },
            config.recordMaxWaitMillis * 2,
        )
        return Outcome.Indeterminate
    }

    private fun finish(
        entry: Entry<T>,
        outcome: Outcome,
        now: Long,
    ) {
        release(entry)
        if (entry.done.complete(outcome) && outcome == Outcome.Committed) hooks.onCommitted(now - entry.enqueuedAt)
    }

    // ------------------------------------------------------------------ the writers

    /** One FIFO queue and the one thread that commits it. */
    private inner class Partition(
        private val index: Int,
    ) {
        private val lock = ReentrantLock()
        private val notEmpty = lock.newCondition()
        private val queue = ArrayDeque<Entry<T>>()
        private var queuedBytes = 0L
        private var exited = false

        fun offer(entry: Entry<T>): Boolean =
            lock.withLock {
                if (exited) return false
                queue.addLast(entry)
                queuedBytes += entry.bytes
                notEmpty.signal()
                true
            }

        fun wake() {
            lock.withLock { notEmpty.signalAll() }
        }

        /** At the drain deadline: fail everything still queued. Claimed items were already released by their callers. */
        fun abandonQueued(): Int =
            lock.withLock {
                var lost = 0
                while (queue.isNotEmpty()) {
                    val entry = queue.removeFirst()
                    if (entry.claim()) {
                        finish(entry, Outcome.Failed(FailureKinds.DRAIN_LOST, null), System.nanoTime())
                        lost++
                    }
                }
                exited = true
                lost
            }

        @Suppress("TooGenericExceptionCaught") // a writer thread must outlive any one batch's failure
        fun run() {
            while (true) {
                val batch = take() ?: return
                try {
                    commit(batch)
                } catch (e: Throwable) {
                    // Reached only by an Error (commit() handles every Exception): fail the batch so
                    // no caller waits on it forever, then let the Error end this thread.
                    batch.forEach { finish(it, Outcome.Failed(FailureKinds.WRITE_FAILED, e), System.nanoTime()) }
                    log.error("event=persistence.writer_died writer={} partition={}", name, index, e)
                    lock.withLock { exited = true }
                    throw e
                }
            }
        }

        /** The next batch, or null when stopped with nothing left. */
        private fun take(): List<Entry<T>>? =
            lock.withLock {
                // Busy = at least BUSY_QUEUE items were already waiting the moment this writer became
                // free: several producers queued up behind the previous commit. Only then does linger
                // apply. ONE waiting item is not load — an awaiting producer has at most one item
                // outstanding, and its next one routinely lands between its commit's completion and
                // this line; lingering on it would add linger-ms to every sequential write (the idle
                // test pins this). A writer that has to wait for its next item writes it at once.
                val busy = queue.size >= BUSY_QUEUE
                while (queue.isEmpty()) {
                    if (!accepting.get()) {
                        exited = true
                        return null
                    }
                    notEmpty.await()
                }
                if (busy && config.lingerMillis > 0) linger()
                drain()
            }

        /** Busy only: wait up to linger-ms for the batch to fill. Called with [lock] held. */
        private fun linger() {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.lingerMillis)
            while (queue.size < config.batchMaxEvents && queuedBytes < config.batchMaxBytes && accepting.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return
                notEmpty.awaitNanos(remaining)
            }
        }

        /** FIFO up to the batch bounds, skipping items their callers claimed back. Called with [lock] held. */
        private fun drain(): List<Entry<T>> {
            val batch = ArrayList<Entry<T>>(minOf(queue.size, config.batchMaxEvents))
            var bytes = 0L
            while (queue.isNotEmpty() && batch.size < config.batchMaxEvents) {
                val next = queue.peekFirst()
                if (batch.isNotEmpty() && bytes + next.bytes > config.batchMaxBytes) break
                queue.removeFirst()
                queuedBytes -= next.bytes
                if (next.take()) {
                    batch += next
                    bytes += next.bytes
                }
            }
            return batch
        }

        @Suppress("TooGenericExceptionCaught") // see writeDirect: a store failure is an outcome
        private fun commit(batch: List<Entry<T>>) {
            if (batch.isEmpty()) return
            val t0 = System.nanoTime()
            try {
                sink.write(batch.map { it.item })
                val now = System.nanoTime()
                hooks.onBatch(batch.size, batch.sumOf { it.bytes.toLong() }, now - t0)
                batch.forEach { finish(it, Outcome.Committed, now) }
            } catch (e: Exception) {
                if (batch.size == 1) failSingle(batch.single(), e) else retryAsSingles(batch, e)
            }
        }

        @Suppress("TooGenericExceptionCaught") // see writeDirect
        private fun retryAsSingles(
            batch: List<Entry<T>>,
            batchFailure: Exception,
        ) {
            hooks.onBatchRetried(batch.size)
            val failed = mutableListOf<String>()
            batch.forEach { entry ->
                try {
                    val t0 = System.nanoTime()
                    sink.write(listOf(entry.item))
                    val now = System.nanoTime()
                    hooks.onBatch(1, entry.bytes.toLong(), now - t0)
                    finish(entry, Outcome.Committed, now)
                } catch (e: Exception) {
                    val kind = sink.classify(e)
                    hooks.onFailure(kind, 1)
                    failed += "${sink.describe(entry.item)}($kind)"
                    finish(entry, Outcome.Failed(kind, e), System.nanoTime())
                }
            }
            log.warn(
                "event=persistence.batch_retried writer={} batch={} committed={} failed={} failed_items={} cause={} sql_state={}",
                name,
                batch.size,
                batch.size - failed.size,
                failed.size,
                failed.joinToString(","),
                FailureShape.cause(batchFailure),
                FailureShape.sqlState(batchFailure),
            )
        }

        private fun failSingle(
            entry: Entry<T>,
            e: Exception,
        ) {
            val kind = sink.classify(e)
            hooks.onFailure(kind, 1)
            log.warn(
                "event=persistence.write_failed writer={} kind={} item={} cause={} sql_state={}",
                name,
                kind,
                sink.describe(entry.item),
                FailureShape.cause(e),
                FailureShape.sqlState(e),
            )
            finish(entry, Outcome.Failed(kind, e), System.nanoTime())
        }
    }

    /** Whether an item got into the queue — and if not, why the caller writes it itself. */
    private sealed interface Admission<T> {
        data class Queued<T>(
            val entry: Entry<T>,
            val deadline: Long,
        ) : Admission<T>

        data class Refused<T>(
            val reason: FallbackReason,
        ) : Admission<T>
    }

    /**
     * One recorded item. [state] is the one place the writer and the item's caller race: the
     * writer [take]s it into a batch, the caller [claim]s it back for the direct path, and only
     * one compare-and-set can win — so an item is written by exactly one of them.
     */
    private class Entry<T>(
        val item: T,
        val bytes: Int,
        val enqueuedAt: Long,
    ) {
        private val state = AtomicInteger(QUEUED)
        val done = CompletableFuture<Outcome>()

        fun take(): Boolean = state.compareAndSet(QUEUED, TAKEN)

        fun claim(): Boolean = state.compareAndSet(QUEUED, CLAIMED)
    }

    /** A WARN at most once per interval — saturation is a state, not an event per item. */
    private class IntervalWarn(
        private val intervalNanos: Long,
    ) {
        private val last = AtomicLong(System.nanoTime() - intervalNanos)

        fun maybe(warn: () -> Unit) {
            val now = System.nanoTime()
            val previous = last.get()
            if (now - previous >= intervalNanos && last.compareAndSet(previous, now)) warn()
        }
    }

    private companion object {
        const val QUEUED = 0
        const val TAKEN = 1
        const val CLAIMED = 2

        /** Items waiting when a writer becomes free that mark it busy (see `take`). */
        const val BUSY_QUEUE = 2
        val SATURATION_WARN_INTERVAL_NANOS: Long = TimeUnit.SECONDS.toNanos(10)

        fun nanosToMillisCeil(nanos: Long): Long = (nanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI

        const val NANOS_PER_MILLI = 1_000_000L

        fun defaultThreadFactory(name: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "dp-persist-$name-${n.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}
