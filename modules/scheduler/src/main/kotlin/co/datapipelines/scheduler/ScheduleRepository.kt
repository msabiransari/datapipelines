package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `schedules` (metadata-db §4.22). Every read and write names the workspace in its SQL — the
 * isolation rule (R9, auth.md §11A.1) — except the dispatcher's due scan, which is the one read
 * that spans workspaces by design and joins the workspace row to skip a deleted or deactivated one.
 *
 * Plain JDBC: no method opens a transaction (§8.5 rule 2); the services demarcate them. JSON
 * columns bind as text with `CAST(:x AS jsonb)`, the `ExecutionRepository` convention.
 */
class ScheduleRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    /** A new schedule, first revision; `next_due_at` is computed by the caller from the ONE function. */
    fun insert(schedule: NewSchedule): Schedule =
        jdbc.queryForObject(
            """
            INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json,
                                   parameters_json, target_ref, cron, timezone, missed_run_policy, enabled,
                                   next_due_at, created_by, updated_by, idempotency_key, idempotency_hash)
            VALUES (:id, :workspaceId, :name, :executorId, :payloadSchemaVersion, CAST(:payload AS jsonb),
                    CAST(:parameters AS jsonb), :targetRef, :cron, :timezone, :policy, TRUE,
                    :nextDueAt, :createdBy, :createdBy, :idempotencyKey, :idempotencyHash)
            RETURNING *
            """.trimIndent(),
            mapOf(
                "id" to schedule.id,
                "workspaceId" to schedule.workspaceId,
                "name" to schedule.name,
                "executorId" to schedule.executorId,
                "payloadSchemaVersion" to schedule.payloadSchemaVersion,
                "payload" to mapper.writeValueAsString(schedule.payload),
                "parameters" to mapper.writeValueAsString(schedule.parameters),
                "targetRef" to schedule.targetRef,
                "cron" to schedule.cron,
                "timezone" to schedule.timezone,
                "policy" to schedule.missedRunPolicy.wire,
                "nextDueAt" to Timestamp.from(schedule.nextDueAt),
                "createdBy" to schedule.createdBy,
                "idempotencyKey" to schedule.idempotencyKey,
                "idempotencyHash" to schedule.idempotencyHash,
            ),
            rowMapper,
        )!!

    /** A LIVE schedule of [workspaceId]; a deleted one is absent. */
    fun findLive(
        workspaceId: UUID,
        id: UUID,
    ): Schedule? =
        jdbc
            .query(
                "SELECT * FROM schedules WHERE id = :id AND workspace_id = :ws AND deleted_at IS NULL",
                mapOf("id" to id, "ws" to workspaceId),
                rowMapper,
            ).singleOrNull()

    /** [findLive] holding the row's lock — every run-inserting path takes it (the overlap guard, record §3). */
    fun lockLive(
        workspaceId: UUID,
        id: UUID,
    ): Schedule? =
        jdbc
            .query(
                "SELECT * FROM schedules WHERE id = :id AND workspace_id = :ws AND deleted_at IS NULL FOR UPDATE",
                mapOf("id" to id, "ws" to workspaceId),
                rowMapper,
            ).singleOrNull()

    /** Any schedule by id — the worker's read, which must see a deleted schedule to skip its queued run. */
    fun findAny(id: UUID): Schedule? = jdbc.query("SELECT * FROM schedules WHERE id = :id", mapOf("id" to id), rowMapper).singleOrNull()

    /** The create replay's read (L1): the schedule this creator made under [key], deleted or not, with its request hash. */
    fun findByIdempotencyKey(
        workspaceId: UUID,
        createdBy: UUID,
        key: String,
    ): Pair<Schedule, String>? =
        jdbc
            .query(
                """
                SELECT * FROM schedules
                WHERE workspace_id = :ws AND created_by = :by AND idempotency_key = :key
                """.trimIndent(),
                mapOf("ws" to workspaceId, "by" to createdBy, "key" to key),
            ) { rs, n -> rowMapper.mapRow(rs, n)!! to rs.getString("idempotency_hash") }
            .singleOrNull()

    /** The workspace's live schedules, by name, optionally under a folder [prefix] (the explorer's read). */
    fun listLive(
        workspaceId: UUID,
        prefix: String?,
        limit: Int,
        offset: Int,
    ): List<Schedule> {
        val params =
            mutableMapOf<String, Any?>(
                "ws" to workspaceId,
                "limit" to limit,
                "offset" to offset,
            )
        val prefixClause =
            if (prefix.isNullOrEmpty()) {
                ""
            } else {
                // A folder prefix matches its subtree; LIKE metacharacters cannot occur in a legal
                // folder path (the grammar admits `_`, which is escaped below), and the caller
                // validated the prefix against the grammar before it got here.
                params["prefix"] = prefix.replace("_", "\\_") + "/%"
                " AND name LIKE :prefix"
            }
        return jdbc.query(
            "SELECT * FROM schedules WHERE workspace_id = :ws AND deleted_at IS NULL$prefixClause " +
                "ORDER BY name LIMIT :limit OFFSET :offset",
            params,
            rowMapper,
        )
    }

    /** Live schedules in [workspaceId] — the B18 cap's count (L4). */
    fun countLive(workspaceId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM schedules WHERE workspace_id = :ws AND deleted_at IS NULL",
            mapOf("ws" to workspaceId),
            Int::class.java,
        )!!

    /**
     * The edit (record §6): every editable column, the revision bumped, guarded by the expected
     * revision (`If-Match`). Null when the revision moved or the schedule is gone — the caller tells
     * the two apart with a re-read.
     */
    fun update(
        workspaceId: UUID,
        id: UUID,
        expectedRevision: Int,
        edit: ScheduleEdit,
        by: UUID,
        now: Instant,
    ): Schedule? =
        jdbc
            .query(
                """
                UPDATE schedules SET
                    name = :name, executor_id = :executorId, payload_schema_version = :payloadSchemaVersion,
                    payload_json = CAST(:payload AS jsonb), parameters_json = CAST(:parameters AS jsonb), target_ref = :targetRef,
                    cron = :cron, timezone = :timezone, missed_run_policy = :policy,
                    next_due_at = COALESCE(CAST(:nextDueAt AS timestamptz), next_due_at),
                    revision = revision + 1, updated_by = :by, updated_at = :now
                WHERE id = :id AND workspace_id = :ws AND revision = :expected AND deleted_at IS NULL
                RETURNING *
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "ws" to workspaceId,
                    "expected" to expectedRevision,
                    "name" to edit.name,
                    "executorId" to edit.executorId,
                    "payloadSchemaVersion" to edit.payloadSchemaVersion,
                    "payload" to mapper.writeValueAsString(edit.payload),
                    "parameters" to mapper.writeValueAsString(edit.parameters),
                    "targetRef" to edit.targetRef,
                    "cron" to edit.cron,
                    "timezone" to edit.timezone,
                    "policy" to edit.missedRunPolicy.wire,
                    "nextDueAt" to edit.nextDueAt?.let(Timestamp::from),
                    "by" to by,
                    "now" to Timestamp.from(now),
                ),
                rowMapper,
            ).singleOrNull()

    /** Soft delete (D-9.6/B17), guarded by the expected revision. False when the revision moved or it is gone. */
    fun softDelete(
        workspaceId: UUID,
        id: UUID,
        expectedRevision: Int,
        by: UUID,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE schedules SET deleted_at = :now, deleted_by = :by, updated_by = :by, updated_at = :now,
                                 revision = revision + 1, next_due_at = NULL
            WHERE id = :id AND workspace_id = :ws AND revision = :expected AND deleted_at IS NULL
            """.trimIndent(),
            mapOf("id" to id, "ws" to workspaceId, "expected" to expectedRevision, "by" to by, "now" to Timestamp.from(now)),
        ) == 1

    /**
     * Pause or resume (record §1.1). Resume passes the recomputed [nextDueAt] (occurrences due while
     * paused are not missed, D-9.6); pause keeps the stored one, which the dispatcher ignores while
     * `enabled` is false. Bumps the revision: a paused schedule is a different configuration.
     */
    fun setEnabled(
        workspaceId: UUID,
        id: UUID,
        enabled: Boolean,
        nextDueAt: Instant?,
        by: UUID,
        now: Instant,
    ): Schedule? =
        jdbc
            .query(
                """
                UPDATE schedules SET enabled = :enabled,
                                     next_due_at = COALESCE(CAST(:nextDueAt AS timestamptz), next_due_at),
                                     revision = revision + 1, updated_by = :by, updated_at = :now
                WHERE id = :id AND workspace_id = :ws AND deleted_at IS NULL
                RETURNING *
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "ws" to workspaceId,
                    "enabled" to enabled,
                    "nextDueAt" to nextDueAt?.let(Timestamp::from),
                    "by" to by,
                    "now" to Timestamp.from(now),
                ),
                rowMapper,
            ).singleOrNull()

    /**
     * Blocks a schedule under [reason], naming the [runId] that caused it (record §1.1). An existing
     * block is kept — the FIRST cause is the one a person must resolve. Returns true when this call
     * set the block (the transition slice 4's "Schedule blocked" notice will fire on).
     */
    fun block(
        id: UUID,
        reason: String,
        runId: UUID,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE schedules SET blocked_reason = :reason, blocked_at = :now, blocked_run_id = :runId, updated_at = :now
            WHERE id = :id AND blocked_at IS NULL
            """.trimIndent(),
            mapOf("id" to id, "reason" to reason, "runId" to runId, "now" to Timestamp.from(now)),
        ) == 1

    /** Clears a block and recomputes [nextDueAt] from now (record §1.1). Null when it is gone. */
    fun unblock(
        workspaceId: UUID,
        id: UUID,
        nextDueAt: Instant,
        by: UUID,
        now: Instant,
    ): Schedule? =
        jdbc
            .query(
                """
                UPDATE schedules SET blocked_reason = NULL, blocked_at = NULL, blocked_run_id = NULL,
                                     next_due_at = :nextDueAt, revision = revision + 1,
                                     updated_by = :by, updated_at = :now
                WHERE id = :id AND workspace_id = :ws AND deleted_at IS NULL
                RETURNING *
                """.trimIndent(),
                mapOf(
                    "id" to id,
                    "ws" to workspaceId,
                    "nextDueAt" to Timestamp.from(nextDueAt),
                    "by" to by,
                    "now" to Timestamp.from(now),
                ),
                rowMapper,
            ).singleOrNull()

    /**
     * The dispatcher's scan (R1): up to [limit] due schedules — enabled, unblocked, live, in a live
     * and ACTIVE workspace — locked `FOR UPDATE SKIP LOCKED`, so two dispatchers (or a dispatcher and
     * a Run now) never hold one schedule at once and neither waits on the other.
     */
    fun lockDue(
        now: Instant,
        limit: Int,
    ): List<Schedule> =
        jdbc.query(
            """
            SELECT s.* FROM schedules s
            JOIN workspaces w ON w.id = s.workspace_id AND NOT w.is_deleted AND w.deactivated_at IS NULL
            WHERE s.enabled AND s.blocked_at IS NULL AND s.deleted_at IS NULL AND s.next_due_at <= :now
            ORDER BY s.next_due_at
            LIMIT :limit
            FOR UPDATE OF s SKIP LOCKED
            """.trimIndent(),
            mapOf("now" to Timestamp.from(now), "limit" to limit),
            rowMapper,
        )

    /** Advances a locked schedule's `next_due_at` (the dispatcher, after recording what was due). */
    fun advance(
        id: UUID,
        nextDueAt: Instant,
    ) {
        jdbc.update(
            "UPDATE schedules SET next_due_at = :next WHERE id = :id",
            mapOf("id" to id, "next" to Timestamp.from(nextDueAt)),
        )
    }

    private val rowMapper = RowMapper { rs: ResultSet, _: Int -> map(rs) }

    private fun map(rs: ResultSet): Schedule =
        Schedule(
            id = rs.getObject("id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            name = rs.getString("name"),
            revision = rs.getInt("revision"),
            executorId = rs.getString("executor_id"),
            payloadSchemaVersion = rs.getInt("payload_schema_version"),
            payload = json(rs.getString("payload_json")),
            parameters = json(rs.getString("parameters_json")),
            targetRef = rs.getString("target_ref"),
            cron = rs.getString("cron"),
            timezone = rs.getString("timezone"),
            missedRunPolicy = MissedRunPolicy.fromWire(rs.getString("missed_run_policy")) ?: MissedRunPolicy.SKIP,
            enabled = rs.getBoolean("enabled"),
            blockedReason = rs.getString("blocked_reason"),
            blockedAt = rs.getTimestamp("blocked_at")?.toInstant(),
            blockedRunId = rs.getObject("blocked_run_id", UUID::class.java),
            nextDueAt = rs.getTimestamp("next_due_at")?.toInstant(),
            createdBy = rs.getObject("created_by", UUID::class.java),
            updatedBy = rs.getObject("updated_by", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
        )

    private fun json(text: String): JsonNode = mapper.readTree(text)
}

/** What [ScheduleRepository.insert] writes. */
data class NewSchedule(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val executorId: String,
    val payloadSchemaVersion: Int,
    val payload: JsonNode,
    val parameters: JsonNode,
    val targetRef: String,
    val cron: String,
    val timezone: String,
    val missedRunPolicy: MissedRunPolicy,
    val nextDueAt: Instant,
    val createdBy: UUID,
    val idempotencyKey: String?,
    val idempotencyHash: String?,
)

/** What [ScheduleRepository.update] writes. [nextDueAt] null keeps the stored value (timing unchanged). */
data class ScheduleEdit(
    val name: String,
    val executorId: String,
    val payloadSchemaVersion: Int,
    val payload: JsonNode,
    val parameters: JsonNode,
    val targetRef: String,
    val cron: String,
    val timezone: String,
    val missedRunPolicy: MissedRunPolicy,
    val nextDueAt: Instant?,
)
