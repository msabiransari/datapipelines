package co.datapipelines.dependencies

import java.time.Clock

/**
 * The bounded dependency walk (record §7.2) over the families a [FamilyRegistry] answers for.
 *
 * - **Memoised twice** (§5.1): request → outcome and identity → node, so an alias or a moving selector neither loads a
 *   body twice nor loses an edge's provenance; every edge OCCURRENCE is retained.
 * - **Explicit stack** with an active-path set, never the host stack. A reference to a node on the active path is a
 *   CYCLE diagnostic with a bounded trail; the walk records it and does not descend, so `acyclic` is false.
 * - **Longest-path depth**: a node reached later by a longer path is measured on that path, because a memoised node's
 *   height is added to the depth it is reached at.
 * - **Batched reads** (§25.4): when a target is not yet resolved, the walk resolves it together with every other pending
 *   reference of the nodes it has expanded, grouped per family, at most [GraphLimits.resolveBatchSize] per provider call
 *   and clamped by the remaining node budget; the [Deadline] is checked before EVERY call ([DeadlineExceeded]).
 * - **Leaves**: MISSING and UNSUPPORTED nodes are leaves; a DISCARDED node is terminal and makes the graph incomplete
 *   (its definitions are not expanded); RELEASED nodes are walked like DRAFT ones (§7.2 step 5).
 * - **Bounds**: edges, depth and nodes stop the walk with a LIMIT diagnostic naming the limit and its maximum
 *   (`complete:false`, `acyclic:null`); retained diagnostics are bounded too — overflow appends one LIMIT
 *   `diagnostics` and marks the graph incomplete without stopping the enumeration. Never a partial graph marked complete.
 *
 * The visit order is a depth-first walk in reference order, the order today's release closure walks in, so a release
 * snapshot's node ids (`n<discovery index>`), edge order and post-order match it exactly (#868 section D).
 */
