package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.BOUND_SLACK_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.CLEANUP_JOIN_MS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_LONG_QUERY
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.H_MAX_AGE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.LAKE
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.PRODUCT_LOGGERS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.REAPER_CADENCE_SECONDS
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.awaitBurning
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.awaitUntil
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.generationBuiltAt
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.leaseManaged
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.linesOf
import co.datapipelines.integration.ManagedLakeReadsFixture.Companion.observeRetirementAndClose
import co.datapipelines.integration.ManagedLakeReadsFixture.LongRead
import co.datapipelines.logcapture.SnapshotListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.io.path.name

/**
 * I3 (#982 split it out of [ManagedLakeReadsE2eTest], so another test worker can run it beside it): a first close that never
 * returns does not delay the next generation's retirement or close; the test waits out the managed close deadline
 * ([POOL_CLOSE_DEADLINE_SECONDS]) for the stuck close's report.
 *
 * A plain JUnit class (no Spring test context): its [ManagedLakeReadsFixture] owns its whole life — its scratch
 * database `managed_lake_reads_stuck_close`, its MinIO and OIDC stub, a setup instance with context A's properties, and the
 * reader fixture (`sales.trips` at CURRENT, status active) before the first test.
 *
 * I3 leases the managed table at once. In [ManagedLakeReadsE2eTest] the ordered tests before it created that table and
 * moved it to CURRENT; here [ManagedLakeReadsFixture.start] provides it.
 */
