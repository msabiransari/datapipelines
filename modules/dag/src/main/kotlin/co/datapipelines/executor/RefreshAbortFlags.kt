package co.datapipelines.executor

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
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

/** A start that is on its way but has no `RUNNING` row yet (#356): who started it, where, for which dashboard. */
data class RefreshStartMarker(
    val workspaceId: UUID,
    val refreshId: UUID,
    val principalUserId: UUID,
    val instanceId: UUID,
    val dashboardId: UUID,
)

/** The outcome of [RefreshStartMarkers.register]. */
enum class StartMarkerRegistration {
    /** The marker is written — or the store could not be reached and the start proceeds unmarked (fail-open). */
    REGISTERED,

    /** The principal already holds the per-principal limit of in-flight starts: the start is refused. */
    AT_BOUND,

    /**
     * A start of this refresh id is ALREADY in flight: a replayed request (or a guessed id), never a second
     * owner. The first start's marker is untouched, and nothing of the replay reaches the bound set — so the
     * replay's exit cannot delete the first start's entry (the 356 merge's security pass).
     */
    ALREADY_IN_FLIGHT,
}

/**
 * The in-flight starts of this deployment — the pre-row half of an abort (#356). A refresh id is CLIENT-minted, so an
 * abort can arrive while `startRefresh` is still evaluating or waiting for admission, before `insertRunning` creates
 * the row an abort is judged against. The start registers itself here as soon as the dashboard is resolved, and the
 * abort route consults it when — and ONLY when — no row exists: a matching marker (same workspace, same dashboard,
 * the caller's own principal AND client instance, or `execution.cancel_all`) is the proof that the caller owns the
 * id, so the abort intent is recorded under it and answered 202; the engine re-reads the flag at job start and ends
 * the refresh ABORTED before any source runs.
 *
 * The store is transient and TTL'd by design — a start's own exit removes its marker (the row is then the only
 * authority), and a start whose process died expires like a flag whose refresh never read it. It is Redis, not
 * JVM-local, because an abort may land on any instance while the start runs on another — the same reason
 * [RefreshAbortFlags] is.
 */
interface RefreshStartMarkers {
    /**
     * Registers an in-flight start, expiring after [ttlSeconds]. [StartMarkerRegistration.AT_BOUND] when the
     * principal already holds [perPrincipalLimit] markers — the start is refused (the per-user stream cap refuses
     * it soon anyway; refusing here keeps the bound exact instead of dropping an older start's abort
     * authorization); [StartMarkerRegistration.ALREADY_IN_FLIGHT] when a start of [RefreshStartMarker.refreshId]
     * is already marked — the marker is written once and never overwritten.
     */
    fun register(
        marker: RefreshStartMarker,
        ttlSeconds: Long,
        perPrincipalLimit: Int,
    ): StartMarkerRegistration

    /** The marker for [refreshId] in [workspaceId], or null — no such start, or its exit already removed it. */
    fun find(
        workspaceId: UUID,
        refreshId: UUID,
    ): RefreshStartMarker?

    /**
     * Removes the marker — `startRefresh`'s own cleanup on EVERY exit (the row, or the refusal, speaks from there).
     * [principalUserId] is the STARTING principal (the caller knows its own): the per-principal bound counts only
     * starts still in flight.
     */
    fun clear(
        workspaceId: UUID,
        principalUserId: UUID,
        refreshId: UUID,
    )
}

/**
 * Redis-backed [RefreshStartMarkers]: the marker at `dp:refresh-start:{workspace}:{refresh}`, the per-principal bound
 * in a sorted set beside it (`dp:refresh-starts:{workspace}:{principal}` — the count of starts still in flight).
 *
 * #365: the members carry their OWN expiry — each is scored by its start's expiry instant, and every count first
 * prunes the members whose expiry has passed, so a start whose `clear` never ran stops consuming a bound slot the
 * moment its TTL dies (the set's own TTL is garbage collection, never the accounting). The write is ONE Lua step
 * (ZADD with the score, then PEXPIRE on the same key), so no command sequence can leave the bound key TTL-less —
 * a later command throwing happens after a key that already carries its TTL. The key changed type SET→ZSET with
 * the fix; an overlapping pre-restart instance's SET writes fail WRONGTYPE and land in the same fail-open catch
 * every other store fault takes (the start proceeds un-marked, self-healing once the old instance exits).
 */
