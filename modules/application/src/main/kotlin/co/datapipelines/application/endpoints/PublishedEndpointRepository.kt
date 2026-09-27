package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * ## Why every read goes through [EndpointRow] (#274)
 *
 * The table holds rows a LATER grammar may refuse: an endpoint published before R-EP5 has a
 * two-segment path that [EndpointPath.parse] throws on. Every read used to map through
 * [PublishedEndpoint.of] directly, so ONE such row made [findAll] throw — and the demo seeder's
 * conflict check ran that at boot, refusing the whole application start. The mapper now parses
 * defensively: a row today's grammar refuses is an [EndpointRow.Legacy] value carrying the
 * refusal's reason, skipped by every valid-endpoint read and exposed only through [findLegacy].
 * Fail closed, never fatal — the same rule the reserved-category check already follows
 * ([EndpointPath.reservedCategory]'s KDoc).
 */
class PublishedEndpointRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(PublishedEndpointRepository::class.java)

    /**
     * The once-per-boot guard behind [queryRows]' WARN. A repository is a singleton bean, but its
     * reads are per request — a counter per ROW or per READ would nag the serve path; ONE line
     * naming the legacy count is the fact an operator needs (#274).
     */
    private val legacyWarned = AtomicBoolean(false)

    /** Every valid endpoint on the deployment, disabled included. */
    fun findAll(): List<PublishedEndpoint> = queryRows(SELECT_COLUMNS, emptyMap()).validEndpoints()

    /** Every valid ENABLED endpoint — what the per-instance registry cache holds. */
    fun findAllEnabled(): List<PublishedEndpoint> =
        queryRows("$SELECT_COLUMNS WHERE is_enabled = TRUE", emptyMap()).validEndpoints()

    /** The valid endpoints of one workspace — the management listing (§6). */
    fun findByWorkspace(workspaceId: UUID): List<PublishedEndpoint> =
        queryRows("$SELECT_COLUMNS WHERE workspace_id = :workspaceId", mapOf("workspaceId" to workspaceId)).validEndpoints()

    /** One valid endpoint by its path — the single-form addressing `?path=` the REST surface uses (§6). */
    fun findByPath(pathPattern: String): PublishedEndpoint? =
        queryRows("$SELECT_COLUMNS WHERE path_pattern = :path", mapOf("path" to pathPattern)).validEndpoints().singleOrNull()

    /**
     * The row at [pathPattern], valid or legacy — what unpublishing needs: the fix for a legacy
     * row IS an unpublish, so the write path must see the row every other read skips.
     */
    fun findRowByPath(pathPattern: String): EndpointRow? =
        queryRows("$SELECT_COLUMNS WHERE path_pattern = :path", mapOf("path" to pathPattern)).singleOrNull()

    /**
     * The legacy rows of one workspace, or every legacy row on the deployment when [workspaceId]
     * is null. A legacy row is listed, flagged with its reason, and unpublishable — never
     * served, never a conflict partner.
     */
    fun findLegacy(workspaceId: UUID?): List<EndpointRow.Legacy> {
        val rows =
            if (workspaceId == null) {
                queryRows(SELECT_COLUMNS, emptyMap())
            } else {
                queryRows("$SELECT_COLUMNS WHERE workspace_id = :workspaceId", mapOf("workspaceId" to workspaceId))
            }
        return rows.filterIsInstance<EndpointRow.Legacy>()
    }

    /** Whether any endpoint publishes [pipelineId] — what a pipeline delete has to know. */
    fun findByPipeline(pipelineId: UUID): List<PublishedEndpoint> =
        queryRows("$SELECT_COLUMNS WHERE pipeline_id = :pipelineId", mapOf("pipelineId" to pipelineId)).validEndpoints()

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

        /**
         * Maps one stored row to [EndpointRow]: a parseable path is a [EndpointRow.Valid]
         * endpoint, anything else is [EndpointRow.Legacy] with the grammar's own refusal as its
         * reason (bounded — the message is stored data's voice, never a stack trace).
         */
        val MAPPER =
            RowMapper<EndpointRow> { rs: ResultSet, _: Int ->
                val pathPattern = rs.getString("path_pattern")
                val parsed =
                    EndpointPath
                        .parse(pathPattern)
                        .getOrElse { failure ->
                            return@RowMapper EndpointRow.Legacy(
                                id = rs.getObject("id", UUID::class.java),
                                workspaceId = rs.getObject("workspace_id", UUID::class.java),
                                pathPattern = pathPattern,
                                pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                                reason = boundedReason(failure.message ?: "path is not a legal path (§4.1)"),
                                enabled = rs.getBoolean("is_enabled"),
                            )
                        }
                EndpointRow.Valid(
                    PublishedEndpoint(
                        id = rs.getObject("id", UUID::class.java),
                        workspaceId = rs.getObject("workspace_id", UUID::class.java),
                        pathPattern = pathPattern,
                        pipelineId = rs.getObject("pipeline_id", UUID::class.java),
                        timeoutSeconds = rs.getInt("timeout_seconds"),
                        description = rs.getString("description"),
                        isEnabled = rs.getBoolean("is_enabled"),
                        createdBy = rs.getObject("created_by", UUID::class.java),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                        parsed = parsed,
                    ),
                )
            }

        /** The reason a legacy row carries: the grammar's own sentence, bounded to a label. */
        fun boundedReason(message: String): String = message.take(REASON_MAX_LENGTH)

        const val REASON_MAX_LENGTH = 300
    }

    /** Every row of one query, mapped defensively (#274). */
    private fun queryRows(
        sql: String,
        params: Map<String, Any?>,
    ): List<EndpointRow> {
        val rows = jdbc.query(sql, params, MAPPER)
        val legacy = rows.count { it is EndpointRow.Legacy }
        if (legacy > 0 && legacyWarned.compareAndSet(false, true)) {
            log.warn(
                "event=endpoint.legacy_rows count={} message=\"rows whose stored path no longer meets today's " +
                    "grammar (R-EP5) are retired: never served, never a conflict partner, listed flagged; " +
                    "unpublishing one is the fix (issue #274)\"",
                legacy,
            )
        }
        return rows
    }

    private fun List<EndpointRow>.validEndpoints(): List<PublishedEndpoint> = filterIsInstance<EndpointRow.Valid>().map { it.endpoint }
}

