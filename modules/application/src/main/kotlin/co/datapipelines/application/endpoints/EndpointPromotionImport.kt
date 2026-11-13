package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import java.util.UUID

/**
 * The receiver-only import of a promoted API (brief §F.2; dependency-graph record Draft 10 erratum (3)) — the ONE path
 * that writes an API version without the authoring capability. A promotion receiver is authoring-DISABLED by
 * construction, so the native creation (an authoring DRAFT save) is not what it calls; this operation shares the native
 * validation (path grammar, the pin's checks) and the persistence (entity + v1 + `current_version = 1` + the shared
 * hash, in the receiver's one transaction) and skips the capability check by design.
 *
 * The pin is the TARGET pipeline's CURRENT at receive time, and v1's status follows the in-place conversion's rule:
 * RELEASED when that pipeline version is RELEASED, DRAFT otherwise — so "promote a pipeline with its APIs" yields what it
 * yielded before #786: the API serves at the target iff it would have served before. No CURRENT refuses the batch with
 * the shape the receiver always answered for an unservable pipeline (`endpoint.pipeline_not_released`), never an
 * invented pin. Audit is the promotion's own, never a user authoring event.
 *
 * Reachability: no route, no MCP tool and no bean. The assembling configuration constructs it inside the
 * `EndpointPromotion` bean method and hands it to that one collaborator (a test and the handback's caller grep prove it).
 * The exact-pin promotion (version and hash on the wire) is follow-up #879.
 */
class EndpointPromotionImport(
    endpoints: PublishedEndpointRepository,
    versions: PublishedEndpointVersionRepository,
    private val pipelines: PipelineRepository,
    private val pins: EndpointPinValidator,
    registry: EndpointRegistry,
    private val timeouts: EndpointPublishService.TimeoutBounds,
    writeGuard: MetadataWriterGuard = MetadataWriterGuard.NONE,
) {
    /** No transaction of its own: the receiver's one transaction holds every write (`PromotionReceiveService`). */
    private val writes = EndpointWrites(endpoints, versions, registry, transactions = null, writeGuard = writeGuard)

    /** Creates the absent API at [pathPattern] over [pipelineName] in [workspaceId]; the entity written. */
    @Suppress("LongParameterList") // the wire entry's fields and the receiving identity
    fun importPromoted(
        workspaceId: UUID,
        actor: UUID,
        pathPattern: String,
        pipelineName: String,
        timeoutSeconds: Int?,
        description: String,
    ): PublishedEndpoint {
        val parsed = EndpointWrites.parsePath(pathPattern)
        writes.writeGuard.workspaces(listOf(workspaceId), PipelineErrorCodes.Endpoint.WRITE_BUSY)
        val record =
            pipelines.findByName(workspaceId, pipelineName)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "No pipeline named '$pipelineName' in this workspace.",
                    details = mapOf("pipeline" to pipelineName),
                )
        val current = record.currentVersion ?: throw noCurrent(pipelineName)
        val body = EndpointWrites.bodyOf(pipelineName, current, timeouts.clamp(timeoutSeconds), description)
        val resolved = pins.check(workspaceId, parsed, body, EndpointPinValidator.Admission.LIVE)
        val status = if (resolved.status == PipelineVersionStatus.RELEASED) PipelineVersionStatus.RELEASED else PipelineVersionStatus.DRAFT
        return writes.createWithFirstVersion(workspaceId, parsed, record.id, body, status, actor)
    }

    private fun noCurrent(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
            message = "'$name' has no current version on this deployment, so the promoted API has nothing to pin.",
            details = mapOf("pipeline" to name),
        )
}
