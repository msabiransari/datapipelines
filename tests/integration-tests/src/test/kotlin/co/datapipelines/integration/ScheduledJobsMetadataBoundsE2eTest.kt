package co.datapipelines.integration

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
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
import java.util.concurrent.TimeUnit

/**
 * #837 over the REAL application: the two waits on the metadata `DataSource` that sit outside every statement's
 * `queryTimeout` are bounded in wall-clock time, observed on the stale-execution sweep (the simplest job on `dp-scheduled`)
 * against this class's OWN Postgres container — a paused SHARED one would stall every later suite in the JVM.
 *
 * - **R3, the acquire** — every connection of the metadata pool is leased by the test, so the sweep's next tick waits
 *   for one. The pool's `connection-timeout` (10 s since #837; HikariCP's 30 s default before) ends the wait: the failed-tick
 *   line (`event=execution.sweep_failed`, `error=CannotGetJdbcConnectionException`) appears 10 s after the tick STARTED,
 *   not 30. The tick's start is Spring's own observation of the execution, so a tick queued behind a sibling job on the
 *   one thread is timed from when it actually began.
 * - **R2, the socket** — the sweep's `UPDATE` is held behind a table lock (the shape of `ScheduledJobsSchedulerE2eTest`),
 *   then the container is `docker pause`d. The statement's `queryTimeout` (10 s) fires, but its cancel is a second
 *   connection the paused server never reads, so only the SOCKET bound (statement + 5 s = 15 s) can end the statement.
 *   The failed-tick line appears 15 s after the statement began, with the lock still held and the server still paused;
 *   once the server is back the next tick runs and succeeds. The pause comes AFTER the statement is in flight on purpose:
 *   HikariCP validates a connection that has idled on checkout, so a pause before the tick fails the ACQUIRE instead (the
 *   `LifecycleWriteBoundIntegrationTest` precedent), which is R3's subject, not this one's.
 *
 * Red at the base of #837: R3's tick is still waiting at 15 s (its wait ends at 30 s), and R2's statement is still blocked at
 * 20 s — each case observes for its bound plus a margin, fails there, and restores the environment in `finally` (the
 * pool's connections released, the container unpaused, the lock rolled back) so a red run cannot hang the suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(OutputCaptureExtension::class)
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class ScheduledJobsMetadataBoundsE2eTest {
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName("datapipelines_837").withUsername("dp").withPassword("dp")
    private val redis: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .withExposedPorts(REDIS_PORT)
    private lateinit var app: ConfigurableApplicationContext

    @BeforeAll
    fun start() {
        postgres.start()
        redis.start()
        app = boot()
    }

    @AfterAll
    fun stop() {
        if (::app.isInitialized && app.isActive) app.close()
        redis.stop()
        postgres.stop()
        oidc.close()
    }

    @Test
    @Order(1)
    fun `R3 - with the metadata pool exhausted a sibling job's acquire fails at the pool's ten second bound, not thirty`(output: CapturedOutput) {
        val pool = app.getBean("dataSource") as HikariDataSource
        val recorder = app.getBean(TickRecorder::class.java)
        val held = (1..pool.maximumPoolSize).map { pool.connection }
        try {
            val logFrom = output.out.length
            val ticksBefore = recorder.startsOf(SWEEP).size
            awaitCondition("the sweep's next tick to start with the pool exhausted", NEXT_TICK_SECONDS) {
                recorder.startsOf(SWEEP).size > ticksBefore
            }
            val tickStartedAt = recorder.startsOf(SWEEP).last()
            // Non-vacuity: a thread is really waiting for a connection (the tick's acquire), not a free pool.
            awaitCondition("a thread waiting for a metadata connection", AWAIT_SECONDS) { pool.hikariPoolMXBean.threadsAwaitingConnection > 0 }
            val failure = awaitFailedLine(output, logFrom, ACQUIRE_BOUND_SECONDS + MARGIN_SECONDS, tickStartedAt)
            val elapsedMs = failure.first
            println("837 R3 acquire bound under an exhausted pool: tick->failed line ${elapsedMs}ms; line=${failure.second}")
            withClue("tick->failed line ${elapsedMs}ms; line=${failure.second}") {
                elapsedMs shouldBeGreaterThanOrEqual TimeUnit.SECONDS.toMillis(ACQUIRE_BOUND_SECONDS - 1)
                elapsedMs shouldBeLessThanOrEqual TimeUnit.SECONDS.toMillis(ACQUIRE_BOUND_SECONDS + MARGIN_SECONDS)
                failure.second shouldContain "error=CannotGetJdbcConnectionException"
                pool.connectionTimeout shouldBe TimeUnit.SECONDS.toMillis(ACQUIRE_BOUND_SECONDS)
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    @Order(2)
    fun `R2 - with the metadata Postgres paused the sweep's held statement ends at its socket bound, and the next tick runs`(
        output: CapturedOutput,
    ) {
        val recorder = app.getBean(TickRecorder::class.java)
        val docker = DockerClientFactory.instance().client()
        var paused = false
        connection().use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE pipeline_executions IN SHARE MODE") }
            try {
                val logFrom: Int
                val queuedAt: Long
                connection().use { watcher ->
                    awaitCondition("the sweep's UPDATE waiting on the lock", SWEEP_WAIT_SECONDS) { sweepWaiting(watcher) }
                    // The pause comes with the statement provably in flight on a connection the tick already holds.
                    logFrom = output.out.length
                    queuedAt = System.nanoTime()
                    docker.pauseContainerCmd(postgres.containerId).exec()
                    paused = true
                }
                val failure = awaitFailedLine(output, logFrom, SOCKET_BOUND_SECONDS + MARGIN_SECONDS, queuedAt)
                val elapsedMs = failure.first
                // Observed while the server is STILL paused: only the socket bound can have ended the statement.
                println("837 R2 socket bound under a paused server: held statement->failed line ${elapsedMs}ms; line=${failure.second}")
                withClue("held statement->failed line ${elapsedMs}ms; line=${failure.second}") {
                    elapsedMs shouldBeGreaterThanOrEqual TimeUnit.SECONDS.toMillis(SWEEP_BOUND_SECONDS)
                    elapsedMs shouldBeLessThanOrEqual TimeUnit.SECONDS.toMillis(SOCKET_BOUND_SECONDS + MARGIN_SECONDS)
                    failure.second shouldContain "sql_state=$CONNECTION_FAILURE_SQL_STATE"
                }
            } finally {
                if (paused) docker.unpauseContainerCmd(postgres.containerId).exec()
                runCatching { lock.rollback() }
            }
        }
        // The server is back: the next tick starts and succeeds (a healthy UPDATE is milliseconds; no new failed line).
        val ticksAtRecovery = recorder.startsOf(SWEEP).size
        val logFrom = output.out.length
        awaitCondition("the sweep's next tick after the server came back", NEXT_TICK_SECONDS) {
            recorder.startsOf(SWEEP).size > ticksAtRecovery
        }
        Thread.sleep(HEALTHY_TICK_SETTLE_MS)
        withClue("a healthy tick after recovery logs no failed-sweep line") {
            output.out
                .substring(logFrom)
                .lineSequence()
                .filter { it.contains("event=execution.sweep_failed") }
                .toList() shouldBe emptyList()
        }
    }

    /**
     * Polls the application's own log, after [from], for the stale sweep's failed-tick line; returns how many milliseconds
     * after [anchorNanos] it was first seen, and the line. Fails at [seconds] after the anchor naming what never happened.
     */
    private fun awaitFailedLine(
        output: CapturedOutput,
        from: Int,
        seconds: Long,
        anchorNanos: Long,
    ): Pair<Long, String> {
        val deadline = anchorNanos + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val line =
                output.out
                    .substring(from)
                    .lineSequence()
                    .firstOrNull { it.contains("event=execution.sweep_failed") }
            if (line != null) return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - anchorNanos) to line
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

        override fun supportsContext(context: Observation.Context): Boolean = context is ScheduledTaskObservationContext

        override fun onStart(context: ScheduledTaskObservationContext) {
            ticks += "${context.targetClass.name}.${context.method.name}" to System.nanoTime()
        }

        fun startsOf(job: String): List<Long> = ticks.filter { it.first == job }.map { it.second }
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

        /** #326: the sweep's statement bound, and #837 D1's socket bound on top of it: statement + 5 s. */
        const val SWEEP_BOUND_SECONDS = 10L
        const val SOCKET_BOUND_SECONDS = 15L

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
