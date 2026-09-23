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
 *  - **One thread per evaluation.** Each admitted evaluation runs on a FRESH daemon
 *    thread named `script-eval-N` — the abandoned thread is never reused, which is the
 *    replacement guarantee §4.3 states: an evaluation outliving its budget (a builtin
 *    the library cannot interrupt, say) keeps its thread until the work ends on its
 *    own or the JVM dies, and the next evaluation gets a new thread immediately.
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
 * NOT a `ThreadPoolExecutor`: a fixed worker pool cannot honour the replacement rule —
 * its worker would sit blocked inside the abandoned evaluation forever, starving every
 * later submission. The fixed resource here is the CAPACITY, not a worker set.
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
     * is full. [label] names the script in the abandonment log (the caller passes its
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
    ): T =
        try {
            if (!admission.tryAcquire()) throw ScriptPoolExhaustedException(size, queue)
            try {
                running.acquire()
                try {
                    await(limits, label, work)
                } finally {
                    running.release()
                }
            } finally {
                admission.release()
            }
        } catch (err: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ScriptEvaluationException("evaluation await interrupted", err)
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
        limits: EvaluationLimits,
        label: String,
        work: () -> T,
    ): T {
        val task = FutureTask(work)
        val thread = Thread(null, task, "script-eval-${counter.incrementAndGet()}")
        thread.isDaemon = true
        val deadline =
            clock.currentTimeMillis() + limits.wallClock.toMillis() + abandonGrace.toMillis()
        thread.start()
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
                    "finish or die with the JVM and the pool has already replaced it",
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
