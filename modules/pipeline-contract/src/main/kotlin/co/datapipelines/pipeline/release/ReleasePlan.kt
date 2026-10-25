package co.datapipelines.pipeline.release

/**
 * The release-plan wire (record §2.1, FROZEN after ratification): `root, nodes[], edges[], complete, blockers[],
 * tree_hash`. The server alone decides topology, statuses and selectability: the root is selected by the Release action
 * itself and a RELEASED node is shown but never selectable, so both carry `selectable:false`; a MISSING node has no body
 * hash. A lensed read (record §5) answers `complete:false` and `tree_hash:null`; that projection and the read routes that
 * serve this wire are S6's (#522) — S1 builds the session plan the coordinator compares against.
 */
data class ReleasePlan(
    val closure: ReleaseClosure,
    val complete: Boolean,
    val blockers: List<Pair<ClosureNode, BlockerReason>>,
    val treeHash: String?,
) {
    /** The wire map, keys in the record's order. */
    fun toWire(maxBlockers: Int): Map<String, Any?> =
        linkedMapOf(
            "root" to closure.rootNode.nodeId,
            "nodes" to
                closure.nodes.map { node ->
                    linkedMapOf(
                        "node_id" to node.nodeId,
                        "family" to node.identity.family.wire,
                        "name" to node.name,
                        "version" to node.identity.version,
                        "status" to node.status.name,
                        "body_hash" to node.bodyHash,
                        "selectable" to selectable(node),
                    )
                },
            "edges" to
                closure.edges.map { edge ->
                    linkedMapOf("from" to closure.node(edge.from).nodeId, "to" to closure.node(edge.to).nodeId, "path" to edge.path)
                },
            "complete" to complete,
            "blockers" to (ReleaseBlockers.details(closure, blockers, maxBlockers)["blockers"]),
            "tree_hash" to treeHash,
        )

    /** Only a DRAFT dependency can be ticked; the root is implicit, RELEASED/MISSING/DISCARDED never (record §2.1). */
    fun selectable(node: ClosureNode): Boolean = node.identity != closure.root && node.status == ReleaseNodeStatus.DRAFT

    companion object {
        /**
         * The session plan of an authorised releaser: complete, and its blockers are the metadata ones only — MISSING and
         * DISCARDED pins. A DRAFT is a checkbox, not a blocker, until a submit leaves it unticked (record §4.1 step 2).
         */
        fun session(
            closure: ReleaseClosure,
            maxWireBytes: Int,
        ): ReleasePlan {
            val drafts =
                closure.nodes
                    .filter { it.status == ReleaseNodeStatus.DRAFT }
                    .map { it.identity }
                    .toSet()
            return ReleasePlan(closure, complete = true, ReleaseBlockers.blockers(closure, drafts), TreeHash.of(closure, maxWireBytes))
        }
    }
}
