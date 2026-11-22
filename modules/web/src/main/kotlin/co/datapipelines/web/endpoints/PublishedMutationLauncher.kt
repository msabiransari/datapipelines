package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointServeAudit
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
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
) {
    private val log = LoggerFactory.getLogger(PublishedMutationLauncher::class.java)

    /** What the launched coroutine reports; it never throws except to its own cancellation. */
    private sealed interface Attempt {
        data class Finished(
            val result: ExecutionResult,
        ) : Attempt

        data class Thrown(
            val error: Throwable,
            val released: Boolean,
        ) : Attempt
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
        val deferred = scope.async { attempt(request, reservation) }
        val timeout = endpointsProperties.clampTimeout(timeoutSeconds)
        // Cancels the AWAIT, never the run: the Deferred belongs to `scope`. Null is the 202 branch.
        val attempt = runBlocking { withTimeoutOrNull(timeout.seconds) { deferred.await() } }
        if (attempt is Attempt.Thrown && attempt.released && attempt.error !is ExecutionRecordUnwritableException) {
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
    ): Attempt =
        try {
            Attempt.Finished(runner.run(request, reservation.workspaceId, ExecutionTrigger.ENDPOINT, failClosed = true))
        } catch (e: CancellationException) {
            throw e
        } catch (
            // The run may fail with anything its coroutine threw; the reservation decision must be made for every one.
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            val released =
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) }
                        .onFailure { log.warn("event=endpoint.mutation.release_failed execution_id={}", reservation.executionId, it) }
                        .getOrDefault(false)
                }
            Attempt.Thrown(e, released)
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
                    answers.fromRecord(reservation, record(reservation), pageRows) to OUTCOME_FAILED
                }
            }

            is Attempt.Thrown -> {
                if (attempt.released) {
                    unrecorded() to OUTCOME_NOT_STARTED
                } else {
                    // The row exists (the run failed after it started) or the release could not be confirmed: the
                    // persisted state is the answer — a terminal row's 409, or 202 while it is not yet terminal.
                    answers.fromRecord(reservation, record(reservation), pageRows) to OUTCOME_FAILED
                }
            }
        }

    private fun record(reservation: PublishedMutationReservation): ExecutionRecord? =
        try {
            executions.findById(reservation.workspaceId, reservation.executionId)
        } catch (e: DataAccessException) {
            log.warn("event=endpoint.mutation.record_unreadable execution_id={}", reservation.executionId, e)
            null
        }

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
                // The durable reservation is the replay mechanism here; the framework's Redis store is never consulted.
                idempotencyKey = null,
            )
        }
    }
}
