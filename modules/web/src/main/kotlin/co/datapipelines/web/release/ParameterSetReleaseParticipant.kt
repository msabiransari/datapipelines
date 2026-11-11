package co.datapipelines.web.release

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetReleaseCoordination
import co.datapipelines.parameters.ParameterSetReleased
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.BlockerReason
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.ReleaseClosure
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.LifecycleVerbs
import java.util.UUID
import org.springframework.transaction.TransactionStatus

/**
 * The parameter-set family in a coordinated release (#873 §B.1; record §4.1) — as the ROOT of its own release and as a
 * dashboard's dependency:
 *
 * - prepare, OUTSIDE the metadata transaction: the exact DRAFT at the consented hash, re-judged against its selector
 *   sources as they are now ([ParameterSetService.prepareRelease]) — the probe opens customer-datasource connections,
 *   which is why this half never runs inside the transaction on either entry path (the alias selections never include a
 *   set, brief §C.8);
 * - commit, INSIDE: the set's existing exact-hash flip and its #778 observer ([ParameterSetService.commitRelease]).
 *
 * A DRAFT that is no longer the snapshot's version or hash is a changed snapshot — the ROOT family's `plan_conflict` —
 * except the root's own `If-Match`, whose staleness keeps the set's native `parameter.version.conflict`.
 */
class ParameterSetReleaseParticipant(
    private val sets: ParameterSetService,
) : ArtifactReleaseRoot {
    override val family: ReleaseFamily = ReleaseFamily.PARAMETER_SET

    /** Today's order (`ParameterSetService.release`): the pin guard refused before the selector revalidation ran. */
    override val dependenciesBeforePrepare: Boolean = true

    /** A prepared set: the stored DRAFT the commit flips. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val actor: UUID,
        val root: Boolean,
        val rootFamily: ReleaseFamily,
        val draft: ParameterSetVersion,
    ) : PreparedRelease(node, expectedHash)

    override fun rootIdentity(
        workspaceId: UUID,
        entityId: UUID,
    ): ReleaseNodeIdentity {
        val (record, draft) = sets.releaseTarget(workspaceId, entityId)
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
        val draft =
            sets.prepareRelease(workspaceId, id, node.identity.version, context.expectedHash)
                ?: throw if (root) sets.staleRelease(workspaceId, id) else ArtifactReleaseRoot.conflict(rootFamily, node)
        return Prepared(node, context.expectedHash, context.actor, root, rootFamily, draft)
    }

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip {
        val set = prepared as Prepared
        val workspaceId = set.node.identity.workspaceId
        val id = set.draft.record.id
        val flipped =
            sets.commitRelease(workspaceId, id, set.expectedHash, set.actor)
                ?: throw if (set.root) sets.staleRelease(workspaceId, id) else ArtifactReleaseRoot.conflict(set.rootFamily, set.node)
        return ReleaseFlip(set.node, flipped)
    }

    /** The set's own `parameter_set.version.released` row (#332's shape): `templates_released` = the flipped templates. */
    override fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
        dependencies: List<ReleasedDependency>,
    ): Pair<String, Map<String, Any?>> {
        val released = flip.result as ParameterSetVersion
        return LifecycleVerbs.PARAMETER_SET_EVENTS.versionReleased to
            LifecycleVerbs.familyReleaseDetails(
                principal,
                LifecycleVerbs.FamilyIdentity("parameter_set_id", "parameter_set_name", entityId, released.record.name, released.detail.version),
                "templates_released",
                ArtifactReleaseRoot.templates(dependencies).map { mapOf("template_id" to it.name, "version" to it.version) },
            )
    }

    /**
     * The alias path's refusal in today's keys (#873 §C.5; `ParameterSetService.draftPinsToRelease`): the first blocker at
     * the top level as `template_id` / `template_version` / `template_status`, and `pins_not_released` naming every direct
     * template pin that is not RELEASED (a consented DRAFT included, as before), then every deeper blocker the planner's
     * walk found beneath a RELEASED pin.
     */
    override fun aliasRefusal(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): DatapipelinesException {
        val first = ArtifactReleaseRoot.directFirst(closure, blockers).first().first
        val listed =
            (ArtifactReleaseRoot.directPins(closure).filter { it.status != ReleaseNodeStatus.RELEASED } + blockers.map { it.first })
                .distinctBy { it.identity }
                .take(maxBlockers)
        return DatapipelinesException(
            ParameterErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            "Template '${first.name}' version ${first.identity.version} is not released; release it first, or release with the templates.",
            pin(first) + ("pins_not_released" to listed.map(::pin)),
        )
    }

    companion object {
        /**
         * The set service's planner entry (#873 §C.1): the request as a [RootReleaseRequest] with the alias flag, the
         * answer in the service's own shape — the released version and the templates this release flipped. [coordinator]
         * is read per call, so the service and the coordinator that holds this participant are built without a cycle.
         */
        fun coordination(coordinator: () -> ReleaseCoordinator): ParameterSetReleaseCoordination =
            ParameterSetReleaseCoordination { request ->
                val released =
                    coordinator().releaseRoot(
                        RootReleaseRequest(
                            ReleaseFamily.PARAMETER_SET,
                            request.workspaceId,
                            request.id,
                            request.expectedHash,
                            request.actor,
                            request.plan,
                        ),
                        releasePinned = request.releasePinnedTemplates,
                    )
                ParameterSetReleased(
                    released.root.result as ParameterSetVersion,
                    ArtifactReleaseRoot.templates(released.dependenciesReleased).map { TemplateRef(it.name, it.version) },
                )
            }
    }

    private fun pin(node: ClosureNode): Map<String, Any?> =
        mapOf("template_id" to node.name, "template_version" to node.identity.version, "template_status" to node.status.name)
}
