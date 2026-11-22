package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.BoundedValueCapture
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.pipelines.executedByKeyKind
import co.datapipelines.web.sse.ExecutionRecordUnwritableException
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the execution a published mutation reservation admits, and shapes its first answer (#1039;
 * published-mutation-apis §4).
 *
 * ## One start, fenced
 *
 * The execution id IS the reservation's id, and the run records fail-closed: the RUNNING row is inserted before the
 * first node, and an insert that cannot be confirmed stops the run there. The row's primary key is therefore the start
 * fence — a slow owner and a retry that took the start claim over can never both run business SQL. The run is launched
 * into the application's scope and awaited with the endpoint's timeout; the timeout cancels the WAIT, never the run,
 * and answers `202` (ruling R-EP3, as GET).
 *
 * ## Refused before it started
 *
 * A run refused before its row exists (the execution-concurrency limit, the run's own body admission, an unwritable
 * record) ran no business SQL, so its reservation is released — conditionally: only while it is still this caller's
 * claim and no execution row exists — and the refusal is the answer (`429 pipeline.execution.concurrency_limit`, …).
 * The same key may then be retried. An admitted execution keeps its reservation on failure or abort.
 *
 * ## Only confirmed state answers
 *
 * The release and the execution-record read each have three outcomes, never two: confirmed yes, confirmed no, and
 * "the store did not answer". A refused release (the claim is another retry's, or the row exists) is answered from the
 * persisted row; with no row, another retry holds the claim, which is the `202` any retry of an admitted request gets.
 * A release or a record read the store did not answer leaves no confirmed execution state, so the answer is
 * `503 idempotency.reservation_unavailable` (published-mutation-apis §4) — the reservation kept, nothing assumed
 * running, nothing claimed to be unadmitted, and the same key answers from the real state once the store is back.
 * A persisted row that DOES answer still gives its normal answer.
 */
@Suppress("LongParameterList") // one composition root for one start; every collaborator is used
class PublishedMutationLauncher(
    private val runner: RecordingExecutionRunner,
    private val reservations: PublishedMutationReservationRepository,
    private val executions: ExecutionRepository,
    private val answers: PublishedMutationAnswers,
    private val endpointsProperties: EndpointsProperties,
    private val audit: AuditEventSink,
    private val scope: CoroutineScope,
    private val meters: MeterRegistry? = null,
    /** #1043: the recorder of a captured admission; null where capture is not wired (no run is then recorded). */
    private val captures: CaptureRecorders? = null,
) {
    private val log = LoggerFactory.getLogger(PublishedMutationLauncher::class.java)

    /** What the launched coroutine reports; it never throws except to its own cancellation. */
    private sealed interface Attempt {
        data class Finished(
            val result: ExecutionResult,
        ) : Attempt

        data class Thrown(
            val error: Throwable,
            val release: Release,
        ) : Attempt
    }

    /** The conditional release's three outcomes. */
    private enum class Release {
        /** The store deleted the unstarted reservation: nothing was admitted, the key is free. */
        RELEASED,

        /** The store answered no: the claim is no longer this caller's, or an execution row exists. */
        REFUSED,

        /** The store did not answer: the reservation's state is unknown. */
        UNCONFIRMED,
    }

    /** The execution-record read: answered (the row, or null for no row), or not answered at all. */
    private sealed interface Recorded {
        data class Answered(
            val record: ExecutionRecord?,
        ) : Recorded

        data object Unreadable : Recorded
    }

    /** Launches [request] for [reservation] (whose id the request carries) and answers within [timeoutSeconds]. */
    fun start(
        endpoint: PublishedEndpoint,
        principal: AuthenticatedPrincipal,
        reservation: PublishedMutationReservation,
        request: ExecuteRequest,
        timeoutSeconds: Int,
        pageRows: Int,
    ): Outcome {
        require(request.executionId == reservation.executionId) { "the run carries its reservation's id" }
        // The key's read ticket for a run it started, written before the answer (#192) — and the audit entry the
        // requirement names: endpoint, method, version, execution id, caller identity, outcome.
        auditMutation(audit, endpoint, principal, reservation, OUTCOME_STARTED)
        log.info(
            "event=endpoint.mutation.started endpoint_id={} method={} api_version={} execution_id={}",
            endpoint.id,
            reservation.method,
            reservation.apiVersion,
            reservation.executionId,
        )
        // #1043: the decision the reservation stored at admission, never a fresh lookup — a retry or takeover reuses it.
        val capture = captures?.of(reservation)
        val captured = if (capture == null) request else request.copy(valueCapture = capture)
        val deferred = scope.async { attempt(captured, reservation, capture) }
        val timeout = endpointsProperties.clampTimeout(timeoutSeconds)
        // Cancels the AWAIT, never the run: the Deferred belongs to `scope`. Null is the 202 branch.
        val attempt = runBlocking { withTimeoutOrNull(timeout.seconds) { deferred.await() } }
        if (attempt is Attempt.Thrown && attempt.release == Release.RELEASED && attempt.error !is ExecutionRecordUnwritableException) {
            // A catalogued refusal of a run that never started (the concurrency limit's 429, the body admission's
            // 400, …) answers exactly as the framework surfaces answer it: the global handler maps its code to the
            // catalogue's status. The reservation is already released, so the same key may be retried.
            auditMutation(audit, endpoint, principal, reservation, OUTCOME_NOT_STARTED)
            count(meters, reservation.method.name, OUTCOME_NOT_STARTED)
            throw attempt.error
        }
        val (outcome, auditOutcome) = answer(attempt, reservation, pageRows)
        auditMutation(audit, endpoint, principal, reservation, auditOutcome)
        count(meters, reservation.method.name, auditOutcome)
        return outcome
    }

    private suspend fun attempt(
        request: ExecuteRequest,
        reservation: PublishedMutationReservation,
        capture: BoundedValueCapture?,
    ): Attempt =
        try {
            Attempt.Finished(runner.run(request, reservation.workspaceId, ExecutionTrigger.ENDPOINT, failClosed = true))
        } catch (e: CancellationException) {
            throw e
        } catch (
            // The run may fail with anything its coroutine threw; the reservation decision must be made for every one.
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            val release =
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken)) {
                            Release.RELEASED
                        } else {
                            Release.REFUSED
                        }
                    } catch (
                        // Whatever the store threw, the release was not confirmed: the state is unknown, never "released".
                        @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
                    ) {
                        log.warn(
                            "event=endpoint.mutation.release_failed execution_id={} error={}",
                            reservation.executionId,
                            failure.javaClass.simpleName,
                        )
                        Release.UNCONFIRMED
                    }
                }
            Attempt.Thrown(e, release)
        } finally {
            // However the run ended — and after the caller's wait timed out too — the closing summary is written once,
            // off the business path; a run released before it started has no reservation, so the store refuses it.
            capture?.let { withContext(NonCancellable + Dispatchers.IO) { it.seal() } }
        }

    private fun answer(
        attempt: Attempt?,
        reservation: PublishedMutationReservation,
        pageRows: Int,
    ): Pair<Outcome, String> =
        when (attempt) {
            null -> {
                answers.running(reservation) to OUTCOME_ACCEPTED
            }

            is Attempt.Finished -> {
                val result = attempt.result
                if (result.status == ExecutionStatus.SUCCESS) {
                    answers.fromSuccess(reservation, result.resultUnavailable, result.nodeStats, pageRows) to OUTCOME_COMPLETED
                } else {
                    // The run finished and recorded fail-closed, so its row is the answer; a row that cannot be read
                    // (or is missing) confirms nothing, and a 202 here would assert a run that has ended.
                    val row = (record(reservation) as? Recorded.Answered)?.record
                    row?.let { answers.fromRecord(reservation, it, pageRows) to OUTCOME_FAILED }
                        ?: (reservationUnavailable() to OUTCOME_RESERVATION_UNAVAILABLE)
                }
            }

            is Attempt.Thrown -> {
                thrownAnswer(attempt.release, reservation, pageRows)
            }
        }

    /** The answer of a run that threw, by what the release CONFIRMED (see the class KDoc, "Only confirmed state"). */
    private fun thrownAnswer(
        release: Release,
        reservation: PublishedMutationReservation,
        pageRows: Int,
    ): Pair<Outcome, String> {
        if (release == Release.RELEASED) return unrecorded() to OUTCOME_NOT_STARTED
        val recorded = record(reservation)
        val row = (recorded as? Recorded.Answered)?.record
        return when {
            // The run failed after it started (or another retry recorded it): the row's terminal 409, or 202 meanwhile.
            row != null -> {
                answers.fromRecord(reservation, row, pageRows) to OUTCOME_FAILED
            }

            // No row, and the store refused the release because the claim is another retry's now: it is starting it.
            recorded is Recorded.Answered && release == Release.REFUSED -> {
                answers.running(reservation) to OUTCOME_FAILED
            }

            // No row and an unconfirmed release, or no answer at all: no execution state is confirmed.
            else -> {
                reservationUnavailable() to OUTCOME_RESERVATION_UNAVAILABLE
            }
        }
    }

    private fun record(reservation: PublishedMutationReservation): Recorded =
        try {
            Recorded.Answered(executions.findById(reservation.workspaceId, reservation.executionId))
        } catch (e: DataAccessException) {
            log.warn("event=endpoint.mutation.record_unreadable execution_id={} error={}", reservation.executionId, e.javaClass.simpleName)
            Recorded.Unreadable
        }

    /** No confirmed execution state: the reservation-store refusal of §4. The reservation is kept; retry with the key. */
    private fun reservationUnavailable(): Outcome =
        Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE,
            "The request's reservation state could not be confirmed, so nothing more was run. Retry with the same key.",
        )

    /** The fail-closed record could not be written, so the run stopped before its first node: the dependency `503`. */
    private fun unrecorded(): Outcome =
        Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE,
            "The execution could not be recorded, so it was not started. Nothing ran; retry with the same key.",
        )

    companion object {
        /** The mutation audit `outcome` vocabulary: `started`, then one terminal row; `replayed` for a retry. */
        const val OUTCOME_STARTED = "started"
        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_ACCEPTED = "accepted"
        const val OUTCOME_NOT_STARTED = "not_started"
        const val OUTCOME_REPLAYED = "replayed"
        const val OUTCOME_RESERVATION_UNAVAILABLE = "reservation_unavailable"

        /** `datapipelines.endpoints.mutations` — one count per answered mutation, tagged `method` × `outcome`. */
        const val METER = "datapipelines.endpoints.mutations"

        private const val HTTP_SERVICE_UNAVAILABLE = 503

        /** The serve audit row of a mutation — the GET row's fields plus method and API version, never a key or a body. */
        internal fun auditMutation(
            audit: AuditEventSink,
            endpoint: PublishedEndpoint,
            principal: AuthenticatedPrincipal,
            reservation: PublishedMutationReservation,
            outcome: String,
        ) {
            audit.log(
                event = EndpointServeAudit.SERVE_EVENT,
                userId = principal.userId,
                keyId = principal.keyId,
                details =
                    mapOf(
                        "endpoint_id" to endpoint.id.toString(),
                        "path_pattern" to endpoint.pathPattern,
                        "method" to reservation.method.name,
                        "api_version" to reservation.apiVersion,
                        "execution_id" to reservation.executionId.toString(),
                        "outcome" to outcome,
                    ),
            )
        }

        /** One low-cardinality count: `method` is one of three, `outcome` a closed vocabulary. */
        internal fun count(
            meters: MeterRegistry?,
            method: String,
            outcome: String,
        ) {
            meters?.counter(METER, "method", method, "outcome", outcome)?.increment()
        }

        /** The ExecuteRequest of a published mutation run; the caller supplies what the pin and the request decided. */
        internal fun requestOf(
            principal: AuthenticatedPrincipal,
            reservation: PublishedMutationReservation,
            pin: MutationPin,
            admitted: MutationAdmitted,
        ): ExecuteRequest {
            val version = pin.executable
            return ExecuteRequest(
                pipelineId = pin.pipelineId,
                pipelineVersion = version.version,
                pipeline = version.pipeline,
                lakeExecutionMetadata =
                    co.datapipelines.pipeline.LakeExecutionMetadata
                        .from(version),
                lakeAppendGrant =
                    co.datapipelines.application.LakeAppendAuthorization
                        .grant(principal, version.writesManagedLake, Instant.now()),
                userId = principal.userId,
                workspaceId = reservation.workspaceId,
                parameters = admitted.parameters,
                resultTtlSeconds = admitted.ttlSeconds,
                triggeredVia = ExecutionTrigger.ENDPOINT,
                executedByKeyKind = principal.executedByKeyKind(),
                executionId = reservation.executionId,
                // A mutation route may invoke a read-only pipeline: it keeps read-only execution mode (§3.5).
                readOnly = admitted.readOnly,
                requestBody = admitted.body,
                boundedDiagnosticsRequired = true,
                // #1043: a published write's computed node values never reach ordinary history, polling or events;
                // only an administrator's capture keeps them, in its own private store.
                privateNodeValues = true,
                // The durable reservation is the replay mechanism here; the framework's Redis store is never consulted.
                idempotencyKey = null,
            )
        }
    }
}
