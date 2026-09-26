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
 * `schedule_runs` and `schedule_run_events` (metadata-db §4.23, §4.24): the runs and their
 * append-only trail (R10). One repository, because a trail row is written in the SAME statement
 * sequence — under the run row's lock — as the run transition it records, and its `seq` is drawn
 * from the run row itself.
 *
 * Plain JDBC; the services own the transactions (§8.5 rule 2).
 */
class ScheduleRunRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    /**
     * Records an occurrence (cron or catch-up) — `ON CONFLICT ON CONSTRAINT
     * uq_schedule_runs_occurrence DO NOTHING` (R1): a second dispatcher that computed the same
     * instant records nothing. Returns false then. Any OTHER uniqueness (the one-active index) is
     * NOT swallowed: every caller holds the schedule's lock and checked it, so a violation there is
     * a defect that must fail the tick loudly.
     */
    fun insertOccurrence(run: NewRun): Boolean =
        jdbc.update(
            INSERT_SQL + " ON CONFLICT ON CONSTRAINT uq_schedule_runs_occurrence DO NOTHING",
            params(run),
        ) == 1

    /** Records a manual run (Run now). A duplicate idempotency key surfaces as `DuplicateKeyException` (L1). */
    fun insertManual(run: NewRun) {
        jdbc.update(INSERT_SQL, params(run))
    }

    /** One run, by id alone — the worker's and the reconciler's read (the task data carries only the id). */
    fun find(id: UUID): ScheduleRun? = jdbc.query("SELECT * FROM schedule_runs WHERE id = :id", mapOf("id" to id), rowMapper).singleOrNull()

    /** [find] holding the run row's lock. */
    fun lock(id: UUID): ScheduleRun? =
        jdbc.query("SELECT * FROM schedule_runs WHERE id = :id FOR UPDATE", mapOf("id" to id), rowMapper).singleOrNull()

    /** A run of [scheduleId] in [workspaceId] — the REST read, workspace-scoped. */
    fun findInSchedule(
        workspaceId: UUID,
        scheduleId: UUID,
        runId: UUID,
    ): ScheduleRun? =
        jdbc
            .query(
                "SELECT * FROM schedule_runs WHERE id = :id AND schedule_id = :sid AND workspace_id = :ws",
                mapOf("id" to runId, "sid" to scheduleId, "ws" to workspaceId),
                rowMapper,
            ).singleOrNull()

    /** The Run now replay's read (L1), with the stored request hash. */
    fun findByIdempotencyKey(
        scheduleId: UUID,
        requestedBy: UUID,
        key: String,
    ): Pair<ScheduleRun, String>? =
        jdbc
            .query(
                "SELECT * FROM schedule_runs WHERE schedule_id = :sid AND requested_by = :by AND idempotency_key = :key",
                mapOf("sid" to scheduleId, "by" to requestedBy, "key" to key),
            ) { rs, n -> rowMapper.mapRow(rs, n)!! to rs.getString("idempotency_hash") }
            .singleOrNull()

    /** A schedule's runs, newest first (record §6). */
    fun listForSchedule(
        workspaceId: UUID,
        scheduleId: UUID,
        limit: Int,
        offset: Int,
    ): List<ScheduleRun> =
        jdbc.query(
            """
            SELECT * FROM schedule_runs WHERE schedule_id = :sid AND workspace_id = :ws
            ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
            """.trimIndent(),
            mapOf("sid" to scheduleId, "ws" to workspaceId, "limit" to limit, "offset" to offset),
            rowMapper,
        )

    /** True when the schedule holds its single active slot (record §3). Read under the schedule's lock. */
    fun hasActive(scheduleId: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM schedule_runs WHERE schedule_id = :sid AND state IN ('queued', 'starting', 'running'))",
            mapOf("sid" to scheduleId),
            Boolean::class.java,
        )!!

    /**
     * **The start claim** (record §2): `queued → starting`, recording the minted execution reference
     * and the executor's snapshot in the same UPDATE. Conditional on `queued` — the one guard that
     * makes a second launch of one run impossible whichever instance or delivery asks.
     */
    fun claim(
        id: UUID,
        executionId: UUID,
        prepared: JsonNode,
        worker: String,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE schedule_runs SET state = 'starting', execution_id = :executionId, prepared_json = CAST(:prepared AS jsonb),
                                     worker = :worker, claimed_at = :now, updated_at = :now
            WHERE id = :id AND state = 'queued'
            """.trimIndent(),
            mapOf(
                "id" to id,
                "executionId" to executionId,
                "prepared" to mapper.writeValueAsString(prepared),
                "worker" to worker,
                "now" to Timestamp.from(now),
            ),
        ) == 1

    /**
     * Moves a run from one of [from] to [to] with [reason]; false when it was not in [from] (another
     * writer got there first). Terminal states stamp `finished_at`; `running` stamps `started_at`.
     */
    fun transition(
        id: UUID,
        from: Set<RunState>,
        to: RunState,
        reason: String?,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE schedule_runs SET state = :to, reason = :reason, updated_at = :now,
                   started_at = CASE WHEN :to = 'running' THEN COALESCE(started_at, :now) ELSE started_at END,
                   finished_at = CASE WHEN :terminal THEN :now ELSE finished_at END
            WHERE id = :id AND state IN (:from)
            """.trimIndent(),
            mapOf(
                "id" to id,
                "to" to to.wire,
                "reason" to reason,
                "now" to Timestamp.from(now),
                "terminal" to !to.active,
                "from" to from.map { it.wire },
            ),
        ) == 1

    /** Counts one capacity retry (R4). */
    fun countAttempt(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update(
            "UPDATE schedule_runs SET attempts = attempts + 1, updated_at = :now WHERE id = :id",
            mapOf("id" to id, "now" to Timestamp.from(now)),
        )
    }

    /**
     * The reconciler's worklist (record §2.1): runs whose execution it still watches — `starting`
     * and `running`, and `unknown` ones younger than [unknownWatchFrom] (a later real terminal is an
     * update about the same run, A5). Live runs first — a pile of watched `unknown` runs must never
     * starve a `running` one of its terminal — then oldest first; bounded.
     */
    fun watched(
        unknownWatchFrom: Instant,
        limit: Int,
    ): List<ScheduleRun> =
        jdbc.query(
            """
            SELECT * FROM schedule_runs
            WHERE state IN ('starting', 'running')
               OR (state = 'unknown' AND execution_id IS NOT NULL AND updated_at >= :watchFrom)
            ORDER BY (state = 'unknown'), updated_at LIMIT :limit
            """.trimIndent(),
            mapOf("watchFrom" to Timestamp.from(unknownWatchFrom), "limit" to limit),
            rowMapper,
        )

    /**
     * Appends one trail row (R10). The `seq` comes from `schedule_runs.trail_seq`, incremented by
     * the same statement — the row lock that UPDATE takes serializes two writers on one run, so
     * the sequence is gap-free and monotonic without a table lock.
     */
    fun appendTrail(
        runId: UUID,
        kind: TrailKind,
        reason: String?,
        at: Instant,
        worker: String?,
        details: JsonNode,
    ): Int =
        jdbc.queryForObject(
            """
            WITH seq AS (
                UPDATE schedule_runs SET trail_seq = trail_seq + 1 WHERE id = :runId RETURNING trail_seq
            )
            INSERT INTO schedule_run_events (run_id, seq, kind, reason, at, worker, details_json)
            SELECT :runId, seq.trail_seq, :kind, :reason, :at, :worker, CAST(:details AS jsonb) FROM seq
            RETURNING seq
            """.trimIndent(),
            mapOf(
                "runId" to runId,
                "kind" to kind.wire,
                "reason" to reason,
                "at" to Timestamp.from(at),
                "worker" to worker,
                "details" to mapper.writeValueAsString(details),
            ),
            Int::class.java,
        )!!

    /** A run's trail, in order. */
    fun trail(runId: UUID): List<TrailEvent> =
        jdbc.query(
            "SELECT * FROM schedule_run_events WHERE run_id = :runId ORDER BY seq",
            mapOf("runId" to runId),
        ) { rs, _ ->
            TrailEvent(
                runId = rs.getObject("run_id", UUID::class.java),
                seq = rs.getInt("seq"),
                kind = TrailKind.fromWire(rs.getString("kind")),
                reason = rs.getString("reason"),
                at = rs.getTimestamp("at").toInstant(),
                worker = rs.getString("worker"),
                details = mapper.readTree(rs.getString("details_json")),
            )
        }

    private fun params(run: NewRun): Map<String, Any?> =
        mapOf(
            "id" to run.id,
            "scheduleId" to run.scheduleId,
            "workspaceId" to run.workspaceId,
            "origin" to run.origin.wire,
            "scheduledAt" to run.scheduledAt?.let(Timestamp::from),
            "referenceAt" to Timestamp.from(run.referenceAt),
            "referenceTimezone" to run.referenceTimezone,
            "admitBy" to Timestamp.from(run.admitBy),
            "scheduleRevision" to run.scheduleRevision,
            "executorId" to run.executorId,
            "payloadSchemaVersion" to run.payloadSchemaVersion,
            "payload" to mapper.writeValueAsString(run.payload),
            "parameters" to mapper.writeValueAsString(run.parameters),
            "actorUserId" to run.actorUserId,
            "requestedBy" to run.requestedBy,
            "state" to run.state.wire,
            "reason" to run.reason,
            "idempotencyKey" to run.idempotencyKey,
            "idempotencyHash" to run.idempotencyHash,
            "createdAt" to Timestamp.from(run.createdAt),
            "finishedAt" to run.finishedAt?.let(Timestamp::from),
        )

    private val rowMapper = RowMapper { rs: ResultSet, _: Int -> map(rs) }

    private fun map(rs: ResultSet): ScheduleRun =
        ScheduleRun(
            id = rs.getObject("id", UUID::class.java),
            scheduleId = rs.getObject("schedule_id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            origin = RunOrigin.fromWire(rs.getString("origin")),
            scheduledAt = rs.getTimestamp("scheduled_at")?.toInstant(),
            referenceAt = rs.getTimestamp("reference_at").toInstant(),
            referenceTimezone = rs.getString("reference_timezone"),
            admitBy = rs.getTimestamp("admit_by").toInstant(),
            scheduleRevision = rs.getObject("schedule_revision") as Int?,
            executorId = rs.getString("executor_id"),
            payloadSchemaVersion = rs.getInt("payload_schema_version"),
            payload = mapper.readTree(rs.getString("payload_json")),
            parameters = mapper.readTree(rs.getString("parameters_json")),
            prepared = rs.getString("prepared_json")?.let(mapper::readTree),
            actorUserId = rs.getObject("actor_user_id", UUID::class.java),
            requestedBy = rs.getObject("requested_by", UUID::class.java),
            executionId = rs.getObject("execution_id", UUID::class.java),
            state = RunState.fromWire(rs.getString("state")),
            reason = rs.getString("reason"),
            worker = rs.getString("worker"),
            attempts = rs.getInt("attempts"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            claimedAt = rs.getTimestamp("claimed_at")?.toInstant(),
            startedAt = rs.getTimestamp("started_at")?.toInstant(),
            finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )

    private companion object {
        const val INSERT_SQL =
            """
            INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, scheduled_at, reference_at,
                                       reference_timezone, admit_by, schedule_revision, executor_id,
                                       payload_schema_version, payload_json, parameters_json, actor_user_id, requested_by,
                                       state, reason, idempotency_key, idempotency_hash, created_at, updated_at,
                                       finished_at)
            VALUES (:id, :scheduleId, :workspaceId, :origin, :scheduledAt, :referenceAt, :referenceTimezone,
                    :admitBy, :scheduleRevision, :executorId, :payloadSchemaVersion, CAST(:payload AS jsonb),
                    CAST(:parameters AS jsonb), :actorUserId, :requestedBy, :state, :reason, :idempotencyKey,
                    :idempotencyHash, :createdAt, :createdAt, :finishedAt)
            """
    }
}

/** What a run insert writes. A run is born `queued`, or already terminal (`skipped`) when policy decides at recording. */
data class NewRun(
    val id: UUID,
    val scheduleId: UUID?,
    val workspaceId: UUID,
    val origin: RunOrigin,
    val scheduledAt: Instant?,
    val referenceAt: Instant,
    val referenceTimezone: String,
    val admitBy: Instant,
    val scheduleRevision: Int?,
    val executorId: String,
    val payloadSchemaVersion: Int,
    val payload: JsonNode,
    val parameters: JsonNode,
    val actorUserId: UUID,
    val requestedBy: UUID?,
    val state: RunState,
    val reason: String?,
    val createdAt: Instant,
    val idempotencyKey: String? = null,
    val idempotencyHash: String? = null,
) {
    /** A run born terminal is finished at birth. */
    val finishedAt: Instant? get() = if (state.active) null else createdAt
}
