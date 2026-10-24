package co.datapipelines.pipeline.release

import co.datapipelines.typesystem.DatapipelinesException

/**
 * The eight release-plan refusals as exceptions (record §14). Every one carries the ROOT's family prefix — the blocker's
 * family is in `details`, never in the code — and no detail reflects a submitted value or a hidden fact: a field locator,
 * a bound and its maximum, or visible identities only.
 */
object ReleaseRefusals {
    fun dependencyNotReleased(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = closure.root.family.code(ReleaseRefusal.DEPENDENCY_NOT_RELEASED),
            message =
                "Release blocked: ${blockers.size} ${if (blockers.size == 1) "dependency is" else "dependencies are"} " +
                    "not released. Release them first, or include the drafts in this release.",
            details = ReleaseBlockers.details(closure, blockers, maxBlockers),
        )

    fun cycle(
        rootFamily: ReleaseFamily,
        trail: List<ClosureNode>,
    ): DatapipelinesException =
        DatapipelinesException(
            code = rootFamily.code(ReleaseRefusal.DEPENDENCY_CYCLE),
            message = "Release blocked: the dependencies form a cycle (${describe(trail)}).",
            details = mapOf("path" to trail.map { ReleaseBlockers.identityOf(it) }),
        )

    private fun describe(trail: List<ClosureNode>): String = trail.joinToString(" → ") { "${it.name} v${it.identity.version}" }

    fun limit(
        rootFamily: ReleaseFamily,
        limit: ReleaseLimit,
        maximum: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = rootFamily.code(ReleaseRefusal.DEPENDENCY_LIMIT),
            message = "Release blocked: the dependencies exceed the configured ${limit.wire} bound of $maximum.",
            details = linkedMapOf("limit" to limit.wire, "maximum" to maximum),
        )

    /** A consented snapshot fact changed; [nodeId] and [path] only when the changed node is visible (record §14). */
    fun planConflict(
        rootFamily: ReleaseFamily,
        nodeId: String? = null,
        path: String? = null,
    ): DatapipelinesException =
        DatapipelinesException(
            code = rootFamily.code(ReleaseRefusal.PLAN_CONFLICT),
            message = "The dependencies changed after the release was previewed. Reopen the release to see the current state.",
            details =
                buildMap {
                    put("reason", "snapshot_changed")
                    if (nodeId != null) put("node_id", nodeId)
                    if (path != null) put("path", path)
                },
        )

    fun planRequired(rootFamily: ReleaseFamily): DatapipelinesException =
        DatapipelinesException(
            code = rootFamily.code(ReleaseRefusal.PLAN_REQUIRED),
            message = "Releasing dependencies needs a current dependency preview: send its tree_hash with the selection.",
        )

    /** [field] is a bounded locator (`include_dependencies[2]`), never the submitted value. */
    fun planInvalid(
        rootFamily: ReleaseFamily,
        field: String,
        reason: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = rootFamily.code(ReleaseRefusal.PLAN_INVALID),
            message = "The release plan is not valid: $reason.",
            details = linkedMapOf("field" to field, "reason" to reason),
        )

    fun busy(
        family: ReleaseFamily,
        lockWaitSeconds: Int,
        cause: Throwable? = null,
    ): DatapipelinesException =
        DatapipelinesException(
            code = family.code(ReleaseRefusal.BUSY),
            message = "Another change in this workspace is in progress. Nothing was written; try again in a moment.",
            details = mapOf("lock_wait_seconds" to lockWaitSeconds),
            cause = cause,
        )
}
