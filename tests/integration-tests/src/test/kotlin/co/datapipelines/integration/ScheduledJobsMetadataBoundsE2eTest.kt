package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import co.datapipelines.DatapipelinesApplication
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * #837 over the REAL application: the two waits on the metadata `DataSource` that sit outside every statement's
 * `queryTimeout` are bounded in wall-clock time, observed on the stale-execution sweep (the simplest job on `dp-scheduled`)
 * against this class's OWN Postgres container — a paused SHARED one would stall every later suite in the JVM.
 *
 * - **R3, the acquire** — every connection of the metadata pool is leased by the test, so the sweep's next tick waits
 *   for one. The pool's `connection-timeout` (10 s since #837; HikariCP's 30 s default before) ends the wait: the failed-tick
 *   line (`event=execution.sweep_failed`, `error=CannotGetJdbcConnectionException`) appears 10 s after the tick STARTED,
 *   not 30. The tick's start is Spring's own observation of the execution, so a tick queued behind a sibling job on the
 *   one thread is timed from when it actually began. The fixture parks the next real sweep in its observation callback
 *   before exhausting the pool, then records the start after release: preceding sibling jobs never wait on our leases.
 * - **R2, the socket** — the sweep's `UPDATE` is held behind a table lock (the shape of `ScheduledJobsSchedulerE2eTest`),
 *   then the container is `docker pause`d. The statement's `queryTimeout` (10 s) fires, but its cancel is a second
 *   connection the paused server never reads, so only the SOCKET bound (statement + 5 s = 15 s) can end the statement.
 *   The driver reports the failed read when its own cancel attempt gives up (`cancelSignalTimeout`, 10 s), so the
 *   failed-tick line appears statement + 10 s = 20 s after the statement began (measured: 19.99 s here, 20.0 s in a bare
 *   pgjdbc probe), with the lock still held and the server still paused;
 *   once the server is back the next tick runs and succeeds. The pause comes AFTER the statement is in flight on purpose:
 *   HikariCP validates a connection that has idled on checkout, so a pause before the tick fails the ACQUIRE instead (the
 *   `LifecycleWriteBoundIntegrationTest` precedent), which is R3's subject, not this one's.
 *
 * Red at the base of #837: R3's tick is still waiting at 15 s (its wait ends at 30 s), and R2's statement is still blocked at
 * 25 s — each case observes for its bound plus a margin, fails there, and restores the environment in `finally` (the
 * pool's connections released, the container unpaused, the lock rolled back) so a red run cannot hang the suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class ScheduledJobsMetadataBoundsE2eTest {
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName("datapipelines_837").withUsername("dp").withPassword("dp")
    private val redis: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .withExposedPorts(REDIS_PORT)
    private lateinit var app: ConfigurableApplicationContext
    private lateinit var failures: SweepFailures
    private val sweepLogger = LoggerFactory.getLogger("co.datapipelines.executor.StaleExecutionSweeper") as Logger

    @BeforeAll
    fun start() {
        postgres.start()
        redis.start()
        app = boot()
        failures = SweepFailures(app.getBean(TickRecorder::class.java)).also { it.start() }
        sweepLogger.addAppender(failures)
    }

    @AfterAll
    fun stop() {
        if (::failures.isInitialized) {
            sweepLogger.detachAppender(failures)
            failures.stop()
        }
        if (::app.isInitialized && app.isActive) app.close()
        redis.stop()
        postgres.stop()
        oidc.close()
    }

    @Test
    @Order(0)
    fun `a different scheduler thread with the same name cannot supply the failure witness`() {
        val before = System.nanoTime()
        val foreign = Thread({ sweepLogger.warn("event=execution.sweep_failed foreign-witness") }, "dp-scheduled")
        foreign.start()
        foreign.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        foreign.isAlive shouldBe false
        failures.since(before) shouldBe emptyList()
    }

    @Test
    @Order(1)
    fun `R3 - with the metadata pool exhausted a sibling job's acquire fails at the pool's ten second bound, not thirty`() {
        val pool = app.getBean("dataSource") as HikariDataSource
        val recorder = app.getBean(TickRecorder::class.java)
        val startGate = recorder.pauseNextSweep()
        val held = mutableListOf<Connection>()
        try {
            withClue("the next real sweep reaches its setup barrier while the pool is available") {
                startGate.awaitArrival() shouldBe true
            }
            val ticksBefore = recorder.startsOf(SWEEP).size
            repeat(pool.maximumPoolSize) { held += pool.connection }
            pool.hikariPoolMXBean.activeConnections shouldBe pool.maximumPoolSize
            startGate.release()
            awaitCondition("the sweep's next tick to start with the pool exhausted", NEXT_TICK_SECONDS) {
                recorder.startsOf(SWEEP).size > ticksBefore
            }
            withClue("the setup barrier was released by the test, not its safety timeout") {
                startGate.releasedBeforeTimeout shouldBe true
            }
            val tickStartedAt = recorder.startsOf(SWEEP)[ticksBefore]
            // Non-vacuity: a thread is really waiting for a connection (the tick's acquire), not a free pool.
            awaitCondition("a thread waiting for a metadata connection", AWAIT_SECONDS) {
                pool.hikariPoolMXBean.threadsAwaitingConnection > 0
            }
            val failure = awaitFailedLine(ACQUIRE_BOUND_SECONDS + MARGIN_SECONDS, tickStartedAt)
            val elapsedMs = failure.first
            println("837 R3 acquire bound under an exhausted pool: tick->failed line ${elapsedMs}ms; line=${failure.second}")
            withClue("tick->failed line ${elapsedMs}ms; line=${failure.second}") {
                elapsedMs shouldBeGreaterThanOrEqual TimeUnit.SECONDS.toMillis(ACQUIRE_BOUND_SECONDS - 1)
                elapsedMs shouldBeLessThanOrEqual TimeUnit.SECONDS.toMillis(ACQUIRE_BOUND_SECONDS + MARGIN_SECONDS)
                failure.second shouldContain "error=CannotGetJdbcConnectionException"
                pool.connectionTimeout shouldBe TimeUnit.SECONDS.toMillis(ACQUIRE_BOUND_SECONDS)
            }
        } finally {
            startGate.release()
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    @Order(2)
    fun `R2 - with the metadata Postgres paused the sweep's held statement ends at its socket bound, and the next tick runs`() {
        val recorder = app.getBean(TickRecorder::class.java)
        val docker = DockerClientFactory.instance().client()
        var paused = false
        connection().use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE pipeline_executions IN SHARE MODE") }
            try {
                val queuedAt: Long
                connection().use { watcher ->
                    awaitCondition("the sweep's UPDATE waiting on the lock", SWEEP_WAIT_SECONDS) { sweepWaiting(watcher) }
                    // The pause comes with the statement provably in flight on a connection the tick already holds.
                    queuedAt = System.nanoTime()
                    docker.pauseContainerCmd(postgres.containerId).exec()
                    paused = true
                }
                val failure = awaitFailedLine(PAUSED_END_SECONDS + MARGIN_SECONDS, queuedAt)
                val elapsedMs = failure.first
                // Observed while the server is STILL paused: only the socket bound can have ended the statement.
                println("837 R2 socket bound under a paused server: held statement->failed line ${elapsedMs}ms; line=${failure.second}")
                withClue("held statement->failed line ${elapsedMs}ms; line=${failure.second}") {
                    elapsedMs shouldBeGreaterThanOrEqual TimeUnit.SECONDS.toMillis(SWEEP_BOUND_SECONDS)
                    elapsedMs shouldBeLessThanOrEqual TimeUnit.SECONDS.toMillis(PAUSED_END_SECONDS + MARGIN_SECONDS)
                    failure.second shouldContain "sql_state=$CONNECTION_FAILURE_SQL_STATE"
                }
            } finally {
                if (paused) docker.unpauseContainerCmd(postgres.containerId).exec()
                runCatching { lock.rollback() }
            }
        }
        // The server is back: the next tick starts and succeeds (a healthy UPDATE is milliseconds; no new failed line).
        val ticksAtRecovery = recorder.startsOf(SWEEP).size
        val recoveryStartedAt = System.nanoTime()
        awaitCondition("the sweep's next tick after the server came back", NEXT_TICK_SECONDS) {
            recorder.startsOf(SWEEP).size > ticksAtRecovery
        }
        Thread.sleep(HEALTHY_TICK_SETTLE_MS)
        withClue("a healthy tick after recovery logs no failed-sweep line") {
            failures.since(recoveryStartedAt) shouldBe emptyList()
        }
    }

    /**
     * Polls only this application's scheduler-thread failures, recorded synchronously with a monotonic timestamp.
     * Another application in the test JVM may log the same event on a different thread with the same name.
     */
    private fun awaitFailedLine(
        seconds: Long,
        anchorNanos: Long,
    ): Pair<Long, String> {
        val deadline = anchorNanos + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val line = failures.since(anchorNanos).firstOrNull()
            if (line != null) return TimeUnit.NANOSECONDS.toMillis(line.first - anchorNanos) to line.second
            check(System.nanoTime() < deadline) { "no event=execution.sweep_failed line within $seconds s of the anchor" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun sweepWaiting(connection: Connection): Boolean =
        connection
            .prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                    "AND query LIKE 'UPDATE pipeline_executions%' AND query LIKE '%SET status = ''ABORTED''%'",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1) > 0
                }
            }

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun awaitCondition(
        what: String,
        seconds: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not within $seconds s: $what" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Spring's observation of each `@Scheduled` execution, with the instant it started, per job. */
    class TickRecorder : ObservationHandler<ScheduledTaskObservationContext> {
        private val ticks = ConcurrentLinkedQueue<Pair<String, Long>>()
        private val sweepThread = AtomicReference<Thread>()
        private val nextSweepGate = AtomicReference<SweepStartGate?>()

        override fun supportsContext(context: Observation.Context): Boolean = context is ScheduledTaskObservationContext

        override fun onStart(context: ScheduledTaskObservationContext) {
            val job = "${context.targetClass.name}.${context.method.name}"
            if (job == SWEEP) {
                nextSweepGate.getAndSet(null)?.pause()
                sweepThread.set(Thread.currentThread())
            }
            ticks += job to System.nanoTime()
        }

        fun pauseNextSweep(): SweepStartGate {
            val gate = SweepStartGate()
            check(nextSweepGate.compareAndSet(null, gate)) { "a sweep setup barrier is already armed" }
            return gate
        }

        fun startsOf(job: String): List<Long> = ticks.filter { it.first == job }.map { it.second }

        fun ownsCurrentThread(): Boolean = Thread.currentThread() === sweepThread.get()
    }

    /** Parks one scheduled invocation before its timing anchor; the test can then exhaust the pool without starving siblings. */
    class SweepStartGate {
        private val arrived = CountDownLatch(1)
        private val proceed = CountDownLatch(1)

        @Volatile
        var releasedBeforeTimeout = false
            private set

        fun awaitArrival(): Boolean = arrived.await(NEXT_TICK_SECONDS, TimeUnit.SECONDS)

        fun pause() {
            arrived.countDown()
            releasedBeforeTimeout = proceed.await(NEXT_TICK_SECONDS, TimeUnit.SECONDS)
        }

        fun release() = proceed.countDown()
    }

    /** Thread identity, not its shared `dp-scheduled` name, ties a failed line to this context's observed tick. */
    private class SweepFailures(
        private val ticks: TickRecorder,
    ) : AppenderBase<ILoggingEvent>() {
        private val recorded = ConcurrentLinkedQueue<Pair<Long, String>>()

        override fun append(event: ILoggingEvent) {
            if (ticks.ownsCurrentThread() && event.formattedMessage.contains("event=execution.sweep_failed")) {
                recorded += System.nanoTime() to event.formattedMessage
            }
        }

        fun since(anchorNanos: Long): List<Pair<Long, String>> = recorded.filter { it.first >= anchorNanos }
    }

    /** The recorder as a bean, so Boot registers it with the observation registry before the first tick. */
    @Configuration(proxyBeanMethods = false)
    class TickRecorderConfiguration {
        @Bean
        fun tickRecorder(): TickRecorder = TickRecorder()
    }

    private fun boot(): ConfigurableApplicationContext {
        val properties =
            mapOf(
                "server.port" to "0",
                "management.server.port" to "0",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "datapipelines.redis.password" to "",
                "datapipelines.redis.host" to redis.host,
                "datapipelines.redis.port" to redis.getMappedPort(REDIS_PORT).toString(),
                "datapipelines.jwt.secret" to E2eSession.newSecret(),
                "datapipelines.db.encryption-key" to ENCRYPTION_KEY,
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
            )
        // As command-line arguments, NOT builder `properties(...)` — PersistenceRestartE2eTest says why.
        return SpringApplicationBuilder(DatapipelinesApplication::class.java, TickRecorderConfiguration::class.java)
            .run(*properties.map { (k, v) -> "--$k=$v" }.toTypedArray())
    }

    private companion object {
        const val REDIS_PORT = 6379
        const val SWEEP = "co.datapipelines.web.config.StaleExecutionSweepScheduler.sweep"

        /** #837 D2: the metadata pool's acquire bound — the issue's number, a literal; never read back from the class under test. */
        const val ACQUIRE_BOUND_SECONDS = 10L

        /** #326: the sweep's statement bound; #837 D1's socket bound (statement + 5 s) lets it end, see the class comment. */
        const val SWEEP_BOUND_SECONDS = 10L

        /** pgjdbc's `cancelSignalTimeout` default: the failed read is reported when the cancel on the paused server gives up. */
        const val DRIVER_CANCEL_WAIT_SECONDS = 10L

        /** Where the paused server's statement ends: its bound plus the driver's cancel wait. */
        const val PAUSED_END_SECONDS = SWEEP_BOUND_SECONDS + DRIVER_CANCEL_WAIT_SECONDS

        /** A loaded box's margin on either wait above. */
        const val MARGIN_SECONDS = 5L

        /** The sweep's cadence (15 s, `fixedDelay`) plus a sibling job's turn on the one thread and a margin. */
        const val NEXT_TICK_SECONDS = 45L
        const val SWEEP_WAIT_SECONDS = 45L
        const val AWAIT_SECONDS = 20L
        const val HEALTHY_TICK_SETTLE_MS = 3_000L
        const val POLL_MS = 50L

        /** pgjdbc's SQLState for a connection that failed mid-statement (`CONNECTION_FAILURE_DURING_TRANSACTION`/`_FAILURE`). */
        const val CONNECTION_FAILURE_SQL_STATE = "08006"
        val ENCRYPTION_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val oidc = OidcDiscoveryStub()
    }
}
