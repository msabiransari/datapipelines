package co.datapipelines.scripting

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * The clock the pool reads. A one-method indirection for `java.time.Clock`: the module's
 * purity rule refuses the ambient clock API, so the caller injects the read instead.
 * [SYSTEM] is the single deliberate ambient read in this module — `ScriptingPurityTest`
 * asserts it is the ONLY one.
 */
fun interface ScriptClock {
    /** Milliseconds since the epoch, same semantics as the JDK wall clock. */
    fun currentTimeMillis(): Long
}

/**
 * The bounded evaluation pool (transform-nodes design §4.3, R7): every production
 * `ScriptEngine.evaluate` call runs here, never on a caller's own executor thread.
 *
 * ## Semantics
 *
 *  - **Capacity.** At most [size] evaluations RUN concurrently and at most [queue]
 *    submissions are ADMITTED at all (running + waiting together — the same reading
 *    the record fixes: on a 4/64 pool the 65th concurrent submission is refused). A
 *    refused submission is [ScriptPoolExhaustedException], immediately.
 *  - **One thread per evaluation, and the thread owns its slot.** Each admitted
 *    evaluation runs on a FRESH daemon thread named `script-eval-N`, and it is that
 *    THREAD — not the caller — that returns the running and admission permits, when the
 *    work actually ends. An evaluation outliving its budget (a builtin the library cannot
 *    interrupt, say) is abandoned by its caller but keeps its slot until its thread
 *    finishes: at most [size] evaluation threads are alive at any moment, abandoned ones
 *    included. This is the bulkhead (record §4.3 as corrected at the 7a merge, R7 — the
 *    executor's own fixed pool holds a `dag-executor` thread for an abandoned statement the
 *    same way): a runaway degrades transform capacity, never the rest of the JVM. The
 *    first reading of §4.3 released the slot at abandonment, which let every abandoned
 *    runaway add one more live thread with no ceiling.
 *  - **A caller waits for a slot at most its own wall clock**, then gets
 *    [ScriptPoolExhaustedException] — a saturated pool (runaways holding every slot) is a
 *    fast, typed refusal, never a caller hung behind someone else's evaluation.
 *  - **Abandonment.** `run` awaits at most `limits.wallClock + [abandonGrace]`
 *    (§4.3: an evaluation is abandoned once it has outlived its wall clock BY the
 *    grace). Past that: `Future.cancel(true)` — whose interrupt reaches nothing in
 *    the JSONata engine (no `Thread.interrupted()` anywhere in its sources) — one
 *    ERROR log naming the script, [abandoned] incremented, and
 *    [ScriptTimeoutException] to the caller. The thread lives on detached.
 *  - **No Micrometer here.** This module has no metrics dependency (typesystem has
 *    none to build on); [abandoned] is the `LongAdder` a caller scrapes into its own
 *    meter (`transform.evaluations.abandoned` is the record's name).
 *
 * NOT a `ThreadPoolExecutor`: the fixed resource here is the CAPACITY, not a worker set —
 * an abandoned evaluation's thread is never handed new work, and its slot comes back only
 * when that thread ends.
 */
