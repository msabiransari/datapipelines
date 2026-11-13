package co.datapipelines.web.release

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.TemplateRef
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
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ReleaseCandidate
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReleaseCoordination
import co.datapipelines.visualization.VisualizationReleased
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.pipelines.LifecycleVerbs
import org.springframework.transaction.TransactionStatus
import java.util.UUID

/**
 * The visualization family in a coordinated release (#873 §B.2; record §4.1) — as the ROOT and as a dashboard's
 * dependency:
 *
 * - prepare, OUTSIDE the metadata transaction on the plan path (inside it on the dashboard's alias path, as today's
 *   cascade ran — brief §C.8): at least one test case (`tests_missing` / `no_cases`), the rules against the transform pin
 *   as it is now, and the evidence gate's [ReleaseCandidate] ([VisualizationService.prepareRelease]);
 * - commit, INSIDE: the [co.datapipelines.visualization.ReleaseEvidence] verdict — every `tests_*` / `mechanical_failed`
 *   refusal kept with its reason (A12) — then the exact-hash flip ([VisualizationService.commitRelease]). The verdict's
 *   mechanical re-run is the visualization's own script, never a customer probe: the ONE in-transaction evaluation.
 */
class VisualizationReleaseParticipant(
    private val visualizations: VisualizationService,
) : ArtifactReleaseRoot {
    override val family: ReleaseFamily = ReleaseFamily.VISUALIZATION

    /** Today's order (`VisualizationService.release`): the test cases and the rules before the pin guard. */
    override val dependenciesBeforePrepare: Boolean = false

    /** A prepared visualization: the evidence gate's candidate the commit judges and flips. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val actor: UUID,
        val root: Boolean,
        val rootFamily: ReleaseFamily,
        val candidate: ReleaseCandidate,
    ) : PreparedRelease(node, expectedHash)

    override fun rootIdentity(
        workspaceId: UUID,
        entityId: UUID,
    ): ReleaseNodeIdentity {
        val (record, draft) = visualizations.releaseTarget(workspaceId, entityId)
        return ReleaseNodeIdentity(workspaceId, family, record.name, draft.version)
    }

    override fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): Prepared {
        val root = node.identity == context.closure.root
        val rootFamily = context.closure.root.family
        val workspaceId = node.identity.workspaceId
        val id = node.entityUuid ?: throw ArtifactReleaseRoot.conflict(rootFamily, node)
        val candidate =
            visualizations.prepareRelease(workspaceId, id, node.identity.version, context.expectedHash)
                ?: throw if (root) visualizations.staleRelease(workspaceId, id) else ArtifactReleaseRoot.conflict(rootFamily, node)
        return Prepared(node, context.expectedHash, context.actor, root, rootFamily, candidate)
    }

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip {
        val visualization = prepared as Prepared
        val workspaceId = visualization.node.identity.workspaceId
        val id = visualization.candidate.visualizationId
        val flipped =
            visualizations.commitRelease(workspaceId, visualization.candidate, visualization.expectedHash, visualization.actor)
                ?: throw if (visualization.root) {
                    visualizations.staleRelease(workspaceId, id)
                } else {
                    ArtifactReleaseRoot.conflict(visualization.rootFamily, visualization.node)
                }
        return ReleaseFlip(visualization.node, flipped)
    }

    /** The visualization's own `visualization.version.released` row (#332's shape): `templates_released` = the flipped templates. */
    override fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
        dependencies: List<ReleasedDependency>,
    ): Pair<String, Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST") // the commit above is the only producer of this flip's result
        val released = flip.result as ArtifactVersion<VisualizationBody>
        return LifecycleVerbs.VISUALIZATION_EVENTS.versionReleased to
            LifecycleVerbs.familyReleaseDetails(
                principal,
                LifecycleVerbs.FamilyIdentity(
                    "visualization_id",
                    "visualization_name",
                    entityId,
                    released.record.name,
                    released.detail.version,
                ),
                "templates_released",
                ArtifactReleaseRoot.templates(dependencies).map { mapOf("template_id" to it.name, "version" to it.version) },
            )
    }

    /**
     * The alias path's refusal in today's keys (#873 §C.5; `VisualizationService.templatesToRelease`): the first blocker
     * as `template` / `template_version` / `template_status` at the top level and every blocker under `pins_not_released`
     * — the transform pin first, then any deeper blocker the walk found beneath a RELEASED transform template.
     */
    override fun aliasRefusal(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): DatapipelinesException {
        val ordered = ArtifactReleaseRoot.directFirst(closure, blockers).map { it.first }
        val first = ordered.first()
        return DatapipelinesException(
            VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            "Template '${first.name}' version ${first.identity.version} is not released; release it first, or release with the templates.",
            pin(first) + ("pins_not_released" to ordered.take(maxBlockers).map(::pin)),
        )
    }

    companion object {
        /** The visualization service's planner entry (#873 §C.1; the set participant's twin). */
        fun coordination(coordinator: () -> ReleaseCoordinator): VisualizationReleaseCoordination =
            VisualizationReleaseCoordination { request ->
                val released =
                    coordinator().releaseRoot(
                        RootReleaseRequest(
                            ReleaseFamily.VISUALIZATION,
                            request.workspaceId,
                            request.id,
                            request.expectedHash,
                            request.actor,
                            request.plan,
                        ),
                        releasePinned = request.releasePinned,
                    )
                @Suppress("UNCHECKED_CAST") // the participant's commit is the only producer of the root's result
                VisualizationReleased(
                    released.root.result as ArtifactVersion<VisualizationBody>,
                    ArtifactReleaseRoot.templates(released.dependenciesReleased).map { TemplateRef(it.name, it.version) },
                )
            }
    }

    private fun pin(node: ClosureNode): Map<String, Any?> =
        mapOf("template" to node.name, "template_version" to node.identity.version, "template_status" to node.status.name)
}
