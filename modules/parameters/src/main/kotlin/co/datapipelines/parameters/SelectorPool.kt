package co.datapipelines.parameters

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/** Who an abandoned statement belonged to — the ONE error line's three names; never the SQL, a bind value or a row. */
data class SelectorLabel(
    val set: String,
    val parameter: String,
    val datasource: String,
)

/** What [SelectorPool.run] answered. */
sealed interface SelectorAdmission {
    /** The task ran to its end on its own worker thread; [run] is what it produced. */
    data class Completed(
        val run: SelectorRun,
    ) : SelectorAdmission

    /** The queue was already full at admission — `parameter.evaluate.selectors_saturated`, decided at once. */
    data object Saturated : SelectorAdmission
}

/**
 * The selector bulkhead (record P31, §5.2 step 3) — `modules/scripting`'s `ScriptEvaluationPool`
 * shape, COPIED (`parameters` may not depend on `scripting`), for JDBC statements instead of
 * scripts. One per process: every evaluate's selector statements share it.
 *
 * ## Semantics
 *
 *  - **Capacity.** At most [size] statements RUN at once (`max-concurrent-selector-queries`) and at
 *    most [waiting] more are admitted to wait for a slot (`max-waiting-selector-queries`): the
 *    admission capacity is [queue] = `size + waiting`. §11 reads its 4/64 as "4 running, 64 more
 *    waiting" — the script pool's `queue` counts running AND waiting together, so the same 4/64 there
 *    admits 64 in all; here it admits 68, and the 69th concurrent submission is refused.
 *  - **Admission is decided at once.** A submission that finds the queue full is
 *    [SelectorAdmission.Saturated] immediately — `selectors_saturated` inline on its parameter, the
 *    response whole, never a form hung behind someone else's query. That is the only refusal the pool
 *    makes: everything after admission belongs to the caller's deadline (Astra's fourth-round item —
 *    the two never race).
 *  - **Waiting is the caller's to bound.** An admitted submission waits for a running slot on a
 *    SUSPENDING, cancellable acquire — the one deliberate difference from the script pool, whose
 *    caller blocks for a slot at most its own wall clock. Here the evaluate's `withTimeout` is the
 *    bound: a deadline that expires while a submission is QUEUED cancels the wait (the admission
 *    permit is returned at once) and is the whole-request `parameter.evaluate.timeout`.
 *  - **One thread per statement, and the thread owns its slot.** Each admitted statement runs on a
 *    FRESH daemon thread named `selector-N`, and it is that THREAD — never the caller — that returns
 *    the running and admission permits, when the driver call actually ends. So at most [size] JDBC
 *    worker threads exist at any moment, abandoned ones included, and repeated timeouts cannot
 *    accumulate workers (the 7a lesson the script pool's KDoc records: releasing the slot at
 *    abandonment let every runaway add a live thread with no ceiling).
 *  - **The deadline is on the AWAIT, never on the worker.** The caller suspends on the worker's
 *    result; the evaluate's `withTimeout` cancels that suspension whether or not the worker returns —
 *    cooperative cancellation reaches no JDBC driver. On cancellation the pool abandons the statement:
 *    [SelectorTask.abandon] (`Statement.cancel()`, best effort, then `ConnectionPool.discard`) runs on
 *    a detached `selector-abandon-N` thread — a driver's cancel may itself block on the network, and
 *    the caller answers `timeout` WITHOUT waiting — [abandoned] is incremented, and ONE error line
 *    names the set, the parameter and the datasource. The worker thread lives on, detached, and frees
 *    its slot when the driver returns.
 *  - **No Micrometer here.** [abandoned] is the `LongAdder` the assembling layer scrapes into the
 *    `parameters.selectors.abandoned` gauge (record §11 — lane D's bean, the
 *    `transform.evaluations.abandoned` shape).
 *
 * NOT a `ThreadPoolExecutor`: the fixed resource is the CAPACITY, not a worker set — an abandoned
 * statement's thread is never handed new work, and its slot comes back only when that thread ends.
 */
