package co.datapipelines.web.pipelines

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * Published endpoints inside a promotion batch (074, rest-api §19.5) — the sender's half and the
 * receiver's half in one place.
 *
 * It exists as a collaborator rather than as more constructor parameters on
 * [PromotionService] and [PromotionReceiveService] because the rules here are ONE subject: what
 * an endpoint entry means on the wire, which keys it needs, and what happens when they are
 * absent. Spreading that across the two promotion classes put three more collaborators on each
 * and left the "keys are environment-local" rule stated in two places.
 *
 * ## Keys are environment-local, so bindings travel by NAME
 *
 * `api_keys` is environment-local (metadata-db §5A): a dev key does not exist in prod, and a
 * promotion must never mint one — minting a credential as a side effect of a deploy is not a
 * thing this system does. So a binding names the key the target is expected to already have, and
 * a target missing it refuses the WHOLE batch before anything is pushed.
 */
class EndpointPromotion(
    private val publishing: EndpointPublishService,
    private val keys: EndpointKeyService,
    private val endpoints: PublishedEndpointRepository,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
    private val pipelines: PipelineRepository,
) {
    // ---------------------------------------------------------------------------------------
    // Sender
    // ---------------------------------------------------------------------------------------

    /**
     * The endpoints published over [promotedPipelineNames], as batch entries.
     *
     * Scoped to the batch's pipelines rather than the whole workspace on purpose: promoting one
     * pipeline must not drag along endpoints over pipelines the operator did not select, which
     * would push URLs whose pipelines the target does not have.
     */
    fun entriesFor(
        workspaceId: UUID,
        promotedPipelineNames: List<String>,
    ): List<PromotionWire.EndpointEntry> {
        val promotedIds = promotedPipelineNames.mapNotNull { pipelines.findByName(workspaceId, it)?.id }.toSet()
        val allBindings = bindings.findAll()
        return endpoints
            .findByWorkspace(workspaceId)
            .filter { it.pipelineId in promotedIds }
            .mapNotNull { endpoint ->
                val pipelineName = pipelines.findById(workspaceId, endpoint.pipelineId)?.name ?: return@mapNotNull null
                PromotionWire.EndpointEntry(
                    path = endpoint.pathPattern,
                    pipeline = pipelineName,
                    timeoutSeconds = endpoint.timeoutSeconds,
                    description = endpoint.description,
                    bindings =
                        allBindings
                            .filter { it.pathPrefix == endpoint.pathPattern }
                            .mapNotNull { apiKeys.findById(it.apiKeyId)?.name }
                            .distinct(),
                )
            }
    }

    // ---------------------------------------------------------------------------------------
    // Receiver
    // ---------------------------------------------------------------------------------------

    /**
     * Refuses the whole batch when it binds a key name this deployment does not have.
     *
     * Reported ONCE for the batch, naming every absent key, BEFORE anything is pushed — the same
     * shape and the same reason as `pipeline.promotion.missing_datasources` (§10.5): a mid-batch
     * failure would leave the target half-promoted, with URLs nobody can call.
     */
    fun refuseIfKeysMissing(
        entries: List<PromotionWire.EndpointEntry>,
        workspaceId: UUID,
        workspaceName: String,
    ) {
        val missing =
            entries
                .flatMap { it.bindings }
                .distinct()
                .filter { apiKeys.findByWorkspaceAndName(workspaceId, it).isEmpty() }
        if (missing.isEmpty()) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PROMOTION_KEY_MISSING,
            message =
                "This batch binds API key(s) ${missing.joinToString(", ")}, which do not exist in workspace " +
                    "'$workspaceName'. Keys are environment-local and a promotion never mints one — create " +
                    "them on this deployment first.",
            details = mapOf("missing_api_keys" to missing, "workspace" to workspaceName),
        )
    }

    /**
     * Publishes one promoted endpoint and binds its keys.
     *
     * Republishing an unchanged endpoint is a no-op rather than a conflict, so a re-push is
     * idempotent like every other entry in the batch.
     */
    fun apply(
        entry: PromotionWire.EndpointEntry,
        promoter: AuthenticatedPrincipal,
    ) {
        if (publishing.get(promoter, entry.path) == null) {
            publishing.publish(
                principal = promoter,
                pathPattern = entry.path,
                pipelineName = entry.pipeline,
                timeoutSeconds = entry.timeoutSeconds,
                description = entry.description,
            )
        }
        val workspaceId = promoter.requireWorkspace().id
        entry.bindings.forEach { keyName ->
            // Proven to exist by refuseIfKeysMissing. An ambiguous name — several keys of that
            // name in one workspace, which the schema permits — binds ALL of them: the batch
            // asked for "the key called X", and picking one would be a guess about whose
            // credential to widen.
            apiKeys.findByWorkspaceAndName(workspaceId, keyName).forEach { key ->
                keys.bind(promoter, key.id, entry.path)
            }
        }
    }
}
