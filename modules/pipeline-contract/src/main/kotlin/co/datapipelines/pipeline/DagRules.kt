package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.2 (DAG) and §12.3 (caller node).
 *
 * `depends_on` is the *only* source of truth for ordering and parallelism — §15.1's change
 * log records `parallel_id` being rejected for exactly that reason: "two nodes run in
 * parallel iff neither is reachable from the other; a second parallelism source-of-truth
 * would create reconciliation bugs". So the graph these rules check is the whole execution
 * plan, and a cycle here is a pipeline that can never start.
 */
internal object DagRules {
    fun check(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        if (pipeline.nodes.isEmpty()) {
            into.add(
                Validation.EMPTY_PIPELINE,
                "nodes",
                "A pipeline must declare at least one node.",
            )
            return
        }
        checkSize(pipeline, into)
        checkDependenciesExist(pipeline, into)
        checkAcyclic(pipeline, into)
        checkCallerNodes(pipeline, into)
    }

    /**
     * §12.2 `pipeline_too_large` — at most [MAX_NODES] nodes.
     *
     * Defence in depth, not the primary control: §12.2's crash-safety rule requires the
     * validator to survive hostile input *regardless* of size, which [checkAcyclic]'s iterative
     * traversal is what actually delivers. This bound caps the work a single `author`-scoped
     * save can commission, and it is deliberately **not** an early return — §17.2's exhaustive
     * collection still applies, and every remaining rule is linear in node count.
     */
    private fun checkSize(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        if (pipeline.nodes.size <= MAX_NODES) return
        into.add(
            Validation.PIPELINE_TOO_LARGE,
            "nodes",
            "A pipeline may declare at most $MAX_NODES nodes; this one declares ${pipeline.nodes.size}.",
            mapOf("nodes" to pipeline.nodes.size, "max" to MAX_NODES),
        )
    }

    private fun checkDependenciesExist(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val known = pipeline.nodes.mapTo(mutableSetOf()) { it.id }
        pipeline.nodes.forEachIndexed { index, node ->
            node.dependsOn.filterNot { it in known }.forEach { missing ->
                into.add(
                    Validation.DANGLING_DEPENDENCY,
                    "nodes[$index].depends_on",
                    "Node '${node.id.truncateForError()}' depends on '${missing.truncateForError()}', " +
                        "which is not a node in this pipeline.",
                    mapOf("node" to node.id.truncateForError(), "missing" to missing.truncateForError()),
                )
            }
        }
    }

    /**
     * §12.2 `cycle_detected`, reported **with the path** — `a -> b -> c -> a` rather than
     * "there is a cycle somewhere". A DAG author staring at fifteen nodes cannot act on the
     * latter.
     *
     * Depth-first with three colours; every back edge to a node still on the stack yields one
     * cycle, and cycles are de-duplicated by their node set so one loop reached from two roots
     * is reported once. Only nodes that actually exist are traversed — a dangling dependency is
     * already reported by [checkDependenciesExist] and following it here would invent an edge.
     *
     * ## Iterative, and that is normative
     *
     * §12.2's crash-safety rule: "graph traversal is **iterative**, never recursive in graph
     * depth — a deep `depends_on` chain must not exhaust the JVM stack". The recursive version
     * of this function died with a `StackOverflowError` on a chain a couple of thousand nodes
     * long, which a 2 KB payload from any `author`-scoped principal buys — a save-time denial
     * of service, and an `Error` (not an `Exception`) escaping past every handler on the way
     * out. The work-list below is the same algorithm with the frames on the heap.
     *
     * [path] mirrors the frame stack exactly, so cycle reconstruction is unchanged.
     */
    private fun checkAcyclic(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val known = pipeline.nodes.mapTo(mutableSetOf()) { it.id }
        val edges = pipeline.nodes.associate { node -> node.id to node.dependsOn.filter { it in known } }
        val state = HashMap<String, Mark>(edges.size)
        val reported = mutableSetOf<Set<String>>()

        edges.keys.forEach { root ->
            if (state[root] == null) traverseFrom(root, edges, state, reported, into)
        }
    }

    /** One iterative depth-first traversal rooted at [root]; see [checkAcyclic]. */
    private fun traverseFrom(
        root: String,
        edges: Map<String, List<String>>,
        state: MutableMap<String, Mark>,
        reported: MutableSet<Set<String>>,
        into: FailureCollector,
    ) {
        val frames = ArrayDeque<Frame>()
        val path = mutableListOf<String>()
        open(root, edges, state, frames, path)

        while (frames.isNotEmpty()) {
            val frame = frames.last()
            val next = frame.nextEdge()
            when {
                next == null -> {
                    state[frame.id] = Mark.CLOSED
                    path.removeAt(path.lastIndex)
                    frames.removeLast()
                }

                // A back edge to a node still on the stack: everything from it to the top is a cycle.
                state[next] == Mark.OPEN -> {
                    reportCycle(path, next, reported, into)
                }

                state[next] == null -> {
                    open(next, edges, state, frames, path)
                }

                else -> {
                    // Mark.CLOSED — already fully explored, and not on the current path.
                }
            }
        }
    }

    private fun open(
        id: String,
        edges: Map<String, List<String>>,
        state: MutableMap<String, Mark>,
        frames: ArrayDeque<Frame>,
        path: MutableList<String>,
    ) {
        state[id] = Mark.OPEN
        path += id
        frames.addLast(Frame(id, edges[id].orEmpty()))
    }

    /** One node's traversal state: which of its edges have been walked. */
    private class Frame(
        val id: String,
        private val edges: List<String>,
    ) {
        private var cursor = 0

        fun nextEdge(): String? = if (cursor < edges.size) edges[cursor++] else null
    }

    private fun reportCycle(
        stack: List<String>,
        backEdgeTarget: String,
        reported: MutableSet<Set<String>>,
        into: FailureCollector,
    ) {
        val cycle = stack.subList(stack.indexOf(backEdgeTarget), stack.size)
        if (!reported.add(cycle.toSet())) return
        val path = (cycle + backEdgeTarget).joinToString(" -> ") { it.truncateForError() }
        into.add(
            Validation.CYCLE_DETECTED,
            "nodes[].depends_on",
            "The dependency graph contains a cycle: $path.",
            mapOf("cycle" to cycle.map { it.truncateForError() }),
        )
    }

    /**
     * §12.3 / §9 — at most one node resolves to `output.target: "caller"`.
     *
     * The default was already applied at deserialization (D1), so "resolves to caller" is
     * simply `output == NodeOutput.Caller`; nothing is re-derived here. Zero caller nodes is
     * legal and deliberately not flagged — a pure write-back pipeline returns stats only
     * (§9.4).
     */
    private fun checkCallerNodes(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val callers = pipeline.nodes.filter { it.isCallerNode }
        if (callers.size <= 1) return
        into.add(
            Validation.MULTIPLE_CALLER_NODES,
            "nodes[].output.target",
            "${callers.size} nodes resolve to output.target 'caller' " +
                "(${callers.joinToString { it.id.truncateForError() }}); at most one may. " +
                "A DQL node with no output block resolves to caller.",
            mapOf("nodes" to callers.map { it.id.truncateForError() }),
        )
    }

    private enum class Mark { OPEN, CLOSED }

    /** §12.2 — the hard node-count bound. */
    internal const val MAX_NODES = 1000
}