class SelectorPool(
    val size: Int,
    val waiting: Int,
) {
    constructor(config: ParametersConfig) : this(config.maxConcurrentSelectorQueries, config.maxWaitingSelectorQueries)

    init {
        require(size > 0) { "size must be positive, was $size" }
        require(waiting >= 0) { "waiting must not be negative, was $waiting" }
    }

    /** The admission capacity — running plus waiting. */
    val queue: Int = size + waiting

    /** Statements abandoned at their evaluate's deadline — scrape into the `parameters.selectors.abandoned` gauge. */
    val abandoned = LongAdder()

    private val admission = java.util.concurrent.Semaphore(queue)

    private val running = Semaphore(size)

    private val workers = AtomicInteger(0)

    private val cancellers = AtomicInteger(0)

    private val abandonedThreads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()

    /**
     * Runs [task] under the bulkhead: [SelectorAdmission.Saturated] at once when the queue is full;
     * otherwise waits (cancellably) for a running slot, runs the task on its own slot-owning thread and
     * suspends on its result. A cancellation of the caller while queued returns the admission permit;
     * while running it ABANDONS the task (see the class KDoc) and rethrows. A task that throws — a
     * defect, never an author's problem — rethrows its exception to the caller.
     */
    suspend fun run(
        label: SelectorLabel,
        task: SelectorTask,
    ): SelectorAdmission {
        if (!admission.tryAcquire()) return SelectorAdmission.Saturated
        try {
            running.acquire()
        } catch (e: CancellationException) {
            admission.release()
            throw e
        }
        // From here the worker THREAD owns both permits: it returns them when the task ends —
        // normally, by failure, or long after its caller abandoned it (the bulkhead).
        val result = CompletableDeferred<SelectorRun>()
        val worker = slotOwningThread(task, result)
        return SelectorAdmission.Completed(awaitWithHandover(label, task, result, worker))
    }

    /** The caller's await: the outcome once the worker's permits are back; abandonment on the deadline; a task's own failure rethrown. */
    private suspend fun awaitWithHandover(
        label: SelectorLabel,
        task: SelectorTask,
        result: CompletableDeferred<SelectorRun>,
        worker: Thread,
    ): SelectorRun {
        val outcome =
            try {
                result.await()
            } catch (e: CancellationException) {
                if (!result.isCompleted) abandon(label, task, worker)
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                // The task's own failure: the worker is ending; let its permits land before the caller acts on it.
                handover(worker)
                throw failure
            }
        handover(worker)
        return outcome
    }

    /**
     * The permit handover: the worker completes the result INSIDE its try and releases both permits in
     * its finally, so a caller resumed by the completion could re-submit before the slot was back and
     * be answered `Saturated` by its own just-finished task (CI's 2-vCPU runner did exactly that on
     * `a task that completes normally returns both permits`). `Completed` means the slot is back: the
     * caller joins the finishing thread — a handover of microseconds, never a wait for work (the
     * thread has already completed the result). An ABANDONED worker is never joined: the deadline
     * path above rethrows without waiting, which is the bulkhead's whole point.
     */
    private fun handover(worker: Thread) {
        worker.join()
    }

    /** Submissions admitted and not yet ended — running (abandoned included) plus waiting; at most [queue]. */
    fun admitted(): Int = queue - admission.availablePermits()

    /**
     * How many abandoned statements' worker threads are still alive right now — the bulkhead's
     * "bounded" evidence: an abandoned worker keeps its slot until this drops.
     */
    fun abandonedThreadsAlive(): Int {
        abandonedThreads.removeIf { !it.isAlive }
        return abandonedThreads.size
    }

    /** Starts the statement's own daemon thread, which completes [result] and returns both permits when [task] ends. */
    private fun slotOwningThread(
        task: SelectorTask,
        result: CompletableDeferred<SelectorRun>,
    ): Thread {
        // #337 (observability §3.3): the statement runs on a selector-N thread; it carries the
        // submitter's MDC (the node's correlation id) for the run, restored after. Module-local
        // capture: `parameters` may not depend on the executor's helpers and propagation is
        // normative — there is no switch to inject or remove.
        val submitted = MdcTask.capture()
        val thread =
            Thread(
                null,
                MdcTask.wrap(submitted) {
                    try {
                        result.complete(task.run())
                    } catch (
                        @Suppress("TooGenericExceptionCaught") failure: Throwable,
                    ) {
                        // Handed to the awaiting caller, whose exception it becomes — nothing is swallowed.
                        result.completeExceptionally(failure)
                    } finally {
                        running.release()
                        admission.release()
                    }
                },
                "selector-${workers.incrementAndGet()}",
            )
        thread.isDaemon = true
        var started = false
        try {
            thread.start()
            started = true
        } finally {
            // A thread that never ran never reaches its own finally — return the permits here.
            if (!started) {
                running.release()
                admission.release()
            }
        }
        return thread
    }

    /** The deadline passed while [task] ran: count it, say so once, and stop it off the caller's thread. */
    private fun abandon(
        label: SelectorLabel,
        task: SelectorTask,
        worker: Thread,
    ) {
        abandoned.increment()
        abandonedThreads.add(worker)
        log.error(
            "event=selector_statement_abandoned set={} parameter={} datasource={} - the evaluate's deadline passed; " +
                "the statement is cancelled (best effort) and its connection discarded, and the worker keeps its " +
                "slot until the driver returns",
            label.set.safeEcho(MAX_LABEL_CHARS),
            label.parameter.safeEcho(MAX_LABEL_CHARS),
            label.datasource.safeEcho(MAX_LABEL_CHARS),
        )
        Thread(
            // #337 (observability §3.3): the abandon work belongs to the evaluate that gave up —
            // captured from THIS thread (the caller's), installed on the detached thread.
            MdcTask.wrap(MdcTask.capture()) { task.abandon() },
            "selector-abandon-${cancellers.incrementAndGet()}",
        ).apply { isDaemon = true }
            .start()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SelectorPool::class.java)

        /** A set name is ≤ 200 characters by its grammar; a parameter or datasource name far less. */
        const val MAX_LABEL_CHARS = 200
    }
}
