package co.datapipelines.web.config

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.ReaderPins
import co.datapipelines.web.SharedPostgres
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A physical reader's lifetime is represented independently of the pool's close-started state. */
class ReaderLeaseKeeperIntegrationTest {
    private val jdbc = JdbcTemplate(SharedPostgres.dataSource()).apply { queryTimeout = LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS }
    private val leases = LakeLeases(jdbc, holderInstance = "reader-keeper-test")
    private val meters = SimpleMeterRegistry()
    private val keeper = ReaderLeaseKeeper(leases, 7, 30_000L, meters)
    private val physical = AtomicBoolean(true)
    private val retired = AtomicInteger()
    private val id = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE lake_leases")
    }

    @Test
    fun `pins stay live across a blocked close and are released only on physical confirmation`() {
        pin()
        repeat(3) { keeper.heartbeat() }
        ReaderPins(jdbc).pinnedLocations("sales", "orders") shouldBe setOf(LOCATION)
        keeper.unpin(id)
        count() shouldBe 1L
        physical.set(false)
        keeper.unpin(id)
        count() shouldBe 0L
        keeper.valid(id) shouldBe false
    }

    @Test
    fun `a partial row loss retires its generation, counts once, and refuses new reads`() {
        keeper.pin(id, "lake", mapOf("sales.orders" to LOCATION, "sales.items" to "s3://b/items.json"), Instant.now(), physical::get, {
            retired.incrementAndGet()
        }) shouldBe
            true
        jdbc.update("DELETE FROM lake_leases WHERE pinned_metadata_location=?", LOCATION)
        keeper.heartbeat()
        keeper.valid(id) shouldBe false
        retired.get() shouldBe 1
        meters.get("datapipelines.lake.reader_lease.lost").counter().count() shouldBe 1.0
        keeper.heartbeat()
        meters.get("datapipelines.lake.reader_lease.lost").counter().count() shouldBe 1.0
        // The surviving row continues to protect a reader whose close has not returned.
        count() shouldBe 1L
    }

    @Test
    fun `deadline uses the protection window and overlong readers keep their pins`() {
        val built = Instant.now().minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        keeper.pin(id, "lake", mapOf("sales.orders" to LOCATION), built, physical::get, { retired.incrementAndGet() }) shouldBe true
        val deadline = jdbc.queryForObject("SELECT deadline_at FROM lake_leases", java.time.OffsetDateTime::class.java)
        requireNotNull(deadline).toInstant() shouldBe built.plus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        keeper.heartbeat()
        keeper.heartbeat()
        keeper.valid(id) shouldBe true
        meters.get("datapipelines.lake.reader_lease.overlong").counter().count() shouldBe 1.0
    }

    @Test
    fun `TTL covers a failed fixed-delay round plus the next successful round`() {
        // Previous successful statement tail 5s + twice (cadence 5s + acquire 30s + statement 5s): 85s.
        keeper.ttl shouldBe Duration.ofSeconds(85)
        keeper.ttl shouldBe
            Duration.ofMillis(
                2 * (ReaderLeaseKeeper.CADENCE_MILLIS + 30_000L + LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS * 1000L) +
                    LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS * 1000L,
            )
        val scheduler = PoolReaderLeaseConfiguration().readerLeaseTaskScheduler()
        scheduler.poolSize shouldBe 1
        scheduler.initialize()
        try {
            val thread = scheduler.scheduledThreadPoolExecutor.submit<Thread> { Thread.currentThread() }.get()
            thread.name shouldBe "dp-reader-lease"
            thread.isDaemon shouldBe true
        } finally {
            scheduler.destroy()
        }
    }

    @Test
    fun `the live port follows physical handles and a later close is cleaned up on one heartbeat`() {
        pin()
        keeper.live() shouldBe setOf(id)
        physical.set(false)
        keeper.live() shouldBe emptySet()
        keeper.heartbeat()
        count() shouldBe 0L
        keeper.valid(id) shouldBe false
        meters.find("datapipelines.lake.reader_lease.lost").counter() shouldBe null
    }

    @Test
    fun `shutdown waits for physical closure while heartbeats can still protect the reader`() {
        val probed = CountDownLatch(1)
        keeper.pin(
            id,
            "lake",
            mapOf("sales.orders" to LOCATION),
            Instant.now(),
            {
                probed.countDown()
                physical.get()
            },
            { retired.incrementAndGet() },
        ) shouldBe true
        val closing = CompletableFuture.runAsync { keeper.close() }
        try {
            probed.await(5, TimeUnit.SECONDS) shouldBe true
            closing.isDone shouldBe false
            keeper.heartbeat()
            ReaderPins(jdbc).pinnedLocations("sales", "orders") shouldBe setOf(LOCATION)
        } finally {
            physical.set(false)
            closing.get(5, TimeUnit.SECONDS)
        }
        count() shouldBe 0L
    }

    @Test
    fun `TTL derives from the configured metadata acquisition wait rather than the Hikari default`() {
        HikariDataSource().use { source ->
            source.connectionTimeout = 90_000L
            val properties = StaticListableBeanFactory().getBeanProvider(LakeProperties::class.java)
            val configured = PoolReaderLeaseConfiguration().managedGenerationPins(JdbcTemplate(source), properties, SimpleMeterRegistry())
            configured.ttl shouldBe Duration.ofSeconds(205)
        }
    }

    private fun pin() {
        keeper.pin(id, "lake", mapOf("sales.orders" to LOCATION), Instant.now(), physical::get, { retired.incrementAndGet() }) shouldBe true
    }

    private fun count(): Long? = jdbc.queryForObject("SELECT count(*) FROM lake_leases WHERE kind='reader'", Long::class.java)

    private companion object {
        const val LOCATION = "s3://b/old.json"
    }
}
