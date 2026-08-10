package co.datapipelines.executor

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID

/**
 * The cross-instance half of cancellation (dag-executor.md §8.3.1).
 *
 * The standard deployment has no sticky sessions, so `DELETE /api/v1/executions/{id}` lands on
 * an arbitrary instance while the execution runs on another. The receiving instance writes
 * `dp:cancel:{execution_id}` here and returns `204`; the **executing** instance reads it on its
 * poll tick and at node boundaries and then runs the full local cancel (§8.3.2) — statements
 * included, since only it holds them.
 *
 * Worst-case latency is therefore about one poll interval
 * (`datapipelines.sse.heartbeat-interval-seconds`); the common same-instance case cancels
 * immediately through the registry and never waits for a poll. Push-based fan-out (Redis
 * pub/sub) is a ROADMAP refinement, not needed for correctness.
 */
interface CancellationFlags {
    /** Writes the flag for [executionId] with a TTL of [ttlSeconds] (the execution timeout). */
    fun request(
        executionId: UUID,
        reason: AbortReason,
        ttlSeconds: Long,
    )

    /** The requested abort reason, or null when no flag is set (or Redis could not be read). */
    fun read(executionId: UUID): AbortReason?

    /** Drops the flag — the executing instance's cleanup once the execution is terminal. */
    fun clear(executionId: UUID)
}

/** Redis-backed [CancellationFlags]. `dag` is one of exactly two modules allowed to talk to Redis. */
class RedisCancellationFlags(
    private val redis: StringRedisTemplate,
) : CancellationFlags {
    override fun request(
        executionId: UUID,
        reason: AbortReason,
        ttlSeconds: Long,
    ) {
        redis.opsForValue().set(key(executionId), reason.wire, Duration.ofSeconds(ttlSeconds))
    }

    /**
     * A Redis fault while polling must not fail a running execution: this is a *cancellation*
     * check, and "could not read the flag" is not "cancel". The next tick retries.
     */
    @Suppress("SwallowedException")
    override fun read(executionId: UUID): AbortReason? =
        try {
            AbortReason.fromWireOrNull(redis.opsForValue().get(key(executionId)))
        } catch (e: DataAccessException) {
            LOG.warn("Could not read the cancel flag for execution {}: {}", executionId, e.message)
            null
        }

    @Suppress("SwallowedException")
    override fun clear(executionId: UUID) {
        try {
            redis.delete(key(executionId))
        } catch (e: DataAccessException) {
            // The key carries a TTL, so a failed delete expires on its own; failing an otherwise
            // successful execution's cleanup over it would be strictly worse.
            LOG.warn("Could not clear the cancel flag for execution {}: {}", executionId, e.message)
        }
    }

    private fun key(executionId: UUID) = "$KEY_PREFIX$executionId"

    private companion object {
        /** §8.3.1 — the key `DELETE /executions/{id}` writes and the executing instance polls. */
        const val KEY_PREFIX = "dp:cancel:"
        val LOG = LoggerFactory.getLogger(RedisCancellationFlags::class.java)
    }
}

/**
 * The entry point every cancellation trigger calls (dag-executor.md §8.3): the REST
 * `DELETE /api/v1/executions/{id}`, the SSE layer's disconnect-grace timer, and the shutdown
 * hook.
 *
 * Both paths run, in this order: the flag is written first so that an execution running on
 * another instance is reached, then the local registry is tried so that the common
 * same-instance case cancels with no poll latency at all.
 */
class ExecutionCancellationService(
    private val registry: CancellationRegistry,
    private val flags: CancellationFlags,
    private val config: ExecutorConfig,
) {
    /**
     * Requests cancellation of [executionId].
     *
     * @return true when this instance was running the execution and cancelled it locally; false
     *   when it was not — the flag is written either way, so false means "handed to whichever
     *   instance owns it", not "failed".
     */
    fun cancel(
        executionId: UUID,
        reason: AbortReason,
    ): Boolean {
        flags.request(executionId, reason, config.executionTimeoutSeconds)
        return registry.cancel(executionId, reason)
    }

    /** The shutdown drain (§8.3): every execution on **this** instance, cancelled locally. */
    fun cancelAllLocal() {
        registry.cancelAll(AbortReason.SHUTDOWN)
    }
}