class DependencyWalker(
    private val registry: FamilyRegistry,
    private val limits: GraphLimits,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Walks [roots] in order, sharing one memo. Refuses (IllegalArgumentException) an empty root list or a root whose
     * registered family does not admit its selector; a root of an unregistered family is an UNSUPPORTED node.
     */
    fun walk(
        roots: List<ObjectRequest>,
        context: ResolveContext,
    ): GraphSnapshot {
        require(roots.isNotEmpty()) { "a walk needs at least one root" }
        roots.forEach { root ->
            val descriptor = registry[root.family]
            require(descriptor == null || root.selector.kind in descriptor.rootSelectors) {
                "family ${root.family} does not admit a ${root.selector.kind} root"
            }
        }
        return Walk(roots.distinct(), context).run()
    }

    /** One walk's state; never shared between walks (per-request memoization only, §7.3). */
    private inner class Walk(
        private val roots: List<ObjectRequest>,
        private val context: ResolveContext,
    ) {
        private val resolutions = HashMap<ObjectRequest, Loaded>()
        private val pending = LinkedHashSet<ObjectRequest>()
        private val nodeIndex = HashMap<NodeKey, Int>()
        private val nodes = ArrayList<Node>()
        private val edges = ArrayList<Edge>()
        private val height = HashMap<Int, Int>()
        private val onPath = HashSet<Int>()
        private val stack = ArrayDeque<Frame>()
        private val postOrder = ArrayList<Int>()
        private val diagnostics = DiagnosticLog(limits.diagnostics)
        private var stopped = false
        private var cyclic = false
        private var incomplete = false

        fun run(): GraphSnapshot {
            prefetch(roots)
            val rootIds = ArrayList<String>()
            for (root in roots) {
                if (!visitRoot(root, rootIds)) break
            }
            return snapshot(rootIds)
        }

        /** Walks one root to exhaustion; false when the walk has stopped at a limit (no later root is visited). */
        private fun visitRoot(
            root: ObjectRequest,
            rootIds: MutableList<String>,
        ): Boolean {
            if (stopped) return false
            val loaded = resolve(root)
            val existing = nodeIndex[keyOf(root, loaded)]
            val index = existing ?: discover(root, loaded, spelledName = null) ?: return false
            rootIds.add(nodes[index].nodeId)
            if (existing == null) enter(index, loaded, depth = 0)
            drain()
            return true
        }

        /** The explicit-stack depth-first loop. */
        private fun drain() {
            while (stack.isNotEmpty() && !stopped) {
                val frame = stack.last()
                if (frame.next < frame.references.size) {
                    follow(frame, frame.references[frame.next++])
                } else {
                    finish(stack.removeLast())
                }
            }
        }

        private fun follow(
            frame: Frame,
            reference: Reference,
        ) {
            if (edges.size + 1 > limits.edges) return stop(LimitKind.EDGES, limits.edges)
            val loaded = resolve(reference.request)
            val existing = nodeIndex[keyOf(reference.request, loaded)]
            if (existing == null) {
                if (frame.depth + 1 > limits.depth) return stop(LimitKind.DEPTH, limits.depth)
                val index = discover(reference.request, loaded, reference.spelledName) ?: return
                addEdge(frame, index, reference)
                enter(index, loaded, frame.depth + 1)
                return
            }
            val edge = addEdge(frame, existing, reference)
            when {
                existing in onPath -> cycle(existing, edge)
                frame.depth + 1 + height.getValue(existing) > limits.depth -> stop(LimitKind.DEPTH, limits.depth)
            }
        }

        private fun finish(frame: Frame) {
            onPath.remove(frame.index)
            // A back edge's target is still on the active path and has no height yet: it adds no depth.
            height[frame.index] = frame.targets.mapNotNull { height[it] }.maxOfOrNull { it + 1 } ?: 0
            postOrder.add(frame.index)
        }

        /** Registers a node, or stops at the nodes bound (null). */
        private fun discover(
            request: ObjectRequest,
            loaded: Loaded,
            spelledName: String?,
        ): Int? {
            if (nodes.size + 1 > limits.nodes) {
                stop(LimitKind.NODES, limits.nodes)
                return null
            }
            val index = nodes.size
            nodes.add(nodeOf("n$index", request, loaded, spelledName))
            nodeIndex[keyOf(request, loaded)] = index
            return index
        }

        private fun enter(
            index: Int,
            loaded: Loaded,
            depth: Int,
        ) {
            val node = nodes[index]
            if (loaded is Loaded.Found && node.lifecycle != Lifecycle.DISCARDED) {
                val references = loaded.references()
                references.forEach { if (it.request !in resolutions) pending.add(it.request) }
                onPath.add(index)
                stack.addLast(Frame(index, depth, references))
                return
            }
            height[index] = 0
            postOrder.add(index)
            when {
                loaded is Loaded.Missing -> diagnostics.add(Diagnostic(DiagnosticKind.MISSING, node.nodeId, null, MISSING_MESSAGE))
                loaded is Loaded.Unsupported -> unsupported(node)
                else -> discarded(node)
            }
        }

        private fun unsupported(node: Node) {
            incomplete = true
            diagnostics.add(Diagnostic(DiagnosticKind.UNSUPPORTED_FAMILY, node.nodeId, null, UNSUPPORTED_MESSAGE))
        }

        private fun discarded(node: Node) {
            incomplete = true
            diagnostics.add(Diagnostic(DiagnosticKind.DISCARDED, node.nodeId, null, DISCARDED_MESSAGE))
        }

        private fun addEdge(
            frame: Frame,
            target: Int,
            reference: Reference,
        ): Edge {
            frame.targets.add(target)
            val edge =
                Edge(
                    edgeId = "e${edges.size}",
                    fromNodeId = nodes[frame.index].nodeId,
                    toNodeId = nodes[target].nodeId,
                    relationship = reference.relationship,
                    sourcePath = reference.sourcePath,
                    requestedSelector = reference.request.selector,
                    resolvedVersion = nodes[target].identity?.artifactVersion,
                    constraints = reference.constraints,
                )
            edges.add(edge)
            return edge
        }

        private fun cycle(
            target: Int,
            edge: Edge,
        ) {
            cyclic = true
            val active = stack.map { it.index }
            val trail = (active.subList(active.indexOf(target), active.size) + target).take(limits.diagnostics)
            diagnostics.add(
                Diagnostic(DiagnosticKind.CYCLE, nodes[target].nodeId, edge.edgeId, CYCLE_MESSAGE, trail = trail.map { nodes[it].nodeId }),
            )
        }

        private fun stop(
            limit: LimitKind,
            maximum: Int,
        ) {
            stopped = true
            diagnostics.add(Diagnostic(DiagnosticKind.LIMIT, null, null, limitMessage(limit, maximum), limit, maximum))
        }

        private fun resolve(request: ObjectRequest): Loaded = resolutions[request] ?: prefetch(listOf(request)).getValue(request)

        /**
         * Resolves [needed] (always) plus pending references up to the remaining node budget, grouped per family in
         * first-seen order, at most [GraphLimits.resolveBatchSize] per provider call, the deadline checked before each.
         */
        private fun prefetch(needed: List<ObjectRequest>): Map<ObjectRequest, Loaded> {
            val wanted = LinkedHashSet<ObjectRequest>(needed.filter { it !in resolutions })
            val budget = limits.nodes - resolutions.size - wanted.size
            pending
                .asSequence()
                .filter { it !in resolutions && it !in wanted }
                .take(budget.coerceAtLeast(0))
                .forEach(wanted::add)
            wanted.groupBy { it.family }.forEach { (family, requests) -> load(family, requests) }
            pending.removeAll(wanted)
            return resolutions
        }

        /** One family's share of a prefetch: UNSUPPORTED when unregistered, else batched with the deadline checked per batch. */
        private fun load(
            family: FamilyKey,
            requests: List<ObjectRequest>,
        ) {
            val descriptor = registry[family]
            if (descriptor == null) {
                requests.forEach { resolutions[it] = Loaded.Unsupported }
                return
            }
            requests.chunked(limits.resolveBatchSize).forEach { chunk ->
                if (context.deadline.expired()) throw DeadlineExceeded(family)
                resolutions.putAll(descriptor.load(chunk, context))
            }
        }

        private fun nodeOf(
            nodeId: String,
            request: ObjectRequest,
            loaded: Loaded,
            spelledName: String?,
        ): Node {
            val found = loaded as? Loaded.Found
            val facts = found?.facts
            return Node(
                nodeId = nodeId,
                identity = found?.identity,
                requestedAddress = request.address,
                requestedSelector = request.selector,
                name = if (found != null) facts?.name else spelledName,
                displayName = facts?.displayName,
                subtype = facts?.subtype,
                versionInfo = facts?.versionInfo,
                resolution =
                    when (loaded) {
                        is Loaded.Found -> Resolution.RESOLVED
                        Loaded.Missing -> Resolution.MISSING
                        Loaded.Unsupported -> Resolution.UNSUPPORTED
                    },
                lifecycle = facts?.lifecycle,
                operational = facts?.operational,
                capabilities = capabilitiesOf(request.family, loaded),
                currentVersion = facts?.currentVersion,
                bodyHash = facts?.bodyHash,
                generation = facts?.generation,
                observations = facts?.observations.orEmpty(),
            )
        }

        /** The family's §4 pair; an UNSUPPORTED node reports NONE/NONE — no adapter answered for it. */
        private fun capabilitiesOf(
            family: FamilyKey,
            loaded: Loaded,
        ): Capabilities = registry[family]?.capabilities?.takeIf { loaded !is Loaded.Unsupported } ?: Capabilities.NONE

        private fun snapshot(rootIds: List<String>): GraphSnapshot {
            if (diagnostics.overflowed) incomplete = true
            val complete = !stopped && !incomplete
            val acyclic = if (complete) !cyclic else null
            return GraphSnapshot(
                schemaVersion = GraphSnapshot.SCHEMA_VERSION,
                roots = rootIds,
                nodes = nodes.toList(),
                edges = edges.toList(),
                diagnostics = diagnostics.entries(),
                complete = complete,
                acyclic = acyclic,
                dependencyFirst = if (acyclic == true) postOrder.map { nodes[it].nodeId } else null,
                observedAt = clock.instant(),
                internalFingerprint =
                    GraphFingerprint.digest(GraphFingerprint.canonical(rootIds, nodes, edges) { registry[it]?.extractorSchemaVersion }),
            )
        }
    }

    /** One frame of the explicit stack: the node, its references, the next one to follow, the targets reached. */
    private class Frame(
        val index: Int,
        val depth: Int,
        val references: List<Reference>,
    ) {
        var next: Int = 0
        val targets: MutableList<Int> = ArrayList()
    }

    /**
     * The bounded diagnostic list: at most `maximum` non-LIMIT entries, then one LIMIT `diagnostics` marking the overflow.
     * LIMIT entries and the FIRST cycle are always kept — a release plan's refusal is decided by them.
     */
    private class DiagnosticLog(
        private val maximum: Int,
    ) {
        private val list = ArrayList<Diagnostic>()
        private var retained = 0
        private var firstCycleKept = false
        var overflowed = false
            private set

        fun add(diagnostic: Diagnostic) {
            val exempt = diagnostic.kind == DiagnosticKind.LIMIT || (diagnostic.kind == DiagnosticKind.CYCLE && !firstCycleKept)
            if (diagnostic.kind == DiagnosticKind.CYCLE) firstCycleKept = true
            when {
                exempt -> {
                    list.add(diagnostic)
                }

                retained < maximum -> {
                    list.add(diagnostic).also { retained++ }
                }

                !overflowed -> {
                    overflowed = true
                    val message = limitMessage(LimitKind.DIAGNOSTICS, maximum)
                    list.add(Diagnostic(DiagnosticKind.LIMIT, null, null, message, LimitKind.DIAGNOSTICS, maximum))
                }
            }
        }

        fun entries(): List<Diagnostic> = list.toList()
    }

    /** A node's memo key (§5.1): its resolved identity, or — unresolved — the request itself (address + selector). */
    private sealed interface NodeKey {
        data class Identity(
            val identity: ResolvedIdentity,
        ) : NodeKey

        data class Unresolved(
            val request: ObjectRequest,
        ) : NodeKey
    }

    private fun keyOf(
        request: ObjectRequest,
        loaded: Loaded,
    ): NodeKey = if (loaded is Loaded.Found) NodeKey.Identity(loaded.identity) else NodeKey.Unresolved(request)

    companion object {
        const val MISSING_MESSAGE = "A referenced object does not exist or is not visible."
        const val DISCARDED_MESSAGE = "A referenced version is discarded; its own dependencies are not expanded."
        const val UNSUPPORTED_MESSAGE = "A referenced object belongs to a family this graph cannot read."
        const val CYCLE_MESSAGE = "The dependencies form a cycle."

        /** The LIMIT wording: the limit's wire name and its maximum, nothing about the graph. */
        fun limitMessage(
            limit: LimitKind,
            maximum: Int,
        ): String = "The ${limit.wire} limit ($maximum) was reached; the graph is incomplete."
    }
}
