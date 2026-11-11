package co.datapipelines.web.release

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.release.BlockerReason
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardReleaseCoordination
import co.datapipelines.visualization.DashboardReleased
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.pipelines.LifecycleVerbs
import java.util.UUID
import org.springframework.transaction.TransactionStatus

/**
 * The dashboard family as a release ROOT (#873 §B.3, §C.4; record §4.1, §6) — a dashboard has no inbound pinner, so it
 * is always the root and flips last:
 *
 * - prepare, OUTSIDE the metadata transaction: every §3.2 rule but the source-status one
 *   ([DashboardService.prepareRelease]) — a DRAFT source pipeline is now a dependency the plan may select;
 * - commit, INSIDE, after every selected dependency flipped: the §3.2 rules again WITH the source-status rule (R5: a
 *   dashboard never commits over a non-RELEASED source), then the exact-hash flip ([DashboardService.commitRelease]).
 */
class DashboardReleaseParticipant(
    private val dashboards: DashboardService,
) : ArtifactReleaseRoot {
    override val family: ReleaseFamily = ReleaseFamily.DASHBOARD

    /** Today's order (`DashboardService.release`): the §3.2 rules before the dependency guard. */
    override val dependenciesBeforePrepare: Boolean = false

    /** A prepared dashboard: the stored DRAFT the commit re-judges and flips. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val actor: UUID,
        val draft: ArtifactVersion<DashboardBody>,
    ) : PreparedRelease(node, expectedHash)

    override fun rootIdentity(
        workspaceId: UUID,
        entityId: UUID,
    ): ReleaseNodeIdentity {
        val (record, draft) = dashboards.releaseTarget(workspaceId, entityId)
        return ReleaseNodeIdentity(workspaceId, family, record.name, draft.version)
    }

    override fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): Prepared {
        val workspaceId = node.identity.workspaceId
        val id = node.entityUuid ?: throw ArtifactReleaseRoot.conflict(context.closure.root.family, node)
        val draft =
            dashboards.prepareRelease(workspaceId, id, node.identity.version, context.expectedHash)
                ?: throw dashboards.staleRelease(workspaceId, id)
        return Prepared(node, context.expectedHash, context.actor, draft)
    }

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip {
        val dashboard = prepared as Prepared
        val workspaceId = dashboard.node.identity.workspaceId
        val flipped =
            dashboards.commitRelease(workspaceId, dashboard.draft, dashboard.expectedHash, dashboard.actor)
                ?: throw dashboards.staleRelease(workspaceId, dashboard.draft.record.id)
        return ReleaseFlip(dashboard.node, flipped)
    }

    /** The dashboard's own `dashboard.version.released` row (#332's shape): `visualizations_released` = the flipped visualizations. */
    override fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
        dependencies: List<ReleasedDependency>,
    ): Pair<String, Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST") // the commit above is the only producer of this flip's result
        val released = flip.result as ArtifactVersion<DashboardBody>
        return LifecycleVerbs.DASHBOARD_EVENTS.versionReleased to
            LifecycleVerbs.familyReleaseDetails(
                principal,
                LifecycleVerbs.FamilyIdentity("dashboard_id", "dashboard_name", entityId, released.record.name, released.detail.version),
                "visualizations_released",
                ArtifactReleaseRoot.visualizations(dependencies).map { mapOf("name" to it.name, "version" to it.version) },
            )
    }

    /**
     * The alias path's refusal in today's keys (#873 §C.5; `DashboardService.dependenciesToRelease`): the first blocker's
     * `kind` / `name` / `version` / `status` at the top level and every blocker under `dependencies_not_released` — the
     * direct pins first (set, source pipelines, visualizations), then the deeper ones. A DRAFT source pipeline is now one
     * of them (kind `pipeline`, §C.4), where the validator's 400 answered before.
     */
    override fun aliasRefusal(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): DatapipelinesException {
        val listed = ArtifactReleaseRoot.directFirst(closure, blockers).map { dependency(it.first) }
        return DatapipelinesException(
            DashboardErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            "The dashboard pins ${listed.size} dependency(ies) that are not released; release them first, " +
                "or release with the pinned visualizations.",
            listed.first() + ("dependencies_not_released" to listed.take(maxBlockers)),
        )
    }

    companion object {
        /** The dashboard service's planner entry (#873 §C.1; the set participant's twin). */
        fun coordination(coordinator: () -> ReleaseCoordinator): DashboardReleaseCoordination =
            DashboardReleaseCoordination { request ->
                val released =
                    coordinator().releaseRoot(
                        RootReleaseRequest(
                            ReleaseFamily.DASHBOARD,
                            request.workspaceId,
                            request.id,
                            request.expectedHash,
                            request.actor,
                            request.plan,
                        ),
                        releasePinned = request.releasePinned,
                    )
                @Suppress("UNCHECKED_CAST") // the participant's commit is the only producer of the root's result
                DashboardReleased(
                    released.root.result as ArtifactVersion<DashboardBody>,
                    ArtifactReleaseRoot.visualizations(released.dependenciesReleased).map { ArtifactRef(it.name, it.version) },
                )
            }
    }

    private fun dependency(node: ClosureNode): Map<String, Any?> =
        mapOf("kind" to node.identity.family.wire, "name" to node.name, "version" to node.identity.version, "status" to node.status.name)
}
