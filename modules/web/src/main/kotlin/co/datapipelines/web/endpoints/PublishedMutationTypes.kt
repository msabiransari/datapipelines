package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.body.StructuredBody
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream
import java.util.UUID

/**
 * The raw mutation inputs of one published request (#1039), handed over by the controller untouched: the
 * `Idempotency-Key`, the body's `Content-Type`/`Content-Encoding` and its unread entity. Nothing here is logged,
 * audited or echoed.
 */
class MutationInput(
    val idempotencyKey: String?,
    val contentType: String?,
    val contentEncoding: String?,
    val entity: InputStream,
    /** The request's declared `Content-Length`: `0` = nothing was sent, `-1` = unknown (chunked or absent). */
    val declaredLength: Long = UNKNOWN_LENGTH,
) {
    companion object {
        /** `Content-Length` was not declared. */
        const val UNKNOWN_LENGTH = -1L

        /** No key, no body — what a caller that supplies no inputs (a unit test of the gate) presents. */
        fun none(): MutationInput = MutationInput(null, null, null, InputStream.nullInputStream(), declaredLength = 0L)
    }
}

/** One authorised mutation call: the visible route entity for [method], and everything the request presented. */
class MutationCall(
    val endpoint: PublishedEndpoint,
    val method: EndpointMethod,
    /** The concrete business path beneath `/api`, with its leading `/`. */
    val path: String,
    val pathVariables: Map<String, String>,
    val principal: AuthenticatedPrincipal,
    val request: EndpointRequestValidator.Request,
    val input: MutationInput,
) {
    init {
        require(method.isMutation) { "a mutation call: $method" }
    }

    /** The key id of the presenting endpoint key — the serve path admits nothing else here. */
    val keyId: String get() = requireNotNull(principal.keyId) { "an API-key principal always carries its key id" }
}

/** What a route serves: its API version, the endpoint timeout, and the exact pipeline version that version pins. */
class MutationPin(
    val apiVersion: Int,
    val timeoutSeconds: Int,
    val pipelineId: UUID,
    val executable: PipelineService.ExecutablePipeline,
)

/**
 * The pins a mutation may run, resolved by the serve service under the posture's eligibility rule (D63): the route's
 * CURRENT version for a fresh request, and the reservation's ORIGINAL pin for a retry that takes over a start claim —
 * a later release never changes what an admitted request runs, nor how a retry of it is judged.
 *
 * Every lookup answers null only for a CONFIRMED absence; a metadata store that does not answer throws (Spring's
 * `DataAccessException`), so the caller can refuse with the dependency `503` instead of reading an outage as "absent".
 */
interface MutationPins {
    /** The route's current, servable pin; null when nothing servable is pointed at. */
    fun current(endpoint: PublishedEndpoint): MutationPin?

    /** The exact pin [reservation] was admitted against, to RUN it; null when it is no longer servable. */
    fun original(
        endpoint: PublishedEndpoint,
        reservation: PublishedMutationReservation,
    ): MutationPin?

    /**
     * The exact pin [reservation] was admitted against, servable or not — whose rules JUDGE a retry (result
     * negotiation, body presence, the parameters' types and defaults). A pinned version's declarations never change,
     * so a retry is compared by the rules of its first call even after a newer release; null when that version no
     * longer exists.
     */
    fun admittedUnder(
        endpoint: PublishedEndpoint,
        reservation: PublishedMutationReservation,
    ): MutationPin?
}

/** A request admitted against its pin: the validated parameters, the decoded body and the execution mode. */
class MutationAdmitted(
    val parameters: Map<String, JsonNode>,
    val ttlSeconds: Long,
    val pageRows: Int,
    val body: StructuredBody,
    val readOnly: Boolean,
)
