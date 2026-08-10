package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.data.redis.core.StringRedisTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What a reservation attempt found (dag-executor.md §11.2). */
sealed interface IdempotencyOutcome {
    /** This request won the key; the caller should execute. */
    data class Reserved(
        val executionId: UUID,
    ) : IdempotencyOutcome

    /**
     * The key is already held by an identical request. The caller returns **that** execution
     * instead of running a second one — including when it is still in flight (§12.1).
     */
    data class Existing(
        val executionId: UUID,
    ) : IdempotencyOutcome
}

/**
 * Client-retry deduplication (dag-executor.md §11.2/§11.3).
 *
 * Idempotency deduplicates **executions**, not streams: a retry after a disconnect attaches to
 * the original execution's record and result (if still within its TTL) but does not resume a
 * dropped SSE stream, and a retry arriving after the original was aborted gets that aborted
 * execution's status, not a fresh run.
 */
interface IdempotencyStore {
    /**
     * Atomically claims `idem:{user_id}:{key_hash}` for [executionId].
     *
     * @throws DatapipelinesException `idempotency.key_reused_for_different_request` when the key
     *   is held by a request with a different hash.
     */
    fun reserve(
        userId: UUID,
        idempotencyKey: String,
        requestHash: String,
        executionId: UUID,
        ttlSeconds: Long,
    ): IdempotencyOutcome
}

/**
 * The Redis implementation (§11.3).
 *
 * `SET … NX` is the whole concurrency story (§12.1 row 1): two clients submitting the same key at
 * the same instant both attempt the write, exactly one wins, and the loser reads the winner's
 * record and returns its `execution_id`. A read-then-write pre-check could not do this — both
 * would read "absent" and both would execute.
 */
class RedisIdempotencyStore(
    private val redis: StringRedisTemplate,
) : IdempotencyStore {
    override fun reserve(
        userId: UUID,
        idempotencyKey: String,
        requestHash: String,
        executionId: UUID,
        ttlSeconds: Long,
    ): IdempotencyOutcome {
        val key = "$KEY_PREFIX$userId:${IdempotencyKeys.hash(idempotencyKey)}"
        val record = Record(executionId, requestHash, Instant.now().plusSeconds(ttlSeconds))
        val won =
            redis
                .opsForValue()
                .setIfAbsent(key, ExecutorJson.write(record), Duration.ofSeconds(ttlSeconds)) == true
        if (won) return IdempotencyOutcome.Reserved(executionId)

        val existing =
            redis.opsForValue().get(key)?.let { ExecutorJson.mapper.readValue<Record>(it) }
                // The key expired between the failed SETNX and this read: no holder, so the caller
                // may proceed under its own execution id rather than being told it lost a race to
                // nobody.
                ?: return IdempotencyOutcome.Reserved(executionId)

        if (existing.requestHash != requestHash) throw reusedForDifferentRequest(idempotencyKey)
        return IdempotencyOutcome.Existing(existing.executionId)
    }

    private fun reusedForDifferentRequest(idempotencyKey: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED,
            message =
                "Idempotency-Key '${idempotencyKey.take(MAX_ECHOED_KEY)}' was already used for a different " +
                    "request body. Use a fresh key, or resend the original request unchanged.",
            details = mapOf("idempotency_key_suffix" to idempotencyKey.takeLast(KEY_SUFFIX_HINT)),
        )

    /** The stored value of §11.3: `{execution_id, request_hash, expires_at}`. */
    internal data class Record(
        val executionId: UUID,
        val requestHash: String,
        val expiresAt: Instant,
    )

    private companion object {
        const val KEY_PREFIX = "idem:"

        /** Reflected client input is bounded before it reaches an error message or a log. */
        const val MAX_ECHOED_KEY = 32
        const val KEY_SUFFIX_HINT = 6
    }
}

/** Hashing for the two §11.2 inputs: the key itself, and the request it is bound to. */
object IdempotencyKeys {
    /**
     * The request hash: `pipeline_id + version + parameters` (§11.2).
     *
     * The **serialized** parameters are hashed, not the map's `hashCode`: a JVM hash is neither
     * stable across processes nor collision-resistant, and this value decides whether two requests
     * are "the same".
     */
    fun requestHash(
        pipelineId: UUID,
        pipelineVersion: Int,
        parametersJson: String,
    ): String = hash("$pipelineId|$pipelineVersion|$parametersJson")

    /** SHA-256, hex-encoded. */
    fun hash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
