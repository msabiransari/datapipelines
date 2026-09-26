package co.datapipelines.scheduler

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * **The admission gate and its shutdown order** (scheduler design revision §7.2, A7; R1's second
 * spike proof).
 *
 * `ExecutionDrainLifecycle` cancels every local execution on SIGTERM. db-scheduler's `Scheduler`
 * bean stops only through its `destroyMethod`, which Spring runs AFTER every lifecycle bean — so,
 * left alone, it would keep claiming and launching runs while the drain cancels executions,
 * and a run launched in that sliver could lose its start confirmation. This bean stops FIRST:
 * [SmartLifecycle.DEFAULT_PHASE] is the highest phase, and the drain sits one below it
 * (`ExecutionDrainLifecycle.PHASE`), so lifecycle beans stop in this order —
 *
 * 1. **this gate closes**: every later admission is refused before it takes capacity, and its
 *    run task is rescheduled — the run stays `queued`, never lost;
 * 2. db-scheduler's polling is **paused** ([pausePolling]) so it stops picking work at all;
 * 3. the gate **waits** up to [shutdownWait] for the launches already inside it to reach a
 *    definitive outcome (started, or not started);
 * 4. only then does the drain run — so a run that was mid-launch has its execution record, the
 *    drain aborts it as `ABORTED` / `shutdown`, and the reconciler records it `aborted`.
 *
 * [shutdownWait] is bounded by `SchedulerProperties.MAX_SHUTDOWN_WAIT_SECONDS`, under Spring's
 * 30 s per-phase timeout: a wedged launch delays shutdown by that bound, not forever.
 */
class SchedulerAdmission(
    private val shutdownWait: Duration,
    /** Pauses db-scheduler's polling; a no-op where the scheduler never started (API mode). */
    private val pausePolling: () -> Unit = {},
) : SmartLifecycle {
    private val open = AtomicBoolean(false)
    private val inFlight = AtomicInteger()

    /** Launches currently inside the gate. */
    val launching: Int get() = inFlight.get()

    /** True while admissions are accepted. */
    val isOpen: Boolean get() = open.get()

    /**
     * Runs [body] inside the gate, or returns null WITHOUT running it when the gate is closed. The
     * counter is taken BEFORE the open check, so [stop] — which closes, then waits for the counter —
     * can never miss a launch that passed the check.
     */
    fun <T> admit(body: () -> T): T? {
        inFlight.incrementAndGet()
        try {
            if (!open.get()) return null
            return body()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun start() {
        open.set(true)
    }

    override fun stop() {
        open.set(false)
        runCatching(pausePolling).onFailure { LOG.warn("db-scheduler polling could not be paused at shutdown", it) }
        val deadline = System.nanoTime() + shutdownWait.toNanos()
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        val left = inFlight.get()
        if (left > 0) {
            LOG.warn(
                "event=scheduler.admission_wait_expired launching={} wait_ms={} " +
                    "message=\"launches still in progress when the drain starts; their runs reconcile from the execution record\"",
                left,
                shutdownWait.toMillis(),
            )
        } else {
            LOG.info("event=scheduler.admission_closed message=\"no scheduled launch in progress; the drain may run\"")
        }
    }

    override fun isRunning(): Boolean = open.get()

    /** The highest phase — above the drain (`ExecutionDrainLifecycle.PHASE`), so this stops first. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE

    private companion object {
        private val LOG = LoggerFactory.getLogger(SchedulerAdmission::class.java)
        const val POLL_MILLIS = 20L
    }
}
