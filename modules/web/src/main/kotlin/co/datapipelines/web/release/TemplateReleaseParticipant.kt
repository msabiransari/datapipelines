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
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.LifecycleVerbs
import co.datapipelines.web.templates.TemplateReleaseCoordination
import co.datapipelines.web.templates.TemplateReleaseService
import java.util.UUID
import org.springframework.transaction.TransactionStatus

/**
 * The template family in a coordinated release (record §4.2, §23.3; #873 §B.4) — as any root's dependency and, since
 * #873, as a ROOT: the exact version flipped at exactly the snapshot's body hash through
 * [TemplateReleaseService.releaseExact], which runs the template's native release (authoring guard, content validation,
 * the one-statement flip) in the coordinator's transaction. It replaces #142's current-hash pickup (`releasePinned`), so
 * a template edited after the preview can never be released in its place. Nothing is prepared outside: a template has no
 * gate that opens a connection.
 *
 * The import closure is the planner's, through the template provider's `IMPORT` edges: an unselected DRAFT import is a
 * `template.release.dependency_not_released` blocker, a missing or DISCARDED one a blocker that is never restored, so no
 * separate closure guard exists (the #518 branch's `requireReleasedClosure` is unnecessary). The root's stale `If-Match`
 * keeps the native `template.version.conflict`; any other drift is the root family's `plan_conflict`.
 */
class TemplateReleaseParticipant(
    private val releases: TemplateReleaseService,
) : ArtifactReleaseRoot {
    override val family: ReleaseFamily = ReleaseFamily.TEMPLATE

    /** A template's native release checked nothing beneath it; the planner's blockers come first. */
    override val dependenciesBeforePrepare: Boolean = true

    /** A prepared template node: the actor and the root family its conflict is spelled in. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val actor: UUID,
        val rootFamily: ReleaseFamily,
        val root: Boolean = false,
    ) : PreparedRelease(node, expectedHash)

    override fun rootIdentity(
        workspaceId: UUID,
        entityId: UUID,
    ): ReleaseNodeIdentity {
        val name = releases.releaseTargetName(workspaceId, entityId)
        val (_, draft) = releases.releaseTarget(workspaceId, name)
        return ReleaseNodeIdentity(workspaceId, family, name, draft.version)
    }

    override fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): Prepared = Prepared(node, context.expectedHash, context.actor, context.closure.root.family, node.identity == context.closure.root)

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip {
        val template = prepared as Prepared
        val identity = template.node.identity
        val released =
            if (template.root) {
                releases.flip(identity.workspaceId, identity.entityId, template.expectedHash, template.actor)
            } else {
                releases.releaseExact(identity.workspaceId, identity.entityId, identity.version, template.expectedHash, template.actor) {
                    ReleaseRefusals.planConflict(template.rootFamily, template.node.nodeId)
                }
            }
        return ReleaseFlip(prepared.node, released)
    }

    /** The template's own `template.version.released` row — today's REST and dialog details (`template_id`, `version`, `via`). */
    override fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
        dependencies: List<ReleasedDependency>,
    ): Pair<String, Map<String, Any?>> {
        val released = flip.result as TemplateReleaseService.Released
        return LifecycleVerbs.TEMPLATE_AUDIT_VERSION_RELEASED to
            LifecycleVerbs.templateReleaseDetails(principal, released.detail.templateId, released.detail.version)
    }

    companion object {
        /** The template service's planner entry (#873 §C.1): the request as a [RootReleaseRequest], the answer the root's own flip. */
        fun coordination(coordinator: () -> ReleaseCoordinator): TemplateReleaseCoordination =
            TemplateReleaseCoordination { request ->
                val released =
                    coordinator().releaseRoot(
                        RootReleaseRequest(
                            ReleaseFamily.TEMPLATE,
                            request.workspaceId,
                            request.entityId,
                            request.expectedHash,
                            request.actor,
                            request.plan,
                        ),
                    )
                released.root.result as TemplateReleaseService.Released
            }
    }

    /** A template had no native dependency refusal: the shared `template.release.dependency_not_released` shape (record §14). */
    override fun aliasRefusal(
        closure: ReleaseClosure,
        blockers: List<Pair<ClosureNode, BlockerReason>>,
        maxBlockers: Int,
    ): DatapipelinesException = ReleaseRefusals.dependencyNotReleased(closure, blockers, maxBlockers)
}
