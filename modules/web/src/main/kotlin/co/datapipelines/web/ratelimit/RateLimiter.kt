package co.datapipelines.web.ratelimit

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.config.RateLimitProperties
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    /**
     * Why the limiter could not decide, when [allowed] is false because the limiter itself is
     * down rather than because the budget is gone (083, owner ruling 2026-09-06).
     *
     * **Null on every counted decision** — that is the whole point of the field. A refusal with a
     * reason is `rate_limit.unavailable`; a refusal without one is `rate_limit.exceeded`. Without
     * the discriminator a fail-closed outage is indistinguishable from a throttle at the filter,
     * in a log, and in a test, and the two need different responses from everyone who sees them.
     *
     * The value is a short technical token (the fault's class name, or `no reply`). It reaches
     * the log; it never reaches the response body — an unauthenticated caller learns that the
     * service refused, not which of its dependencies fell over (observability §9.2).
     */
    val reason: String? = null,
) {
    /** True when this refusal is the limiter's own failure, not the caller's budget. */
    val unavailable: Boolean get() = reason != null
}

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
 * **Fail CLOSED, once loudly** (owner ruling 2026-09-06; round 083). A limiter that fails open
 * publishes an unmetered API to anyone who can reach the Redis it depends on: the cheapest way
 * past the limit becomes "make the limiter's dependency fail", which is the opposite of what a
 * limiter is for. So a request the limiter cannot judge is refused, with its OWN catalogued code
 * ([PipelineErrorCodes.Limits.RATE_LIMIT_UNAVAILABLE]) at the same 429 — a client can tell "you
 * are throttled" from "the limiter is down" and back off accordingly.
 *
 * There is deliberately **no fail-open switch**. A toggle would be a per-deployment answer to a
 * question the contract answers once, and the only time anyone would reach for it is exactly
 * when the reasoning above applies.
 *
 * The outage is logged **once per outage, not once per request**: the WARN fires on the
 * healthy → unavailable transition and an INFO marks the recovery, so a Redis blip costs an
 * operator two lines instead of one per request at the full request rate (which is a second
 * outage of its own — the one in the log pipeline). The state is in-memory and per instance,
 * which is correct: each instance reports its own view of its own connection.
 */
class RedisRateLimiter(
    private val redis: StringRedisTemplate,
    private val properties: RateLimitProperties,
    private val clock: () -> Instant = Instant::now,
) : RateLimiter {
    private val log = LoggerFactory.getLogger(RedisRateLimiter::class.java)

    /** True while this instance is inside an outage episode — see [warnOncePerOutage]. */
    private val unavailable = AtomicBoolean(false)

    override fun consume(userId: UUID): RateLimitDecision {
        val now = clock()
        val second = hit(userId, SECOND_WINDOW, now.epochSecond, properties.requestsPerSecond, SECOND_TTL)
        val minute = hit(userId, MINUTE_WINDOW, now.epochSecond / SECONDS_PER_MINUTE, properties.requestsPerMinute, MINUTE_TTL)
        // Both windows answered from Redis: whatever outage was open is over. Closing the episode
        // here rather than inside `hit` keeps a single counted window from clearing the flag while
        // the other one is still failing — the log would then flip-flop once per request, which is
        // precisely the volume `warnOncePerOutage` exists to avoid.
        if (!second.unavailable && !minute.unavailable) recovered()

        // Both windows are always consumed — short-circuiting would leave the minute counter
        // under-counting exactly the bursty traffic it exists to bound. The reported decision is
        // the binding one: a rejection first, then whichever window has less headroom. On a tie
        // the shorter window wins, because its reset is sooner and a client told to wait 60s for a
        // one-second burst limit backs off an order of magnitude too far. A fail-closed refusal
        // carries a `reason`, so it lands in the first branch and reaches the filter as the
        // distinct `rate_limit.unavailable` rather than as a throttle.
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
                } ?: return failClosed(limit, window, bucket, ttl, reason = NO_REPLY, cause = null)
            } catch (e: DataAccessException) {
                return failClosed(limit, window, bucket, ttl, reason = e.javaClass.simpleName, cause = e)
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

    /**
     * The decision for a request the limiter could not judge: **refused**, with the reason that
     * makes it `rate_limit.unavailable` rather than `rate_limit.exceeded` at the filter.
     *
     * `remaining` is 0 and `Retry-After` is [UNAVAILABLE_RETRY_AFTER_SECONDS] — a short, honest
     * "try again shortly". A longer back-off would be a guess about someone else's outage, and a
     * `remaining` of `limit` would tell a well-behaved client it has budget it cannot spend.
     */
    private fun failClosed(
        limit: Long,
        window: String,
        bucket: Long,
        ttl: Duration,
        reason: String,
        cause: Throwable?,
    ): RateLimitDecision {
        warnOncePerOutage(reason, cause)
        val resetAt = (bucket + 1) * ttl.seconds
        return RateLimitDecision(
            allowed = false,
            limit = limit,
            remaining = 0,
            resetEpochSeconds = resetAt,
            retryAfterSeconds = UNAVAILABLE_RETRY_AFTER_SECONDS,
            window = window,
            reason = reason,
        )
    }

    /**
     * The limiter's own back-off state: one WARN on the healthy → unavailable transition, and
     * nothing more until Redis answers again.
     *
     * Per instance and in memory on purpose. The alternative — a WARN per refused request —
     * turns a Redis outage into a log outage at the full request rate, and buries the one line
     * an operator actually needs under thousands of copies of itself. [recovered] closes the
     * episode, so the log carries the outage's shape (start, end) rather than its volume.
     */
    private fun warnOncePerOutage(
        reason: String,
        cause: Throwable?,
    ) {
        if (!unavailable.compareAndSet(false, true)) return
        log.warn(
            "Rate limiter unavailable (Redis: {}); failing CLOSED — requests are refused with {} until Redis answers again.",
            reason,
            PipelineErrorCodes.Limits.RATE_LIMIT_UNAVAILABLE,
            cause,
        )
    }

    /** Closes an outage episode the first time a window is counted again. */
    private fun recovered() {
        if (unavailable.compareAndSet(true, false)) {
            log.info("Rate limiter recovered; Redis is answering again and requests are metered as normal.")
        }
    }

    private companion object {
        /** module-structure §5.9 — `web` owns this keyspace; `dag` owns `dp:result` / `dp:cancel`. */
        const val KEY_PREFIX = "dp:rl:"

        /** `INCR` answered with no value at all — a fault, not a count of zero. */
        const val NO_REPLY = "no reply"

        /** `Retry-After` on a fail-closed refusal: short, because the outage is not the caller's. */
        const val UNAVAILABLE_RETRY_AFTER_SECONDS = 1L
        const val SECOND_WINDOW = "s"
        const val MINUTE_WINDOW = "m"
        const val SECONDS_PER_MINUTE = 60L
        val SECOND_TTL: Duration = Duration.ofSeconds(1)
        val MINUTE_TTL: Duration = Duration.ofSeconds(SECONDS_PER_MINUTE)
    }
}
