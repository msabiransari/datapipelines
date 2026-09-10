package co.datapipelines.executor

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects per-node outcomes as they happen so a stats snapshot can be taken at **any** moment —
 * success, failure, timeout, or cancellation (dag-executor.md §5.1 step 12c, §7.2, §8.3.2 step 3).
 *
 * Stats are collected unconditionally (§2 principle 5): an aborted execution reports stats too,
 * and it cannot wait for `Deferred.await()` to hand them over because the coroutines that would
 * have completed those deferreds were just cancelled. So outcomes are recorded here at the moment
 * they occur, and [snapshot] fills every node the DAG has — recorded or not.
 *
 * Concurrent by construction: node coroutines run in parallel and all write here.
 */
class NodeStatsCollector {
    private val startedAt = ConcurrentHashMap<String, Instant>()
    private val outcomes = ConcurrentHashMap<String, NodeStats>()

    /** Rows staged so far by a node still draining (108 §D) — cleared by nothing; read live. */
    private val rowsSoFar = ConcurrentHashMap<String, Long>()

    /** Records that [nodeId] began executing (its `node_started` moment). */
    fun started(
        nodeId: String,
        at: Instant = Instant.now(),
    ) {
        startedAt[nodeId] = at
    }

    /** Records a successful node from the value its coroutine returned. */
    fun completed(result: NodeResult) {
        outcomes[result.nodeId] = NodeStats.of(result)
    }

    /** Records a failed node. Its start time comes from the matching [started] call. */
    fun failed(
        nodeId: String,
        error: MappedError,
        failedAt: Instant = Instant.now(),
    ) {
        outcomes[nodeId] = NodeStats.failed(nodeId, error, startedAt[nodeId], failedAt)
    }

    /**
     * Records a node the executor stopped, keeping the reason but **not** calling it a failure.
     *
     * Used on the two suppressed paths: a cancel-induced driver error, and a node interrupted while
     * the execution unwound. See [NodeStats.abortedWithCause] for why the status stays `ABORTED`.
     */
    fun abortedWithCause(
        nodeId: String,
        error: MappedError,
    ) {
        outcomes[nodeId] = NodeStats.abortedWithCause(nodeId, error, startedAt[nodeId])
    }

    /**
     * Records how many rows [nodeId] has staged so far (108 §D).
     *
     * Written from the staging drain, read by [liveSnapshot] from another coroutine — hence the
     * concurrent map, and hence no attempt at consistency between this and [startedAt]: a live
     * progress row is a diagnostic, and a count one batch behind is the right answer one batch ago.
     */
    fun progress(
        nodeId: String,
        rowsStaged: Long,
    ) {
        rowsSoFar[nodeId] = rowsStaged
    }

    /**
     * The **in-flight** snapshot (108 §D): a node that has started and not finished reports
     * `RUNNING` with its start time and its rows so far.
     *
     * Deliberately a second function rather than a flag on [snapshot]. The two answer different
     * questions and must keep giving different answers: a TERMINAL snapshot reports a
     * started-but-unfinished node as `ABORTED` (§7.2 — the execution ended and the node did not),
     * and relabelling that would rewrite the meaning of every stored `node_stats_json`. This one
     * is only ever written to a row whose status is still RUNNING.
     *
     * A node that has **not started** is OMITTED rather than given a status. There is no `PENDING`
     * in [NodeStatus] and inventing one is a wire change three other surfaces would have to learn;
     * reusing `ABORTED` — what the terminal snapshot means by "never started" — would have a live
     * screen showing "aborted" beside a run that is going perfectly. Absence says "not started
     * yet" without claiming anything, and the DAG the reader already has says which nodes those
     * are.
     */
    fun liveSnapshot(nodeIds: Collection<String>): List<NodeStats> =
        nodeIds.mapNotNull { id ->
            outcomes[id] ?: startedAt[id]?.let { NodeStats.running(id, it, rowsSoFar[id] ?: NodeResult.NOT_MEASURED) }
        }

    /** The node ids that had started but never reported an outcome — the timeout's blame set. */
    fun runningNodeIds(): Set<String> = startedAt.keys - outcomes.keys

    /**
     * One [NodeStats] per node in [nodeIds], in the order given.
     *
     * A node with no recorded outcome reports `ABORTED` — the §7.2 row for "never started, or
     * running when the execution was cancelled". A node that had *started* keeps its start time
     * so an operator can see how far it got.
     */
    fun snapshot(nodeIds: Collection<String>): List<NodeStats> = nodeIds.map { id -> outcomes[id] ?: NodeStats.aborted(id, startedAt[id]) }
}
