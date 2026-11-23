package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointGrants
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.web.api.ApiErrors
import java.util.UUID

/**
 * The polling rule of a run a published mutation started (#1039; published-mutation-apis §2, §6): it is readable ONLY
 * through the grant that initiated it — the same key, under the same concrete business path, while that path still
 * resolves to the same route entity for the same method in a live workspace and the key still holds a grant that
 * admits that entity and method NOW: its exact route grant, or (#1111) its namespace-writes opt-in at the deciding
 * node while the deployment's authoring capability is on — the same [EndpointGrants] decision the serve path's
 * visibility applies. Everything else is `404 result.execution_not_found`, the answer an unknown id gets. A route
 * deleted and recreated inside a namespace is callable as a new route, but its id differs from the origin's, so the
 * old runs stay unreadable.
 *
 * Which branch applies is decided by [originOf]: a reservation row for the id in the key's workspace, started by THIS
 * key. A run another key started is not this key's origin, so the caller falls back to the framework rule, whose
 * ownership check (`executed_by`) refuses it exactly as it refuses an id that does not exist — no answer distinguishes
 * "someone's mutation run" from "nothing". A GET grant at another path can therefore never read a mutation run.
 */
class PublishedMutationOrigins(
    private val origins: PublishedMutationReservationRepository,
    private val registry: EndpointRegistry,
    /** #1111 — the serve path's own grant decision, so polling can never be wider or narrower than calling. */
    private val grants: EndpointGrants,
    private val workspaceLiveness: WorkspaceLiveness,
    private val urls: PublishedMutationUrls,
) {
    /** The reservation of [executionId] when THIS key's mutation started it; null sends the caller down the GET rule. */
    fun originOf(
        executionId: UUID,
        principal: AuthenticatedPrincipal,
    ): PublishedMutationReservation? {
        val keyId = principal.keyId ?: return null
        return origins.findOrigin(executionId, principal.requireWorkspace().id)?.takeIf { it.apiKeyId == keyId }
    }

    /** Throws the not-found answer unless [businessPath] is still [origin]'s initiating grant (see the class KDoc). */
    fun requireInitiatingGrant(
        origin: PublishedMutationReservation,
        businessPath: String,
        principal: AuthenticatedPrincipal,
    ) {
        requireNotNull(principal.keyId) { "an origin is only ever found for a key" }
        val granted =
            origin.businessPath == businessPath &&
                workspaceLiveness.isActive(origin.workspaceId) &&
                registry
                    .matcher()
                    .match(businessPath, origin.method)
                    ?.endpoint
                    ?.id == origin.endpointId &&
                grants.at(businessPath, principal, origin.workspaceId).mayMutate(origin.endpointId, origin.method)
        if (!granted) throw ApiErrors.executionNotFound(origin.executionId.toString())
    }

    /**
     * The status projection a mutation's caller sees: the framework's metadata less what a mutation never relays —
     * no `parameters`, a failure as its bounded code only, node stats as ids, statuses and counts — and its URLs under
     * the business path.
     */
    fun statusOf(
        origin: PublishedMutationReservation,
        record: ExecutionRecord?,
        projection: Map<String, Any?>?,
    ): Map<String, Any?> {
        val id = origin.executionId
        val base =
            mapOf(
                "execution_id" to id.toString(),
                "status" to (record?.status ?: ExecutionStatus.RUNNING).name,
                "status_url" to urls.statusUrl(origin.businessPath, id),
                "result_url" to urls.resultUrl(origin.businessPath, id),
            )
        if (record == null || projection == null) return base
        val kept = projection - DROPPED
        return kept + base +
            mapOf(
                "node_stats" to PublishedMutationAnswers.nodeSummary(record.nodeStatsJson),
                "error" to
                    record.errorJson?.let { mapOf("code" to PublishedMutationAnswers.codeOf(it)) },
            )
    }

    private companion object {
        /** Projection keys a mutation status never carries (or carries re-derived, above). */
        val DROPPED = setOf("parameters", "error", "node_stats", "result_url")
    }
}
