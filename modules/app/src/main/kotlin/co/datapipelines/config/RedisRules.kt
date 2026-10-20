package co.datapipelines.config

import org.springframework.boot.convert.DurationStyle
import java.time.Duration

/**
 * §7 / §3.1 (#488) — the Redis client bounds. Owns its file like `RequestLimitsRules` and
 * `ScriptRules` — the validator's companion is the registry, not the home, of the §3.x
 * families, and this keeps the companion under the size the static analysis allows.
 */
internal object RedisRules {
    /** The ceiling: Lettuce's own command default — what an operator may deliberately restore, never more. */
    private val TIMEOUT_MAX: Duration = Duration.ofSeconds(60)

    /**
     * The two Redis client bounds must parse as a Duration in `(0, 60 s]`. RedisClientProperties
     * binds the operator keys and RedisClientConfiguration builds Lettuce from that one binding
     * (#500), so these checks judge exactly the values the client reads.
     *
     * Zero is the silent case: Lettuce 6.6 waits without a bound when the command timeout is
     * `<= 0` (`Futures.awaitOrCancel`, `CommandExpiryWriter`), restoring the outage stall #482
     * removed — `RedisZeroCommandTimeoutIntegrationTest` shows it. Blank values are refused;
     * unset uses the yml default. Lettuce's own defaults are 60 s per command and 10 s to
     * connect. Parsed untrimmed with Boot's own `DurationStyle`, so this
     * accepts exactly what the binder does.
     */
    fun checkRedisTimeoutBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        listOf(
            "datapipelines.redis.command-timeout" to snapshot.redisCommandTimeout,
            "datapipelines.redis.connect-timeout" to snapshot.redisConnectTimeout,
        ).forEach { (key, raw) ->
            if (raw == null) return@forEach
            val parsed = runCatching { DurationStyle.detectAndParse(raw) }.getOrNull()
            if (parsed == null || parsed <= Duration.ZERO || parsed > TIMEOUT_MAX) {
                violations += "$key is '$raw'; §3.1 requires a duration above 0 and at most 60s."
            }
        }
    }
}
