package co.datapipelines.scripting

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The JavaScript engine's wall-clock bound (#8 A.2b/A.3): one daemon thread that runs a Context's
 * `close(true)` when its evaluation's wall clock is up. `sandbox.MaxCPUTime` beside it counts CPU
 * only (a blocked or descheduled guest spends wall clock without CPU), so the watchdog is the
 * wall-clock bound and the pool's abandonment is the backstop.
 */
internal object JavaScriptWatchdog {
    private val scheduler: ScheduledThreadPoolExecutor =
        (
            Executors.newScheduledThreadPool(1) { task ->
                Thread(task, "dp-js-watchdog").apply { isDaemon = true }
            } as ScheduledThreadPoolExecutor
        ).apply { removeOnCancelPolicy = true }

    /** Runs [stop] after [wallClock] unless the returned handle is closed first. */
    fun arm(
        wallClock: Duration,
        stop: Runnable,
    ): AutoCloseable {
        val future: ScheduledFuture<*> = scheduler.schedule(stop, wallClock.toNanos(), TimeUnit.NANOSECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}
