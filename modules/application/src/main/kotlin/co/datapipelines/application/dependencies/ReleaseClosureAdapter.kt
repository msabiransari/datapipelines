package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.DiagnosticKind
import co.datapipelines.dependencies.Edge
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.LimitKind
import co.datapipelines.dependencies.Node
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Resolution
import co.datapipelines.pipeline.release.ClosureEdge
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseLimit
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.pipeline.release.ReleaseRefusals
import java.util.UUID

/**
 * A neutral [GraphSnapshot] as the existing [ReleaseClosure] (#868 G1 section D.2): the same node ids, statuses, body
 * hashes, pointers, entity ids, edge paths, depth and dependency-first order `ReleaseGraph` produced, so `TreeHash`, the
 * blockers and the plan wire are unchanged; and the same refusals — the FIRST `CYCLE` or traversal `LIMIT` in the
 * snapshot's event order is the one `ReleaseGraph` threw, with the same code and details.
 *
 * Release reads every unresolved node (MISSING or a family with no source, UNSUPPORTED) as `MISSING` with the name its pin
 * spelled, and a `DISCARDED` version as a leaf. The snapshot withholds its dependency-first order when it is incomplete
 * (a DISCARDED or UNSUPPORTED leaf), so the adapter replays the post-order and the longest path over the snapshot's edges
 * — in edge order, which is the walk's own order. A `diagnostics` overflow is not a release refusal (release lists
 * its blockers from the nodes, bounded by `max_blockers`).
 */
object ReleaseClosureAdapter {
    /**
     * The closure of [root], or null when the root itself did not resolve (the caller's native not-found).
     *
     * #873 §F.3 b: the snapshot is SPLIT. Its datasource nodes are prerequisites ([prerequisites]), never closure nodes:
     * they and the edges that reach them are left out, and the kept artifact nodes are numbered `n0`, `n1`… in snapshot
     * order. A datasource is a leaf discovered after the references that precede it, so removing it leaves the artifact
     * nodes and edges in exactly the order — and with exactly the ids — an artifact-only walk gives: `TreeHash` v1, the
     * blockers and the plan wire over the closure are byte-identical.
     */
    fun closure(
        root: ReleaseNodeIdentity,
        snapshot: GraphSnapshot,
    ): ReleaseClosure? {
        val rootNode = snapshot.node(snapshot.roots.single())
        // An unresolved root is a leaf: no edge, so no cycle and no limit can precede it (ReleaseGraph's first check).
        if (rootNode.resolution != Resolution.RESOLVED) return null
        val kept = snapshot.nodes.filterNot(::isPrerequisite)
        val closureNodes = kept.withIndex().associate { (index, node) -> node.nodeId to closureNode(node, "n$index") }
        val edges = snapshot.edges.filter { it.toNodeId in closureNodes }
        refusal(root, snapshot, closureNodes)?.let { throw it }
        val replay = Replay(rootNode.nodeId, edges)
        return ReleaseClosure(
            root = root,
            nodes = kept.map { closureNodes.getValue(it.nodeId) },
            edges =
                edges.map { edge ->
                    ClosureEdge(
                        from = closureNodes.getValue(edge.fromNodeId).identity,
                        to = closureNodes.getValue(edge.toNodeId).identity,
                        path = requireNotNull(edge.sourcePath) { "a release pin always has its field path" },
                    )
                },
            depth = replay.height(rootNode.nodeId),
            dependencyFirst = replay.postOrder.map { closureNodes.getValue(it) },
        )
    }

    /**
     * The datasource prerequisites of [snapshot] (#873 §F.3 b), one per distinct authored name in discovery order: a
     * RESOLVED node is available with its own `readonly` flag; a MISSING one (absent, or not granted to the caller's
     * workspace — G3's provider answers both alike, record §12) or an UNSUPPORTED one is unavailable with `readonly`
     * null. The row is built from the scoped outcome and the authored name alone, so the two unavailable cases are
     * byte-identical.
     */
    fun prerequisites(snapshot: GraphSnapshot): List<ReleasePrerequisite> =
        snapshot.nodes
            .filter(::isPrerequisite)
            .distinctBy { it.requestedAddress.lookupKey }
            .map { node ->
                val readonly = (node.operational as? Operational.Datasource)?.readonly.takeIf { node.resolution == Resolution.RESOLVED }
                ReleasePrerequisite(node.requestedAddress.lookupKey, available = readonly != null, readonly = readonly)
            }

