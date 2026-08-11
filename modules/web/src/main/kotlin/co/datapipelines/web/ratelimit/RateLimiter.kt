package co.datapipelines.web.ratelimit

import co.datapipelines.web.config.RateLimitProperties
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The IETF draft rate-limit response headers (rest-api.md §12.2). */
object RateLimitHeaders {
    const val LIMIT: String = "RateLimit-Limit"
    const val REMAINING: String = "RateLimit-Remaining"
    const val RESET: String = "RateLimit-Reset"
}

/**
 * One limiter decision (rest-api §12).
 *
 * [limit] / [remaining] / [resetEpochSeconds] populate the §12.2 headers on **every** response,
 * not just on a rejection — the headers are what lets a well-behaved client pace itself instead of
 * discovering the limit by being 429'd.
 */
data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Long,
    val remaining: Long,
    val resetEpochSeconds: Long,
    val retryAfterSeconds: Long,
    val window: String,
)

/** The per-user request limiter (rest-api §12.1). */
fun interface RateLimiter {
    /** Consumes one request's budget for [userId]. */
    fun consume(userId: UUID): RateLimitDecision
}

/**
 * The Redis-backed per-user limiter (rest-api §12.1, module-structure §5.9).
 *
 * ## Why Redis and not an in-process counter
 * §12.1 is explicit: "Counters are tracked in Redis, so limits hold across instances". A local
 * counter would multiply every limit by the instance count — a deployment scaled to four pods
 * would silently allow 400 rps against a documented 100.
 *
 * ## The algorithm
 * Two fixed windows per user, one second and one minute, each an `INCR` on a key whose name
 * carries the window's start epoch. `INCR` on a missing key creates it at 1, so the first request
 * of a window and the expiry are set in the same round trip; the key then dies on its own and no
 * sweep is needed. Both windows must pass — the per-second limit bounds bursts, the per-minute
 * limit bounds sustained load, and checking only one leaves the other unpoliced.
 *
 * A fixed window admits up to 2× the limit across a window boundary. That is the documented
 * trade-off of the simplest correct shared limiter; a sliding-log would cost one Redis list per
 * user per window to remove a factor-of-two edge on a limit that is itself a round number.
 *
 * ## Redis unavailable
 * **Fail open, loudly.** A limiter that fails closed converts a Redis blip into a total outage of
 * an API whose actual data path (results) has its own Redis failure mode with its own error code.
 * The failure is logged at WARN every time, never swallowed silently (rules/02).
 */
class RedisRateLimiter(
    private val redis: StringRedisTemplate,
    private val properties: RateLimitProperties,
    private val clock: () -> Instant = Instant::now,
) : RateLimiter {
    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)

    override fun consume(userId: UUID): RateLimitDecision {
        val now = clock()
        val second = hit(userId, SECOND_WINDOW, now.epochSecond, properties.requestsPerSecond, SECOND_TTL)
        val minute = hit(userId, MINUTE_WINDOW, now.epochSecond / SECONDS_PER_MINUTE, properties.requestsPerMinute, MINUTE_TTL)
        // Both windows are always consumed — short-circuiting would leave the minute counter
        // under-counting exactly the bursty traffic it exists to bound. The reported decision is
        // the binding one: a rejection first, then whichever window has less headroom. On a tie
        // the shorter window wins, because its reset is sooner and a client told to wait 60s for a
        // one-second burst limit backs off an order of magnitude too far.
        return when {
            !second.allowed -> second
            !minute.allowed -> minute
            second.remaining <= minute.remaining -> second
            else -> minute
        }
    }

    private fun hit(
        userId: UUID,
        window: String,
        bucket: Long,
        limit: Long,
        ttl: Duration,
    ): RateLimitDecision {
        val key = "$KEY_PREFIX$userId:$window:$bucket"
        val used =
            try {
                redis.opsForValue().increment(key)?.also {
                    if (it == 1L) redis.expire(key, ttl.seconds, TimeUnit.SECONDS)
                } ?: return failOpen(limit, window, bucket, ttl, reason = "no reply")
            } catch (e: DataAccessException) {
                log.warn("Rate limiter unavailable (Redis); failing OPEN for user {} on the {} window.", userId, window, e)
                return failOpen(limit, window, bucket, ttl, reason = e.javaClass.simpleName)
            }
        val resetAt = (bucket + 1) * ttl.seconds
        return RateLimitDecision(
            allowed = used <= limit,
            limit = limit,
            remaining = (limit - used).coerceAtLeast(0),
            resetEpochSeconds = resetAt,
            retryAfterSeconds = (resetAt - clock().epochSecond).coerceAtLeast(1),
            window = window,
        )
    }

    private fun failOpen(
        limit: Long,
        window: String,
        bucket: Long,
        ttl: Duration,
        reason: String,
    ): RateLimitDecision {
        log.warn("Rate limit check skipped on the {} window ({}); request allowed.", window, reason)
        val resetAt = (bucket + 1) * ttl.seconds
        return RateLimitDecision(
            allowed = true,
            limit = limit,
            remaining = limit,
            resetEpochSeconds = resetAt,
            retryAfterSeconds = 1,
            window = window,
        )
    }

    private companion object {
        /** module-structure §5.9 — `web` owns this keyspace; `dag` owns `dp:result` / `dp:cancel`. */
        const val KEY_PREFIX = "dp:rl:"
        const val SECOND_WINDOW = "s"
        const val MINUTE_WINDOW = "m"
        const val SECONDS_PER_MINUTE = 60L
        val SECOND_TTL: Duration = Duration.ofSeconds(1)
        val MINUTE_TTL: Duration = Duration.ofSeconds(SECONDS_PER_MINUTE)
    }
}
