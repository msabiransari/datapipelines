package co.datapipelines.application.endpoints

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * One administrator-opened capture window (metadata-db §4.62, #1043) for one published route ENTITY and one mutation
 * method. The half-open window is `[startedAt, endedAt)`: [deadlineAt] is fixed at start, [stoppedAt] is set by an
 * early stop (never later than the deadline) or by the next start once the deadline has passed.
 */
data class CaptureSession(
    val id: UUID,
    val workspaceId: UUID,
    val endpointId: UUID,
    val method: EndpointMethod,
    val pathPattern: String,
    val correlationId: UUID?,
    val startedBy: UUID,
    val startedAt: Instant,
    val deadlineAt: Instant,
    val stoppedAt: Instant? = null,
    val stoppedBy: UUID? = null,
    val endReason: EndReason? = null,
) {
    init {
        require(method.isMutation) { "a capture session is a mutation's: $method" }
        require(deadlineAt.isAfter(startedAt)) { "a capture window ends after it starts" }
    }

    /** Why a window ended; the stored spelling is the name. */
    enum class EndReason { STOPPED, EXPIRED }

    /** The exclusive end of the window: the early stop, else the deadline. */
    val endedAt: Instant get() = stoppedAt ?: deadlineAt

    /** Whether the window admits a new execution at [now]. */
    fun activeAt(now: Instant): Boolean = stoppedAt == null && now.isBefore(deadlineAt)

    /** How the window ended as seen at [now] — null while it is active. */
    fun endReasonAt(now: Instant): EndReason? = endReason ?: EndReason.EXPIRED.takeIf { !now.isBefore(deadlineAt) }
}

/** What a start answered. */
sealed interface CaptureStart {
    /** The window was opened. */
    data class Opened(
        val session: CaptureSession,
    ) : CaptureStart

    /** An active window already holds this route and method — the caller answers 409 naming it. */
    data class AlreadyActive(
        val active: CaptureSession,
    ) : CaptureStart
}

/** What a stop answered. Stop is idempotent: a window that already ended answers its own state, never another's. */
sealed interface CaptureStop {
    /** This call ended the window (an early stop, or a stop at or after the deadline recorded as its expiry). */
    data class Stopped(
        val session: CaptureSession,
    ) : CaptureStop

    /** The window had already ended; nothing was written. */
    data class AlreadyEnded(
        val session: CaptureSession,
    ) : CaptureStop

    /** No such window for this route in this workspace. */
    data object NotFound : CaptureStop
}

/**
 * Persistence for `execution_capture_sessions` (metadata-db §4.62). Every query carries the workspace predicate (D11):
 * another workspace's session is absent, never filtered afterwards.
 *
 * The admission side of the window lives in [PublishedMutationReservationRepository.reserveDeciding], which reads the
 * open session under `FOR SHARE` in the reserving statement; [stop] is an `UPDATE` of the same row, so the database
 * orders every stop strictly before or after every admission.
 */
class ExecutionCaptureSessionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val transactions: TransactionOperations,
) {
    /**
     * Opens [candidate] unless an ACTIVE window holds its route and method. In one transaction: a window past its
     * deadline that nobody stopped is closed as `EXPIRED` at its deadline, then the insert runs; the partial unique
     * index refuses a second open window, including one a concurrent start inserted first.
     */
    fun start(candidate: CaptureSession): CaptureStart {
        require(candidate.stoppedAt == null) { "only an open window is started" }
        return try {
            transactions.execute {
                jdbc.update(
                    """
                    UPDATE execution_capture_sessions
                       SET stopped_at = deadline_at, end_reason = 'EXPIRED'
                     WHERE workspace_id = :workspaceId AND endpoint_id = :endpointId AND method = :method
                       AND stopped_at IS NULL AND deadline_at <= :now
                    """.trimIndent(),
                    scope(candidate) + ("now" to Timestamp.from(candidate.startedAt)),
                )
                jdbc.update(
                    """
                    INSERT INTO execution_capture_sessions (
                        id, workspace_id, endpoint_id, method, path_pattern, correlation_id, started_by, started_at, deadline_at)
                    VALUES (
                        :id, :workspaceId, :endpointId, :method, :pathPattern, :correlationId, :startedBy, :startedAt, :deadlineAt)
                    """.trimIndent(),
                    scope(candidate) +
                        mapOf(
                            "id" to candidate.id,
                            "pathPattern" to candidate.pathPattern,
                            "correlationId" to candidate.correlationId,
                            "startedBy" to candidate.startedBy,
                            "startedAt" to Timestamp.from(candidate.startedAt),
                            "deadlineAt" to Timestamp.from(candidate.deadlineAt),
                        ),
                )
            }
            CaptureStart.Opened(candidate)
        } catch (
            @Suppress("SwallowedException") e: DuplicateKeyException,
        ) {
            // The one open window is someone else's (or a concurrent start's) — read it back to name it.
            val active =
                open(candidate.workspaceId, candidate.endpointId, candidate.method)
                    ?: throw IllegalStateException("the open capture window vanished between refusal and read", e)
            CaptureStart.AlreadyActive(active)
        }
    }

    /**
     * Ends window [sessionId] of route [endpointId] in [workspaceId] — only that window, so a delayed stop can never
     * close a newer one. At or after the deadline the end is recorded as `EXPIRED` at the deadline; before it, as
     * `STOPPED` at [now] (never before the window started).
     */
    fun stop(
        workspaceId: UUID,
        endpointId: UUID,
        sessionId: UUID,
        stoppedBy: UUID,
        now: Instant,
    ): CaptureStop {
        val stopped =
            jdbc
                .query(
                    """
                    UPDATE execution_capture_sessions
                       SET stopped_at = LEAST(GREATEST(:now, started_at), deadline_at),
                           end_reason = CASE WHEN :now >= deadline_at THEN 'EXPIRED' ELSE 'STOPPED' END,
                           stopped_by = :stoppedBy
                     WHERE id = :id AND workspace_id = :workspaceId AND endpoint_id = :endpointId AND stopped_at IS NULL
                    RETURNING $COLUMNS
                    """.trimIndent(),
                    mapOf(
                        "id" to sessionId,
                        "workspaceId" to workspaceId,
                        "endpointId" to endpointId,
                        "stoppedBy" to stoppedBy,
                        "now" to Timestamp.from(now),
                    ),
                    MAPPER,
                ).firstOrNull()
        if (stopped != null) return CaptureStop.Stopped(stopped)
        val existing = find(workspaceId, endpointId, sessionId) ?: return CaptureStop.NotFound
        return CaptureStop.AlreadyEnded(existing)
    }

    /** Window [sessionId] of route [endpointId] in [workspaceId], or null. */
    fun find(
        workspaceId: UUID,
        endpointId: UUID,
        sessionId: UUID,
    ): CaptureSession? =
        jdbc
            .query(
                "SELECT $COLUMNS FROM execution_capture_sessions WHERE id = :id AND workspace_id = :workspaceId AND endpoint_id = :endpointId",
                mapOf("id" to sessionId, "workspaceId" to workspaceId, "endpointId" to endpointId),
                MAPPER,
            ).firstOrNull()

    /** The not-yet-stopped window of a route and method — possibly past its deadline (see [CaptureSession.activeAt]). */
    fun open(
        workspaceId: UUID,
        endpointId: UUID,
        method: EndpointMethod,
    ): CaptureSession? =
        jdbc
            .query(
                "SELECT $COLUMNS FROM execution_capture_sessions WHERE workspace_id = :workspaceId AND endpoint_id = :endpointId " +
                    "AND method = :method AND stopped_at IS NULL",
                mapOf("workspaceId" to workspaceId, "endpointId" to endpointId, "method" to method.name),
                MAPPER,
            ).firstOrNull()

    /** A route's windows, newest first, at most [limit]. */
    fun history(
        workspaceId: UUID,
        endpointId: UUID,
        limit: Int,
    ): List<CaptureSession> =
        jdbc.query(
            "SELECT $COLUMNS FROM execution_capture_sessions WHERE workspace_id = :workspaceId AND endpoint_id = :endpointId " +
                "ORDER BY started_at DESC, id LIMIT :limit",
            mapOf("workspaceId" to workspaceId, "endpointId" to endpointId, "limit" to limit),
            MAPPER,
        )

    /** How many executions window [sessionId] in [workspaceId] admitted for capture. */
    fun capturedExecutions(
        workspaceId: UUID,
        sessionId: UUID,
    ): List<UUID> =
        jdbc.query(
            "SELECT execution_id FROM published_mutation_reservations WHERE capture_session_id = :id AND workspace_id = :workspaceId " +
                "ORDER BY created_at DESC, execution_id LIMIT $CAPTURED_LIST_LIMIT",
            mapOf("id" to sessionId, "workspaceId" to workspaceId),
        ) { rs, _ -> rs.getObject("execution_id", UUID::class.java) }

    private fun scope(session: CaptureSession): Map<String, Any?> =
        mapOf("workspaceId" to session.workspaceId, "endpointId" to session.endpointId, "method" to session.method.name)

    companion object {
        /** The newest captured executions a window lists; the window is minutes long and one reproduction is the aim. */
        const val CAPTURED_LIST_LIMIT = 50

        private const val COLUMNS =
            "id, workspace_id, endpoint_id, method, path_pattern, correlation_id, started_by, started_at, deadline_at, " +
                "stopped_at, stopped_by, end_reason"

        private val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                CaptureSession(
                    id = rs.getObject("id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    endpointId = rs.getObject("endpoint_id", UUID::class.java),
                    method = EndpointMethod.valueOf(rs.getString("method")),
                    pathPattern = rs.getString("path_pattern"),
                    correlationId = rs.getObject("correlation_id", UUID::class.java),
                    startedBy = rs.getObject("started_by", UUID::class.java),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    deadlineAt = rs.getTimestamp("deadline_at").toInstant(),
                    stoppedAt = rs.getTimestamp("stopped_at")?.toInstant(),
                    stoppedBy = rs.getObject("stopped_by", UUID::class.java),
                    endReason = rs.getString("end_reason")?.let { CaptureSession.EndReason.valueOf(it) },
                )
            }
    }
}
