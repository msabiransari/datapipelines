package co.datapipelines.executor

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID

/**
 * The cross-instance half of a dashboard refresh's abort (#10 L2, the implementation spec's §8.4 as corrected by §18
 * premise 6) — [CancellationFlags]' shape, one level up.
 *
 * The executor's cancel flag (`dp:cancel:{execution_id}`) is per EXECUTION, so it reaches a running source but nothing
 * that belongs to the REFRESH itself: a pending transform, a source not yet started, the stream. The abort route lands
 * on an arbitrary instance while the refresh runs on another; it writes `dp:refresh-abort:{refresh_id}` here and
 * returns 202, and the OWNING instance's refresh coroutine reads it at every stage boundary and ends ABORTED with every
 * not-yet-started source skipped. Worst-case latency is one poll; the common same-instance abort never waits for one.
 *
 * The key expires by itself (the refresh's own maximum deadline plus a grace) — a flag whose refresh never read it
 * cannot outlive the refresh and abort a LATER refresh that reused the id (a refresh id is single-use anyway).
 */
interface RefreshAbortFlags {
    /** Writes the flag for [refreshId], expiring after [ttlSeconds]. */
    fun request(
        refreshId: UUID,
        ttlSeconds: Long,
    )

    /** True while the flag is set. A Redis fault reads as false: "could not read" is not "abort" (the next poll retries). */
    fun isRequested(refreshId: UUID): Boolean

    /** Drops the flag — the owning instance's cleanup once the refresh is terminal. */
    fun clear(refreshId: UUID)
}

/** Redis-backed [RefreshAbortFlags]. `dag` is one of exactly two modules allowed to talk to Redis. */
class RedisRefreshAbortFlags(
    private val redis: StringRedisTemplate,
) : RefreshAbortFlags {
    override fun request(
        refreshId: UUID,
        ttlSeconds: Long,
    ) {
        redis.opsForValue().set(key(refreshId), FLAG_VALUE, Duration.ofSeconds(ttlSeconds))
    }

    @Suppress("SwallowedException")
    override fun isRequested(refreshId: UUID): Boolean =
        try {
            redis.opsForValue().get(key(refreshId)) != null
        } catch (e: DataAccessException) {
            LOG.warn("event=dashboard.refresh_abort_flag_unreadable refresh_id={} error={}", refreshId, e.javaClass.simpleName)
            false
        }

    @Suppress("SwallowedException")
    override fun clear(refreshId: UUID) {
        try {
            redis.delete(key(refreshId))
        } catch (e: DataAccessException) {
            // The key carries a TTL, so a failed delete expires on its own.
            LOG.warn("event=dashboard.refresh_abort_flag_not_cleared refresh_id={} error={}", refreshId, e.javaClass.simpleName)
        }
    }

    private fun key(refreshId: UUID) = "$KEY_PREFIX$refreshId"

    private companion object {
        const val KEY_PREFIX = "dp:refresh-abort:"
        const val FLAG_VALUE = "1"
        val LOG = LoggerFactory.getLogger(RedisRefreshAbortFlags::class.java)
    }
}
