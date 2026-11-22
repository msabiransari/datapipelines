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
 * For a legacy GET entry, the pin is the TARGET pipeline's CURRENT at receive time and v1 follows the conversion rule:
 * RELEASED when that pipeline version is RELEASED, DRAFT otherwise — so "promote a pipeline with its APIs" yields what it
 * yielded before #786: the API serves at the target iff it would have served before. No CURRENT refuses the batch with
 * the shape the receiver always answered for an unservable pipeline (`endpoint.pipeline_not_released`), never an
 * invented pin. Audit is the promotion's own, never a user authoring event.
 *
 * Reachability: no route, no MCP tool and no bean. The assembling configuration constructs it inside the
 * `EndpointPromotion` bean method and hands it to that one collaborator (a test and the handback's caller grep prove it).
 * The exact-pin promotion of a GET route (version and hash on the wire) is follow-up #879; a method-bearing route
 * (POST, PUT, DELETE) travels exactly pinned since #1040 ([importExact]) — published-mutation-apis §7 admits no other
 * transfer for it.
 */
class EndpointPromotionImport(
    private val endpoints: PublishedEndpointRepository,
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
        // This is the legacy GET import: an absent method means GET. Mutation entries go through importExact.
        val resolved = pins.check(workspaceId, parsed, body, EndpointPinValidator.Admission.LIVE, EndpointMethod.GET)
        val status = if (resolved.status == PipelineVersionStatus.RELEASED) PipelineVersionStatus.RELEASED else PipelineVersionStatus.DRAFT
        return writes.createWithFirstVersion(workspaceId, parsed, EndpointMethod.GET, record.id, body, status, actor)
    }

    /** What [importExact] did to the route. */
    enum class ExactOutcome {
        /** The route was absent: created with v1 RELEASED on the exact pin. */
        CREATED,

        /** The route's current version already pins the same pipeline version: nothing written. */
        UNCHANGED,

        /** The route existed on another pin: a new RELEASED version on the exact pin became current. */
        NEW_VERSION,
    }

    /**
     * The EXACT-pin import of one method-bearing route (#1040; published-mutation-apis §7): the route at
     * ([pathPattern], [method]) pins [pipelineName]@[pipelineVersion] — the sender's pin, never this deployment's current
     * — and that version must be here, RELEASED, with the sender's [pipelineBodyHash]. Anything else refuses the whole
     * batch: a pin that cannot be honoured exactly is never reinterpreted as another version (§7: "refuse before
     * applying a batch that could be … pinned to a different pipeline").
     *
     * Version numbers are this deployment's own (an API version number is local; the pin is the portable identity), so a
     * route whose current version already pins the same exact pipeline version is left as it is, and one on another pin
     * gains a new RELEASED version that becomes current. Runs in the receiver's one transaction: a later refusal rolls it
     * back with the batch. The native pin checks (path variables, the body deliverable under [method], the RELEASED
     * status) are [EndpointPinValidator.check]'s, so this path stores nothing another writer would refuse.
     */
    @Suppress("LongParameterList") // the wire entry's exact-pin fields and the receiving identity
    fun importExact(
        workspaceId: UUID,
        actor: UUID,
        pathPattern: String,
        method: EndpointMethod,
        pin: ExactPin,
    ): ExactOutcome {
        val parsed = EndpointWrites.parsePath(pathPattern)
        writes.writeGuard.workspaces(listOf(workspaceId), PipelineErrorCodes.Endpoint.WRITE_BUSY)
        val record =
            pipelines.findByName(workspaceId, pin.pipelineName)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "No pipeline named '${pin.pipelineName}' in this workspace.",
                    details = mapOf("pipeline" to pin.pipelineName),
                )
        val detail = pipelines.findVersionDetail(workspaceId, record.id, pin.pipelineVersion)
        if (detail != null && detail.bodyHash != pin.pipelineBodyHash) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Import.VERSION_CONFLICT,
                message =
                    "'${pin.pipelineName}' version ${pin.pipelineVersion} exists here with different content, so the " +
                        "${method.name} route at '${parsed.pattern}' cannot pin it exactly. Nothing was imported.",
                details = mapOf("pipeline" to pin.pipelineName, "pipeline_version" to pin.pipelineVersion, "method" to method.name),
            )
        }
        val body = EndpointWrites.bodyOf(pin.pipelineName, pin.pipelineVersion, timeouts.clamp(pin.timeoutSeconds), pin.description)
        pins.check(workspaceId, parsed, body, EndpointPinValidator.Admission.RELEASED_ONLY, method)
        val existing = endpoints.findByPath(parsed.pattern, method)?.takeIf { it.workspaceId == workspaceId }
        return when {
            existing == null -> {
                writes.createWithFirstVersion(workspaceId, parsed, method, record.id, body, PipelineVersionStatus.RELEASED, actor)
                ExactOutcome.CREATED
            }

            existing.current?.body?.let { it.pipelineName == body.pipelineName && it.pipelineVersion == body.pipelineVersion } == true -> {
                ExactOutcome.UNCHANGED
            }

            else -> {
                writes.appendReleasedCurrent(existing.id, body, actor)
                ExactOutcome.NEW_VERSION
            }
        }
    }

    /** The exact pin a method-bearing promotion entry carries (#1040). */
    data class ExactPin(
        val pipelineName: String,
        val pipelineVersion: Int,
        val pipelineBodyHash: String,
        val timeoutSeconds: Int?,
        val description: String,
    )

    private fun noCurrent(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
            message = "'$name' has no current version on this deployment, so the promoted API has nothing to pin.",
            details = mapOf("pipeline" to name),
        )
}
