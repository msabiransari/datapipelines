package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.CaptureSession
import co.datapipelines.application.endpoints.CaptureStart
import co.datapipelines.application.endpoints.CaptureStop
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.ExecutionCaptureSessionRepository
import co.datapipelines.application.endpoints.ExecutionCaptureStore
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.application.endpoints.StoredCaptureRun
import co.datapipelines.application.endpoints.StoredCaptureValue
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.CorrelationId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A route's capture state as the console shows it: the active window, if any, and the recent ones with their runs. */
data class CaptureOverview(
    val endpoint: PublishedEndpoint,
    val active: CaptureSession?,
    val recent: List<CaptureWindow>,
    val now: Instant,
)

/** One window and the executions it admitted for capture (newest first, bounded). */
data class CaptureWindow(
    val session: CaptureSession,
    val executions: List<UUID>,
)

/**
 * What an administrator reads for one captured execution. [values] are the declared value-mode outputs the run kept
 * (or their omission markers); [status] says whether the set is complete, so an empty list is never ambiguous.
 */
data class CaptureDetail(
    val executionId: UUID,
    val sessionId: UUID,
    val endpointId: UUID,
    val method: EndpointMethod,
    val admittedAt: Instant,
    val expiresAt: Instant,
    val executionStatus: ExecutionStatus?,
    val status: CaptureStatus,
    val summary: StoredCaptureRun?,
    val values: List<StoredCaptureValue>,
)

/** The honest state of a captured execution's private detail (rest-api §26). */
enum class CaptureStatus(
    val wire: String,
) {
    /** The run has not finished: values may still arrive. */
    RECORDING("recording"),

    /** The run finished and its summary was written with no store failure: [CaptureDetail.values] is the whole set. */
    COMPLETE("complete"),

    /** The run finished, but a value write failed or the closing summary was not written: the set may be partial. */
    INCOMPLETE("incomplete"),
}

/**
 * Runtime capture of published mutations (#1043; published-mutation-apis §6, §7; rest-api §26): open, list and stop a
 * route's capture window, and inspect a captured execution's private detail. Every call is scoped to the caller's
 * selected workspace; the controllers have already enforced the session-only `execution.capture.*` permission for the
 * caller's CURRENT role.
 *
 * Opening a window grants nothing else: it changes only which future admissions of that one route and method store
 * their declared outputs privately. A caller-supplied correlation header only filters a window an administrator opened.
 */
