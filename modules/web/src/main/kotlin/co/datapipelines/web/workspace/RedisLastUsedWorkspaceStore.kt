package co.datapipelines.web.workspace

import co.datapipelines.auth.LastUsedWorkspaceStore
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID

/**
 * The Redis [LastUsedWorkspaceStore] (design §5.1): which workspace a session user last
 * worked in, so the next login stamps it as the JWT's `active_workspace`.
 *
 * Lives in `web` because Redis is talked to from `dag` and `web` only (module-structure
 * §3.1 rule 3); the port is `auth`'s and the bean is wired here, exactly the
 * [co.datapipelines.mcp.McpExecutionRunner] inversion pattern.
 *
 * **Fail open, loudly** (the [co.datapipelines.web.ratelimit.RedisRateLimiter] posture):
 * last-used is a convenience, never state of record — a Redis blip degrades a login to
 * "first membership", so a store failure is a WARN, never a failed login or request.
 */
class RedisLastUsedWorkspaceStore(
    private val redis: StringRedisTemplate,
) : LastUsedWorkspaceStore {
    private val log = LoggerFactory.getLogger(RedisLastUsedWorkspaceStore::class.java)

    override fun lastUsed(userId: UUID): String? =
        try {
            redis.opsForValue().get(key(userId))
        } catch (e: DataAccessException) {
            log.warn("Last-used workspace read failed (Redis); login falls back to first membership for user {}.", userId, e)
            null
        }

    override fun recordUsed(
        userId: UUID,
        workspaceName: String,
    ) {
        try {
            redis.opsForValue().set(key(userId), workspaceName, TTL)
        } catch (e: DataAccessException) {
            log.warn("Last-used workspace write failed (Redis) for user {}; the switch itself succeeded.", userId, e)
        }
    }

    private fun key(userId: UUID): String = "$KEY_PREFIX$userId"

    private companion object {
        /** module-structure §5.9 — `web` owns this keyspace, sibling of `dp:rl:`. */
        const val KEY_PREFIX = "dp:ws:last:"

        /** Long enough to survive a vacation, short enough that a dormant user's world can change. */
        val TTL: Duration = Duration.ofDays(30)
    }
}