class ManagedLakeReadsStuckCloseE2eTest {
    /**
     * #818d I3 (item 6) — a close that does not return must cost a `dp-pool-close` thread, never a reap. The instance
     * boots with a test seam on its `PoolCloser` ([DelayFirstManagedClose]): the FIRST managed generation handed to
     * the closer is passed on to the production closer wrapped so that its close waits on a latch the test holds.
     * Gen 1 is leased once and returned; the reaper retires it at its age bound and hands its close away, where it
     * hangs. Gen 2 is then built by a lease that runs the long reader: the reaper must retire and hard-close gen 2 in
     * bound while gen 1's close is still hung, and `datasource.pool_close_stuck` must name gen 1 once, no earlier than
     * its own deadline after its hand-off, and carry that deadline (#840: a managed generation's, 90 s here). Red
     * with the synchronous close restored (the reaper then hangs in gen 1's close and never reaches gen 2), and with
     * the `scheduler` attribute removed (the retirement lines' thread).
     */
    @Test
    @Order(18)
    @Suppress("LongMethod") // one timeline on the real scheduler is the witness; its instants are asserted together
    fun `I3 - a first close that never returns does not delay the next generation's retirement or close`() {
        val release = CountDownLatch(1)
        val seam = DelayFirstManagedClose(release)
        val reader = fixture.bootReaderProfile(APP_I3, initializers = listOf(seam.initializer()))
        val logger = LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger
        val capture = SnapshotListAppender()
        logger.addAppender(capture)
        try {
            leaseManaged(reader).close()
            val gen1BuiltAt = generationBuiltAt(reader).shouldNotBeNull()
            val gen1RetireBy = gen1BuiltAt.plusSeconds(H_MAX_AGE_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
            awaitUntil(gen1RetireBy, "gen 1 retired and handed off") {
                seam.handedOffAt.get() != null
            }
            val handedOffAt = seam.handedOffAt.get().shouldNotBeNull()
            val lease = leaseManaged(reader)
            val builtAt = generationBuiltAt(reader).shouldNotBeNull()
            val statement = lease.prepareStatement(H_LONG_QUERY)
            val run = LongRead(statement)
            try {
                run.start()
                awaitBurning(run)
                val timeline = observeRetirementAndClose(reader, capture, run, builtAt, "I3 first close held")
                val stuckBy = handedOffAt.plusSeconds(POOL_CLOSE_DEADLINE_SECONDS + STUCK_SLACK_SECONDS)
                awaitUntil(stuckBy, "the stuck close is reported") { linesOf(capture, "event=datasource.pool_close_stuck").isNotEmpty() }
                val stuck = linesOf(capture, "event=datasource.pool_close_stuck")
                println(
                    "I3_TIMELINE gen1_built=$gen1BuiltAt gen1_handed_off=$handedOffAt gen1=${seam.generation.get()} " +
                        "gen1_close_released=${release.count == 0L} ${timeline.text} " +
                        "stuck=${stuck.map { "${it.at} ${it.thread} ${it.text}" }}",
                )
                assertAll(
                    { timeline.assertInBound() },
                    { withClue("gen 1's close was still held when gen 2 closed") { release.count shouldBe 1L } },
                    { stuck.size shouldBe 1 },
                    { stuck.single().text shouldContain "datasource=$LAKE generation=${seam.generation.get()} " },
                    { stuck.single().text shouldContain " deadline_seconds=$POOL_CLOSE_DEADLINE_SECONDS " },
                    { (stuck.single().at >= handedOffAt.plusSeconds(POOL_CLOSE_DEADLINE_SECONDS)) shouldBe true },
                )
            } finally {
                runCatching { statement.cancel() }
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
            }
        } finally {
            release.countDown()
            logger.detachAppender(capture)
            capture.stop()
            reader.close()
        }
    }

    /**
     * I3's test seam: the application's `PoolCloser`, decorated. The first managed generation handed to it goes on to
     * the production closer (the `poolCloser` bean) wrapped so its close first waits for [release]; every other pool
     * goes on unchanged. Registered `@Primary`, so `DomainConfiguration.datasourceRegistry` takes it. Built from JDK
     * proxies because this module compiles against `app` only (the pool and closer types live in `datasources`).
     */
    private class DelayFirstManagedClose(
        private val release: CountDownLatch,
    ) {
        val handedOffAt = AtomicReference<Instant?>(null)
        val generation = AtomicReference<String?>(null)
        private val poolType = Class.forName(POOL_TYPE)
        private val closerType = Class.forName(CLOSER_TYPE)

        fun initializer(): ApplicationContextInitializer<ConfigurableApplicationContext> =
            ApplicationContextInitializer { context ->
                @Suppress("UNCHECKED_CAST")
                (context as GenericApplicationContext).registerBean(
                    "delayFirstManagedClose",
                    closerType as Class<Any>,
                    Supplier { closer(context) },
                    BeanDefinitionCustomizer { it.isPrimary = true },
                )
            }

        private fun closer(context: ApplicationContext): Any =
            Proxy.newProxyInstance(closerType.classLoader, arrayOf(closerType)) { proxy, method, args ->
                when (method.name) {
                    "close" -> closerType.getMethod("close", poolType).invoke(context.getBean("poolCloser"), delayedIfFirst(args!![0]))
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> "DelayFirstManagedClose"
                }
            }

        private fun delayedIfFirst(pool: Any): Any {
            val managed = poolType.getMethod("getManagedGeneration").invoke(pool) != null
            if (!managed || !generation.compareAndSet(null, poolType.getMethod("getGeneration").invoke(pool) as String?)) return pool
            handedOffAt.set(Instant.now())
            return Proxy.newProxyInstance(poolType.classLoader, arrayOf(poolType)) { _, method, args ->
                if (method.name == "close" && method.parameterCount == 0) release.await()
                method.invoke(pool, *(args ?: emptyArray()))
            }
        }
    }

    companion object {
        /**
         * #840 — a MANAGED generation's close deadline on this profile, a literal; I3 waits for its WARN. The lake pool
         * runs HikariCP's 30 s `connectionTimeout`, the metadata pool the shipped 10 s acquire bound (#837): 30 (the
         * lake pool's own first shutdown wait) + 3 × 10 + 5 (the fixed terms) + 10 + 5 + 10 (the reader-pin release:
         * metadata checkout, bounded DELETE, the driver's cancel wait on a server that never answers; #833 merge review
         * F1) = 90. #818d's constant 65 had neither the first term's source nor the fourth.
         */
        private const val POOL_CLOSE_DEADLINE_SECONDS = 90L
        private const val APP_I3 = "dp818-i3"
        private const val STUCK_SLACK_SECONDS = 10L
        private const val POOL_TYPE = "co.datapipelines.datasources.pooling.ConnectionPool"
        private const val CLOSER_TYPE = "co.datapipelines.datasources.pooling.PoolCloser"

        private val fixture = ManagedLakeReadsFixture("managed_lake_reads_stuck_close", ManagedLakeReadsStuckCloseE2eTest::class)

        @BeforeAll
        @JvmStatic
        fun startFixture() {
            fixture.start()
        }

        @AfterAll
        @JvmStatic
        fun closeFixture() {
            fixture.close()
        }
    }
}
