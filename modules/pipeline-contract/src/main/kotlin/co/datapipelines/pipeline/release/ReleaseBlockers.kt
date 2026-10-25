package co.datapipelines.pipeline.release

/**
 * The shared `<family>.release.dependency_not_released` details (record §14, one shape for the five families):
 *
 * `{root, blockers:[{node_id, family, name, version, status, reason, paths}], blockers_truncated}`
 *
 * `root` is typed identity metadata — `{node_id, family, name, version}` — never a body. A blocker is a visible MISSING
 * or DISCARDED pin, or a DRAFT the request did not include; at most [ReleaseBounds.maxBlockers] are listed, in closure
 * discovery order, and the rest set `blockers_truncated`. Hidden and absent pins are one answer (`MISSING`), so the
 * shape is byte-equal for the two (record §13). S1 applies it to the pipeline root; S2–S5 to theirs.
 */
object ReleaseBlockers {
    /** One blocker of [closure], or null when [node] does not block (RELEASED, or a DRAFT the request includes). */
    fun blockerOf(
        node: ClosureNode,
        included: Set<ReleaseNodeIdentity>,
    ): BlockerReason? =
        when (node.status) {
            ReleaseNodeStatus.MISSING -> BlockerReason.MISSING
            ReleaseNodeStatus.DISCARDED -> BlockerReason.DISCARDED
            ReleaseNodeStatus.DRAFT -> if (node.identity in included) null else BlockerReason.NOT_INCLUDED
            ReleaseNodeStatus.RELEASED -> null
        }

    /** Every blocker of [closure] beneath the root, in discovery order. */
    fun blockers(
        closure: ReleaseClosure,
        included: Set<ReleaseNodeIdentity>,
    ): List<Pair<ClosureNode, BlockerReason>> = closure.nodes.drop(1).mapNotNull { node -> blockerOf(node, included)?.let { node to it } }

    /** The details map for [blockers], truncated at [maxBlockers]. */
    fun details(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): Map<String, Any?> =
        linkedMapOf(
            "root" to identityOf(closure.rootNode),
            "blockers" to
                blockers.take(maxBlockers).map { (node, reason) ->
                    identityOf(node) +
                        linkedMapOf(
                            "status" to node.status.name,
                            "reason" to reason.wire,
                            "paths" to closure.pathsTo(node.identity),
                        )
                },
            "blockers_truncated" to (blockers.size > maxBlockers),
        )

    /**
     * A RESTORE's refusal details (#517 round three, record §12.1 RULED 2026-10-25) in the same shape over a one-level
     * walk: the restored version is `n0`, each distinct present-but-not-RELEASED direct pin is `n1`, `n2`… in pin order
     * with every field path that pins it (at most [ReleaseClosure.MAX_PATHS_PER_NODE]), reason `not_released`; at most
     * [maxBlockers] are listed and the rest set `blockers_truncated`.
     */
    fun restoreDetails(
        root: ReleaseNodeIdentity,
        rootName: String,
        pins: List<RestoreBlocker>,
        maxBlockers: Int,
    ): Map<String, Any?> {
        val targets = pins.groupBy { it.identity }.values.toList()
        return linkedMapOf(
            "root" to identity("n0", root, rootName),
            "blockers" to
                targets.take(maxBlockers).mapIndexed { index, occurrences ->
                    val first = occurrences.first()
                    identity("n${index + 1}", first.identity, first.name) +
                        linkedMapOf(
                            "status" to first.status.name,
                            "reason" to BlockerReason.NOT_RELEASED.wire,
                            "paths" to occurrences.take(ReleaseClosure.MAX_PATHS_PER_NODE).map { it.path },
                        )
                },
            "blockers_truncated" to (targets.size > maxBlockers),
        )
    }

    /** A node's typed identity as every refusal names it: the locator, the family word, the authored name, the version. */
    fun identityOf(node: ClosureNode): Map<String, Any?> = identity(node.nodeId, node.identity, node.name)

    private fun identity(
        nodeId: String,
        identity: ReleaseNodeIdentity,
        name: String,
    ): Map<String, Any?> =
        linkedMapOf(
            "node_id" to nodeId,
            "family" to identity.family.wire,
            "name" to name,
            "version" to identity.version,
        )
}

/** One pin occurrence a RESTORE found present but not RELEASED: the target, its authored name, its status, the field path. */
data class RestoreBlocker(
    val identity: ReleaseNodeIdentity,
    val name: String,
    val status: ReleaseNodeStatus,
    val path: String,
)
