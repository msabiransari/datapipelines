package co.datapipelines.executor

import co.datapipelines.events.SseEventType
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * One `execution_events` row (metadata-db §4.7).
 *
 * [eventId] is monotonic **per execution** (1, 2, 3…) and is what `UNIQUE (execution_id,
 * event_id)` enforces — it is the SSE `id:` clients use for gap detection (rest-api §6.7), not a
 * surrogate key.
 */
data class ExecutionEventRecord(
    val executionId: UUID,
    val eventId: Int,
    val eventType: String,
    val timestamp: Instant,
    val payloadJson: String,
)

/**
 * Persistence for `execution_events` (metadata-db §4.7) — the **durable 7-day** event record.
 *
 * This is not the store a live or just-finished consumer reads from: that is the 1-hour Redis
 * event log, which `web` owns (module-structure §5.9). Both are written; only this one survives
 * past the hour, and they expire on different clocks by design (D9).
 *
 * Append-only and immutable: no `updated_at`, and the only DELETE is the retention job.
 */
@Repository
class ExecutionEventRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * Appends one event.
     *
     * A duplicate `(execution_id, event_id)` violates `uq_events_execution_event` and surfaces as
     * a `DuplicateKeyException` — deliberately not swallowed: a repeated sequence number means the
     * emitter lost count, and silently dropping the row would hide it while corrupting replay.
     */
    fun append(record: ExecutionEventRecord) {
        jdbc.update(
            """
            INSERT INTO execution_events (execution_id, event_id, event_type, timestamp, payload_json)
            VALUES (:executionId, :eventId, :eventType, :timestamp, CAST(:payloadJson AS jsonb))
            """.trimIndent(),
            mapOf(
                "executionId" to record.executionId,
                "eventId" to record.eventId,
                "eventType" to record.eventType,
                "timestamp" to Timestamp.from(record.timestamp),
                "payloadJson" to record.payloadJson,
            ),
        )
    }

    /** Appends [event] with the next sequence number, as [SseEventType]'s wire name. */
    fun append(
        executionId: UUID,
        eventId: Int,
        type: SseEventType,
        timestamp: Instant,
        payloadJson: String,
    ) = append(ExecutionEventRecord(executionId, eventId, type.wire, timestamp, payloadJson))

    /**
     * Every event for one execution, in sequence order — the replay query of
     * `GET /api/v1/executions/{id}/events` (rest-api §10.3).
     *
     * The access path is the UNIQUE constraint's own index on `(execution_id, event_id)`; there is
     * deliberately no second index on the same columns (metadata-db §4.7).
     */
    fun findByExecution(executionId: UUID): List<ExecutionEventRecord> =
        jdbc.query(
            """
            SELECT execution_id, event_id, event_type, timestamp, payload_json::TEXT AS payload_json
              FROM execution_events
             WHERE execution_id = :executionId
             ORDER BY event_id
            """.trimIndent(),
            mapOf("executionId" to executionId),
            MAPPER,
        )

    /** The highest sequence number stored for [executionId], or 0 when it has no events yet. */
    fun lastEventId(executionId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(event_id), 0) FROM execution_events WHERE execution_id = :executionId",
            mapOf("executionId" to executionId),
            Int::class.java,
        ) ?: 0

    /**
     * The retention job (metadata-db §8.1), parameterized from
     * `datapipelines.executions.event-retention-days` — no interval literal lives here (D8).
     *
     * Retention is decided per **execution**, on its `completed_at`, not per event on its own
     * timestamp (F3). Deleting by event timestamp opened a front-gap: a long-running — or a stuck
     * `RUNNING` — execution would lose its early events while keeping its late ones, so a replay
     * would start mid-stream with no `execution_started` and no way to tell that from a real gap.
     * An execution that has not completed keeps every event it has.
     *
     * @return rows purged.
     */
    fun deleteOlderThan(cutoff: Instant): Int =
        jdbc.update(
            """
            DELETE FROM execution_events
             WHERE execution_id IN (
                       SELECT execution_id
                         FROM pipeline_executions
                        WHERE completed_at IS NOT NULL AND completed_at < :cutoff
                   )
            """.trimIndent(),
            mapOf("cutoff" to Timestamp.from(cutoff)),
        )

    private companion object {
        val MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ExecutionEventRecord(
                    executionId = rs.getObject("execution_id", UUID::class.java),
                    eventId = rs.getInt("event_id"),
                    eventType = rs.getString("event_type"),
                    timestamp = rs.getTimestamp("timestamp").toInstant(),
                    payloadJson = rs.getString("payload_json"),
                )
            }
    }
}
