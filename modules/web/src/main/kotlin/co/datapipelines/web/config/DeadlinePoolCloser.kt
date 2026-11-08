package co.datapipelines.web.config

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.PoolCloser
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.lake.LakeLeases
import co.datapipelines.persistence.FailureShape
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import javax.sql.DataSource

/**
 * #818d (dp-lake record §10 RULING 4 amended, item 6) — the production [PoolCloser]: every close the reaper's pass
 * hands off runs on a `dp-pool-close-<n>` thread, never on the pass's own `dp-reaper` thread, under a per-close
 * deadline counted from the hand-off.
 *
 * - **The deadline is the pool's own (#840).** [deadlineOf]: [PoolCloser.deadlineFor] at the pool's own
 *   [ConnectionPool.connectionTimeout], plus [managedReleaseBound] for a managed lake generation, whose close also
 *   releases its reader pins on the metadata database (the fourth term; see [PoolCloser.deadlineFor]). 65 s for a pool
 *   at Hikari's default 30 s ([PoolCloser.DEADLINE_SECONDS]); a datasource configured with a longer
 *   `connectionTimeout` gets a longer deadline instead of a stuck report while Hikari is inside a wait it admits.
 *
 * - **The pass never waits.** [close] submits and returns; a Hikari shutdown that runs its full waits, or a driver
 *   close that never returns, costs one `dp-pool-close` thread, never the next pass.
 * - **A stuck close is reported once, never retried.** Past the deadline the close is still running (the thread is
 *   left to finish; nothing can safely cut a native close): one WARN `datasource.pool_close_stuck` with the datasource,
 *   the instance generation, the seconds elapsed since the hand-off, the active count the pass read at hand-off and the
 *   deadline it was held to, and one increment of `datapipelines.datasource.pool.close_stuck`. [ConnectionPool.close] starts independent
 *   physical-handle close attempts first. A native reader can remain blocked; reader pins follow its physical lifetime.
 * - **Threads: daemon, named, one per close, never queued.** A cached executor: a thread is made for a hand-off
 *   when none is idle, and an idle one ends after [IDLE_SECONDS]. No cap and no queue, on purpose: a close waiting
 *   behind other closes would delay even starting its generation's physical close attempts. Reader termination is
 *   not bounded by this executor; live handles retain their snapshot pins until confirmed physical closure.
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
    /**
     * #840 — the fourth deadline term, for a managed lake generation only: the bound of its reader-pin release
     * ([managedReleaseBound] derives it from the metadata pool). Zero where no pins are wired.
     */
    private val managedReleaseBound: Duration = Duration.ZERO,
    /** The pool-independent terms, [PoolCloser.FIXED_CLOSE_TERMS]; a test scales them down to seconds. */
    private val fixedTerms: Duration = PoolCloser.FIXED_CLOSE_TERMS,
) : PoolCloser,
    DisposableBean {
    private val threadCount = AtomicInteger()

    private val executor =
        ThreadPoolExecutor(0, Int.MAX_VALUE, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "$THREAD_PREFIX${threadCount.incrementAndGet()}").apply { isDaemon = true }
        }

    /** #840 — [pool]'s own close deadline (class KDoc). */
    fun deadlineOf(pool: ConnectionPool): Duration =
        PoolCloser.deadlineFor(
            pool.connectionTimeout,
            releaseBound = if (pool.managedGeneration != null) managedReleaseBound else Duration.ZERO,
            fixedTerms = fixedTerms,
        )

    override fun close(pool: ConnectionPool) {
        val handedOffAt = System.nanoTime()
        val activeAtHandOff = pool.activeConnections
        val deadline = deadlineOf(pool)
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
                    is TimeoutException -> stuck(pool, handedOffAt, activeAtHandOff, deadline)
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
        deadline: Duration,
    ) {
        LOG.warn(
            "event=datasource.pool_close_stuck datasource={} generation={} elapsed_seconds={} active_connections={} " +
                "deadline_seconds={} " +
                "message=\"a retired pool's close is still running past its deadline; the close thread is left to finish\"",
            pool.name,
            pool.generation ?: NO_GENERATION,
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - handedOffAt),
            activeAtHandOff,
            deadline.seconds,
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

        /**
         * #840 — the fourth term for a managed generation, derived from the metadata pool its pins are released on
         * (`PoolReaderLeaseConfiguration.managedGenerationPins` builds the keeper over the same [metadata]): the
         * checkout, bounded by that pool's own `connectionTimeout` (HikariCP's default when it is not a Hikari pool,
         * as the keeper reads it), plus the DELETE, bounded at `LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS` by its
         * statement timeout and its socket timeout. 10 + 5 = 15 s on the shipped metadata pool
         * (`spring.datasource.hikari.connection-timeout` = 10 000 ms since #837); 30 + 5 = 35 s on HikariCP's default.
         */
        fun managedReleaseBound(metadata: DataSource?): Duration =
            Duration
                .ofMillis((metadata as? HikariDataSource)?.connectionTimeout ?: HikariConfig().connectionTimeout)
                .plusSeconds(LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS.toLong())
    }
}
