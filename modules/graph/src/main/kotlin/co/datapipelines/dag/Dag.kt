package co.datapipelines.dag

/**
 * A minimal directed acyclic graph of `T` keyed by node id (dag-executor.md §3).
 *
 * **No Guava, no JGraphT** (§2 principle 1): the structure is small, the hard part is the
 * parallel executor, and a graph library would be a dependency carried for ~150 lines of code.
 *
 * Instances are immutable and are built through [build]; the builder is what rejects duplicate
 * ids, dangling dependencies and cycles, so a constructed [Dag] is always well-formed.
 *
 * ## The reverse index is precomputed
 *
 * §3.2's `dependentsOf` scans every node on each call, which makes [topologicalOrder] O(n²).
 * The edge set is fixed at construction, so the reverse index is built once here instead —
 * same semantics, same public API, linear walk. (`pipeline.validation.pipeline_too_large` caps
 * a pipeline at 1000 nodes, so the difference is not load-bearing; it is simply free.)
 */
class Dag<T> private constructor(
    private val nodes: Map<String, T>,
    private val dependencies: Map<String, Set<String>>,
) {
    /** Reverse edges: id → the nodes that declare it in their `depends_on`. */
    private val dependents: Map<String, Set<String>> =
        buildMap<String, MutableSet<String>> {
            nodes.keys.forEach { put(it, linkedSetOf()) }
            dependencies.forEach { (id, deps) -> deps.forEach { dep -> getValue(dep).add(id) } }
        }

    /** Every node id, in insertion order. */
    val nodeIds: Set<String> get() = nodes.keys

    /** The value stored under [id]. */
    fun node(id: String): T = nodes[id] ?: error("Unknown node id: $id")

    /**
     * The ids [id] depends on.
     *
     * A node with no entry in the dependency map yields `emptySet()` rather than throwing —
     * the §3.2 `dependencies[id]!!` shape was a NPE waiting for the first root node.
     */
    fun dependenciesOf(id: String): Set<String> = dependencies[id] ?: emptySet()

    /** The ids that depend on [id]. */
    fun dependentsOf(id: String): Set<String> = dependents[id] ?: emptySet()

    /**
     * Kahn's algorithm (§3.2): a dependency order in which every node follows all of its
     * dependencies. This is the executor's iteration order for *scheduling* coroutines — not
     * for running them in sequence (§3.3).
     */
    fun topologicalOrder(): List<String> {
        val inDegree = nodes.keys.associateWithTo(mutableMapOf()) { dependenciesOf(it).size }
        val queue = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
        val result = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            result.add(id)
            dependentsOf(id).forEach { dependent ->
                val remaining = inDegree.getValue(dependent) - 1
                inDegree[dependent] = remaining
                if (remaining == 0) queue.add(dependent)
            }
        }

        check(result.size == nodes.size) { "Cycle detected: ${nodes.keys - result.toSet()}" }
        return result
    }

    /**
     * The waves of nodes that could run in parallel (§3.2).
     *
     * **Diagnostic / UI API only — no executor path calls this** (§3.3). The executor starts
     * every node coroutine up front and lets each await its own dependencies, which is strictly
     * more parallel than wave scheduling. This exists for the pipeline editor's layer layout and
     * for `EXPLAIN`-style diagnostics, and is unit-tested despite having no production caller.
     */
    fun independentBatches(): List<Set<String>> {
        val batches = mutableListOf<Set<String>>()
        val completed = mutableSetOf<String>()
        val remaining = nodes.keys.toMutableSet()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filterTo(linkedSetOf()) { id -> dependenciesOf(id).all { it in completed } }
            check(ready.isNotEmpty()) { "Cycle detected among: $remaining" }
            batches.add(ready)
            completed += ready
            remaining -= ready
        }
        return batches
    }

    /**
     * The first cycle found, as the path of node ids that closes it (first id repeated last), or
     * null when the graph is acyclic.
     *
     * The executor runs this at execution start as defence in depth; write-time validation
     * (`pipeline.validation.cycle_detected`) is the primary guard (§3.3).
     */
    fun detectCycle(): List<String>? = CycleSearch(nodes.keys, ::dependentsOf).run()

    companion object {
        /** Builds a [Dag], throwing on duplicate ids, dangling dependencies or cycles. */
        fun <T> build(block: DagBuilder<T>.() -> Unit): Dag<T> = DagBuilder<T>().apply(block).build()

        internal fun <T> of(
            nodes: Map<String, T>,
            dependencies: Map<String, Set<String>>,
        ): Dag<T> = Dag(nodes, dependencies)
    }
}

