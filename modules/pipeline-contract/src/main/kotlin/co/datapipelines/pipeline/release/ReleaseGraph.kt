package co.datapipelines.pipeline.release

/**
 * The release closure walk (record §1.2): every version the root pins, transitively, across the families [sources]
 * answer for — enumerated with an EXPLICIT stack (never the host stack), memoised per exact identity (a diamond reads
 * its shared node once), with active-path cycle detection and the longest-path depth.
 *
 * Every bound in [bounds] is checked WHILE the closure is enumerated, so a graph over a bound is refused before the walk
 * materialises it: `versions` as each new identity is discovered, `edges` as each pin occurrence is read, and `depth`
 * as the LONGEST path — a node first reached by a short path and later by a long one is measured on the long one, because
 * a memoised node's height is added to the depth it is reached at. MISSING and DISCARDED nodes are leaves: neither can
 * be released, so their pins (a DISCARDED version's) are never walked.
 *
 * Refusals carry the ROOT's family (record §14): `dependency_cycle` with the active path, `dependency_limit` with the
 * exhausted bound. A missing [sources] entry for a pinned family resolves as MISSING — a family S1 cannot read is
 * never treated as released.
 */
class ReleaseGraph(
    private val sources: Map<ReleaseFamily, DependencySource>,
    private val bounds: ReleaseBounds,
) {
    /**
     * Walks the closure of [root]; null when the root itself does not resolve (the caller's native not-found/not-draft).
     *
     * One iterative walk: the bounds and the cycle rule share its state, and each bound is its own refusal.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ThrowsCount")
    fun walk(root: ReleaseNodeIdentity): ReleaseClosure? {
        val rootNode = resolve(root) ?: return null
        val nodes = LinkedHashMap<ReleaseNodeIdentity, ClosureNode>()
        val pinsOf = HashMap<ReleaseNodeIdentity, List<ReleasePin>>()
        val height = HashMap<ReleaseNodeIdentity, Int>()
        val onPath = HashSet<ReleaseNodeIdentity>()
        val edges = ArrayList<ClosureEdge>()
        val postOrder = ArrayList<ReleaseNodeIdentity>()
        val stack = ArrayDeque<Frame>()

        fun discover(
            identity: ReleaseNodeIdentity,
            resolved: ResolvedNode?,
            pin: ReleasePin?,
        ): ClosureNode {
            if (nodes.size + 1 > bounds.maxVersions) throw ReleaseRefusals.limit(root.family, ReleaseLimit.VERSIONS, bounds.maxVersions)
            val node =
                ClosureNode(
                    nodeId = "n${nodes.size}",
                    identity = identity,
                    name = resolved?.name ?: requireNotNull(pin).targetName,
                    status = resolved?.status ?: ReleaseNodeStatus.MISSING,
                    bodyHash = resolved?.bodyHash,
                    currentVersion = resolved?.currentVersion,
                )
            nodes[identity] = node
            return node
        }

        fun enter(
            node: ClosureNode,
            resolved: ResolvedNode?,
            depth: Int,
        ) {
            val walkable = resolved != null && (node.status == ReleaseNodeStatus.DRAFT || node.status == ReleaseNodeStatus.RELEASED)
            if (walkable) {
                pinsOf[node.identity] = resolved.pins
                onPath.add(node.identity)
                stack.addLast(Frame(node.identity, depth))
            } else {
                height[node.identity] = 0
                postOrder.add(node.identity)
            }
        }

        enter(discover(root, rootNode, null), rootNode, 0)
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            val pins = pinsOf.getValue(frame.identity)
            if (frame.next < pins.size) {
                val pin = pins[frame.next++]
                if (edges.size + 1 > bounds.maxEdges) throw ReleaseRefusals.limit(root.family, ReleaseLimit.EDGES, bounds.maxEdges)
                edges.add(ClosureEdge(frame.identity, pin.target, pin.path))
                val target = pin.target
                when {
                    target in onPath -> {
                        throw cycle(root, stack, target, nodes)
                    }

                    target in height -> {
                        if (frame.depth + 1 + height.getValue(target) > bounds.maxDepth) {
                            throw ReleaseRefusals.limit(root.family, ReleaseLimit.DEPTH, bounds.maxDepth)
                        }
                    }

                    else -> {
                        if (frame.depth + 1 > bounds.maxDepth) throw ReleaseRefusals.limit(root.family, ReleaseLimit.DEPTH, bounds.maxDepth)
                        val resolved = resolve(target)
                        enter(discover(target, resolved, pin), resolved, frame.depth + 1)
                    }
                }
            } else {
                stack.removeLast()
                onPath.remove(frame.identity)
                val own = pins.maxOfOrNull { height.getValue(it.target) + 1 } ?: 0
                height[frame.identity] = own
                postOrder.add(frame.identity)
            }
        }
        // The walk's own checks already bound every path; this restates the invariant the refusal promises.
        if (height.getValue(root) > bounds.maxDepth) throw ReleaseRefusals.limit(root.family, ReleaseLimit.DEPTH, bounds.maxDepth)
        return ReleaseClosure(
            root = root,
            nodes = nodes.values.toList(),
            edges = edges,
            depth = height.getValue(root),
            dependencyFirst = postOrder.map { nodes.getValue(it) },
        )
    }

    private fun resolve(identity: ReleaseNodeIdentity): ResolvedNode? = sources[identity.family]?.resolve(identity)

    private fun cycle(
        root: ReleaseNodeIdentity,
        stack: ArrayDeque<Frame>,
        target: ReleaseNodeIdentity,
        nodes: Map<ReleaseNodeIdentity, ClosureNode>,
    ): RuntimeException {
        val active = stack.map { it.identity }
        val trail = active.subList(active.indexOf(target), active.size) + target
        return ReleaseRefusals.cycle(root.family, trail.map { nodes.getValue(it) })
    }

    /** One frame of the explicit stack: the node, the next pin to read, its depth on the active path. */
    private class Frame(
        val identity: ReleaseNodeIdentity,
        val depth: Int,
    ) {
        var next: Int = 0
    }
}