/**
 * One row of `published_endpoints` as STORED — the parse verdict, not the parse itself (#274).
 *
 * [Valid] carries the endpoint every consumer wants. [Legacy] carries the least a listing needs
 * to surface a row today's grammar refuses: who owns it, what it claims to serve, whether it is
 * enabled, and WHY it is refused — the grammar's own message, so the operator reads the rule the
 * row predates, not an exception. A legacy row is never served (the registry never sees it),
 * never a conflict partner ([conflictWith] sees valid rows only), and never repaired in place —
 * unpublishing it and republishing at a legal path is the fix.
 */
sealed interface EndpointRow {
    val id: UUID
    val workspaceId: UUID
    val pathPattern: String
    val enabled: Boolean

    /** A row whose path parses — the ordinary case. */
    data class Valid(
        val endpoint: PublishedEndpoint,
    ) : EndpointRow {
        override val id: UUID get() = endpoint.id
        override val workspaceId: UUID get() = endpoint.workspaceId
        override val pathPattern: String get() = endpoint.pathPattern
        override val enabled: Boolean get() = endpoint.isEnabled
    }

    /** A row saved before a later grammar; retired, never fatal (#274). [pipelineId] is the
     * row's own column, carried so the promoter lens can hide the row the same way it hides a
     * valid endpoint whose pipeline is out of view. */
    data class Legacy(
        override val id: UUID,
        override val workspaceId: UUID,
        override val pathPattern: String,
        val pipelineId: UUID,
        val reason: String,
        override val enabled: Boolean,
    ) : EndpointRow
}
