package co.datapipelines.web

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A [ScheduledExecutorService] a test advances by hand (#564). What it MODELS:
 *
 *  - `execute` queues the task; [runPending] drains it FIFO, each task run to completion — a
 *    replay's ordering is proven by ONE synchronous run: the loop inside the task is the only
 *    order there is. Queued (rather than immediate) keeps the test the sole decider of when
 *    work happens, the same discipline `tick()` gives the follow cases.
 *  - `scheduleWithFixedDelay` records the task; every [tick] runs each live task AT MOST ONCE,
 *    never re-entrantly — fixed-delay semantics, one poll per tick.
 *  - the returned future's `cancel` stops the ticks: the streamer's `cancel(emitter)` path is
 *    observable as "one more tick does nothing".
 *  - a run that throws ends THAT task — a real `ScheduledThreadPoolExecutor` suppresses every
 *    subsequent execution of a throwing periodic task (the contract the log streamer's
 *    tick-level catch is written around) — and the throwable is kept on the future.
 *  - `shutdown`/`isShutdown`: shutdown rejects further work and stops the ticks; that is all
 *    the tests need of it.
 *
 * What it REFUSES (throws [UnsupportedOperationException] — an unmodelled call is loud, never
 * silently ignored): `schedule`, `scheduleAtFixedRate`, `submit`, `invokeAll`, `invokeAny`,
 * `awaitTermination`, `shutdownNow`, `isTerminated`, and the future's `get`/delay plumbing
 * beyond what cancellation needs. Single test thread; no internal locking.
 */
class TickScheduler : ScheduledExecutorService {
    private val pending = ArrayDeque<Runnable>()
    private val periodic = mutableListOf<FixedDelayTask>()

    @Volatile private var stopped = false

    /** Runs every queued `execute` task, FIFO, to completion — including ones queued en route. */
    fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }

    /** One poll: every live fixed-delay task runs at most once, in registration order. */
    fun tick() {
        periodic.removeAll { it.cancelled || it.dead }
        for (task in periodic.toList()) {
            if (task.running) continue
            task.running = true
            try {
                task.command.run()
            } catch (e: RuntimeException) {
                task.dead = true
                task.failure = e
            } finally {
                task.running = false
            }
        }
    }

    override fun execute(command: Runnable) {
        if (stopped) throw RejectedExecutionException("TickScheduler is shut down")
        pending.addLast(command)
    }

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> {
        if (stopped) throw RejectedExecutionException("TickScheduler is shut down")
        return FixedDelayTask(command).also { periodic.add(it) }
    }

    override fun shutdown() {
        stopped = true
        pending.clear()
        periodic.forEach { it.cancelled = true }
        periodic.clear()
    }

    override fun isShutdown(): Boolean = stopped

    override fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = refused("schedule(Runnable)")

    override fun <V : Any> schedule(
        callable: Callable<V>,
        delay: Long,
        unit: TimeUnit,
    ): ScheduledFuture<V> = refused("schedule(Callable)")

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit,
    ): ScheduledFuture<*> = refused("scheduleAtFixedRate")

    override fun <T : Any> submit(task: Callable<T>): java.util.concurrent.Future<T> = refused("submit(Callable)")

    override fun <T : Any> submit(
        task: Runnable,
        result: T,
    ): java.util.concurrent.Future<T> = refused("submit(Runnable, T)")

    override fun submit(task: Runnable): java.util.concurrent.Future<*> = refused("submit(Runnable)")

    override fun <T : Any> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<java.util.concurrent.Future<T>> =
        refused("invokeAll")

    override fun <T : Any> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): MutableList<java.util.concurrent.Future<T>> = refused("invokeAll(timeout)")

    override fun <T : Any> invokeAny(tasks: MutableCollection<out Callable<T>>): T = refused("invokeAny")

    override fun <T : Any> invokeAny(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit,
    ): T = refused("invokeAny(timeout)")

    override fun shutdownNow(): MutableList<Runnable> = refused("shutdownNow")

    override fun isTerminated(): Boolean = refused("isTerminated")

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = refused("awaitTermination")

    private fun refused(what: String): Nothing = throw UnsupportedOperationException("TickScheduler does not model $what")

    /** The handle a fixed-delay task is driven and cancelled through. */
    private inner class FixedDelayTask(
        val command: Runnable,
    ) : ScheduledFuture<Unit> {
        @Volatile var cancelled = false

        @Volatile var dead = false
        var running = false
        var failure: RuntimeException? = null

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (cancelled) return false
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled

        override fun isDone(): Boolean = cancelled || dead

        override fun get(): Unit =
            when {
                cancelled -> throw CancellationException("the task was cancelled")
                failure != null -> throw ExecutionException(failure)
                else -> refused("get() on a task that never completed")
            }

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Unit = get()

        override fun getDelay(unit: TimeUnit): Long = refused("getDelay")

        override fun compareTo(other: Delayed): Int = refused("compareTo")
    }
}
