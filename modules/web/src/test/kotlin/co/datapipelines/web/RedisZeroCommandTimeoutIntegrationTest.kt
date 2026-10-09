package co.datapipelines.web

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.lettuce.core.RedisCommandTimeoutException
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Why `RedisRules.checkRedisTimeoutBounds` refuses `datapipelines.redis.command-timeout: 0s`
 * (#488), shown rather than cited. Lettuce 6.6 uses the one configured timeout twice:
 *
 *  - as the COMMAND bound, where zero means NO bound (`Futures.awaitOrCancel` waits only when
 *    `timeout > 0`; `CommandExpiryWriter` skips expiry when `timeout <= 0`). Against a Redis that
 *    has gone away, a `GET` on a zero-timeout client is still waiting when the outer deadline
 *    passes, while the same `GET` on the shipped 2 s client fails inside its bound — the stall #482
 *    removed, and the one value an operator could have used to bring it back without a word;
 *  - as the connection HANDSHAKE deadline (`ConnectionBuilder` hands `RedisURI.timeout` to
 *    `RedisHandshakeHandler`), where zero means NOW: the guard fires on the client timer's next tick,
 *    so a handshake slower than that tick fails ("Connection initialization timed out after no
 *    timeout"). A warm local handshake usually wins (30 of 30 in the lane's measurement); the first
 *    connection of a cold test JVM lost once. Either way `0s` is a broken client, never a choice.
 *
 * Both clients in the first case connect BEFORE the server stops, the production shape: the outage
 * hits a live connection, Lettuce reconnects in the background and buffers the command, and only
 * the command timeout decides when the caller hears. The zero-timeout factories deliberately pass
 * their own client configuration — `TestRedisTimeoutParityTest`'s sweep requires one on every
 * factory, and these are the counterexample the shipped bounds exist to rule out.
 */
class RedisZeroCommandTimeoutIntegrationTest {
    @Test
    fun `a zero command timeout waits without a bound on a stopped Redis while the shipped bound fails within it`() {
        val disposable = TestRedis.disposable()
        val shipped = disposable.template.connectionFactory as LettuceConnectionFactory
        val unbounded = zeroTimeoutFactory(shipped.standaloneConfiguration)
        val callers = Executors.newFixedThreadPool(2) { task -> Thread(task, "redis-zero-timeout-caller").apply { isDaemon = true } }
        try {
            val zero = StringRedisTemplate(unbounded).apply { afterPropertiesSet() }
            connectDespiteTheHandshakeRace(zero)
            disposable.template.opsForValue().get(KEY) shouldBe null
            disposable.stopServer()

            val startedAt = System.nanoTime()
            val bounded =
                callers.submit(
                    Callable {
                        val failure = runCatching { disposable.template.opsForValue().get(KEY) }.exceptionOrNull()
                        failure to elapsedMs(startedAt)
                    },
                )
            val waiting = callers.submit(Callable { zero.opsForValue().get(KEY) })

            val (failure, boundedMs) = bounded.get(OUTER_DEADLINE_MS, TimeUnit.MILLISECONDS)
            generateSequence(failure) { it.cause }.any { it is RedisCommandTimeoutException } shouldBe true
            boundedMs shouldBeGreaterThanOrEqual TestRedis.COMMAND_TIMEOUT.toMillis()
            boundedMs shouldBeLessThan OUTER_DEADLINE_MS

            TimeUnit.MILLISECONDS.sleep((OUTER_DEADLINE_MS - elapsedMs(startedAt)).coerceAtLeast(0))
            val zeroMs = elapsedMs(startedAt)
            waiting.isDone shouldBe false
            println(
                "#488: stopped Redis — 2 s client failed in ${boundedMs}ms; 0 s client still pending at ${zeroMs}ms " +
                    "(outer deadline $OUTER_DEADLINE_MS ms)",
            )
        } finally {
            unbounded.destroy()
            callers.shutdownNow()
            disposable.close()
        }
    }

    @Test
    fun `a zero timeout is also the handshake deadline - a server that never answers fails the connect at once`() {
        // A listening socket nobody reads: the kernel completes the TCP connect, the HELLO is never
        // answered, so the handshake can only end by its deadline. Deterministic, no container.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val unbounded = zeroTimeoutFactory(RedisStandaloneConfiguration(silent.inetAddress.hostAddress, silent.localPort))
            try {
                val startedAt = System.nanoTime()
                val failure = runCatching { StringRedisTemplate(unbounded).apply { afterPropertiesSet() }.opsForValue().get(KEY) }
                val failedMs = elapsedMs(startedAt)
                val root = generateSequence(failure.exceptionOrNull()) { it.cause }.last()
                root.message.orEmpty() shouldContain "Connection initialization timed out after no timeout"
                failedMs shouldBeLessThan TestRedis.COMMAND_TIMEOUT.toMillis()
                println("#488: silent server — 0 s client's handshake failed in ${failedMs}ms")
            } finally {
                unbounded.destroy()
            }
        }
    }

    private fun zeroTimeoutFactory(config: RedisStandaloneConfiguration) =
        LettuceConnectionFactory(config, LettuceClientConfiguration.builder().commandTimeout(Duration.ZERO).build())
            .apply { afterPropertiesSet() }

    /**
     * The zero-deadline handshake races the client timer's next tick (see the class KDoc); a lost race
     * leaves the shared connection unset and the next call connects afresh. The case under test is
     * the COMMAND half, so it needs a connection, however many handshakes that takes.
     */
    private fun connectDespiteTheHandshakeRace(template: StringRedisTemplate) {
        var lost: Throwable? = null
        repeat(HANDSHAKE_ATTEMPTS) { attempt ->
            val result = runCatching { template.opsForValue().get(KEY) }
            if (result.isSuccess) {
                println("#488: zero-timeout client connected on handshake attempt ${attempt + 1}")
                return
            }
            lost = result.exceptionOrNull()
        }
        error("no zero-timeout handshake succeeded in $HANDSHAKE_ATTEMPTS attempts: $lost")
    }

    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        const val KEY = "dp:488:zero-timeout"

        /** One second past the shipped 2 s command bound: the zero-timeout caller must outlive it. */
        const val OUTER_DEADLINE_MS = 3_000L

        /** Each lost handshake costs at most one timer tick (100 ms); ten is a second, not a wait. */
        const val HANDSHAKE_ATTEMPTS = 10
    }
}