/**
 * Accumulates nodes and edges, then validates the whole graph in [build] (§3.1).
 *
 * Order-independent by design: [addDependency] may name a node that has not been added yet, so a
 * caller can walk a pipeline's nodes once instead of twice.
 */
class DagBuilder<T> {
    private val nodes = LinkedHashMap<String, T>()
    private val dependencies = LinkedHashMap<String, MutableSet<String>>()

    /** Registers [id]. Throws when [id] was already added. */
    fun addNode(
        id: String,
        value: T,
    ) {
        require(id !in nodes) { "Duplicate node id: $id" }
        nodes[id] = value
    }

    /** Records that [from] depends on [dependsOn]. Both ids are resolved in [build]. */
    fun addDependency(
        from: String,
        dependsOn: String,
    ) {
        dependencies.getOrPut(from) { linkedSetOf() }.add(dependsOn)
    }

    /** Validates and freezes the graph. */
    fun build(): Dag<T> {
        dependencies.forEach { (from, deps) ->
            require(from in nodes) { "Dependency declared for unknown node: $from" }
            deps.forEach { dep ->
                require(dep in nodes) { "Node '$from' depends on unknown node: $dep" }
                require(dep != from) { "Node '$from' depends on itself" }
            }
        }
        val dag = Dag.of(nodes.toMap(), dependencies.mapValues { it.value.toSet() })
        dag.detectCycle()?.let { cycle -> throw IllegalArgumentException("Cycle detected: ${cycle.joinToString(" -> ")}") }
        return dag
    }
}

/**
 * Iterative three-colour DFS (§3.2's algorithm, without the recursion).
 *
 * Recursion would be bounded at 1000 frames by `pipeline_too_large`, but the executor also
 * builds DAGs from paths that never passed save-time validation (imports, defence-in-depth
 * checks), and a stack overflow is not a diagnosable failure mode. The explicit stack costs
 * nothing and cannot blow up.
 */
private class CycleSearch(
    private val ids: Set<String>,
    private val dependentsOf: (String) -> Set<String>,
) {
    private val colour = ids.associateWithTo(mutableMapOf()) { WHITE }
    private val parent = mutableMapOf<String, String>()

    fun run(): List<String>? {
        ids.forEach { start ->
            if (colour[start] == WHITE) {
                search(start)?.let { return it }
            }
        }
        return null
    }

    private fun search(start: String): List<String>? {
        val stack = ArrayDeque(listOf(Frame(start, dependentsOf(start).iterator())))
        colour[start] = GREY
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.pending.hasNext()) {
                colour[frame.id] = BLACK
                stack.removeLast()
            } else {
                val next = frame.pending.next()
                // GREY = the edge closes back onto the current path: a cycle.
                if (colour[next] == GREY) return cyclePath(cycleStart = next, cycleEnd = frame.id)
                if (colour[next] == WHITE) {
                    colour[next] = GREY
                    parent[next] = frame.id
                    stack.addLast(Frame(next, dependentsOf(next).iterator()))
                }
                // BLACK falls through deliberately: a cross edge into an already-finished subtree
                // is not a cycle, and re-walking it is what would make this quadratic.
            }
        }
        return null
    }

    /** Walks parents back from the edge that closed the cycle, then re-appends the entry node. */
    private fun cyclePath(
        cycleStart: String,
        cycleEnd: String,
    ): List<String> {
        val path = mutableListOf<String>()
        var current: String? = cycleEnd
        while (current != null && current != cycleStart) {
            path.add(current)
            current = parent[current]
        }
        path.add(cycleStart)
        path.reverse()
        return path + cycleStart
    }

    private class Frame(
        val id: String,
        val pending: Iterator<String>,
    )

    private companion object {
        const val WHITE = 0
        const val GREY = 1
        const val BLACK = 2
    }
}
