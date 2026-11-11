package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.IncomingEdge
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.IncomingScope
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.incomingPage
import co.datapipelines.pipeline.inverse.InverseAfter
import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope

/**
 * One inverse arm of a target family (#877 §B, record §14): the ONE [dependents] family whose stored references it reads,
 * and the dependent repository's native bounded page query [read] — `limit + 1` rows after a position, in the inverse
 * total order. [versioned] is false for a dependent with no versions (a schedule: version 0, no lifecycle).
 */
class IncomingArm(
    val dependents: FamilyKey,
    val versioned: Boolean,
    val read: (InverseRequest) -> List<InverseRow>,
)

/**
 * A target family's set of inverse arms — what its provider's `findIncoming` answers through (#877 §B). One mapping for
 * every family, so a page's order, cursor and edge shape never depend on which arm answered:
 *
 * - a dependent family with no arm is [IncomingResult.Unsupported] (never an empty page, §A.4);
 * - the target must be of [target]'s family and carry the family's owner scope — the context's workspace, or the
 *   deployment for a [deploymentScoped] family (a datasource's identity is its name at deployment scope) — anything else
 *   is a caller defect (IAE); the DEPENDENTS read are always the context's workspace's;
 * - an exact-version target is refused (IAE) by a family that admits only the entity form ([exactAdmitted] false: a
 *   datasource is referenced by its flat name, erratum (7));
 * - each page is ONE statement of the arm, bounded by [ceiling] with the context's live statement bound
 *   ([BoundedGraphRead], #786 R3 — a spent deadline is `DeadlineExceeded`, never a short answer);
 * - each row maps to an [IncomingEdge] whose relationship is the §6 row between the two families.
 *
 * Unlensed by contract: the view boundary is the adapter above (§12).
 */
class IncomingArms(
    val target: FamilyKey,
    arms: List<IncomingArm>,
    private val ceiling: StatementCeiling,
    private val exactAdmitted: Boolean = true,
    private val deploymentScoped: Boolean = false,
) {
    private val byDependents: Map<FamilyKey, IncomingArm> = arms.associateBy { it.dependents }

    init {
        require(byDependents.size == arms.size) { "one arm per dependent family into $target" }
        arms.forEach { arm ->
            requireNotNull(Relationship.between(arm.dependents, target)) { "${arm.dependents} → $target is not a §6 row" }
        }
    }

    /** The dependent families this target answers — the provider's `incomingFrom`. */
    val incomingFrom: Set<FamilyKey> get() = byDependents.keys

    fun find(
        query: IncomingQuery,
        context: ResolveContext,
    ): IncomingResult {
        val arm = byDependents[query.dependents] ?: return IncomingResult.Unsupported
        val target = query.target
        require(target.family == this.target) { "a ${this.target} provider answers ${this.target} targets, got ${target.family}" }
        val ownerScope = if (deploymentScoped) OwnerScope.Deployment else OwnerScope.Workspace(context.workspaceId)
        require(target.ownerScope == ownerScope) { "a ${this.target} target is owned by $ownerScope, got ${target.ownerScope}" }
        val version =
            when (target) {
                is IncomingTarget.ExactVersion -> {
                    require(exactAdmitted) { "${this.target} is referenced by name, never at a version: ask for the entity" }
                    target.version
                }

                is IncomingTarget.Entity -> {
                    null
                }
            }
        val relationship = checkNotNull(Relationship.between(arm.dependents, this.target))
        val after = query.cursor?.let { InverseAfter(it.dependentId, it.dependentVersion, it.sourcePath) }
        val request = InverseRequest(context.workspaceId, target.key, version, query.scope.inverse, after, query.limit)
        val rows = BoundedGraphRead.run(this.target, emptyList(), context, ceiling) { arm.read(request) }
        return incomingPage(query, rows.map { edge(it, arm, relationship, context) })
    }

    private fun edge(
        row: InverseRow,
        arm: IncomingArm,
        relationship: Relationship,
        context: ResolveContext,
    ): IncomingEdge =
        IncomingEdge(
            dependent =
                ResolvedIdentity(
                    family = arm.dependents,
                    ownerScope = OwnerScope.Workspace(context.workspaceId),
                    stableEntityId = row.dependentId,
                    artifactVersion = row.dependentVersion.takeIf { arm.versioned },
                ),
            dependentName = row.dependentName,
            dependentLifecycle = row.status?.let { ArtifactReleaseProvider.lifecycleOf(it) },
            relationship = relationship,
            sourcePath = row.sourcePath,
            storedVersion = row.storedVersion,
            elementId = row.elementId,
        )

    companion object {
        /** No arms: a family nothing references (a dashboard, a schedule, an API) — every dependent family is Unsupported. */
        fun none(target: FamilyKey): IncomingArms = IncomingArms(target, emptyList(), StatementCeiling.NONE)
    }
}

/** The contract's dependent scope as the native queries' — the same three questions under one definition. */
val IncomingScope.inverse: InverseScope
    get() =
        when (this) {
            IncomingScope.WORKING -> InverseScope.WORKING
            IncomingScope.LIVE -> InverseScope.LIVE
            IncomingScope.ANY -> InverseScope.ANY
        }
