package co.datapipelines.web.release

import co.datapipelines.application.endpoints.EndpointReleaseSteps
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.PreparedRelease
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseFlip
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.LifecycleVerbs
import org.springframework.transaction.TransactionStatus
import java.util.UUID

/**
 * The API family as a release ROOT (#786 C.2; record §8, A42): [prepare] runs the API's native checks outside the
 * metadata transaction (authoring capability, the exact DRAFT at the consented hash, the pin with a same-release DRAFT
 * pipeline admitted), and [commitPrepared] — after the workspace guard and every dependency's flip — rechecks the pin
 * RELEASED-only and flips the API version with one CAS, moving the API's current pointer. An API has no inbound pinner,
 * so it is never a dependency; it is always the root and always flips last.
 */
class EndpointReleaseParticipant(
    private val steps: EndpointReleaseSteps,
) : ReleaseRoot {
    override val family: ReleaseFamily = ReleaseFamily.ENDPOINT

    /** A prepared API root. */
    class Prepared(
        node: ClosureNode,
        expectedHash: String,
        val native: EndpointReleaseSteps.Prepared,
    ) : PreparedRelease(node, expectedHash)

    override fun rootIdentity(
        workspaceId: UUID,
        entityId: UUID,
    ): ReleaseNodeIdentity =
        steps.rootIdentity(workspaceId, entityId)
            ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.NOT_FOUND,
                message = "No API with that id in this workspace.",
                details = mapOf("endpoint_id" to entityId.toString()),
            )

    override fun prepare(
        node: ClosureNode,
        context: ReleaseContext,
    ): Prepared = Prepared(node, context.expectedHash, steps.prepare(node.identity, node.entityUuid, context.expectedHash, context.actor))

    override fun commitPrepared(
        prepared: PreparedRelease,
        transaction: TransactionStatus,
    ): ReleaseFlip = ReleaseFlip(prepared.node, steps.commit((prepared as Prepared).native))

    override fun auditRow(
        principal: AuthenticatedPrincipal,
        entityId: UUID,
        flip: ReleaseFlip,
    ): Pair<String, Map<String, Any?>> {
        val released = flip.result as PublishedEndpointVersion
        return AUDIT_RELEASED to
            mapOf(
                "endpoint_id" to entityId.toString(),
                "path_pattern" to flip.node.identity.entityId,
                "version" to released.version,
                "pipeline" to released.body.pipelineName,
                "pipeline_version" to released.body.pipelineVersion,
                "via" to LifecycleVerbs.via(principal),
            )
    }

    companion object {
        /** The API family's release audit kind (brief A.3; `docs/enums.md`). */
        const val AUDIT_RELEASED = "endpoint.released"
    }
}
