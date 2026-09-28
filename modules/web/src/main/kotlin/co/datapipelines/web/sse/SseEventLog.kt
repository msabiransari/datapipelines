package co.datapipelines.web.sse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
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
 * One replay-log entry, serialized once by [SseEventLog.entry] — so the batching writer can weigh it
 * against its byte bounds and a payload that cannot be serialized fails before it is queued.
 */
data class ReplayLogEntry(
    val executionId: UUID,
    val eventId: Int,
    val json: String,
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
        } catch (
            // Generic ON PURPOSE (class KDoc: the debugging log NEVER fails an execution).
            // The narrower DataAccessException catch let a JsonProcessingException from an
            // unserializable payload escape and kill the run (T36); enumerating types here
            // re-creates that hole for the next unexpected one.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("SSE event log append failed for execution {} (replay will be incomplete).", executionId, e)
        }
    }

    /** Serializes [event] for [appendAll]; throws when the payload cannot be serialized (the caller's WARN). */
    fun entry(
        executionId: UUID,
        event: LoggedSseEvent,
    ): ReplayLogEntry = ReplayLogEntry(executionId, event.eventId, mapper.writeValueAsString(event))

    /**
     * Appends a batch — entries of any number of executions — in ONE round trip: one Lua script
     * ([APPEND_SCRIPT], `EVALSHA` after its first load) that, per execution, runs one variadic
     * `RPUSH` of its entries in order and one `PEXPIRE` (#266 B.2; [append] costs two round trips per
     * event and re-sets the TTL on every one). A script runs atomically on the server, and — unlike a
     * pipelined `MULTI`/`EXEC`, which Spring Data Redis runs on a DEDICATED Lettuce connection, a new
     * TCP connection and handshake per batch (measured: 20 batches, 20 connections, ~7 ms each) — it
     * rides the application's shared connection.
     *
     * **Throws** on failure, unlike [append]: its caller is the batching writer, which counts the
     * failure, retries the batch one entry at a time, and hands the outcome back to the emitter —
     * whose WARN is the same "replay will be incomplete" this class has always logged. A batch whose
     * reply was lost may be re-sent and land twice, which [replay] absorbs by keeping the first entry
     * per event id.
     */
    fun appendAll(entries: List<ReplayLogEntry>) {
        if (entries.isEmpty()) return
        val byExecution = entries.groupBy({ it.executionId }, { it.json })
        val keys = byExecution.keys.map(::key)
        // ARGV: the TTL, then per key (in KEYS order) its entry count followed by its entries.
        val args = ArrayList<String>(1 + byExecution.size + entries.size)
        args += RETENTION.toMillis().toString()
        byExecution.values.forEach { values ->
            args += values.size.toString()
            args += values
        }
        // RedisTemplate.execute takes the script's arguments only as varargs; the array is built once per batch.
        @Suppress("SpreadOperator")
        redis.execute(APPEND_SCRIPT, keys, *args.toTypedArray())
    }

    /**
     * The stored stream in original order, or null when the log has expired or never existed —
     * which §10.3 answers with `410`, and which the caller must distinguish from an empty list.
     *
     * Each event id is served ONCE, the first stored copy (#266): the batched append is
     * at-least-once, and a client resuming by `Last-Event-ID` must never see an event twice.
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
        return stored
            .mapNotNull { raw ->
                runCatching { mapper.readValue<LoggedSseEvent>(raw) }
                    .onFailure { log.warn("Unreadable event in the log for execution {}; skipped.", executionId, it) }
                    .getOrNull()
            }.distinctBy { it.eventId }
    }

    private fun key(executionId: UUID) = "$KEY_PREFIX$executionId"

    private companion object {
        /**
         * [appendAll]'s one round trip. `unpack` of one execution's entries is bounded by the batch
         * (`batch-max-events`, default 200) — far under Lua's stack limit.
         */
        val APPEND_SCRIPT: RedisScript<Long> =
            RedisScript.of(
                """
                local ttl = ARGV[1]
                local i = 2
                for k = 1, #KEYS do
                  local n = tonumber(ARGV[i])
                  i = i + 1
                  redis.call('RPUSH', KEYS[k], unpack(ARGV, i, i + n - 1))
                  i = i + n
                  redis.call('PEXPIRE', KEYS[k], ttl)
                end
                return #KEYS
                """.trimIndent(),
                Long::class.java,
            )

        /** module-structure §5.9 — `web`'s own keyspace, distinct from `dag`'s `dp:result` / `dp:cancel`. */
        const val KEY_PREFIX = "dp:events:"

        /** rest-api §10.3 — one hour past completion, explicitly not configurable. */
        val RETENTION: Duration = Duration.ofHours(1)
    }
}
