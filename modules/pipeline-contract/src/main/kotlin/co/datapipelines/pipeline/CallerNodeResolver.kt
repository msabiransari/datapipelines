package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException

/**
 * Resolves the **caller node** — the node whose ResultSet is the pipeline's result
 * (pipeline-contract §9).
 *
 * ## There is nothing topological here, and that is the point
 *
 * D1 deleted terminal-node auto-detection outright: there is no `terminal_node_id` field and
 * no derivation from DAG shape. The result node is simply the node that resolves to
 * `output.target: "caller"` — explicitly, or by omitting its `output` block, a default
 * already applied at deserialization. So this resolver is a filter over `output`, and it
 * asserts nothing about position: §9 states the caller node "may be a sink, or sit mid-DAG",
 * and other sinks (write-back DQL, DML side effects) are not its business.
 *
 * ## Zero is legal
 *
 * A pure write-back/ETL pipeline has no caller node (§9.4): execution returns stats only and
 * emits no `data_ready` event. `null` from [resolve] is therefore an ordinary answer, not an
 * error — the executor's only caller-related behaviour is "if there is one, materialize it"
 * (dag-executor §4.1).
 */
object CallerNodeResolver {
    /**
     * The pipeline's caller node, or null when it has none.
     *
     * Throws [DatapipelinesException] carrying `pipeline.validation.multiple_caller_nodes` if
     * more than one node resolves to `caller`. That is defensive, not a validation path:
     * [PipelineValidator] rejects such a pipeline at save time (D2), so reaching here with two
     * means an unvalidated pipeline got into the executor — which must fail loudly rather
     * than pick one ResultSet arbitrarily and return the wrong data.
     */
    fun resolve(pipeline: Pipeline): Node? = resolve(pipeline.nodes)

    /** As [resolve], over a node list. */
    fun resolve(nodes: List<Node>): Node? {
        val callers = nodes.filter { it.isCallerNode }
        if (callers.size > 1) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Validation.MULTIPLE_CALLER_NODES,
                message =
                    "${callers.size} nodes resolve to output.target 'caller'; at most one may. " +
                        "This pipeline was not validated before execution.",
                details = mapOf("nodes" to callers.map { it.id.truncateForError() }),
            )
        }
        return callers.firstOrNull()
    }

    /**
     * True when the pipeline returns data to its caller — i.e. a `data_ready` event will follow.
     *
     * Defined as `resolve(pipeline) != null` rather than a separate count, so the two functions
     * cannot disagree. They did: an earlier `count { … } == 1` answered `false` for a
     * two-caller pipeline while [resolve] threw, which would have let the executor skip result
     * materialisation on a pipeline it should refuse to run at all.
     */
    fun hasCallerNode(pipeline: Pipeline): Boolean = resolve(pipeline) != null
}
