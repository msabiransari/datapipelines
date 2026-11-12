package co.datapipelines.pipeline.release

/**
 * The legacy consent `release_pinned_templates=true` / `release_pinned_visualizations=true` (#142, #332; record §6):
 * scoped to its OLD direct edges — the root's own DRAFT pins in the family its flag named — never "release every
 * family". A deeper draft, a draft child pipeline or a MISSING/DISCARDED pin stays a blocker under it.
 */
object LegacyReleaseAlias {
    /**
     * **The arming rule (brief resolution 1, #522; flipped by #876 §F.4, record §25.4 D15).** False: every release dialog
     * now posts the plan its GET rendered (`tree_hash` + `include_dependencies`), so a bare `release_pinned_templates=true`
     * or `release_pinned_visualizations=true` without any plan field refuses `<root family>.release.plan_required` (428)
     * as record §6 and §17 row 6 rule — on every surface, whatever the closure holds. The alias together with a plan
     * field stays `plan_invalid` (two authorities). [selection] and [order] remain for the coordinator's compatibility
     * arm, reachable only by a coordinator constructed with the alias admitted (its tests).
     */
    const val LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED = false

    /**
     * The DRAFT dependencies the root pins DIRECTLY in the families its legacy consent named — what the alias consents to
     * (#873 §C.3, record §16 rule 4: never broadened). Per root family, exactly the cascade each verb ran before the
     * planner: a pipeline's, a parameter set's and a visualization's DRAFT templates (`release_pinned_templates`); a
     * dashboard's DRAFT visualizations (`release_pinned_visualizations`) — not their templates, not its parameter set,
     * not its source pipelines; a template and an API consent to nothing.
     */
    fun selection(closure: ReleaseClosure): Set<ReleaseNodeIdentity> {
        val consented = consentedFamilies(closure.root.family)
        return closure.edges
            .filter { it.from == closure.root }
            .map { closure.node(it.to) }
            .filter { it.identity.family in consented && it.status == ReleaseNodeStatus.DRAFT }
            .map { it.identity }
            .toSet()
    }

    /** The families [root]'s legacy consent flag names (#873 §C.3); empty when the family had none. */
    fun consentedFamilies(root: ReleaseFamily): Set<ReleaseFamily> =
        when (root) {
            ReleaseFamily.PIPELINE, ReleaseFamily.PARAMETER_SET, ReleaseFamily.VISUALIZATION -> setOf(ReleaseFamily.TEMPLATE)
            ReleaseFamily.DASHBOARD -> setOf(ReleaseFamily.VISUALIZATION)
            ReleaseFamily.TEMPLATE, ReleaseFamily.ENDPOINT -> emptySet()
        }

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
