package co.datapipelines.scheduler

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * #725 — run rows straight into `schedule_runs` for the last-run filter's suites: the filter and the
 * summary read states, not how a run reached one, so a fixture writes the state it needs. A
 * `starting`/`running` row carries an execution id (`chk_schedule_runs_claimed`); [createdAt] and
 * [id] are explicit so a suite can build a `created_at` tie broken by id.
 */
internal object ScheduleRunRows {
    fun insert(
        scheduleId: UUID,
        state: RunState,
        createdAt: Instant,
        id: UUID = UUID.randomUUID(),
        scheduledAt: Instant = createdAt,
    ) {
        SchedulerTestDb.jdbc.update(
            """
            INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, scheduled_at, reference_at, reference_timezone,
                                       admit_by, executor_id, payload_schema_version, payload_json, actor_user_id, state,
                                       execution_id, created_at, updated_at)
            VALUES (:id, :sid, :ws, 'cron', :scheduled, :scheduled, 'UTC', :scheduled, :executor, 1, '{}'::jsonb, :actor, :state,
                    :execution, :at, :at)
            """.trimIndent(),
            mapOf(
                "id" to id,
                "sid" to scheduleId,
                "ws" to SchedulerTestDb.WORKSPACE,
                "scheduled" to Timestamp.from(scheduledAt),
                "at" to Timestamp.from(createdAt),
                "executor" to FakeExecutor.ID,
                "actor" to SchedulerTestDb.SYSTEM_ACTOR,
                "state" to state.wire,
                "execution" to if (state == RunState.STARTING || state == RunState.RUNNING) UUID.randomUUID() else null,
            ),
        )
    }
}