class ScriptEvaluationPool(
    val size: Int,
    val queue: Int,
    val abandonGrace: Duration,
    private val clock: ScriptClock,
) {
    init {
        require(size > 0) { "size must be positive, was $size" }
        require(queue >= size) { "queue ($queue) must admit at least the $size running evaluations" }
        require(!abandonGrace.isNegative && abandonGrace != Duration.ZERO) {
            "abandonGrace must be positive, was $abandonGrace"
        }
    }

    /** Count of evaluations abandoned past their budget — scrape into a meter. */
    val abandoned = LongAdder()

    private val admission = java.util.concurrent.Semaphore(queue)

    private val running = java.util.concurrent.Semaphore(size)

    private val counter = AtomicInteger(0)

    private val abandonedThreads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()

    /**
     * Runs [work] under [limits], throwing [ScriptTimeoutException] when the budget
     * plus [abandonGrace] is overrun and [ScriptPoolExhaustedException] when the pool
     * is full — admission refused at once, or no running slot freed within the caller's
     * own wall clock (every slot held, abandoned runaways included). [label] names the script in the abandonment log (the caller passes its
     * template id@version; "script" when it has none). Errors from [work] propagate
     * unchanged when they are [ScriptingException]s; anything else is wrapped in
     * [ScriptEvaluationException].
     *
     * A caller thread interrupted while WAITING (for a slot or for the result) gets
     * [ScriptEvaluationException] with the interrupt flag restored — the pool never
     * leaks a raw `InterruptedException` into Kotlin callers, and never reports a
     * caller interruption as the script's timeout.
     */
    fun <T> run(
        limits: EvaluationLimits,
        label: String = DEFAULT_LABEL,
        work: () -> T,
    ): T {
        acquireSlot(limits)
        // From here the evaluation THREAD owns both permits: it returns them when the work
        // ends — normally, by failure, or long after its caller abandoned it (the bulkhead).
        val task = FutureTask(work)
        val thread = slotOwningThread(task)
        return try {
            await(task, thread, limits, label)
        } catch (err: InterruptedException) {
            throw awaitInterrupted(err)
        }
    }

    /** Admission first (immediate refusal when full), then a running slot within the caller's wall clock. */
    private fun acquireSlot(limits: EvaluationLimits) {
        val admitted = admission.tryAcquire() && acquireRunning(limits)
        if (!admitted) throw ScriptPoolExhaustedException(size, queue)
    }

    /** Waits at most [limits]' wall clock for a running slot; gives the admission permit back when none comes. */
    private fun acquireRunning(limits: EvaluationLimits): Boolean {
        val slot =
            try {
                running.tryAcquire(limits.wallClock.toMillis(), TimeUnit.MILLISECONDS)
            } catch (err: InterruptedException) {
                admission.release()
                throw awaitInterrupted(err)
            }
        if (!slot) admission.release()
        return slot
    }

    /** Starts the evaluation's own daemon thread, which returns both permits when [task] ends. */
    private fun <T> slotOwningThread(task: FutureTask<T>): Thread {
        val thread =
            Thread(
                null,
                {
                    try {
                        task.run()
                    } finally {
                        running.release()
                        admission.release()
                    }
                },
                "script-eval-${counter.incrementAndGet()}",
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

    /** The caller was interrupted while waiting — restore the flag; never report it as the script's timeout. */
    private fun awaitInterrupted(err: InterruptedException): ScriptEvaluationException {
        Thread.currentThread().interrupt()
        return ScriptEvaluationException("evaluation await interrupted", err)
    }

    /**
     * How many abandoned evaluations' threads are still alive right now — the breach
     * suite's "unbounded" evidence (§4.5: a thread alive past the grace is the
     * measurable difference between a bounded loss and an abandoned runaway).
     */
    fun abandonedThreadsAlive(): Int {
        abandonedThreads.removeIf { !it.isAlive }
        return abandonedThreads.size
    }

    private fun <T> await(
        task: FutureTask<T>,
        thread: Thread,
        limits: EvaluationLimits,
        label: String,
    ): T {
        val deadline =
            clock.currentTimeMillis() + limits.wallClock.toMillis() + abandonGrace.toMillis()
        try {
            return task.get(deadline - clock.currentTimeMillis(), TimeUnit.MILLISECONDS)
        } catch (
            @Suppress("SwallowedException") err: TimeoutException,
        ) {
            // The timeout ITSELF is the event being handled — the JDK exception carries
            // nothing the abandonment log below does not already record.
            abandoned.increment()
            abandonedThreads.add(thread)
            log.error(
                "event=script_evaluation_abandoned script={} budget_ms={} grace_ms={} - the " +
                    "engine cannot interrupt a running evaluation; the thread is left to " +
                    "finish or die with the JVM and keeps its pool slot until it ends",
                label,
                limits.wallClock.toMillis(),
                abandonGrace.toMillis(),
            )
            task.cancel(true)
            throw ScriptTimeoutException(limits.wallClock, label)
        } catch (
            @Suppress("SwallowedException") err: ExecutionException,
        ) {
            // `err` is only the wrapper; the WORK's throwable is `err.cause` and it is
            // rethrown or re-wrapped as the caller's exception below — nothing is lost.
            throw completionError(err.cause)
        }
    }

    /** Unwraps the work's failure: scripting exceptions and Errors as-is, the rest wrapped. */
    private fun completionError(cause: Throwable?): Nothing =
        when (cause) {
            is ScriptingException -> throw cause

            is Error -> throw cause

            else -> throw ScriptEvaluationException(
                "evaluation failed unexpectedly: ${cause?.message ?: cause?.javaClass?.name}",
                cause,
            )
        }

    companion object {
        private val log = LoggerFactory.getLogger(ScriptEvaluationPool::class.java)

        /** The label used when a caller has no script identity to name. */
        const val DEFAULT_LABEL = "script"

        /**
         * The module's ONE ambient clock read (see [ScriptClock]). `ScriptingPurityTest`
         * enforces the count: exactly one `currentTimeMillis` occurrence in src/main.
         */
        val SYSTEM = ScriptClock { System.currentTimeMillis() }
    }
}
