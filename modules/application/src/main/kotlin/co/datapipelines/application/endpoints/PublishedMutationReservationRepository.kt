package co.datapipelines.application.endpoints

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
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
) {
    init {
        require(method.isMutation) { "a reservation is a mutation's: $method" }
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
        return jdbc.update(
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
            """.trimIndent(),
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
            ),
        ) == 1
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

    private companion object {
        const val SELECT =
            "SELECT execution_id, workspace_id, api_key_id, endpoint_id, api_version, pipeline_id, pipeline_version, " +
                "has_caller, method, business_path, scope_digest, request_fingerprint, body_bytes, owner_token, created_at, " +
                "expires_at, retired_at FROM published_mutation_reservations"

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
                )
            }
    }
}
