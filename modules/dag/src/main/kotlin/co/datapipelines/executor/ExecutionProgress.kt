package co.datapipelines.executor

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an execution's LIVE progress goes (108 §D, T199 #5/#6).
 *
 * Node stats used to be written once, at the end. So a `GET /api/v1/executions/{id}` on a running
 * execution answered "RUNNING" and nothing else — not which node, not how far — and a row an
 * instance died holding said "RUNNING" for the next sixty minutes. Both are the same gap seen
 * from two sides: nothing was written between the start and the end.
 *
 * A port rather than a direct repository call because `dag` produces the progress and `web` owns
 * the persistence wiring — the same seam `EventEmitter` uses, for the same reason. [NONE] keeps
 * every existing construction site and every fixture working unchanged, and means exactly what it
 * says: an executor with no progress sink writes no progress and is otherwise identical.
 */
interface ExecutionProgress {
    /**
     * Persists [nodeStats] as the execution's current per-node state.
     *
     * Called at node boundaries and from the throttled staging ticker. Implementations must be
     * safe to call for an execution that has since finished — the write is expected to land on
     * nothing (`ExecutionRepository.recordProgress` guards on `status = 'RUNNING'`).
     */
    fun record(
        executionId: UUID,
        nodeStats: List<NodeStats>,
    )

    /**
     * The throttled variant, for callers that produce progress thousands of times — the staging
     * drain, once per batch.
     *
     * The stats are a lambda so an implementation that decides not to write does not pay for
     * building the snapshot, which on a wide DAG is the larger half of the cost.
     */
    fun recordThrottled(
        executionId: UUID,
        nodeStats: () -> List<NodeStats>,
    )

    /** Stamps "this instance is alive and still on it" — the sweep's signal (V21). */
    fun heartbeat(executionId: UUID)

    /** Releases any per-execution state — called from the executor's `finally`, on every path. */
    fun forget(executionId: UUID)

    companion object {
        /** The no-op sink: a runtime with no metadata database, and every unit fixture. */
        val NONE: ExecutionProgress =
            object : ExecutionProgress {
                override fun record(
                    executionId: UUID,
                    nodeStats: List<NodeStats>,
                ) = Unit

                override fun recordThrottled(
                    executionId: UUID,
                    nodeStats: () -> List<NodeStats>,
                ) = Unit

                override fun heartbeat(executionId: UUID) = Unit

                override fun forget(executionId: UUID) = Unit
            }
    }
}

/**
 * The production [ExecutionProgress]: throttled writes into `pipeline_executions` (metadata-db
 * §4.6, §8.3).
 *
 * ## Throttling, and what is deliberately NOT throttled
 *
 * A staging drain reports per batch — thousands of times for a large node — and one UPDATE per
 * batch would put a metadata-DB write on the hot insert path. So progress from the drain is
 * throttled to `progress-write-interval-seconds` (5) per execution.
 *
 * Node boundaries are **not** throttled. A node starting or finishing is the event a watcher is
 * actually waiting for, there are at most a few dozen of them, and throttling them would mean the
 * screen showing the wrong node for up to five seconds — which is the one thing this feature
 * exists to fix. The distinction is why [record] takes no "force" flag: the throttle lives at the
 * call site that produces high-frequency events, not in this class's contract.
 *
 * ## Failure handling
 *
 * A write that fails is logged at DEBUG and dropped. Progress is a diagnostic: an execution must
 * never fail because its progress row could not be updated, and a metadata DB under pressure must
 * not have this class amplifying the load with retries. The terminal write has its own error
 * handling and is not this class's business.
 *
 * @param nodeStatsJson the serializer, injected rather than chosen here: the stats carry
 *   `java.time` values, and the mapper that cannot serialize them is exactly how `execution_started`
 *   went missing from the durable record once before (T36). The assembling layer passes the same
 *   mapper it uses for the terminal write, so the two shapes cannot drift.
 */
class JdbcExecutionProgress(
    private val executions: ExecutionRepository,
    private val nodeStatsJson: (List<NodeStats>) -> String,
    private val throttleMillis: Long,
) : ExecutionProgress {
    /** Per execution, the last time a THROTTLED write went out. Cleared by nothing — see below. */
    private val lastWriteMs = ConcurrentHashMap<UUID, Long>()

    override fun record(
        executionId: UUID,
        nodeStats: List<NodeStats>,
    ) {
        write(executionId, nodeStats)
    }

    /**
     * The throttled variant the staging drain calls.
     *
     * The map is never swept and that is intentional: an entry is one UUID and one Long, an
     * execution is minutes long, and a sweep would need a lifecycle hook this class has no other
     * reason to own. Left unbounded it would grow with total executions, so [forget] is called
     * from the executor's `finally` — the same place the cancellation registry is deregistered,
     * which is the one path every execution takes.
     */
    override fun recordThrottled(
        executionId: UUID,
        nodeStats: () -> List<NodeStats>,
    ) {
        val now = System.currentTimeMillis()
        val last = lastWriteMs[executionId]
        if (last != null && now - last < throttleMillis) return
        lastWriteMs[executionId] = now
        write(executionId, nodeStats())
    }

    /** Drops [executionId]'s throttle state — called from the executor's `finally`. */
    override fun forget(executionId: UUID) {
        lastWriteMs.remove(executionId)
    }

    override fun heartbeat(executionId: UUID) {
        runCatching { executions.heartbeat(executionId) }
            .onFailure { LOG.debug("heartbeat for execution {} not written: {}", executionId, it.message) }
    }

    private fun write(
        executionId: UUID,
        nodeStats: List<NodeStats>,
    ) {
        runCatching { executions.recordProgress(executionId, nodeStatsJson(nodeStats)) }
            .onFailure { LOG.debug("progress for execution {} not written: {}", executionId, it.message) }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(JdbcExecutionProgress::class.java)
    }
}
