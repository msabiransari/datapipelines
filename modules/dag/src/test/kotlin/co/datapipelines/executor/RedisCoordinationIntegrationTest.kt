package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The two Redis-mediated coordination primitives, against a real Redis:
 * idempotency reservations (§11.2/§11.3, §12.1 row 1) and cross-instance cancel flags (§8.3.1).
 *
 * Both claims are about **atomicity across processes**, which no in-memory stand-in can establish.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisCoordinationIntegrationTest {
    private val redis: StringRedisTemplate = RedisSupport.template()

    @BeforeEach
    fun setUp() {
        RedisSupport.flush(redis)
    }

    // ------------------------------------------------------------ idempotency

    @Test
    fun `the first request wins the key and a retry gets the original execution id`() {
        val store = RedisIdempotencyStore(redis)
        val user = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        store.reserve(user, "key-1", HASH, first, TTL) shouldBe IdempotencyOutcome.Reserved(first)
        // Same key, same request body → the original execution, not a second run (§11.2).
        store.reserve(user, "key-1", HASH, second, TTL) shouldBe IdempotencyOutcome.Existing(first)
    }

    @Test
    fun `the same key with a different request body is refused`() {
        val store = RedisIdempotencyStore(redis)
        val user = UUID.randomUUID()
        store.reserve(user, "key-2", HASH, UUID.randomUUID(), TTL)

        shouldThrow<DatapipelinesException> {
            store.reserve(user, "key-2", "a-different-hash", UUID.randomUUID(), TTL)
        }.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED
    }

    @Test
    fun `the key is scoped per user, so two users may use the same header value`() {
        val store = RedisIdempotencyStore(redis)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val execA = UUID.randomUUID()
        val execB = UUID.randomUUID()

        store.reserve(a, "shared", HASH, execA, TTL) shouldBe IdempotencyOutcome.Reserved(execA)
        store.reserve(b, "shared", HASH, execB, TTL) shouldBe IdempotencyOutcome.Reserved(execB)
    }

    @Test
    fun `simultaneous submissions of one key admit exactly one execution`() {
        // §12.1 row 1: "Redis atomic SETNX — first wins, second returns the in-flight execution_id".
        // A read-then-write pre-check could not do this: both racers would read "absent".
        val store = RedisIdempotencyStore(redis)
        val user = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(RACERS)
        try {
            val outcomes =
                pool
                    .invokeAll(
                        (1..RACERS).map {
                            Callable { store.reserve(user, "race", HASH, UUID.randomUUID(), TTL) }
                        },
                    ).map { it.get(10, TimeUnit.SECONDS) }

            val reserved = outcomes.filterIsInstance<IdempotencyOutcome.Reserved>()
            reserved.size shouldBe 1
            // Every loser is pointed at the winner's execution — never at its own.
            outcomes
                .filterIsInstance<IdempotencyOutcome.Existing>()
                .all { it.executionId == reserved.single().executionId }
                .shouldBeTrue()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the reservation carries the configured TTL`() {
        val store = RedisIdempotencyStore(redis)
        val user = UUID.randomUUID()
        store.reserve(user, "ttl-key", HASH, UUID.randomUUID(), TTL)

        val key = "idem:$user:${IdempotencyKeys.hash("ttl-key")}"
        (redis.getExpire(key) in 1..TTL).shouldBeTrue()
        // The raw header value is hashed, never stored as a key segment.
        redis.hasKey("idem:$user:ttl-key") shouldBe false
    }

    // ------------------------------------------------------------ cancel flags

    @Test
    fun `release deletes the matching reservation and the same key can reserve again`() {
        val store = RedisIdempotencyStore(redis)
        val user = UUID.randomUUID()
        val first = UUID.randomUUID()
        val next = UUID.randomUUID()
        store.reserve(user, "release", HASH, first, TTL)
        val key = "idem:$user:${IdempotencyKeys.hash("release")}"
        // Pin the wire field the Lua comparison reads, independently of the Record class.
        ExecutorJson.mapper
            .readTree(redis.opsForValue().get(key))
            .path("executionId")
            .asText() shouldBe first.toString()

        store.release(user, "release", first) shouldBe true
        redis.hasKey(key) shouldBe false
        store.reserve(user, "release", HASH, next, TTL) shouldBe IdempotencyOutcome.Reserved(next)
    }

    @Test
    fun `a delayed release keeps a newer reservation and its TTL`() {
        val store = RedisIdempotencyStore(redis)
        val user = UUID.randomUUID()
        val old = UUID.randomUUID()
        val newer = UUID.randomUUID()
        store.reserve(user, "release", HASH, old, TTL)
        store.release(user, "release", old) shouldBe true
        store.reserve(user, "release", HASH, newer, TTL)
        val key = "idem:$user:${IdempotencyKeys.hash("release")}"
        val record = redis.opsForValue().get(key)

        store.release(user, "release", old) shouldBe false
        redis.opsForValue().get(key) shouldBe record
        (redis.getExpire(key) in 1..TTL).shouldBeTrue()
        store.reserve(user, "release", HASH, UUID.randomUUID(), TTL) shouldBe IdempotencyOutcome.Existing(newer)
    }

    @Test
    fun `release of an absent key is false`() {
        RedisIdempotencyStore(redis).release(UUID.randomUUID(), "absent", UUID.randomUUID()) shouldBe false
    }

    @Test
    fun `release cannot delete another user's reservation with the same key and execution id`() {
        val store = RedisIdempotencyStore(redis)
        val owner = UUID.randomUUID()
        val execution = UUID.randomUUID()
        store.reserve(owner, "shared", HASH, execution, TTL)

        store.release(UUID.randomUUID(), "shared", execution) shouldBe false
        store.reserve(owner, "shared", HASH, UUID.randomUUID(), TTL) shouldBe IdempotencyOutcome.Existing(execution)
    }

    @Test
    fun `a flag written by one instance is readable by another and expires on its own`() {
        val writer = RedisCancellationFlags(redis)
        val reader = RedisCancellationFlags(redis) // a different instance's view of the same Redis
        val executionId = UUID.randomUUID()

        reader.read(executionId).shouldBeNull()

        writer.request(executionId, AbortReason.CANCELLED, TTL)

        reader.read(executionId) shouldBe AbortReason.CANCELLED
        (redis.getExpire("dp:cancel:$executionId") in 1..TTL).shouldBeTrue()
    }

    @Test
    fun `every abort reason round-trips through its wire value`() {
        val flags = RedisCancellationFlags(redis)

        AbortReason.entries.forEach { reason ->
            val id = UUID.randomUUID()
            flags.request(id, reason, TTL)
            flags.read(id) shouldBe reason
            redis.opsForValue().get("dp:cancel:$id") shouldBe reason.wire
        }
    }

    @Test
    fun `clear drops the flag and an unknown wire value reads as no cancellation`() {
        val flags = RedisCancellationFlags(redis)
        val executionId = UUID.randomUUID()
        flags.request(executionId, AbortReason.SHUTDOWN, TTL)

        flags.clear(executionId)
        flags.read(executionId).shouldBeNull()

        // A value this version does not know must not be guessed at — an unrecognized reason is
        // "no cancellation", never a default like CANCELLED.
        redis.opsForValue().set("dp:cancel:$executionId", "from_a_future_version")
        flags.read(executionId).shouldBeNull()
    }

    private companion object {
        const val TTL = 600L
        const val RACERS = 16
        val HASH = IdempotencyKeys.requestHash(UUID.randomUUID(), 1, """{"p":1}""")
    }
}
