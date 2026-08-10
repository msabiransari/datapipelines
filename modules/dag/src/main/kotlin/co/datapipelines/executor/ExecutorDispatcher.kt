package co.datapipelines.executor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * The `dag` module's own bounded IO dispatcher (dag-executor.md §15.2).
 *
 * Executor code **never** references `Dispatchers.IO`: sharing the JVM-wide IO pool with Spring's
 * own blocking work makes executor throughput a function of unrelated load and makes starvation
 * impossible to attribute. Node work is blocking JDBC plus blocking Redis, so it needs real
 * threads — this is the pool that owns them, and its size is the only thing that bounds them.
 *
 * Sized from `max-concurrent-executions-global × max-parallel-nodes` (§15.2): the largest number
 * of nodes that can be doing SQL at one instant under the configured limits. Threads are daemons,
 * so a forgotten [close] cannot keep the JVM alive.
 */
class ExecutorDispatcher private constructor(
    private val pool: ExecutorService,
    /** The coroutine context every node coroutine is dispatched on. */
    val context: CoroutineDispatcher,
    /** Threads in the pool — the assertion surface for "this dispatcher is really bounded". */
    val threadCount: Int,
) : Closeable {
    override fun close() {
        pool.shutdownNow()
    }

    companion object {
        /**
         * Builds the dispatcher for [config].
         *
         * @param maxThreads an explicit override for tests that want a deliberately tiny pool;
         *   production passes nothing and gets the §15.2 sizing.
         */
        fun forConfig(
            config: ExecutorConfig,
            maxThreads: Int = config.maxConcurrentExecutionsGlobal * config.maxParallelNodes,
        ): ExecutorDispatcher {
            require(maxThreads > 0) { "ExecutorDispatcher needs at least one thread, was $maxThreads" }
            // §15.2 sizes the pool as global × per-execution parallelism, which multiplies: the
            // shipped defaults (100 × 4) already ask for 400 platform threads, and an operator who
            // raises either key gets thousands — ~1 MB of stack each — allocated eagerly at
            // startup. The ceiling keeps the sizing rule as the intent while refusing to let it
            // become the process's largest resource decision by accident (F16).
            val bounded = maxThreads.coerceAtMost(MAX_THREADS)
            if (bounded < maxThreads) {
                LOG.warn(
                    "Executor dispatcher sized {} from max-concurrent-executions-global x max-parallel-nodes; " +
                        "capped to {}. Nodes queue beyond that rather than each taking a thread.",
                    maxThreads,
                    bounded,
                )
            }
            val pool = Executors.newFixedThreadPool(bounded, DaemonThreads)
            return ExecutorDispatcher(pool, pool.asCoroutineDispatcher(), bounded)
        }

        /** Hard ceiling on the executor's own pool. Beyond this, work queues instead of forking. */
        const val MAX_THREADS = 512

        private val LOG = LoggerFactory.getLogger(ExecutorDispatcher::class.java)
    }

    private object DaemonThreads : ThreadFactory {
        private val counter = AtomicLong()

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "dag-executor-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}