    /** A datasource node: a prerequisite a release needs and never flips (record §8). */
    fun isPrerequisite(node: Node): Boolean = node.family == FamilyKey.DATASOURCE

    private fun refusal(
        root: ReleaseNodeIdentity,
        snapshot: GraphSnapshot,
        nodes: Map<String, ClosureNode>,
    ): RuntimeException? {
        val first =
            snapshot.diagnostics.firstOrNull {
                it.kind == DiagnosticKind.CYCLE || (it.kind == DiagnosticKind.LIMIT && it.limit in RELEASE_LIMITS)
            } ?: return null
        if (first.kind == DiagnosticKind.CYCLE) return ReleaseRefusals.cycle(root.family, first.trail.map(nodes::getValue))
        return ReleaseRefusals.limit(root.family, RELEASE_LIMITS.getValue(requireNotNull(first.limit)), requireNotNull(first.maximum))
    }

    private fun closureNode(
        node: Node,
        nodeId: String,
    ): ClosureNode {
        val identity =
            checkNotNull(ReleaseDependencyFamilies.identityOf(ObjectRequest(node.requestedAddress, node.requestedSelector))) {
                "release walks exact release-family pins only, got ${node.requestedAddress} ${node.requestedSelector}"
            }
        val resolved = node.resolution == Resolution.RESOLVED
        return ClosureNode(
            nodeId = nodeId,
            identity = identity,
            name = requireNotNull(node.name) { "every release node carries the name it was found or pinned by" },
            status = if (resolved) statusOf(requireNotNull(node.lifecycle)) else ReleaseNodeStatus.MISSING,
            bodyHash = node.bodyHash.takeIf { resolved },
            currentVersion = node.currentVersion.takeIf { resolved },
            entityUuid = node.identity?.let { UUID.fromString(it.stableEntityId) },
        )
    }

    private fun statusOf(lifecycle: Lifecycle): ReleaseNodeStatus =
        when (lifecycle) {
            Lifecycle.DRAFT -> ReleaseNodeStatus.DRAFT
            Lifecycle.RELEASED -> ReleaseNodeStatus.RELEASED
            Lifecycle.DISCARDED -> ReleaseNodeStatus.DISCARDED
        }

    /** The walk's bounds that `ReleaseGraph` refused with: `versions` is the node bound. */
    private val RELEASE_LIMITS =
        mapOf(LimitKind.NODES to ReleaseLimit.VERSIONS, LimitKind.EDGES to ReleaseLimit.EDGES, LimitKind.DEPTH to ReleaseLimit.DEPTH)

    /** The depth-first replay over a complete-or-leaf-truncated snapshot: post-order and longest path, both from the edges. */
    private class Replay(
        root: String,
        edges: List<Edge>,
    ) {
        private val outgoing = edges.groupBy({ it.fromNodeId }, { it.toNodeId })
        private val heights = HashMap<String, Int>()
        val postOrder = ArrayList<String>()

        init {
            visit(root)
        }

        fun height(nodeId: String): Int = heights.getValue(nodeId)

        /** Iterative: a frame per node, children in edge order, a node finished once its last child is. */
        private fun visit(root: String) {
            val seen = HashSet<String>().apply { add(root) }
            val stack = ArrayDeque<Pair<String, Iterator<String>>>()
            stack.addLast(root to outgoing[root].orEmpty().iterator())
            while (stack.isNotEmpty()) {
                val children = stack.last().second
                val child = if (children.hasNext()) children.next() else null
                when {
                    child == null -> finish(stack.removeLast().first)
                    seen.add(child) -> stack.addLast(child to outgoing[child].orEmpty().iterator())
                }
            }
        }

        private fun finish(node: String) {
            heights[node] = outgoing[node].orEmpty().maxOfOrNull { heights.getValue(it) + 1 } ?: 0
            postOrder.add(node)
        }
    }
}
