package co.datapipelines.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The audit-log retention job (#310, metadata-db §8.2) over the SHIPPED schema — the
 * [KeyRetentionPurgeIntegrationTest] shape: the migrations off [SharedPostgres], the
 * [AuditLogRetention] class over a plain [NamedParameterJdbcTemplate], each batch its own
 * auto-committed statement exactly as in production.
 *
 * Every fixture row is aged by the DATABASE's clock (`NOW() - make_interval(…)` in the INSERT),
 * because the job's cutoff is the database's clock too — a row's age and the cutoff must come
 * from one clock or the boundary assertions measure skew, not the job.
 *
 * What each test holds the job to:
 * - rows older than the cutoff are gone, EXACTLY those, the ones a minute either side of the
 *   boundary included, and the rows existed on both sides before the purge (non-vacuity);
 * - the delete is batched: the statement count is the one a `LIMIT :batch` loop must make;
 * - a second tick deletes nothing (idempotent) and logs nothing;
 * - a tick stops at its batch ceiling or its time budget with a WARN, and the next tick finishes;
 * - the JVM clock never moves the cutoff;
 * - a fault mid-loop keeps the batches already committed, counts them, and WARNs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogRetentionIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun clean() {
        // The cleaning rule (SharedPostgres): this suite truncates the one table it seeds.
        jdbc.jdbcTemplate.execute("TRUNCATE audit_log")
    }

    @Test
    fun `a tick deletes exactly the rows older than the cutoff, in bounded batches, and says so`() {
        seedStraddlingThousand()
        // Non-vacuity: the fixture really does straddle the cutoff before the purge runs.
        count(OLD) shouldBe OLD_ROWS
        count(YOUNG) shouldBe YOUNG_ROWS
        val youngIdsBefore = ids(YOUNG)
        val registry = SimpleMeterRegistry()

        val (result, lines) = captured { retention(registry = registry).purgeOnce() }

        result shouldBe AuditLogRetention.Result(purged = OLD_ROWS, batches = 6, stop = AuditLogRetention.Stop.DRAINED)
        count(OLD) shouldBe 0
        ids(YOUNG) shouldBe youngIdsBefore
        registry.counter(AuditLogRetention.PURGED_METER).count() shouldBe OLD_ROWS.toDouble()

        val info = lines.single { it.level == Level.INFO }
        info.formattedMessage shouldContain "event=audit.retention purged=550 cutoff="
        info.formattedMessage shouldContain "batches=6"
        lines.filter { it.level == Level.WARN }.shouldBeEmpty()
        // Never a row's content: the fixture's event names and details stay out of the log.
        lines.forEach {
            it.formattedMessage shouldNotContain OLD
            it.formattedMessage shouldNotContain "secret-marker"
        }
    }

    @Test
    fun `a second tick deletes nothing and logs nothing`() {
        seedStraddlingThousand()
        val registry = SimpleMeterRegistry()
        retention(registry = registry).purgeOnce()

        val (again, lines) = captured { retention(registry = registry).purgeOnce() }

        again shouldBe AuditLogRetention.Result(purged = 0, batches = 1, stop = AuditLogRetention.Stop.DRAINED)
        count(YOUNG) shouldBe YOUNG_ROWS
        lines.shouldBeEmpty()
        registry.counter(AuditLogRetention.PURGED_METER).count() shouldBe OLD_ROWS.toDouble()
    }

    @Test
    fun `a tick that reaches its batch ceiling stops with a WARN and the next tick finishes`() {
        seedStraddlingThousand()
        val registry = SimpleMeterRegistry()

        val (first, lines) = captured { retention(registry = registry, maxBatches = 3).purgeOnce() }

        first shouldBe AuditLogRetention.Result(purged = 300, batches = 3, stop = AuditLogRetention.Stop.BATCH_CEILING)
        count(OLD) shouldBe OLD_ROWS - 300
        val warn = lines.single { it.level == Level.WARN }
        warn.formattedMessage shouldContain "event=audit.retention_incomplete"
        warn.formattedMessage shouldContain "reason=batch_ceiling"
        lines.single { it.level == Level.INFO }.formattedMessage shouldContain "purged=300"

        val second = retention(registry = registry, maxBatches = 3).purgeOnce()

        second shouldBe AuditLogRetention.Result(purged = 250, batches = 3, stop = AuditLogRetention.Stop.DRAINED)
        count(OLD) shouldBe 0
        count(YOUNG) shouldBe YOUNG_ROWS
        registry.counter(AuditLogRetention.PURGED_METER).count() shouldBe OLD_ROWS.toDouble()
    }

    @Test
    fun `a tick that spends its time budget stops after the batch in hand`() {
        seedStraddlingThousand()

        val (result, lines) = captured { retention(tickBudget = Duration.ZERO).purgeOnce() }

        result shouldBe AuditLogRetention.Result(purged = 100, batches = 1, stop = AuditLogRetention.Stop.TIME_BUDGET)
        lines.single { it.level == Level.WARN }.formattedMessage shouldContain "reason=time_budget"
        count(OLD) shouldBe OLD_ROWS - 100
    }

    @Test
    fun `the cutoff is the database clock - a JVM clock a hundred days ahead deletes nothing extra`() {
        // 300 days old: inside a 365-day window by the database's clock, OUTSIDE it by a JVM
        // clock a hundred days fast. 400 days old: outside by both.
        seed(YOUNG, count = 1, ageSql = "make_interval(days => 300)")
        seed(OLD, count = 1, ageSql = "make_interval(days => 400)")
        val fastClock = Clock.offset(Clock.systemUTC(), Duration.ofDays(100))

        val result = retention(clock = fastClock).purgeOnce()

        result.purged shouldBe 1
        count(YOUNG) shouldBe 1
        count(OLD) shouldBe 0
    }

    @Test
    fun `an empty table is a quiet tick`() {
        val (result, lines) = captured { retention().purgeOnce() }

        result shouldBe AuditLogRetention.Result(purged = 0, batches = 1, stop = AuditLogRetention.Stop.DRAINED)
        lines.shouldBeEmpty()
    }

    @Test
    fun `a fault mid-loop keeps the committed batches, counts them, and WARNs`() {
        seedStraddlingThousand()
        val registry = SimpleMeterRegistry()
        // Connection 1 reads the cutoff, 2 and 3 run two batches; the fourth connection fails.
        val failing = FailingAfter(connections = 3)

        val (result, lines) =
            captured {
                retention(jdbc = NamedParameterJdbcTemplate(failing), registry = registry).purgeOnce()
            }

        result shouldBe AuditLogRetention.Result(purged = 200, batches = 2, stop = AuditLogRetention.Stop.FAILED)
        count(OLD) shouldBe OLD_ROWS - 200
        count(YOUNG) shouldBe YOUNG_ROWS
        registry.counter(AuditLogRetention.PURGED_METER).count() shouldBe 200.0
        val warn = lines.single { it.level == Level.WARN }
        warn.formattedMessage shouldContain "event=audit.retention_failed purged=200 batches=2"
        failing.handedOut.get() shouldBeGreaterThan 3
    }

    @Test
    fun `the job refuses a retention under the floor however it is constructed`() {
        shouldThrow<IllegalArgumentException> { retention(retentionDays = 29) }
            .message shouldContain "30"
    }

    // --- fixture ------------------------------------------------------------------------------

    /**
     * 1,000 rows: [OLD_ROWS] older than a 365-day cutoff (one of them a minute past it) and
     * [YOUNG_ROWS] younger (one of them a minute short of it). 550 = five full batches of 100 and
     * a partial sixth, so a drained tick is six statements.
     */
    private fun seedStraddlingThousand() {
        seed(OLD, count = 1, ageSql = "make_interval(days => 365, mins => 1)")
        seed(OLD, count = OLD_ROWS - 1, ageSql = "make_interval(days => 366 + g)")
        seed(YOUNG, count = 1, ageSql = "make_interval(days => 365) - make_interval(mins => 1)")
        seed(YOUNG, count = YOUNG_ROWS - 1, ageSql = "make_interval(hours => g)")
        (count(OLD) + count(YOUNG)) shouldBe 1_000
    }

    /** [count] rows named [event], aged by the database clock; `g` is the series ordinal (1-based). */
    private fun seed(
        event: String,
        count: Int,
        ageSql: String,
    ) {
        jdbc.update(
            """
            INSERT INTO audit_log (timestamp, event, details_json)
            SELECT NOW() - ($ageSql), :event, '{"note":"secret-marker"}'::jsonb
              FROM generate_series(1, :count) AS g
            """.trimIndent(),
            mapOf("event" to event, "count" to count),
        )
    }

    private fun count(event: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE event = :event", mapOf("event" to event), Int::class.java)!!

    private fun ids(event: String): Set<Long> =
        jdbc.queryForList("SELECT id FROM audit_log WHERE event = :event", mapOf("event" to event), Long::class.javaObjectType).toSet()

    private fun retention(
        jdbc: NamedParameterJdbcTemplate = this.jdbc,
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        retentionDays: Int = 365,
        clock: Clock = Clock.systemUTC(),
        maxBatches: Int = 50,
        tickBudget: Duration = Duration.ofMinutes(1),
    ): AuditLogRetention =
        AuditLogRetention(
            jdbc = jdbc,
            retentionDays = retentionDays,
            meterRegistry = registry,
            clock = clock,
            batchSize = BATCH,
            maxBatches = maxBatches,
            tickBudget = tickBudget,
        )

    /** Runs [block] with the job's logger captured; returns its value and every line it logged. */
    private fun <T> captured(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(AuditLogRetention::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val value =
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
        return value to appender.list.toList()
    }

    /** Hands out [connections] real connections, then refuses — a metadata DB lost mid-tick. */
    private class FailingAfter(
        private val connections: Int,
    ) : DelegatingDataSource(SharedPostgres.dataSource()) {
        val handedOut = AtomicInteger()

        override fun getConnection(): Connection {
            if (handedOut.incrementAndGet() > connections) throw SQLException("metadata database unreachable (test)")
            return super.getConnection()
        }
    }

    private companion object {
        const val OLD = "test.retention.old"
        const val YOUNG = "test.retention.young"
        const val OLD_ROWS = 550
        const val YOUNG_ROWS = 450
        const val BATCH = 100
    }
}