@Suppress("LongParameterList") // one service, one collaborator per table it reads or writes
class ExecutionCaptureService(
    private val endpoints: PublishedEndpointRepository,
    private val sessions: ExecutionCaptureSessionRepository,
    private val reservations: PublishedMutationReservationRepository,
    private val store: ExecutionCaptureStore,
    private val executions: ExecutionRepository,
    private val audit: AuditEventSink,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(!window.isNegative && !window.isZero) { "the capture window must be positive, was $window" }
    }

    /** The route's capture state, or `endpoint.not_found`. Never writes. */
    fun overview(
        workspaceId: UUID,
        endpointId: UUID,
    ): CaptureOverview {
        val endpoint = route(workspaceId, endpointId)
        val now = clock.instant()
        val recent =
            sessions.history(workspaceId, endpointId, RECENT_WINDOWS).map {
                CaptureWindow(it, sessions.capturedExecutions(workspaceId, it.id))
            }
        val active = recent.firstOrNull { it.session.activeAt(now) }?.session
        return CaptureOverview(endpoint, active, recent, now)
    }

    /**
     * Opens a window on route [endpointId] for the configured capture window; [correlationText] (optional) restricts it to one exact
     * request correlation UUID. Refuses a GET route (`execution.capture.invalid`), a malformed filter, an unknown route
     * and a second active window (`execution.capture.already_active`, naming it).
     */
    @Suppress("ThrowsCount") // each refusal is a distinct catalogued code a caller must tell apart
    fun start(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        endpointId: UUID,
        correlationText: String?,
    ): CaptureSession {
        val endpoint = route(workspaceId, endpointId)
        if (!endpoint.method.isMutation) throw invalid(REASON_METHOD, "Only a published POST, PUT or DELETE can be captured.")
        val correlation =
            correlationText?.takeIf { it.isNotBlank() }?.let {
                CorrelationId.parseOrNull(it) ?: throw invalid(REASON_CORRELATION, "The correlation filter must be one UUID.")
            }
        val now = clock.instant()
        val candidate =
            CaptureSession(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                endpointId = endpoint.id,
                method = endpoint.method,
                pathPattern = endpoint.pathPattern,
                correlationId = correlation,
                startedBy = principal.userId,
                startedAt = now,
                // Fixed here: a later change to capture-window-seconds can never extend an open window.
                deadlineAt = now.plus(window),
            )
        return when (val started = sessions.start(candidate)) {
            is CaptureStart.Opened -> {
                audited(AUDIT_STARTED, principal, started.session)
                started.session
            }

            is CaptureStart.AlreadyActive -> {
                throw ApiException(
                    PipelineErrorCodes.ExecutionCapture.ALREADY_ACTIVE,
                    "A capture is already active for this API and method. Stop it before starting another.",
                    mapOf(
                        "capture_id" to started.active.id.toString(),
                        "deadline_at" to started.active.deadlineAt.toString(),
                    ),
                )
            }
        }
    }

    /** Ends window [sessionId] of [endpointId] — only that window. Idempotent: an ended window answers its own state. */
    fun stop(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        endpointId: UUID,
        sessionId: UUID,
    ): CaptureSession =
        when (val stopped = sessions.stop(workspaceId, endpointId, sessionId, principal.userId, clock)) {
            is CaptureStop.Stopped -> {
                audited(AUDIT_STOPPED, principal, stopped.session)
                stopped.session
            }

            is CaptureStop.AlreadyEnded -> {
                stopped.session
            }

            CaptureStop.NotFound -> {
                throw notFound(mapOf("capture_id" to sessionId.toString()))
            }
        }

    /**
     * The private detail of captured execution [executionId] in [workspaceId]. An execution that was never captured,
     * or belongs to another workspace, is `execution.capture.not_found` exactly as an absent one; a captured one past its
     * expiry is `execution.capture.expired` — even before the retention tick has deleted it.
     */
    fun inspect(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        executionId: UUID,
    ): CaptureDetail {
        val origin = reservations.findOrigin(executionId, workspaceId)
        val sessionId = origin?.captureSessionId ?: throw notFound(mapOf("execution_id" to executionId.toString()))
        val expiresAt = requireNotNull(origin.captureExpiresAt) { "a captured reservation carries its detail expiry" }
        val now = clock.instant()
        if (!now.isBefore(expiresAt)) {
            throw ApiException(
                PipelineErrorCodes.ExecutionCapture.EXPIRED,
                "The captured detail of this execution has expired and is no longer available.",
                mapOf("execution_id" to executionId.toString(), "expired_at" to expiresAt.toString()),
            )
        }
        val summary = store.run(executionId, workspaceId, now)
        val values = store.values(executionId, workspaceId, now)
        val executionStatus = executions.findById(workspaceId, executionId)?.status
        audit.log(
            event = AUDIT_INSPECTED,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "workspace_id" to workspaceId.toString(),
                    "endpoint_id" to origin.endpointId.toString(),
                    "method" to origin.method.name,
                    "execution_id" to executionId.toString(),
                    "capture_id" to sessionId.toString(),
                ),
        )
        return CaptureDetail(
            executionId = executionId,
            sessionId = sessionId,
            endpointId = origin.endpointId,
            method = origin.method,
            admittedAt = origin.createdAt,
            expiresAt = expiresAt,
            executionStatus = executionStatus,
            status = statusOf(executionStatus, summary),
            summary = summary,
            values = values,
        )
    }

    private fun route(
        workspaceId: UUID,
        endpointId: UUID,
    ): PublishedEndpoint =
        endpoints.findById(workspaceId, endpointId)
            ?: throw ApiException(
                PipelineErrorCodes.Endpoint.NOT_FOUND,
                "No API with that id in this workspace.",
                mapOf("endpoint_id" to endpointId.toString()),
            )

    private fun audited(
        event: String,
        principal: AuthenticatedPrincipal,
        session: CaptureSession,
    ) {
        audit.log(
            event = event,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "workspace_id" to session.workspaceId.toString(),
                    "endpoint_id" to session.endpointId.toString(),
                    "path_pattern" to session.pathPattern,
                    "method" to session.method.name,
                    "capture_id" to session.id.toString(),
                    "correlation_filtered" to (session.correlationId != null),
                    "deadline_at" to session.deadlineAt.toString(),
                    "ended_at" to session.stoppedAt?.toString(),
                    "end_reason" to session.endReason?.name,
                ),
        )
    }

    companion object {
        /** How many recent windows the console lists with their captured runs. */
        const val RECENT_WINDOWS = 10

        /** enums.md §15 / auth.md §10.1 — the capture audit events; their details carry identities and times, no values. */
        const val AUDIT_STARTED = "execution.capture.started"
        const val AUDIT_STOPPED = "execution.capture.stopped"
        const val AUDIT_INSPECTED = "execution.capture.inspected"

        const val REASON_METHOD = "method_not_capturable"
        const val REASON_CORRELATION = "correlation_id_invalid"

        /** The capture status rule (rest-api §26): running → recording; finished → complete only with a clean summary. */
        fun statusOf(
            executionStatus: ExecutionStatus?,
            summary: StoredCaptureRun?,
        ): CaptureStatus =
            when {
                summary != null -> if (summary.writeFailures == 0) CaptureStatus.COMPLETE else CaptureStatus.INCOMPLETE
                executionStatus == null || executionStatus == ExecutionStatus.RUNNING -> CaptureStatus.RECORDING
                else -> CaptureStatus.INCOMPLETE
            }

        private fun invalid(
            reason: String,
            message: String,
        ) = ApiException(PipelineErrorCodes.ExecutionCapture.INVALID, message, mapOf("reason" to reason))

        private fun notFound(details: Map<String, Any?>) =
            ApiException(
                PipelineErrorCodes.ExecutionCapture.NOT_FOUND,
                "No captured detail with that id in this workspace.",
                details,
            )
    }
}
