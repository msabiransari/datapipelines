package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistence for `published_endpoints` (metadata-db §4.13, published-endpoints design §4).
 *
 * ## Why the ambiguity check takes a lock
 *
 * §4.1 refuses to publish a pattern that could match the same URL as an existing one. The
 * database can enforce EXACT duplication (`UNIQUE (path_pattern)`) and nothing more: "`/a/{x}`
 * overlaps `/a/b`" is not expressible as a constraint over one row. So the check is a read
 * followed by a write, and two concurrent publishes of `/a/{x}` and `/a/b` could each read
 * before the other wrote and both land — leaving a URL with two meanings, which is precisely the
 * state the whole matcher design assumes is impossible.
 *
 * Rather than document that as a known race, [insert] takes a transaction-scoped Postgres
 * advisory lock ([REGISTRY_LOCK_KEY]) before it reads. Publishing is a rare, human-initiated
 * write — measured in publishes per day, not per second — so serialising it deployment-wide
 * costs nothing real and turns "almost always true" into an invariant the matcher can rely on.
 * The lock is released with the transaction, including on rollback, because it is `_xact_`.
 *
 * The `UNIQUE` constraint stays as the second line: it catches the exact-duplicate case even if
 * a future caller forgets the lock, and its violation is translated to the same
 * `endpoint.path_conflict` a lock-holding caller would have raised, so the two paths are
 * indistinguishable to a client.
 */
class PublishedEndpointRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** Every enabled endpoint on the deployment — what the per-instance registry cache holds. */
    fun findAllEnabled(): List<PublishedEndpoint> = jdbc.query("$SELECT_COLUMNS WHERE is_enabled = TRUE", MAPPER)

    /** Every endpoint, disabled included — the tree screen shows both, flagged. */
    fun findAll(): List<PublishedEndpoint> = jdbc.query(SELECT_COLUMNS, MAPPER)

    /** The endpoints of one workspace — the management listing (§6). */
    fun findByWorkspace(workspaceId: UUID): List<PublishedEndpoint> =
        jdbc.query("$SELECT_COLUMNS WHERE workspace_id = :workspaceId", mapOf("workspaceId" to workspaceId), MAPPER)

    /** One endpoint by its path — the single-form addressing `?path=` the REST surface uses (§6). */
    fun findByPath(pathPattern: String): PublishedEndpoint? =
        jdbc
            .query("$SELECT_COLUMNS WHERE path_pattern = :path", mapOf("path" to pathPattern), MAPPER)
            .singleOrNull()

    /** Whether any endpoint publishes [pipelineId] — what a pipeline delete has to know. */
    fun findByPipeline(pipelineId: UUID): List<PublishedEndpoint> =
        jdbc.query("$SELECT_COLUMNS WHERE pipeline_id = :pipelineId", mapOf("pipelineId" to pipelineId), MAPPER)

    /**
     * Publishes [endpoint], refusing a pattern that could match the same URL as an existing one.
     *
     * MUST be called inside a transaction — the advisory lock is transaction-scoped, and without
     * one it would be released the instant the `SELECT` returned, which is exactly when it is
     * needed. The service layer's `@Transactional` supplies it.
     *
     * @throws DatapipelinesException `endpoint.path_conflict`, naming the other path.
     */
    fun insert(endpoint: PublishedEndpoint): PublishedEndpoint {
        // `pg_advisory_xact_lock` returns void, so the row is consumed and discarded — the
        // point is the side effect, which lasts until this transaction ends (commit OR rollback).
        jdbc.query("SELECT pg_advisory_xact_lock(:key)", mapOf("key" to REGISTRY_LOCK_KEY), RowCallbackHandler { })
        conflictWith(endpoint.parsed)?.let { other ->
            throw pathConflict(endpoint.pathPattern, other.pathPattern)
        }
        try {
            jdbc.update(INSERT_SQL, params(endpoint))
        } catch (e: DuplicateKeyException) {
            // Only reachable if a caller published without the lock; translated so the client
            // cannot tell the two paths apart.
            throw pathConflict(endpoint.pathPattern, endpoint.pathPattern, e)
        }
        return endpoint
    }

    /**
     * The already-published endpoint whose pattern overlaps [candidate], or null.
     *
     * Reads the whole registry rather than filtering in SQL: overlap is a property of the parsed
     * segments (§4.1), not of the string, and the registry is small by construction — a
     * deployment has tens of endpoints, not millions. Doing it in Kotlin keeps ONE implementation
     * of the rule, shared with the publish-time check and the tests, instead of a second one
     * written in SQL that could disagree with it.
     */
    fun conflictWith(candidate: EndpointPath.Parsed): PublishedEndpoint? =
        findAll().firstOrNull { EndpointPath.overlaps(candidate, it.parsed) }

    /** Unpublishes by path. Returns whether a row was removed. */
    fun deleteByPath(pathPattern: String): Boolean =
        jdbc.update("DELETE FROM published_endpoints WHERE path_pattern = :path", mapOf("path" to pathPattern)) > 0

    /** Enables or disables an endpoint; a disabled one answers `404` exactly like an unknown path (§5.6). */
    fun setEnabled(
        pathPattern: String,
        enabled: Boolean,
    ): Boolean =
        jdbc.update(
            "UPDATE published_endpoints SET is_enabled = :enabled, updated_at = NOW() WHERE path_pattern = :path",
            mapOf("path" to pathPattern, "enabled" to enabled),
        ) > 0

    private fun params(endpoint: PublishedEndpoint): Map<String, Any?> =
        mapOf(
            "id" to endpoint.id,
            "workspaceId" to endpoint.workspaceId,
            "path" to endpoint.pathPattern,
            "pipelineId" to endpoint.pipelineId,
            "timeoutSeconds" to endpoint.timeoutSeconds,
            "description" to endpoint.description,
            "isEnabled" to endpoint.isEnabled,
            "createdBy" to endpoint.createdBy,
        )

    private fun pathConflict(
        candidate: String,
        other: String,
        cause: Throwable? = null,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Endpoint.PATH_CONFLICT,
        message =
            "Path '$candidate' could match the same URL as the already-published '$other'. " +
                "Ambiguity is refused rather than resolved by precedence — pick a path that cannot collide.",
        details = mapOf("path_pattern" to candidate, "conflicting_path" to other),
        cause = cause,
    )

    private companion object {
        /**
         * The advisory-lock key the registry serialises publishes on. An arbitrary constant, but
         * a NAMED one: any other advisory lock this application takes must not reuse it.
         */
        const val REGISTRY_LOCK_KEY = 74_0001L

        val SELECT_COLUMNS =
            """
            SELECT id, workspace_id, path_pattern, pipeline_id, timeout_seconds,
                   description, is_enabled, created_by, created_at, updated_at
              FROM published_endpoints
            """.trimIndent()

        val INSERT_SQL =
            """
            INSERT INTO published_endpoints
                (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, is_enabled, created_by)
            VALUES (:id, :workspaceId, :path, :pipelineId, :timeoutSeconds, :description, :isEnabled, :createdBy)
            """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                PublishedEndpoint.of(
                    id = rs.getObject("id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    pathPattern = rs.getString("path_pattern"),
                    pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                    timeoutSeconds = rs.getInt("timeout_seconds"),
                    description = rs.getString("description"),
                    isEnabled = rs.getBoolean("is_enabled"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }
    }
}
