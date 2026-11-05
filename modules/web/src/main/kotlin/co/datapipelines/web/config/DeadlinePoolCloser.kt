package co.datapipelines.web.config

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.PoolCloser
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * #818d (dp-lake record §10 RULING 4 amended, item 6) — the production [PoolCloser]: every close the reaper's pass
 * hands off runs on a `dp-pool-close-<n>` thread, never on the pass's own `dp-reaper` thread, under a per-close
 * deadline of [PoolCloser.DEADLINE_SECONDS] counted from the hand-off.
 *
 * - **The pass never waits.** [close] submits and returns; a Hikari shutdown that runs its full waits, or a driver
 *   close that never returns, costs one `dp-pool-close` thread, never the next pass.
 * - **A stuck close is reported once, never retried.** Past the deadline the close is still running (the thread is
 *   left to finish; nothing can safely cut a native close): one WARN `datasource.pool_close_stuck` with the datasource,
 *   the instance generation, the seconds elapsed since the hand-off and the active count the pass read at hand-off,
 *   and one increment of `datapipelines.datasource.pool.close_stuck`. The reader the close was for is not waiting on
 *   it: [ConnectionPool.close] interrupts every reader of a LAKE generation first.
 * - **Threads: daemon, named, one per close, never queued.** A cached executor: a thread is made for a hand-off
 *   when none is idle, and an idle one ends after [IDLE_SECONDS]. No cap and no queue, on purpose: a close waiting
 *   behind other closes would wait with its generation's readers still running — interrupt-first is the close's
 *   first act, and the reads lifetime rule counts the reader's termination from the hard close, not from a queue.
 *   The count is bounded by the closes in flight, and each is one retiring pool handed off exactly once (a pool
 *   leaves the retiring queue before the hand-off, and a stuck close is reported, never retried), so it grows only
 *   with closes that do not end — each of which is a `pool_close_stuck` WARN and a counted increment.
 * - **Destroyed after the drains.** A Spring singleton's `destroy` runs after every lifecycle stop, the execution
 *   drain included, and after the registry — which depends on this bean — has closed every pool synchronously
 *   (application shutdown never uses this closer: `ConnectionPoolManager.close`). Destruction stops taking
 *   closes; one already running finishes on its daemon thread or dies with the JVM.
 *
 * A hand-off this executor refuses (it has been destroyed) closes on the caller's thread: a pool is never left open
 * because of the order beans are torn down in.
 */
class DeadlinePoolCloser(
    private val metrics: PoolLifecycleMetrics,
    private val deadline: Duration = Duration.ofSeconds(PoolCloser.DEADLINE_SECONDS),
) : PoolCloser,
    DisposableBean {
    private val threadCount = AtomicInteger()

    private val executor =
        ThreadPoolExecutor(0, Int.MAX_VALUE, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "$THREAD_PREFIX${threadCount.incrementAndGet()}").apply { isDaemon = true }
        }

    override fun close(pool: ConnectionPool) {
        val handedOffAt = System.nanoTime()
        val activeAtHandOff = pool.activeConnections
        val running =
            try {
                CompletableFuture.runAsync(pool::close, executor)
            } catch (
                // Destroyed: close here rather than leave the generation open (class KDoc).
                @Suppress("SwallowedException") ignored: RejectedExecutionException,
            ) {
                pool.close()
                return
            }
        running
            .orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete { _, error ->
                when (val cause = unwrapped(error)) {
                    null -> Unit
                    is TimeoutException -> stuck(pool, handedOffAt, activeAtHandOff)
                    else -> failed(pool, cause)
                }
            }
    }

    /**
     * [ConnectionPool.close] promises not to throw; a close that does anyway is reported here, once, by class only
     * (a driver's message can carry a URL). On the synchronous path the same throw escaped into the reaper's pass and
     * cut it short for every pool after it; here it is contained to its own thread.
     */
    private fun failed(
        pool: ConnectionPool,
        cause: Throwable,
    ) {
        LOG.warn(
            "event=datasource.pool_close_failed datasource={} generation={} error={} " +
                "message=\"a retired pool's close threw; the pool is left to the JVM\"",
            pool.name,
            pool.generation ?: NO_GENERATION,
            FailureShape.cause(cause),
        )
    }

    private fun stuck(
        pool: ConnectionPool,
        handedOffAt: Long,
        activeAtHandOff: Int,
    ) {
        LOG.warn(
            "event=datasource.pool_close_stuck datasource={} generation={} elapsed_seconds={} active_connections={} " +
                "message=\"a retired pool's close is still running past its deadline; the close thread is left to finish\"",
            pool.name,
            pool.generation ?: NO_GENERATION,
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - handedOffAt),
            activeAtHandOff,
        )
        metrics.poolCloseStuck(pool.name)
    }

    /** Stops taking closes; one in flight finishes on its daemon thread (class KDoc). */
    override fun destroy() {
        executor.shutdown()
    }

    /** Closes handed off and not yet finished (tests, diagnostics). */
    fun inFlight(): Int = executor.activeCount

    private fun unwrapped(error: Throwable?): Throwable? = if (error is CompletionException) error.cause else error

    companion object {
        private val LOG = LoggerFactory.getLogger(DeadlinePoolCloser::class.java)

        /** The prefix of every close thread's name; the rest is a sequence number. */
        const val THREAD_PREFIX = "dp-pool-close-"

        /** How long an idle close thread lives before it ends — the cached executor's shape. */
        const val IDLE_SECONDS = 60L

        /** What the stuck line's `generation=` carries for a pool that owns no instance (every non-LAKE pool). */
        const val NO_GENERATION = "none"
    }
}
