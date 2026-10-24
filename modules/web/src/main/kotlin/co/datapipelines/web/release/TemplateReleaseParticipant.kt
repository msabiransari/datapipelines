package co.datapipelines.web.release

import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseParticipant
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.web.templates.TemplateReleaseService
import org.springframework.transaction.TransactionStatus
import java.util.UUID

/**
 * The template family's release inside a cascade — MINIMAL in S1 (record §4.2; brief A.4): the exact version flipped at
 * exactly the snapshot's body hash through [TemplateReleaseService.releaseExact], which runs the template's native
 * release (authoring guard, content validation, the one-statement flip) in the coordinator's transaction. It replaces
 * #142's current-hash pickup (`releasePinned`), so a template edited after the preview can never be released in its
 * place. Nothing is prepared outside: a template has no gate that opens a connection.
 *
 * S2 (#518) owns this file's extension: the template's import closure and its participation as a ROOT.
 */
class TemplateReleaseParticipant(
    private val releases: TemplateReleaseService,
) : ReleaseParticipant {
    override val family: ReleaseFamily = ReleaseFamily.TEMPLATE

    /** A prepared template node: the actor and the root family its conflict is spelled in. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val actor: UUID,
        val rootFamily: ReleaseFamily,
    ) : PreparedRelease(node, expectedHash)

    override fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): Prepared = Prepared(node, context.expectedHash, context.actor, context.closure.root.family)

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip {
        val template = prepared as Prepared
        val identity = template.node.identity
        val released =
            releases.releaseExact(identity.workspaceId, identity.entityId, identity.version, template.expectedHash, template.actor) {
                ReleaseRefusals.planConflict(template.rootFamily, template.node.nodeId)
            }
        return ReleaseFlip(prepared.node, released)
    }
}
