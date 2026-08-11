package co.datapipelines.web.sse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One replayable event (rest-api.md §10.3), as stored in the Redis log. */
data class LoggedSseEvent(
    val eventId: Int,
    val eventName: String,
    val payload: Map<String, Any?>,
)

/**
 * The **post-completion SSE event log** (module-structure §5.9, rest-api §10.3).
 *
 * `web` owns this Redis keyspace; it is not the durable record. Two stores, two retention windows,
 * on purpose (D9):
 *
 * | Store | Owner | Lives | Serves |
 * |---|---|---|---|
 * | this log | `web` (Redis) | **1 hour**, not configurable | `GET /executions/{id}/events` — a replayable *stream* |
 * | `execution_events` | `dag` (Postgres) | `datapipelines.executions.event-retention-days` (7) | the durable per-event record |
 *
 * The 1-hour window is a fixed product decision (§10.3: "not configurable"), so it is a constant
 * here rather than a config key — configuration.md defines none, and inventing one would create a
 * second authority.
 *
 * ## Failure policy
 * A Redis write failure here **never** fails an execution. This log is a debugging convenience;
 * the durable record and the live stream are the guarantees. The failure is logged at WARN, never
 * swallowed (rules/02).
 */
class SseEventLog(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SseEventLog::class.java)

    /** Appends one event and refreshes the log's 1-hour expiry. */
    fun append(
        executionId: UUID,
        event: LoggedSseEvent,
    ) {
        val key = key(executionId)
        try {
            redis.opsForList().rightPush(key, mapper.writeValueAsString(event))
            redis.expire(key, RETENTION.seconds, TimeUnit.SECONDS)
        } catch (e: DataAccessException) {
            log.warn("SSE event log append failed for execution {} (replay will be incomplete).", executionId, e)
        }
    }

    /**
     * The stored stream in original order, or null when the log has expired or never existed —
     * which §10.3 answers with `410`, and which the caller must distinguish from an empty list.
     */
    fun replay(executionId: UUID): List<LoggedSseEvent>? {
        val stored =
            try {
                redis.opsForList().range(key(executionId), 0, -1)
            } catch (e: DataAccessException) {
                log.warn("SSE event log read failed for execution {}.", executionId, e)
                return null
            }
        if (stored.isNullOrEmpty()) return null
        return stored.mapNotNull { raw ->
            runCatching { mapper.readValue<LoggedSseEvent>(raw) }
                .onFailure { log.warn("Unreadable event in the log for execution {}; skipped.", executionId, it) }
                .getOrNull()
        }
    }

    private fun key(executionId: UUID) = "$KEY_PREFIX$executionId"

    private companion object {
        /** module-structure §5.9 — `web`'s own keyspace, distinct from `dag`'s `dp:result` / `dp:cancel`. */
        const val KEY_PREFIX = "dp:events:"

        /** rest-api §10.3 — one hour past completion, explicitly not configurable. */
        val RETENTION: Duration = Duration.ofHours(1)
    }
}
