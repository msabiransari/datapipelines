package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.PublishedMutationDigests
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.CallerNodeResolver
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Admission, replay and start-claim recovery of one published POST, PUT or DELETE (#1039; published-mutation-apis §4,
 * §5, §7). Reached only AFTER the serve service's session refusal and its visibility rule chose the route entity
 * (404/405 already answered), so nothing below is disclosed to a caller who may not call this method here.
 *
 * ## The order
 *
 * 1. **The key and the body's envelope.** A missing or malformed `Idempotency-Key` is a `400` entry; the body is read
 *    once through the bounded codec (`413`/`415` answer at once; a malformed body is a `400` entry merged with the
 *    key's). Nothing has been looked up yet.
 * 2. **The reservation, before any newer current version.** The scope digest finds a live reservation: a different
 *    fingerprint is `409 idempotency.key_reused_for_different_request` (no id, no data); the same is answered from the
 *    ORIGINAL execution by the §4 table — while the mutations flag is off too, and whatever the route's current pin
 *    is now. A reservation past its window is retired and the request is fresh.
 * 3. **A fresh request.** The mutations flag (`503 endpoint.mutations_disabled`), the route's current pin
 *    (`503 endpoint.pipeline_not_released`), the pin's body rules and parameter validation (`406`/`400`), then the
 *    durable reservation — written before the run can start, so before any business SQL. A reservation that cannot be
 *    read or written is `503 idempotency.reservation_unavailable`, and nothing runs.
 * 4. **The run** ([PublishedMutationLauncher]) under the reservation's id.
 *
 * A retry that finds the reservation with no execution row after the start window takes the start claim over (a
 * compare-and-set on the owner token) and runs the ORIGINAL pin; the execution row's primary key fences it against a
 * slow original owner.
 */
@Suppress("LongParameterList") // one composition root for one admission; every collaborator is used
class PublishedMutationService(
    private val reservations: PublishedMutationReservationRepository,
    private val digests: PublishedMutationDigests,
    private val executions: ExecutionRepository,
    private val codec: PublishedRequestBodyCodec,
    private val answers: PublishedMutationAnswers,
    private val launcher: PublishedMutationLauncher,
    private val readOnlyRule: ReadOnlyPipelineRule,
    private val endpointsProperties: EndpointsProperties,
    private val requests: MutationRequestRules,
    private val windows: Windows,
    private val audit: AuditEventSink,
    private val meters: MeterRegistry? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(PublishedMutationService::class.java)

    /** The two durations: the replay window (`datapipelines.idempotency.ttl-seconds`) and the start window. */
    data class Windows(
        val replay: Duration,
        val start: Duration,
    )

    /** Answers [call] (see the class KDoc for the order); blocking, bounded by the route's timeout. */
    @Suppress("ReturnCount") // the order IS the refusal order; each stage answers on its own terms
    fun serve(
        call: MutationCall,
        pins: MutationPins,
    ): Outcome {
        val envelope = requests.envelope(call, codec)
        if (envelope is MutationRequestRules.Envelope.Refused) return counted(call, envelope.outcome, OUTCOME_REFUSED)
        val body = (envelope as MutationRequestRules.Envelope.Read).body
        val clientKey = requireNotNull(call.input.idempotencyKey) { "the envelope admitted a key" }

        val scope = digests.scope(call.endpoint.workspaceId, call.keyId, call.endpoint.id, call.method, call.path, clientKey)
        val fingerprint = digests.fingerprint(call.method, call.path, call.request.queryParameters, body)
        val now = clock.instant()

        val existing = durable { reservations.findLive(scope) } ?: return reservationUnavailable(call)
        existing.value?.let { live ->
            if (!live.expiredAt(now)) return replay(call, pins, live, fingerprint, body, now)
            durable { reservations.retire(live.executionId, scope, now) } ?: return reservationUnavailable(call)
        }
        return admitFresh(call, pins, scope, fingerprint, body, now)
    }

    @Suppress("ReturnCount", "LongParameterList") // each stage refuses on its own terms
    private fun admitFresh(
        call: MutationCall,
        pins: MutationPins,
        scope: String,
        fingerprint: String,
        body: StructuredBody,
        now: Instant,
    ): Outcome {
        // §7 — off withholds a FRESH request only, before any durable work; replay and polling stay available.
        if (!endpointsProperties.mutationsEnabled) return counted(call, requests.disabled(call), OUTCOME_DISABLED)
        val pin = dependency { pins.current(call.endpoint) } ?: return dependencyUnavailable(call)
        val current = pin.value ?: return counted(call, requests.notReleased(call.endpoint), OUTCOME_REFUSED)
        val admitted =
            when (val checked = admit(call, current, body)) {
                is Admission.Refused -> return counted(call, checked.outcome, OUTCOME_REFUSED)
                is Admission.Admitted -> checked.admitted
            }
        val reservation =
            PublishedMutationReservation(
                executionId = UUID.randomUUID(),
                workspaceId = call.endpoint.workspaceId,
                apiKeyId = call.keyId,
                endpointId = call.endpoint.id,
                apiVersion = current.apiVersion,
                pipelineId = current.pipelineId,
                pipelineVersion = current.executable.version,
                hasCaller = CallerNodeResolver.hasCallerNode(current.executable.pipeline),
                method = call.method,
                businessPath = call.path,
                scopeDigest = scope,
                requestFingerprint = fingerprint,
                bodyBytes =
                    body
                        .canonical()
                        ?.toByteArray(Charsets.UTF_8)
                        ?.size
                        ?.toLong(),
                ownerToken = UUID.randomUUID(),
                createdAt = now,
                expiresAt = now.plus(windows.replay),
            )
        val won = durable { reservations.reserve(reservation) } ?: return reservationUnavailable(call)
        if (won.value != true) {
            // Another request — on this replica or another — reserved the scope first: one execution per scope.
            val winner = durable { reservations.findLive(scope) }?.value ?: return reservationUnavailable(call)
            return replay(call, pins, winner, fingerprint, body, now)
        }
        val request = PublishedMutationLauncher.requestOf(call.principal, reservation, current, admitted)
        return launcher.start(call.endpoint, call.principal, reservation, request, current.timeoutSeconds, admitted.pageRows)
    }

    @Suppress("ReturnCount", "LongParameterList") // each stage answers on its own terms
    private fun replay(
        call: MutationCall,
        pins: MutationPins,
        reservation: PublishedMutationReservation,
        fingerprint: String,
        body: StructuredBody,
        now: Instant,
    ): Outcome {
        if (reservation.requestFingerprint != fingerprint) {
            // A key collision never returns another request's execution: no id, no status, no data.
            return counted(
                call,
                Outcome.Refused(
                    HTTP_CONFLICT,
                    PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request. Use a new key for a new request.",
                ),
                OUTCOME_KEY_REUSED,
            )
        }
        val record =
            durable { executions.findById(reservation.workspaceId, reservation.executionId) }
                ?: return reservationUnavailable(call)
        if (record.value == null && !now.isBefore(reservation.createdAt.plus(windows.start))) {
            return takeOver(call, pins, reservation, body)
        }
        val replayed = PublishedMutationLauncher.OUTCOME_REPLAYED
        PublishedMutationLauncher.auditMutation(audit, call.endpoint, call.principal, reservation, replayed)
        return counted(call, answers.fromRecord(reservation, record.value, requests.pageRows(call)), replayed)
    }

    /**
     * The admitting replica never recorded the run within the start window (it died, or is stuck before the start).
     * Exactly one retry wins the compare-and-set and starts the ORIGINAL pin under the original id; every other
     * contender answers `202`. The execution row's primary key keeps a late original owner from also running.
     */
    @Suppress("ReturnCount") // each stage answers on its own terms
    private fun takeOver(
        call: MutationCall,
        pins: MutationPins,
        reservation: PublishedMutationReservation,
        body: StructuredBody,
    ): Outcome {
        val claimed = reservation.copy(ownerToken = UUID.randomUUID())
        val won =
            durable { reservations.takeOver(reservation.executionId, reservation.ownerToken, claimed.ownerToken) }
                ?: return reservationUnavailable(call)
        // Lost: another retry claimed it and is starting it — the same 202 any replay of a running request gets.
        if (!won.value) return counted(call, answers.running(reservation), PublishedMutationLauncher.OUTCOME_REPLAYED)
        log.warn(
            "event=endpoint.mutation.start_taken_over endpoint_id={} method={} execution_id={}",
            call.endpoint.id,
            call.method,
            reservation.executionId,
        )
        val pin = dependency { pins.original(call.endpoint, reservation) }?.value
        val admitted = pin?.let { admit(call, it, body) }
        if (pin == null || admitted !is Admission.Admitted) {
            // Nothing can start: release the claim so the key is not stuck, and say why.
            durable { reservations.releaseUnstarted(claimed.executionId, claimed.ownerToken) }
            val refusal = (admitted as? Admission.Refused)?.outcome ?: requests.notReleased(call.endpoint)
            return counted(call, refusal, OUTCOME_REFUSED)
        }
        val request = PublishedMutationLauncher.requestOf(call.principal, claimed, pin, admitted.admitted)
        return launcher.start(call.endpoint, call.principal, claimed, request, pin.timeoutSeconds, admitted.admitted.pageRows)
    }

    private sealed interface Admission {
        class Admitted(
            val admitted: MutationAdmitted,
        ) : Admission

        class Refused(
            val outcome: Outcome,
        ) : Admission
    }

    /** The pin's own rules for this request: body required/accepted, parameters, `Accept`; then the execution mode. */
    private fun admit(
        call: MutationCall,
        pin: MutationPin,
        body: StructuredBody,
    ): Admission {
        val checked = requests.validate(call, pin, body)
        if (checked is MutationRequestRules.Validated.Refused) return Admission.Refused(checked.outcome)
        val valid = (checked as MutationRequestRules.Validated.Valid).valid
        val readOnly =
            dependency { readOnlyRule.check(pin.executable.pipeline, call.endpoint.workspaceId).isValid }?.value
                ?: return Admission.Refused(dependencyOutcome())
        return Admission.Admitted(MutationAdmitted(valid.parameters, valid.ttlSeconds, valid.pageRows, body, readOnly))
    }

    /** A read or write of the reservation store; null when the store did not answer (the caller refuses with 503). */
    private fun <T> durable(block: () -> T): Held<T>? =
        try {
            Held(block())
        } catch (e: DataAccessException) {
            log.warn("event=endpoint.mutation.reservation_unavailable error={}", e.javaClass.simpleName)
            null
        }

    /** A metadata read before admission; null when the store did not answer. */
    private fun <T> dependency(block: () -> T): Held<T>? =
        try {
            Held(block())
        } catch (e: DataAccessException) {
            log.warn("event=endpoint.mutation.dependency_unavailable error={}", e.javaClass.simpleName)
            null
        }

    /** A value that may itself be null, so "the store answered null" and "the store did not answer" stay distinct. */
    private class Held<T>(
        val value: T,
    )

    private fun reservationUnavailable(call: MutationCall): Outcome =
        counted(
            call,
            Outcome.Refused(
                HTTP_SERVICE_UNAVAILABLE,
                PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE,
                "The request's reservation could not be read or recorded, so nothing was run. Retry with the same key.",
            ),
            OUTCOME_RESERVATION_UNAVAILABLE,
        )

    private fun dependencyUnavailable(call: MutationCall): Outcome = counted(call, dependencyOutcome(), OUTCOME_REFUSED)

    private fun dependencyOutcome(): Outcome =
        Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE,
            "A service this route depends on is unavailable, so nothing was run. Retry with the same key.",
        )

    private fun counted(
        call: MutationCall,
        outcome: Outcome,
        meterOutcome: String,
    ): Outcome {
        PublishedMutationLauncher.count(meters, call.method.name, meterOutcome)
        return outcome
    }

    private companion object {
        const val OUTCOME_REFUSED = "refused"
        const val OUTCOME_DISABLED = "disabled"
        const val OUTCOME_KEY_REUSED = "key_reused"
        const val OUTCOME_RESERVATION_UNAVAILABLE = "reservation_unavailable"

        const val HTTP_CONFLICT = 409
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
