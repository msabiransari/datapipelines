package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import co.datapipelines.DatapipelinesApplication
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
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
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.HexFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * #858 over the REAL application: the managed lake's settlement tick (`LakeSettlementScheduler`, on the application's one
 * scheduler thread) ends a metadata statement that an unresponsive server never answers, at its derived bound. The app
 * boots with the lake enabled on this class's OWN Postgres (a paused shared one would stall every later suite), Redis and
 * MinIO, and the tick is the real one: Spring's observation of the `@Scheduled` execution records its start and thread.
 *
 * The tick's first statement — the mode read `SELECT mode = … FROM lake_control` — is held behind an `ACCESS EXCLUSIVE`
 * lock (nothing else in the running application reads `lake_control`), observed waiting in `pg_stat_activity`, and only
 * then is the container `docker pause`d: the observation starts at the confirmed in-flight statement. Its 5 s
 * `queryTimeout` fires, but the cancel is a second connection the paused server never reads, so only the SOCKET bound
 * (5 s + 5 s = 10 s, #858) can end the read; the driver reports it when its own cancel attempt gives up
 * (`cancelSignalTimeout`, 10 s), so the deferred-tick line (`event=lake.settle.sweep_deferred … sql_state=08006`) appears
 * at max(10, 5 + 10) = 15 s after the statement began — with the lock still held and the server still paused. Once the
 * server is back and the lock released, the next tick runs and succeeds (its `event=lake.settle.sweep` line, and
 * `lake_control.last_sweep_at` moves).
 *
 * Red without the socket bound: the paused server's statement never ends, so no deferred line appears within the 15 s
 * plus [MARGIN_SECONDS] window; the case fails there and restores the environment in `finally` (container unpaused, lock
 * rolled back), so a red run cannot hang the suite. The pool carries no socket bound of its own (asserted), so the
 * template's is the only one that can end the read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class LakeSettlementMetadataBoundsE2eTest {
    private val password = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName("datapipelines_858").withUsername("dp").withPassword(password)
    private val redis: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .withExposedPorts(REDIS_PORT)
    private val minio: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(SharedE2e.MINIO_IMAGE))
            .withEnv("MINIO_ROOT_USER", STORE_ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", password)
            .withCommand("server", "/data")
            .withExposedPorts(MINIO_PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))
    private lateinit var app: ConfigurableApplicationContext
    private lateinit var lines: SettlementLines
    private val schedulerLogger = LoggerFactory.getLogger("co.datapipelines.web.config.LakeSettlementScheduler") as Logger
    private val sweepLogger = LoggerFactory.getLogger("co.datapipelines.lake.settle.LakeSettlementSweep") as Logger

    @BeforeAll
    fun start() {
        postgres.start()
        redis.start()
        minio.start()
        createBucket()
        app = boot()
        lines = SettlementLines(app.getBean(TickRecorder::class.java)).also { it.start() }
        schedulerLogger.addAppender(lines)
        sweepLogger.addAppender(lines)
    }

    @AfterAll
    fun stop() {
        if (::lines.isInitialized) {
            schedulerLogger.detachAppender(lines)
            sweepLogger.detachAppender(lines)
            lines.stop()
        }
        if (::app.isInitialized && app.isActive) app.close()
        minio.stop()
        redis.stop()
        postgres.stop()
        oidc.close()
    }

    @Test
    @Order(0)
    fun `a different scheduler thread with the same name cannot supply the deferred-tick witness`() {
        val recorder = app.getBean(TickRecorder::class.java)
        // The real tick's thread is known first, so the witness filter has an identity to compare against.
        awaitCondition("the boot settlement tick", AWAIT_SECONDS) { recorder.thread() != null }
        val before = System.nanoTime()
        val name = requireNotNull(recorder.thread()).name
        val foreign = Thread({ schedulerLogger.warn("event=lake.settle.sweep_deferred foreign-witness sql_state=08006") }, name)
        foreign.start()
        foreign.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        foreign.isAlive shouldBe false
        lines.since(before, DEFERRED) shouldBe emptyList()
    }

    @Test
    @Order(1)
    fun `with the metadata Postgres paused the settlement tick's held statement ends at its derived bound, and the next tick runs`() {
        val recorder = app.getBean(TickRecorder::class.java)
        val pool = app.getBean("dataSource") as HikariDataSource
        // Non-vacuity: the pool sets no socket bound of its own, so only the runtime template's can end the read.
        // Booleans, so a failure never prints the connection string itself.
        withClue("the metadata pool's driver property names ${pool.dataSourceProperties.keys}; socketTimeout in its URL") {
            pool.dataSourceProperties.containsKey("socketTimeout") shouldBe false
            ("socketTimeout" in pool.jdbcUrl) shouldBe false
        }
        // The boot tick has ended, so the lock below cannot catch it half-way and a NEW tick is the one held.
        awaitCondition("the boot settlement tick to end", AWAIT_SECONDS) { recorder.stops().isNotEmpty() }
        holdPauseAndObserve(recorder, pool)
        nextTickSucceeds()
    }

    /**
     * Holds the tick's mode read on the lock, pauses the container once it is confirmed in flight, and observes the
     * deferred-tick line while the server is still paused and the lock still held. Restores both in `finally`.
     */
    private fun holdPauseAndObserve(
        recorder: TickRecorder,
        pool: HikariDataSource,
    ) {
        val docker = DockerClientFactory.instance().client()
        var paused = false
        connection().use { lock ->
            lock.autoCommit = false
            val ticksBefore = recorder.starts().size
            lock.createStatement().use { it.execute("LOCK TABLE lake_control IN ACCESS EXCLUSIVE MODE") }
            try {
                val anchor: Long
                connection().use { watcher ->
                    awaitCondition("the settlement tick's mode read waiting on the lock", TICK_WAIT_SECONDS) { modeReadWaiting(watcher) }
                    // The observation starts here: the statement is confirmed in flight on a connection the tick holds.
                    anchor = System.nanoTime()
                    docker.pauseContainerCmd(postgres.containerId).exec()
                    paused = true
                }
                recorder.starts().size shouldBe ticksBefore + 1
                val tickStartedAt = recorder.starts().last()
                val pausedAfterMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tickStartedAt)
                // The pause landed before the 5 s queryTimeout's cancel could reach a live server.
                withClue("tick start -> paused (at most) ${pausedAfterMs}ms") {
                    pausedAfterMs shouldBeLessThan TimeUnit.SECONDS.toMillis(STATEMENT_BOUND_SECONDS)
                }
                val failure = awaitLine(PAUSED_END_SECONDS + MARGIN_SECONDS, anchor, DEFERRED)
                verdict(failure, anchor, tickStartedAt, pausedAfterMs, recorder, pool)
            } finally {
                if (paused) docker.unpauseContainerCmd(postgres.containerId).exec()
                runCatching { lock.rollback() }
                println("858 cleanup: unpaused=$paused lock rolled back")
            }
        }
    }

    /** Observed while the server is STILL paused and the lock still held: only the socket bound can have ended the read. */
    @Suppress("LongParameterList")
    private fun verdict(
        failure: Pair<Long, String>,
        anchor: Long,
        tickStartedAt: Long,
        pausedAfterMs: Long,
        recorder: TickRecorder,
        pool: HikariDataSource,
    ) {
        val fromAnchorMs = TimeUnit.NANOSECONDS.toMillis(failure.first - anchor)
        val fromTickMs = TimeUnit.NANOSECONDS.toMillis(failure.first - tickStartedAt)
        val thread = requireNotNull(recorder.thread())
        println(
            "858 paused-server settlement tick: thread=${thread.name}#${System.identityHashCode(thread)} " +
                "tick->paused ${pausedAfterMs}ms in-flight->deferred ${fromAnchorMs}ms tick->deferred ${fromTickMs}ms " +
                "pool connectionTimeout=${pool.connectionTimeout}ms validationTimeout=${pool.validationTimeout}ms " +
                "maximumPoolSize=${pool.maximumPoolSize} driverProperties=${pool.dataSourceProperties.keys} " +
                "line=${failure.second}",
        )
        withClue("in-flight->deferred ${fromAnchorMs}ms, tick->deferred ${fromTickMs}ms; line=${failure.second}") {
            fromTickMs shouldBeGreaterThanOrEqual TimeUnit.SECONDS.toMillis(SOCKET_BOUND_SECONDS)
            fromTickMs shouldBeLessThanOrEqual TimeUnit.SECONDS.toMillis(PAUSED_END_SECONDS + MARGIN_SECONDS)
            fromAnchorMs shouldBeLessThanOrEqual TimeUnit.SECONDS.toMillis(PAUSED_END_SECONDS + MARGIN_SECONDS)
            failure.second shouldContain "sql_state=$CONNECTION_FAILURE_SQL_STATE"
        }
    }

    /** The server is back and the lock released: the next tick runs and succeeds on the same thread. */
    private fun nextTickSucceeds() {
        val recoveryStartedAt = System.nanoTime()
        val recoveryClock = connection().use { clock(it) }
        val healthy = awaitLine(NEXT_TICK_SECONDS, recoveryStartedAt, HEALTHY)
        val sweptAt = connection().use { lastSweepAt(it) }
        println(
            "858 recovery: healthy tick ${TimeUnit.NANOSECONDS.toMillis(healthy.first - recoveryStartedAt)}ms after recovery; " +
                "last_sweep_at=$sweptAt (database clock at recovery $recoveryClock); line=${healthy.second}",
        )
        withClue("after recovery: healthy line=${healthy.second}, last_sweep_at=$sweptAt, recovery clock=$recoveryClock") {
            lines.since(recoveryStartedAt, DEFERRED) shouldBe emptyList()
            requireNotNull(sweptAt).after(recoveryClock) shouldBe true
        }
    }

    /** Polls only this application's settlement-thread lines, recorded synchronously with a monotonic timestamp. */
    private fun awaitLine(
        seconds: Long,
        anchorNanos: Long,
        event: String,
    ): Pair<Long, String> {
        val deadline = anchorNanos + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val line = lines.since(anchorNanos, event).firstOrNull()
            if (line != null) return line
            check(System.nanoTime() < deadline) { "no $event line within $seconds s of the anchor" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun modeReadWaiting(connection: Connection): Boolean =
        connection
            .prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                    "AND query LIKE 'SELECT mode = % FROM lake_control WHERE id = 1'",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1) > 0
                }
            }

    private fun clock(connection: Connection): Timestamp =
        connection.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r -> r.next().let { r.getTimestamp(1) } } }

    private fun lastSweepAt(connection: Connection): Timestamp? =
        connection.createStatement().use { s ->
            s.executeQuery("SELECT last_sweep_at FROM lake_control WHERE id = 1").use { r -> r.next().let { r.getTimestamp(1) } }
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

    private fun createBucket() =
        S3Client
            .builder()
            .endpointOverride(URI.create(storeEndpoint()))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(STORE_ACCESS, password)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build()
            .use { s3 -> s3.createBucket { it.bucket(BUCKET) } }

    private fun storeEndpoint(): String = "http://${minio.host}:${minio.getMappedPort(MINIO_PORT)}"

    /** Spring's observation of each settlement execution: when it started and ended, and on which thread. */
    class TickRecorder : ObservationHandler<ScheduledTaskObservationContext> {
        private val started = ConcurrentLinkedQueue<Long>()
        private val stopped = ConcurrentLinkedQueue<Long>()
        private val settlementThread = AtomicReference<Thread>()

        override fun supportsContext(context: Observation.Context): Boolean = context is ScheduledTaskObservationContext

        override fun onStart(context: ScheduledTaskObservationContext) {
            if (job(context) != SETTLEMENT) return
            settlementThread.set(Thread.currentThread())
            started += System.nanoTime()
        }

        override fun onStop(context: ScheduledTaskObservationContext) {
            if (job(context) == SETTLEMENT) stopped += System.nanoTime()
        }

        private fun job(context: ScheduledTaskObservationContext) = "${context.targetClass.name}.${context.method.name}"

        fun starts(): List<Long> = started.toList()

        fun stops(): List<Long> = stopped.toList()

        fun thread(): Thread? = settlementThread.get()

        fun ownsCurrentThread(): Boolean = Thread.currentThread() === settlementThread.get()
    }

    /** Thread identity, not the scheduler thread's shared name, ties a line to this context's observed settlement tick. */
    private class SettlementLines(
        private val ticks: TickRecorder,
    ) : AppenderBase<ILoggingEvent>() {
        private val recorded = ConcurrentLinkedQueue<Pair<Long, String>>()

        override fun append(event: ILoggingEvent) {
            if (ticks.ownsCurrentThread()) recorded += System.nanoTime() to event.formattedMessage
        }

        fun since(
            anchorNanos: Long,
            event: String,
        ): List<Pair<Long, String>> = recorded.filter { it.first >= anchorNanos && it.second.startsWith(event) }
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
                "datapipelines.db.encryption-key" to E2eSession.newSecret(),
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.lake.enabled" to "true",
                "datapipelines.lake.name" to "lake",
                "datapipelines.lake.storage.bucket" to BUCKET,
                "datapipelines.lake.storage.region" to "us-east-1",
                "datapipelines.lake.storage.endpoint" to storeEndpoint(),
                "datapipelines.lake.storage.path-style" to "true",
                "datapipelines.lake.storage.access-key" to STORE_ACCESS,
                "datapipelines.lake.storage.secret-key" to password,
            )
        // As command-line arguments, NOT builder `properties(...)` — PersistenceRestartE2eTest says why.
        return SpringApplicationBuilder(DatapipelinesApplication::class.java, TickRecorderConfiguration::class.java)
            .run(*properties.map { (k, v) -> "--$k=$v" }.toTypedArray())
    }

    private companion object {
        const val REDIS_PORT = 6379
        const val MINIO_PORT = 9000
        const val BUCKET = "dp-lake-858"
        const val STORE_ACCESS = "dplake858"
        const val SECRET_BYTES = 16
        const val SETTLEMENT = "co.datapipelines.web.config.LakeSettlementScheduler.sweep"
        const val DEFERRED = "event=lake.settle.sweep_deferred"
        const val HEALTHY = "event=lake.settle.sweep "

        /** The runtime's statement bound — the issue's number, a literal; never read back from the class under test. */
        const val STATEMENT_BOUND_SECONDS = 5L

        /** #858: the socket bound, the statement bound plus five seconds — a literal. */
        const val SOCKET_BOUND_SECONDS = 10L

        /** pgjdbc's `cancelSignalTimeout` default: the failed read is reported when the cancel on the paused server gives up. */
        const val DRIVER_CANCEL_WAIT_SECONDS = 10L

        /** Where the paused server's statement ends: max(socket bound, statement bound + the driver's cancel wait) = 15 s. */
        val PAUSED_END_SECONDS = maxOf(SOCKET_BOUND_SECONDS, STATEMENT_BOUND_SECONDS + DRIVER_CANCEL_WAIT_SECONDS)

        /** The declared margin on the 15 s end: scheduling and log latency on a loaded box. */
        const val MARGIN_SECONDS = 3L

        /** The settlement cadence (60 s, `fixedDelay`) plus a sibling job's turn on the one thread and a margin. */
        const val TICK_WAIT_SECONDS = 100L
        const val NEXT_TICK_SECONDS = 100L
        const val AWAIT_SECONDS = 30L
        const val POLL_MS = 50L

        /** pgjdbc's SQLState for a connection that failed mid-statement. */
        const val CONNECTION_FAILURE_SQL_STATE = "08006"
        val oidc = OidcDiscoveryStub()
    }
}
