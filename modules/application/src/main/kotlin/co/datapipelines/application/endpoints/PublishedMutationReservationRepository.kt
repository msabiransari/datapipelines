package co.datapipelines.application.endpoints

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One admitted published mutation (metadata-db §4.61, #1039): the durable Idempotency-Key reservation and the origin of
 * the execution it admits. [executionId] IS the execution's id. [scopeDigest] and [requestFingerprint] are keyed
 * digests ([PublishedMutationDigests]); both are null once the reservation is retired.
 */
data class PublishedMutationReservation(
    val executionId: UUID,
    val workspaceId: UUID,
    val apiKeyId: String,
    val endpointId: UUID,
    val apiVersion: Int,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val hasCaller: Boolean,
    val method: EndpointMethod,
    val businessPath: String,
    val scopeDigest: String?,
    val requestFingerprint: String?,
    val bodyBytes: Long?,
    val ownerToken: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val retiredAt: Instant? = null,
    /**
     * #1043 — the capture window that admitted this execution for runtime capture, decided ONCE at admission by
     * [PublishedMutationReservationRepository.reserveDeciding] and reused by every retry; null = not captured.
     */
    val captureSessionId: UUID? = null,
    /** #1043 — when the private captured details expire (admission + the detail retention); set iff [captureSessionId]. */
    val captureExpiresAt: Instant? = null,
) {
    init {
        require(method.isMutation) { "a reservation is a mutation's: $method" }
        require((captureSessionId == null) == (captureExpiresAt == null)) { "a capture decision carries its expiry" }
    }

    /** Whether the replay window has elapsed at [now]; an expired live row is retired by the next request in its scope. */
    fun expiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}

/**
 * Persistence for `published_mutation_reservations` (metadata-db §4.61).
 *
 * Every statement here is a single autocommit statement whose correctness is a property of Postgres, not of a
 * transaction the caller holds: the `ON CONFLICT (scope_digest) DO NOTHING` insert is what makes two replicas racing
 * one key agree on one execution id; the retire and the start-claim take-over are compare-and-set updates on the value
 * the caller read; the release of an unstarted run is conditional on its execution row being absent. No business SQL
 * ever runs inside a metadata transaction this class opens.
 */
class PublishedMutationReservationRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    /** #1043 — the metadata transactions [reserveDeciding] runs in (its row and window locks are held until it commits). */
    private val transactions: TransactionOperations,
) {
    /**
     * Inserts [candidate] unless a LIVE reservation holds its scope digest. True when this call's row was written —
     * the caller owns the execution id and starts the run; false when another request already holds the scope (read it
     * with [findLive]).
     */
    fun reserve(candidate: PublishedMutationReservation): Boolean {
        require(candidate.retiredAt == null && candidate.scopeDigest != null && candidate.requestFingerprint != null) {
            "only a live reservation is inserted"
        }
        return jdbc.update(INSERT_LIVE, params(candidate)) == 1
    }

    /**
     * #1043 — [reserve] that also DECIDES runtime capture and stamps the ONE admission instant, in one transaction whose
     * every lock is taken BEFORE the clock is read:
     *
     * 1. the reservation row is inserted (`ON CONFLICT (scope_digest) DO NOTHING`) with a provisional stamp no other
     *    transaction can see — every wait the durable write can meet (a table lock, a racing replica's uncommitted row
     *    under the same digest, a referenced row's lock) happens HERE; a loser writes nothing and returns null;
     * 2. the route entity's and method's not-stopped capture window is read under `FOR SHARE`, so a concurrent stop
     *    (which takes `FOR UPDATE` on that row, see [ExecutionCaptureSessionRepository.stop]) is ordered by the database
     *    strictly before this admission (the window reads stopped) or after its commit;
     * 3. only then is [AdmissionStamp.clock] read — the admission instant `T`, taken after every database wait of the
     *    admission and while the window's state is held, never a timestamp sampled earlier during the caller's
     *    preflight, pin resolution, validation or a blocked write;
     * 4. the window captures iff `started_at <= T < deadline_at` (both bounds of the half-open window) and its
     *    correlation filter is absent or equals [correlationId];
     * 5. the transaction's own uncommitted row is stamped with `created_at = T`, `expires_at = T + replay` and, when
     *    captured, `capture_session_id` and `capture_expires_at = T + captureRetention` — an update of a row this
     *    transaction inserted, whose only foreign-key check reads the window row it already holds `FOR SHARE`, so it
     *    cannot wait; the persisted admission time, the decision and both deadlines describe the same admission.
     *
     * The candidate's own `createdAt`, `expiresAt` and capture fields are ignored. Returns the reservation as stored
     * when this call's row was written; null when another request already holds the scope (its row carries ITS stamp
     * and decision — read it with [findLive]). A start committing after step 2 is not seen: such an admission is not
     * captured (a window captures only once its start is visible — never an admission it did not see).
     */
    fun reserveDeciding(
        candidate: PublishedMutationReservation,
        correlationId: UUID?,
        stamp: AdmissionStamp,
    ): PublishedMutationReservation? {
        require(candidate.retiredAt == null && candidate.scopeDigest != null && candidate.requestFingerprint != null) {
            "only a live reservation is inserted"
        }
        return transactions.execute { _ ->
            val provisional = stamp.clock.instant()
            val written =
                jdbc.update(
                    INSERT_LIVE,
                    params(candidate.copy(createdAt = provisional, expiresAt = provisional.plus(stamp.replay))),
                ) == 1
            if (!written) return@execute null
            val windows = openWindows(candidate)
            val admittedAt = stamp.clock.instant()
            val window = windows.firstOrNull { it.admits(admittedAt, correlationId) }
            val stamped =
                candidate.copy(
                    createdAt = admittedAt,
                    expiresAt = admittedAt.plus(stamp.replay),
                    captureSessionId = window?.id,
                    captureExpiresAt = window?.let { admittedAt.plus(stamp.captureRetention) },
                )
            jdbc.update(
                """
                UPDATE published_mutation_reservations
                   SET created_at = :createdAt, expires_at = :expiresAt,
                       capture_session_id = :captureSessionId, capture_expires_at = :captureExpiresAt
                 WHERE execution_id = :executionId
                """.trimIndent(),
                mapOf(
                    "executionId" to stamped.executionId,
                    "createdAt" to Timestamp.from(stamped.createdAt),
                    "expiresAt" to Timestamp.from(stamped.expiresAt),
                    "captureSessionId" to stamped.captureSessionId,
                    "captureExpiresAt" to stamped.captureExpiresAt?.let(Timestamp::from),
                ),
            )
            stamped
        }
    }

    /** The not-stopped capture windows of [candidate]'s route entity and method, held `FOR SHARE` until commit. */
    private fun openWindows(candidate: PublishedMutationReservation): List<OpenWindow> =
        jdbc.query(
            """
            SELECT id, started_at, deadline_at, correlation_id FROM execution_capture_sessions
             WHERE workspace_id = :workspaceId AND endpoint_id = :endpointId AND method = :method
               AND stopped_at IS NULL
               FOR SHARE
            """.trimIndent(),
            mapOf(
                "workspaceId" to candidate.workspaceId,
                "endpointId" to candidate.endpointId,
                "method" to candidate.method.name,
            ),
        ) { rs, _ ->
            OpenWindow(
                rs.getObject("id", UUID::class.java),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("deadline_at").toInstant(),
                rs.getObject("correlation_id", UUID::class.java),
            )
        }

    /** A not-stopped capture window as the reserving transaction read it. */
    private data class OpenWindow(
        val id: UUID,
        val startedAt: Instant,
        val deadlineAt: Instant,
        val correlationId: UUID?,
    ) {
        /** `[started_at, deadline_at)` holds [at], and the filter is absent or equals [correlation]. */
        fun admits(
            at: Instant,
            correlation: UUID?,
        ): Boolean = !at.isBefore(startedAt) && at.isBefore(deadlineAt) && (correlationId == null || correlationId == correlation)
    }

    /** The live reservation holding [scopeDigest], or null. */
    fun findLive(scopeDigest: String): PublishedMutationReservation? =
        jdbc.query("$SELECT WHERE scope_digest = :scopeDigest", mapOf("scopeDigest" to scopeDigest), MAPPER).firstOrNull()

    /**
     * The origin of [executionId] in [workspaceId], live or retired — what polling under a business path checks. The
     * workspace predicate is in the query (D11): another workspace's origin is absent, never filtered afterwards.
     */
    fun findOrigin(
        executionId: UUID,
        workspaceId: UUID,
    ): PublishedMutationReservation? =
        jdbc
            .query(
                "$SELECT WHERE execution_id = :executionId AND workspace_id = :workspaceId",
                mapOf("executionId" to executionId, "workspaceId" to workspaceId),
                MAPPER,
            ).firstOrNull()

    /**
     * Retires the expired live reservation [executionId] — only if it still holds [scopeDigest], so two requests
     * retiring the same row cannot both succeed and a row someone else already retired is left alone. The origin
     * columns stay; the digests go.
     */
    fun retire(
        executionId: UUID,
        scopeDigest: String,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE published_mutation_reservations
               SET scope_digest = NULL, request_fingerprint = NULL, retired_at = :now
             WHERE execution_id = :executionId AND scope_digest = :scopeDigest
            """.trimIndent(),
            mapOf("executionId" to executionId, "scopeDigest" to scopeDigest, "now" to Timestamp.from(now)),
        ) == 1

    /**
     * Takes over the start claim of [executionId] from [expectedToken] to [newToken] — the compare-and-set a retry makes
     * when the admitting replica never recorded the run. True for exactly one contender. The execution row's primary key
     * remains the fence against a slow original owner (its fail-closed start then cannot record the row).
     */
    fun takeOver(
        executionId: UUID,
        expectedToken: UUID,
        newToken: UUID,
    ): Boolean =
        jdbc.update(
            """
            UPDATE published_mutation_reservations
               SET owner_token = :newToken
             WHERE execution_id = :executionId AND owner_token = :expectedToken AND retired_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM pipeline_executions e WHERE e.execution_id = :executionId)
            """.trimIndent(),
            mapOf("executionId" to executionId, "expectedToken" to expectedToken, "newToken" to newToken),
        ) == 1

    /**
     * Releases the reservation of a run that was refused BEFORE it started — no execution row exists, so no business
     * SQL ran and the same key may be used again. Conditional on [ownerToken] (a taken-over claim is not the caller's to
     * release) and on the execution row's absence (an admitted execution keeps its reservation on failure or abort).
     */
    fun releaseUnstarted(
        executionId: UUID,
        ownerToken: UUID,
    ): Boolean =
        jdbc.update(
            """
            DELETE FROM published_mutation_reservations
             WHERE execution_id = :executionId AND owner_token = :ownerToken
               AND NOT EXISTS (SELECT 1 FROM pipeline_executions e WHERE e.execution_id = :executionId)
            """.trimIndent(),
            mapOf("executionId" to executionId, "ownerToken" to ownerToken),
        ) == 1

    private fun params(candidate: PublishedMutationReservation): Map<String, Any?> =
        mapOf(
            "executionId" to candidate.executionId,
            "workspaceId" to candidate.workspaceId,
            "apiKeyId" to candidate.apiKeyId,
            "endpointId" to candidate.endpointId,
            "apiVersion" to candidate.apiVersion,
            "pipelineId" to candidate.pipelineId,
            "pipelineVersion" to candidate.pipelineVersion,
            "hasCaller" to candidate.hasCaller,
            "method" to candidate.method.name,
            "businessPath" to candidate.businessPath,
            "scopeDigest" to candidate.scopeDigest,
            "requestFingerprint" to candidate.requestFingerprint,
            "bodyBytes" to candidate.bodyBytes,
            "ownerToken" to candidate.ownerToken,
            "createdAt" to Timestamp.from(candidate.createdAt),
            "expiresAt" to Timestamp.from(candidate.expiresAt),
        )

    private companion object {
        /** A live reservation's insert; the loser of a scope-digest race writes nothing. */
        val INSERT_LIVE =
            """
            INSERT INTO published_mutation_reservations (
                execution_id, workspace_id, api_key_id, endpoint_id, api_version, pipeline_id, pipeline_version,
                has_caller, method, business_path, scope_digest, request_fingerprint, body_bytes, owner_token,
                created_at, expires_at)
            VALUES (
                :executionId, :workspaceId, :apiKeyId, :endpointId, :apiVersion, :pipelineId, :pipelineVersion,
                :hasCaller, :method, :businessPath, :scopeDigest, :requestFingerprint, :bodyBytes, :ownerToken,
                :createdAt, :expiresAt)
            ON CONFLICT (scope_digest) DO NOTHING
            """.trimIndent()

        const val SELECT =
            "SELECT execution_id, workspace_id, api_key_id, endpoint_id, api_version, pipeline_id, pipeline_version, " +
                "has_caller, method, business_path, scope_digest, request_fingerprint, body_bytes, owner_token, created_at, " +
                "expires_at, retired_at, capture_session_id, capture_expires_at FROM published_mutation_reservations"

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PublishedMutationReservation(
                    executionId = rs.getObject("execution_id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    apiKeyId = rs.getString("api_key_id"),
                    endpointId = rs.getObject("endpoint_id", UUID::class.java),
                    apiVersion = rs.getInt("api_version"),
                    pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                    pipelineVersion = rs.getInt("pipeline_version"),
                    hasCaller = rs.getBoolean("has_caller"),
                    method = EndpointMethod.valueOf(rs.getString("method")),
                    businessPath = rs.getString("business_path"),
                    scopeDigest = rs.getString("scope_digest"),
                    requestFingerprint = rs.getString("request_fingerprint"),
                    bodyBytes = rs.getObject("body_bytes") as Long?,
                    ownerToken = rs.getObject("owner_token", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    retiredAt = rs.getTimestamp("retired_at")?.toInstant(),
                    captureSessionId = rs.getObject("capture_session_id", UUID::class.java),
                    captureExpiresAt = rs.getTimestamp("capture_expires_at")?.toInstant(),
                )
            }
    }
}

/**
 * #1043 — how [PublishedMutationReservationRepository.reserveDeciding] stamps an admission: [clock] is read for the
 * admission instant ONCE, after the durable insert and under the capture window's lock, and the replay window
 * ([replay], `datapipelines.idempotency.ttl-seconds`) and the capture detail retention ([captureRetention],
 * `datapipelines.endpoints.capture-detail-retention-hours`) are measured from it.
 */
data class AdmissionStamp(
    val clock: Clock,
    val replay: Duration,
    val captureRetention: Duration,
) {
    init {
        require(!replay.isNegative && !replay.isZero) { "the replay window must be positive, was $replay" }
        require(!captureRetention.isNegative && !captureRetention.isZero) {
            "the capture detail retention must be positive, was $captureRetention"
        }
    }
}
