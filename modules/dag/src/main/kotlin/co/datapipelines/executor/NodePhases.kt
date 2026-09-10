package co.datapipelines.executor

import java.util.concurrent.ConcurrentHashMap

/**
 * Which [NodePhase] each node of one execution is currently inside (108).
 *
 * The node wall-clock deadline (§5.3) has to say WHERE the budget went, because the fix differs
 * completely by phase: an `EXECUTE` overrun wants a cheaper query or a pushed-down filter, a
 * `STAGE` overrun wants fewer rows crossing the wire, a `CONNECT` overrun is a pool or a network
 * problem and no amount of SQL rewriting will help. Without it `pipeline.node.timeout` would say
 * only "too slow", which is what the driver text already said.
 *
 * Written by [NodeRunner]'s phase wrapper on every phase entry and read by [PipelineExecutor]
 * from a DIFFERENT coroutine at the instant the deadline fires — so it is a concurrent map and
 * not a field, and the read is a plain lookup with no coordination: the value is a diagnostic,
 * and a phase boundary crossed in the same microsecond as the deadline is honestly either answer.
 *
 * Per execution, not per node runner: one [NodeRunner] serves every execution on the instance.
 */
class NodePhases {
    private val current = ConcurrentHashMap<String, NodePhase>()

    /** Records that [nodeId] has entered [phase]. */
    fun enter(
        nodeId: String,
        phase: NodePhase,
    ) {
        current[nodeId] = phase
    }

    /**
     * The phase [nodeId] is in, or null when it has entered none yet.
     *
     * Null is a real answer, not a gap: a node stopped before its first phase was waiting on a
     * dependency or on a parallelism permit, and reporting a phase it never reached would be a
     * fabrication. Callers render it as `unknown`.
     */
    fun current(nodeId: String): NodePhase? = current[nodeId]
}
