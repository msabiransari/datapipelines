package co.datapipelines.web.release

import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseParticipant
import co.datapipelines.pipeline.release.ReleaseRefusals
import org.springframework.transaction.TransactionStatus

/**
 * The pipeline family's release inside a cascade (record §4.2), over the native [PipelineReleaseService] split at the
 * transaction boundary:
 *
 * - prepare, OUTSIDE: the authoring guard, the draft read, §12 validation with DRAFT child pipelines admitted (they flip
 *   first, in the same transaction) and the 140 release-check gate with the node's OWN override reason — the probes open
 *   customer connections, which is why this half can never run inside the metadata transaction.
 * - commit, INSIDE: strict §12 validation again, now that the dependencies read RELEASED, then the one-statement flip at
 *   the consented hash (the root's `If-Match`, a child's snapshot hash).
 *
 * A prepared draft that is no longer the snapshot's version is a changed snapshot, refused as the ROOT's `plan_conflict`.
 */
class PipelineReleaseParticipant(
    private val releases: PipelineReleaseService,
    private val pipelines: PipelineRepository,
) : ReleaseParticipant {
    override val family: ReleaseFamily = ReleaseFamily.PIPELINE

    /** A prepared pipeline node: the native preparation the commit flips. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val actor: java.util.UUID,
        val native: PipelineReleaseService.Prepared,
    ) : PreparedRelease(node, expectedHash)

    override fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): Prepared {
        val identity = node.identity
        val rootFamily = context.closure.root.family
        val record =
            pipelines.findByNameAnyStatus(identity.workspaceId, identity.entityId)
                ?: throw ReleaseRefusals.planConflict(rootFamily, node.nodeId)
        val native =
            releases.prepare(identity.workspaceId, record.id, context.actor, context.checkOverrideReason, allowDraftChildren = true)
        if (native.draft.version != identity.version) throw ReleaseRefusals.planConflict(rootFamily, node.nodeId)
        return Prepared(node, context.expectedHash, context.actor, native)
    }

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip {
        val pipeline = prepared as Prepared
        releases.revalidateStrict(pipeline.native)
        val flipped = releases.flip(pipeline.native, pipeline.expectedHash, pipeline.actor)
        return ReleaseFlip(prepared.node, flipped)
    }
}
