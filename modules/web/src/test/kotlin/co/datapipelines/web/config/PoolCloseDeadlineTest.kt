package co.datapipelines.web.config

import ch.qos.logback.classic.Logger
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ManagedLakeGeneration
import co.datapipelines.datasources.pooling.PoolCloser
import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import co.datapipelines.web.capturingLogEvents
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CountDownLatch

/**
 * #840 — a retired pool's close deadline is its OWN: [PoolCloser.deadlineFor] at the pool's `connectionTimeout`, plus,
 * for a managed lake generation, the bound of its reader-pin release (the fourth term). Before #840 every close was
 * held to the 65 s constant, so a datasource configured with a longer `connectionTimeout` was reported stuck while
 * Hikari was still inside a wait its own settings admit.
 *
 * The closer cases are a SHORT-SCALED pair on the real [DeadlinePoolCloser] and real time (its `orTimeout` reads the
 * JDK's clock; no fake clock reaches it): the pool-independent terms are scaled from 35 s to [SCALED_FIXED_TERMS],
 * and the two pools' timeouts stand for Hikari's 30 s default and a 120 s datasource. Their deadlines are 2 s and
 * 4 s, so at 3 s the long pool's close is not yet stuck while the default one's is, and by 5 s both are. Falsified by
 * reading HikariCP's default instead of the pool's own timeout in [DeadlinePoolCloser.deadlineOf]: neither pool is
 * then stuck at 3 s.
 */
class PoolCloseDeadlineTest {
    private val released = CountDownLatch(1)

    @AfterEach
    fun release() {
        released.countDown()
    }

    @Test
    fun `the deadline is the pool's connection timeout plus the fixed terms - 65 s at the default, 155 s at 120 s`() {
        ConnectionPool.DEFAULT_CONNECTION_TIMEOUT shouldBe Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)
        PoolCloser.deadlineFor(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)) shouldBe Duration.ofSeconds(PoolCloser.DEADLINE_SECONDS)
        PoolCloser.deadlineFor(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)) shouldBe Duration.ofSeconds(DEFAULT_DEADLINE_SECONDS)
        PoolCloser.deadlineFor(Duration.ofSeconds(LONG_TIMEOUT_SECONDS)) shouldBe Duration.ofSeconds(LONG_DEADLINE_SECONDS)
        // The fourth term adds exactly itself.
        PoolCloser.deadlineFor(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS), releaseBound = Duration.ofSeconds(RELEASE_SECONDS)) shouldBe
            Duration.ofSeconds(DEFAULT_DEADLINE_SECONDS + RELEASE_SECONDS)
    }

    @Test
    fun `the closer reads each pool's own deadline - a longer timeout is not stuck where the default is`() {
        val closer = DeadlinePoolCloser(PoolLifecycleMetrics.NONE, fixedTerms = SCALED_FIXED_TERMS)
        val default = BlockedPool("default_timeout", SCALED_DEFAULT_TIMEOUT)
        val long = BlockedPool("long_timeout", SCALED_LONG_TIMEOUT)
        closer.deadlineOf(default) shouldBe Duration.ofSeconds(2)
        closer.deadlineOf(long) shouldBe Duration.ofSeconds(4)
        try {
            val handedOffAt = System.currentTimeMillis()
            val lines =
                capturingLogEvents(closerLogger) {
                    closer.close(default)
                    closer.close(long)
                    Thread.sleep(AFTER_BOTH_MILLIS)
                }.filter { it.formattedMessage.startsWith("event=datasource.pool_close_stuck") }
            val stuck = lines.map { (it.timeStamp - handedOffAt) to it.formattedMessage }
            println("POOL_CLOSE_DEADLINE ${stuck.map { (at, line) -> "+${at}ms ${line.substringBefore(" message=")}" }}")
            stuck.filter { (at, _) -> at < BETWEEN_MILLIS }.map { (_, line) -> field(line, "datasource") } shouldContainExactly
                listOf("default_timeout")
            stuck.map { (_, line) -> field(line, "datasource") } shouldContainExactly listOf("default_timeout", "long_timeout")
            stuck.map { (_, line) -> field(line, "deadline_seconds") } shouldContainExactly listOf("2", "4")
        } finally {
            released.countDown()
            closer.destroy()
        }
    }

    @Test
    fun `a managed generation's deadline adds the bound of its reader-pin release - every other pool none`() {
        val closer = DeadlinePoolCloser(PoolLifecycleMetrics.NONE, managedReleaseBound = Duration.ofSeconds(RELEASE_SECONDS))
        val plain = BlockedPool("plain", Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
        val managed = BlockedPool("managed", Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS), ManagedLakeGeneration("managed", emptyList()))
        closer.deadlineOf(plain) shouldBe Duration.ofSeconds(DEFAULT_DEADLINE_SECONDS)
        closer.deadlineOf(managed) shouldBe Duration.ofSeconds(DEFAULT_DEADLINE_SECONDS + RELEASE_SECONDS)
        closer.destroy()
    }

    @Test
    fun `the release bound is the metadata pool's checkout plus the reader statement bound - 35 s shipped`() {
        // LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS = 5, a literal here: never read back from the class under test.
        DeadlinePoolCloser.managedReleaseBound(null) shouldBe Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS + READER_STATEMENT_SECONDS)
        // The no-argument constructor is lazy: it validates and starts nothing until a connection is asked for.
        HikariDataSource().apply { connectionTimeout = Duration.ofSeconds(LONG_TIMEOUT_SECONDS).toMillis() }.use {
            DeadlinePoolCloser.managedReleaseBound(it) shouldBe Duration.ofSeconds(LONG_TIMEOUT_SECONDS + READER_STATEMENT_SECONDS)
        }
    }

    private fun field(
        line: String,
        key: String,
    ): String = line.substringAfter("$key=").substringBefore(' ')

    /** A pool whose close blocks until the test releases it, with its own `connectionTimeout`. */
    private inner class BlockedPool(
        override val name: String,
        override val connectionTimeout: Duration,
        override val managedGeneration: ManagedLakeGeneration? = null,
    ) : ConnectionPool {
        override fun leaseConnection(): Connection = error("not used")

        override fun close() {
            released.await()
        }
    }

    private companion object {
        val closerLogger = LoggerFactory.getLogger(DeadlinePoolCloser::class.java) as Logger

        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val DEFAULT_DEADLINE_SECONDS = 65L
        const val LONG_TIMEOUT_SECONDS = 120L
        const val LONG_DEADLINE_SECONDS = 155L
        const val RELEASE_SECONDS = 35L
        const val READER_STATEMENT_SECONDS = 5L

        /** The scaled pair: 35 s of fixed terms become 1 s; 30 s and 120 s timeouts become 1 s and 3 s. */
        val SCALED_FIXED_TERMS: Duration = Duration.ofSeconds(1)
        val SCALED_DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(1)
        val SCALED_LONG_TIMEOUT: Duration = Duration.ofSeconds(3)
        const val BETWEEN_MILLIS = 3_000L
        const val AFTER_BOTH_MILLIS = 5_000L
    }
}
