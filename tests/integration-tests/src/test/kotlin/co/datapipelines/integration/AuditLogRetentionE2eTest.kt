package co.datapipelines.integration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.config.TaskManagementConfigUtils
import org.springframework.scheduling.config.TaskSchedulerRouter
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #310 on the REAL application: `datapipelines.audit.retention-days` flows from configuration
 * through `AuditProperties` into the retention sweep's audit step, and one tick of the sweep —
 * the `@Scheduled` adapter's own `retain()`, called directly — deletes exactly the audit rows
 * older than the cutoff from the application's own metadata database, logs its line and counts
 * them on the application's meter registry.
 *
 * Beans by NAME, members by reflection — the `SampleDataBootstrapE2eTest` seam: this module
 * depends on `:modules:app` alone (module-structure §4.2, `verifyModuleDependencies`), and `app`
 * exposes `web` and `auth` as `implementation`, so their classes are on the runtime classpath but
 * not the compile one. The database is reached over plain JDBC for the same reason.
 *
 * ## Why 30 days, and why the scheduler is drained first
 * This context runs at the FLOOR (30 days) and seeds rows 30–34 days old: every other cached
 * context in this JVM runs the default 365 days, so no other context's hourly tick can take
 * this suite's rows — the result below is this context's job alone. And this context's own
 * startup tick (a `fixedDelay` job's first run is at startup) is waited out before seeding, by
 * queueing a no-op on the ONE thread every `@Scheduled` job shares: it runs only after the
 * startup ticks queued ahead of it. Synchronised on the event, never on time.
 *
 * ## Which thread that is — measured here, not assumed
 * The jobs' own scheduler since #316: `SweepSchedulingConfiguration`'s `taskScheduler`, ONE thread
 * (`dp-scheduled`) that every `@Scheduled` job ticks on and nothing else uses. [drainStartupTicks]
 * reads the scheduler the processor actually holds and pins that it is that bean with one thread:
 * the drain is sound only on one thread. (Until #316 the pin read `sseLogScheduler` — the SSE log
 * streamer's `dp-sse-log` thread, which Spring fell back to while no `TaskScheduler` bean existed;
 * the retention tick's two-second budget, `AuditLogRetention.DEFAULT_TICK_BUDGET`, was sized for
 * sharing THAT thread and now bounds how long the sweep and the reaper wait behind it.)
 *
 * The tick is invoked directly rather than by lowering the delay: the cadence is a code constant
 * (not a key), and a test-only knob would be a key the product ships for a test.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class AuditLogRetentionE2eTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @AfterEach
    fun removeSeededRows() {
        sql { it.prepareStatement("DELETE FROM audit_log WHERE event IN ('$OLD', '$YOUNG')").executeUpdate() }
    }

    @Test
    fun `one sweep tick deletes the application's audit rows older than the configured retention`() {
        boundRetentionDays() shouldBe RETENTION_DAYS
        drainStartupTicks()
        seed(OLD, "make_interval(days => 30, mins => 1)")
        (31..34).forEach { days -> seed(OLD, "make_interval(days => $days)") }
        seed(YOUNG, "make_interval(days => 30) - make_interval(mins => 1)")
        seed(YOUNG, "make_interval(days => 29)")
        seed(YOUNG, "make_interval(days => 1)")
        // Non-vacuity: both sides of the cutoff hold rows before the tick.
        count(OLD) shouldBe 5
        count(YOUNG) shouldBe 3
        val meterBefore = purgedMeter()

        val lines = sweepTickLogs()

        count(OLD) shouldBe 0
        count(YOUNG) shouldBe 3
        val info = lines.single { it.level == Level.INFO && it.formattedMessage.startsWith("event=audit.retention ") }
        val purged =
            Regex("""purged=(\d+)""")
                .find(info.formattedMessage)
                .shouldNotBeNull()
                .groupValues[1]
                .toInt()
        // At least this suite's five: the shared database may hold other suites' rows, none of
        // them aged (no suite seeds an aged audit row), but the assertion does not depend on it.
        withClue(info.formattedMessage) { purged shouldBeGreaterThanOrEqual 5 }
        (purgedMeter() - meterBefore) shouldBe purged.toDouble()
    }

    /** `AuditProperties.retentionDays` as the application bound it. */
    private fun boundRetentionDays(): Int {
        val properties = applicationContext.getBean(Class.forName("co.datapipelines.auth.AuditProperties"))
        return properties.javaClass.getMethod("getRetentionDays").invoke(properties) as Int
    }

    /** Runs a no-op on the scheduler every `@Scheduled` job uses, and waits for it — see the class KDoc. */
    private fun drainStartupTicks() {
        val processor = applicationContext.getBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
        val registrar =
            ReflectionTestUtils
                .getField(processor.shouldBeInstanceOf<ScheduledAnnotationBeanPostProcessor>(), "registrar")
                .shouldBeInstanceOf<ScheduledTaskRegistrar>()
        // Spring 6.1+: the registrar holds a router whose DEFAULT scheduler is resolved lazily —
        // a TaskScheduler bean, else the context's unique ScheduledExecutorService, else a local one.
        val scheduler: TaskScheduler = registrar.scheduler.shouldNotBeNull().shouldBeInstanceOf<TaskSchedulerRouter>()
        val resolved =
            ReflectionTestUtils
                .getField(scheduler, "defaultScheduler")
                .shouldBeInstanceOf<java.util.function.Supplier<*>>()
                .get()
        // The drain is only sound on ONE thread. The default resolved to the jobs' own scheduler
        // (#316), which SweepSchedulingConfiguration builds with one thread.
        resolved shouldBeSameInstanceAs applicationContext.getBean("taskScheduler")
        resolved.shouldBeInstanceOf<ThreadPoolTaskScheduler>().poolSize shouldBe 1
        val drained = CompletableFuture<Unit>()
        scheduler.schedule({ drained.complete(Unit) }, Instant.now())
        drained.get(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** One tick of the sweep on THIS thread, with the audit job's lines from this thread only. */
    private fun sweepTickLogs(): List<ILoggingEvent> {
        val sweep = applicationContext.getBean("executionEventRetentionScheduler")
        val logger = LoggerFactory.getLogger("co.datapipelines.auth.AuditLogRetention") as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            sweep.javaClass.getMethod("retain").invoke(sweep)
        } finally {
            logger.detachAppender(appender)
        }
        val thread = Thread.currentThread().name
        return appender.list.filter { it.threadName == thread }
    }

    /** The application registry's `datapipelines.audit.retention.purged` count (0 before the first tick). */
    private fun purgedMeter(): Double {
        val registryType = Class.forName("io.micrometer.core.instrument.MeterRegistry")
        val registry = applicationContext.getBean(registryType)
        val search = registryType.getMethod("find", String::class.java).invoke(registry, PURGED_METER)
        val counter = search.javaClass.getMethod("counter").invoke(search) ?: return 0.0
        return Class.forName("io.micrometer.core.instrument.Counter").getMethod("count").invoke(counter) as Double
    }

    private fun seed(
        event: String,
        ageSql: String,
    ) {
        sql { connection ->
            connection
                .prepareStatement("INSERT INTO audit_log (timestamp, event, details_json) VALUES (NOW() - ($ageSql), ?, '{}'::jsonb)")
                .use { statement ->
                    statement.setString(1, event)
                    statement.executeUpdate()
                }
        }
    }

    private fun count(event: String): Int =
        sql { connection ->
            connection.prepareStatement("SELECT count(*) FROM audit_log WHERE event = ?").use { statement ->
                statement.setString(1, event)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun <T> sql(block: (java.sql.Connection) -> T): T =
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use(block)

    companion object {
        private const val RETENTION_DAYS = 30
        private const val DRAIN_TIMEOUT_SECONDS = 60L
        private const val PURGED_METER = "datapipelines.audit.retention.purged"
        private const val OLD = "e2e.audit_retention.old"
        private const val YOUNG = "e2e.audit_retention.young"
        private const val ADMIN_EMAIL = "retention-admin@datapipelines.test"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private val random = SecureRandom()

        /** Generated per run, the LocalAdminSeedE2eTest shape — no literal secret in any fixture (HIGH-2). */
        private fun seedPassword(): String = "e2e-seed-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            // Generated per run — no literal secret in any test fixture (HIGH-2).
            registry.add("datapipelines.jwt.secret") { E2eSession.newSecret() }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            // No OIDC: local accounts only, the LocalAdminSeedE2eTest posture — authentication is
            // not this suite's subject, and it needs no provider stub.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.bootstrap-admin-email") { ADMIN_EMAIL }
            registry.add("datapipelines.auth.local.bootstrap-password") { seedPassword() }
            // The subject: the floor, through the real binding.
            registry.add("datapipelines.audit.retention-days") { RETENTION_DAYS }
        }
    }
}
