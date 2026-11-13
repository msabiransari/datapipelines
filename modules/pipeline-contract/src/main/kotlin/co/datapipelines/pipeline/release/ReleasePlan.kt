package co.datapipelines.pipeline.release

/**
 * The release-plan wire (record §2.1, FROZEN after ratification): `root, nodes[], edges[], complete, blockers[],
 * tree_hash`. The server alone decides topology, statuses and selectability: the root is selected by the Release action
 * itself and a RELEASED node is shown but never selectable, so both carry `selectable:false`; a MISSING node has no body
 * hash. A lensed read (record §5) answers `complete:false` and `tree_hash:null`; that projection and the read routes that
 * serve this wire are S6's (#522) — S1 builds the session plan the coordinator compares against.
 *
 * #873 §F.3 b (record erratum (7)) adds ONE key, `prerequisites`, after `tree_hash`: the datasources the closure needs,
 * one [ReleasePrerequisite] row per distinct name — never a node, an edge, a checkbox or a `tree_hash` input. An
 * unavailable prerequisite blocks the plan: its versionless `{kind, name, reason}` blocker follows the node blockers in
 * `blockers`, inside the same [maxBlockers] bound. With no unavailable prerequisite, `nodes`, `edges`, `blockers` and
 * `tree_hash` are byte-identical to the wire before the key existed.
 */
data class ReleasePlan(
    val closure: ReleaseClosure,
    val complete: Boolean,
    val blockers: List<Pair<ClosureNode, BlockerReason>>,
    val treeHash: String?,
    val prerequisites: List<ReleasePrerequisite> = emptyList(),
) {
    /** The prerequisites that block this plan: every unavailable one, in the order the plan lists them. */
    val unavailablePrerequisites: List<ReleasePrerequisite> get() = prerequisites.filterNot { it.available }

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
            "blockers" to wireBlockers(maxBlockers),
            "tree_hash" to treeHash,
            "prerequisites" to prerequisites.map { it.toWire() },
        )

    /** The node blockers first, then the unavailable prerequisites' versionless blockers, at most [maxBlockers] in all. */
    private fun wireBlockers(maxBlockers: Int): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        val nodes = ReleaseBlockers.details(closure, blockers, maxBlockers)["blockers"] as List<Any?>
        return nodes + unavailablePrerequisites.take((maxBlockers - nodes.size).coerceAtLeast(0)).map { it.blocker() }
    }

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
            prerequisites: List<ReleasePrerequisite> = emptyList(),
        ): ReleasePlan {
            val drafts =
                closure.nodes
                    .filter { it.status == ReleaseNodeStatus.DRAFT }
                    .map { it.identity }
                    .toSet()
            return ReleasePlan(
                closure,
                complete = true,
                ReleaseBlockers.blockers(closure, drafts),
                TreeHash.of(closure, maxWireBytes),
                prerequisites,
            )
        }
    }
}