/**
 * A walked closure: [nodes] in discovery order (the root first, `n0`; `node_id` is this snapshot's opaque locator, never
 * an entity id — record §2.1), every pin occurrence in [edges], the longest path in [depth], and [dependencyFirst] — the
 * post-order the coordinator releases in (every dependency before its dependants, the root last; record §4.1 step 6).
 */
data class ReleaseClosure(
    val root: ReleaseNodeIdentity,
    val nodes: List<ClosureNode>,
    val edges: List<ClosureEdge>,
    val depth: Int,
    val dependencyFirst: List<ClosureNode>,
) {
    private val byIdentity: Map<ReleaseNodeIdentity, ClosureNode> = nodes.associateBy { it.identity }

    /** The root node, `n0`. */
    val rootNode: ClosureNode get() = nodes.first()

    fun node(identity: ReleaseNodeIdentity): ClosureNode = byIdentity.getValue(identity)

    fun nodeById(nodeId: String): ClosureNode? = nodes.firstOrNull { it.nodeId == nodeId }

    /**
     * The bounded field paths that reach [identity] from the root (record §14 `paths`): each incoming pin occurrence's
     * path, prefixed by its parent's first-discovery route and joined with `/`; at most [MAX_PATHS_PER_NODE], in edge
     * order — never every expanded path of a diamond.
     */
    fun pathsTo(identity: ReleaseNodeIdentity): List<String> =
        edges
            .filter { it.to == identity }
            .take(MAX_PATHS_PER_NODE)
            .map { edge -> listOfNotNull(firstRoute[edge.from]?.takeIf { it.isNotEmpty() }, edge.path).joinToString("/") }

    private val firstRoute: Map<ReleaseNodeIdentity, String> by lazy {
        val routes = HashMap<ReleaseNodeIdentity, String>()
        routes[root] = ""
        // Discovery order is parent-before-child for the edge that discovered each node, so one pass resolves every route.
        nodes.drop(1).forEach { node ->
            val discovering = edges.first { it.to == node.identity && it.from in routes }
            val prefix = routes.getValue(discovering.from).takeIf { it.isNotEmpty() }
            routes[node.identity] = listOfNotNull(prefix, discovering.path).joinToString("/")
        }
        routes
    }

    companion object {
        /** A blocker names at most this many routes (record §14: bounded locators). */
        const val MAX_PATHS_PER_NODE = 8
    }
}

/** One node of a [ReleaseClosure]: what the plan shows and the tree hash covers (record §2.1). */
data class ClosureNode(
    val nodeId: String,
    val identity: ReleaseNodeIdentity,
    val name: String,
    val status: ReleaseNodeStatus,
    /** Null exactly when [status] is MISSING — an unresolved pin has no body hash (record §2.1). */
    val bodyHash: String?,
    val currentVersion: Int?,
)

/** One pin occurrence between two closure nodes. */
data class ClosureEdge(
    val from: ReleaseNodeIdentity,
    val to: ReleaseNodeIdentity,
    val path: String,
)
