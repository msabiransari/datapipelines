package co.datapipelines.pipeline.release

/**
 * The legacy consent `release_pinned_templates=true` (#142; record §6): scoped to its OLD direct edges — the root's own
 * DRAFT template pins — never "release every family". A deeper draft, a draft child pipeline or a MISSING/DISCARDED pin
 * stays a blocker under it.
 */
object LegacyReleaseAlias {
    /**
     * **The arming rule (brief resolution 1, #522).** True: a bare `release_pinned_templates=true` without any plan field
     * keeps the #142 direct-template cascade, because the pipeline dialog posts exactly that until S6 (#522) teaches it
     * `tree_hash`. The alias together with a plan field is always `plan_invalid` (two authorities). S6 flips this to
     * false, after which the bare alias refuses `pipeline.release.plan_required` (428) as record §6 and §17 row 6 rule.
     */
    const val LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED = true

    /** The DRAFT templates the root pins directly — what the alias consents to. */
    fun selection(closure: ReleaseClosure): Set<ReleaseNodeIdentity> =
        closure.edges
            .filter { it.from == closure.root }
            .map { closure.node(it.to) }
            .filter { it.identity.family == ReleaseFamily.TEMPLATE && it.status == ReleaseNodeStatus.DRAFT }
            .map { it.identity }
            .toSet()

    /** [selected] in the root's pin order, deduplicated — the order #142 released and audited them in. */
    fun order(
        closure: ReleaseClosure,
        selected: Set<ReleaseNodeIdentity>,
    ): List<ReleaseNodeIdentity> =
        closure.edges
            .filter { it.from == closure.root && it.to in selected }
            .map { it.to }
            .distinct()
}