class RedisRefreshStartMarkers(
    private val redis: StringRedisTemplate,
) : RefreshStartMarkers {
    override fun register(
        marker: RefreshStartMarker,
        ttlSeconds: Long,
        perPrincipalLimit: Int,
    ): StartMarkerRegistration =
        try {
            val key = markerKey(marker.workspaceId, marker.refreshId)
            val ttl = Duration.ofSeconds(ttlSeconds)
            // The marker is written ONCE (SET NX), and FIRST: a replayed start of an id already in flight never
            // overwrites the first start's owner and never touches the bound set — a same-principal replay would
            // otherwise share the set member, and its rollback would take the first start's slot with it.
            val written = redis.opsForValue().setIfAbsent(key, value(marker), ttl) ?: false
            if (!written) {
                StartMarkerRegistration.ALREADY_IN_FLIGHT
            } else {
                val set = principalSetKey(marker.workspaceId, marker.principalUserId)
                // ONE atomic step: the member lands WITH its own expiry as the score and the key with its TTL —
                // the #365 rule that no key this store writes can exist TTL-less, whatever fails after.
                redis.execute(
                    REGISTER_SCRIPT,
                    listOf(set),
                    (System.currentTimeMillis() + ttl.toMillis()).toString(),
                    marker.refreshId.toString(),
                    ttl.toMillis().toString(),
                )
                // The bound counts only starts that can still be in flight: the members whose expiry
                // passed (a `clear` that never ran) are pruned before the count, never after it.
                redis.opsForZSet().removeRangeByScore(set, Double.NEGATIVE_INFINITY, System.currentTimeMillis().toDouble())
                if ((redis.opsForZSet().size(set) ?: 0L) > perPrincipalLimit) {
                    // The bound is the point: the NEWEST start is the refused one, so an older start never loses
                    // its abort authorization. The set entry AND the marker of a refused start go with it.
                    redis.opsForZSet().remove(set, marker.refreshId.toString())
                    redis.delete(key)
                    StartMarkerRegistration.AT_BOUND
                } else {
                    StartMarkerRegistration.REGISTERED
                }
            }
        } catch (e: DataAccessException) {
            // A store fault must not refuse the start (the flags' own posture): the start proceeds un-marked, and
            // a pre-row abort of it answers 404 exactly as before this store existed — never a false grant.
            LOG.warn("event=dashboard.refresh_start_marker_unwritable refresh_id={} error={}", marker.refreshId, e.javaClass.simpleName)
            StartMarkerRegistration.REGISTERED
        }

    @Suppress("SwallowedException")
    override fun find(
        workspaceId: UUID,
        refreshId: UUID,
    ): RefreshStartMarker? =
        try {
            redis.opsForValue().get(markerKey(workspaceId, refreshId))?.let { parse(workspaceId, refreshId, it) }
        } catch (e: DataAccessException) {
            // "Could not read" is "no marker": an authorization is never granted by a fault.
            LOG.warn("event=dashboard.refresh_start_marker_unreadable refresh_id={} error={}", refreshId, e.javaClass.simpleName)
            null
        }

    @Suppress("SwallowedException")
    override fun clear(
        workspaceId: UUID,
        principalUserId: UUID,
        refreshId: UUID,
    ) {
        try {
            redis.delete(markerKey(workspaceId, refreshId))
            redis.opsForZSet().remove(principalSetKey(workspaceId, principalUserId), refreshId.toString())
        } catch (e: DataAccessException) {
            // Both keys carry a TTL, so a failed cleanup expires on its own.
            LOG.warn("event=dashboard.refresh_start_marker_not_cleared refresh_id={} error={}", refreshId, e.javaClass.simpleName)
        }
    }

    private fun value(marker: RefreshStartMarker): String = "${marker.principalUserId} ${marker.instanceId} ${marker.dashboardId}"

    /** The stored `principal instance dashboard` triple; a value that does not parse reads as no marker. */
    private fun parse(
        workspaceId: UUID,
        refreshId: UUID,
        raw: String,
    ): RefreshStartMarker? {
        val parts = raw.split(' ')
        if (parts.size != VALUE_PARTS) return null
        return runCatching {
            RefreshStartMarker(
                workspaceId = workspaceId,
                refreshId = refreshId,
                principalUserId = UUID.fromString(parts[0]),
                instanceId = UUID.fromString(parts[1]),
                dashboardId = UUID.fromString(parts[2]),
            )
        }.getOrNull()
    }

    private fun markerKey(
        workspaceId: UUID,
        refreshId: UUID,
    ) = "$KEY_PREFIX$workspaceId:$refreshId"

    private fun principalSetKey(
        workspaceId: UUID,
        principalUserId: UUID,
    ) = "$SET_PREFIX$workspaceId:$principalUserId"

    private companion object {
        const val KEY_PREFIX = "dp:refresh-start:"
        const val SET_PREFIX = "dp:refresh-starts:"
        const val VALUE_PARTS = 3
        val LOG = LoggerFactory.getLogger(RedisRefreshStartMarkers::class.java)

        /**
         * #365 — the bound member's write as ONE step (ZADD the member scored by its expiry instant,
         * PEXPIRE the key): either both land or neither, so the TTL-less window a thrown EXPIRE used
         * to leave behind cannot exist. Scores are epoch millis; the prune reads the same clock domain.
         */
        val REGISTER_SCRIPT: RedisScript<Long> =
            RedisScript.of(
                """
                redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
                return 1
                """.trimIndent(),
                Long::class.java,
            )
    }
}
