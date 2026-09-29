package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
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
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.config.TaskManagementConfigUtils
import org.springframework.scheduling.config.TaskSchedulerRouter
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import org.springframework.test.util.ReflectionTestUtils
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * #316 over the REAL application: every `@Scheduled` job ticks on the jobs' own scheduler thread
 * (`dp-scheduled`), never on the SSE log streamer's (`dp-sse-log`), so a stalled tick no longer
 * stalls a replay — and the jobs' scheduler stops after both shutdown drains.
 *
 * On PRIVATE containers, the `PersistenceRestartE2eTest` shape: the suite holds a table lock, waits
 * for the stale sweep's own `UPDATE` to queue behind it (read off `pg_stat_activity`, where only
 * this application runs), and closes the application — none of which the shared containers' other
 * suites may see, and none of which another application's sweep may satisfy.
 *
 * - **Which thread** — read off Spring's own observation of every `@Scheduled` execution
 *   (`tasks.scheduled.execution`, which Boot wires into the registrar): [TickRecorder] is an
 *   `ObservationHandler` bean, so it is registered before the first tick and sees each job's startup
 *   tick — the job's own execution, scheduled by Spring, never a call the test makes.
 * - **The replay during a stalled tick** — rest-api §10.3's replay of a finished execution, served
 *   while the sweep's tick waits on a held lock (the brief's "held table lock for 3 s"). Until #316
 *   the tick held `dp-sse-log` and the replay waited for the lock to go.
 * - **The shutdown order** — `shutdown.drain_complete` < every writer's `persistence.drained` <
 *   `shutdown.scheduled_jobs_stopped`, off the application's own log.
 *
 * The web module's `ScheduledJobsSchedulerTest` is the small-context twin (and the one that proves a
 * tick cannot START once the context is closing).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(OutputCaptureExtension::class)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class ScheduledJobsSchedulerE2eTest {
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName("datapipelines_316").withUsername("dp").withPassword("dp")
    private val redis: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .withExposedPorts(REDIS_PORT)
    private val http = HttpClient.newHttpClient()
    private val mapper =
        com.fasterxml.jackson.databind
            .ObjectMapper()
    private lateinit var app: ConfigurableApplicationContext

    @BeforeAll
    fun start() {
        postgres.start()
        redis.start()
        DriverManager.getConnection(H2_URL, "sa", "sa").use { h2 ->
            h2.createStatement().execute("CREATE TABLE IF NOT EXISTS probe (id INT)")
            h2.createStatement().execute("INSERT INTO probe VALUES (1), (2)")
        }
        app = boot()
    }

    @AfterAll
    fun stop() {
        if (app.isActive) app.close()
        redis.stop()
        postgres.stop()
        oidc.close()
    }

    @Test
    @Order(1)
    fun `every scheduled job's own tick runs on dp-scheduled, and nothing else shares that scheduler`() {
        val recorder = app.getBean(TickRecorder::class.java)
        awaitCondition("a startup tick of each of the three jobs") { SCHEDULED_METHODS.all { job -> recorder.threadsOf(job).isNotEmpty() } }
        val observed = SCHEDULED_METHODS.associateWith { job -> recorder.threadsOf(job) }
        // Non-vacuity: the clue names the thread each job was actually observed on.
        withClue("the thread each job's ticks ran on, as Spring's observation saw them: $observed") {
            observed shouldBe SCHEDULED_METHODS.associateWith { setOf(JOBS_THREAD) }
            // Every @Scheduled task the application runs, not just the three named here.
            recorder.jobs() shouldBe SCHEDULED_METHODS
        }

        // The registrar's default is the `taskScheduler` bean — one thread — and the only bean wired to it
        // is Spring's @Scheduled processor (its router registers itself when it resolves the default).
        val scheduler = app.getBean(SCHEDULER_BEAN).shouldBeInstanceOf<ThreadPoolTaskScheduler>()
        val processor =
            app
                .getBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
                .shouldBeInstanceOf<ScheduledAnnotationBeanPostProcessor>()
        val registrar = ReflectionTestUtils.getField(processor, "registrar").shouldBeInstanceOf<ScheduledTaskRegistrar>()
        val router = registrar.scheduler.shouldBeInstanceOf<TaskSchedulerRouter>()
        ReflectionTestUtils.getField(router, "defaultScheduler").shouldBeInstanceOf<Supplier<*>>().get() shouldBeSameInstanceAs scheduler
        scheduler.poolSize shouldBe 1
        processor.scheduledTasks.map { it.task.toString() }.toSet() shouldBe SCHEDULED_METHODS
        app.beanFactory.getDependentBeans(SCHEDULER_BEAN).toList() shouldBe
            listOf(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    }

    @Test
    @Order(2)
    fun `a replay is served in its own time while the stale sweep's tick waits on a held lock`() {
        seedAdmin()
        val port = portOf(app)
        registerDatasource(port)
        val (executionId, liveIds) = executeToEnd(port, pipeline(port))
        // Non-vacuity: the replay serves this execution's ids before anything is held.
        replay(port, executionId).second shouldBe liveIds

        val stalled = replayWhileTheSweepWaits(port, executionId)
        // The measurement itself, for the evidence log — a green run should show its number too.
        println("316 replay during a stalled sweep tick: $stalled")
        withClue("$stalled; the sweep's ticks ran on ${app.getBean(TickRecorder::class.java).threadsOf(SWEEP)}") {
            stalled.ids shouldBe liveIds
            // The stall was real: the sweep's UPDATE was still queued behind the lock when it was let go.
            stalled.sweepWaitingAtRelease shouldBe true
            // And the replay did not wait for it.
            stalled.servedWhileHeld shouldBe true
            stalled.elapsedMs shouldBeLessThan REPLAY_BOUND_MS
        }
    }

    @Test
    @Order(3)
    fun `on close the jobs' scheduler stops after the execution drain and every writer's drain`(output: CapturedOutput) {
        app.close()
        val log = output.out
        val executionDrain = log.indexOf("event=shutdown.drain_complete")
        val drained = WRITERS.map { writer -> log.indexOf("event=persistence.drained writer=$writer") }
        val stopped = log.indexOf("event=shutdown.scheduled_jobs_stopped")
        withClue("the application's own shutdown log, in order: drain $executionDrain, writers $drained, scheduler $stopped") {
            (executionDrain >= 0) shouldBe true
            drained.filter { it <= executionDrain }.shouldBeEmpty()
            drained.filter { it >= stopped }.shouldBeEmpty()
            log.contains("event=shutdown.scheduled_jobs_stopped thread=$JOBS_THREAD") shouldBe true
            log.contains("event=shutdown.scheduled_jobs_incomplete") shouldBe false
        }
    }

    // ------------------------------------------------------------------ the stalled tick

    private data class StalledReplay(
        val elapsedMs: Long,
        val ids: List<Int>,
        val servedWhileHeld: Boolean,
        val sweepWaitingAtRelease: Boolean,
    )

    /**
     * Holds `SHARE` on `pipeline_executions` — it blocks the sweep's `UPDATE` and leaves every
     * `SELECT` (the replay's authority checks) alone — waits for the sweep's own statement to queue
     * behind it, then replays while a timer lets the lock go [HOLD_MS] later. The release is on its
     * own thread, never after the replay returns: with the tick on the replay's thread (the defect)
     * the replay can only finish once the lock goes, and that must read as a slow replay, not a hang.
     */
    private fun replayWhileTheSweepWaits(
        port: Int,
        executionId: String,
    ): StalledReplay =
        connection().use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE pipeline_executions IN SHARE MODE") }
            // The sweep ticks every 15 s: its next UPDATE queues behind the lock within one cadence.
            // Polled on its own connection — pg_stat_activity inside a transaction is a frozen snapshot.
            connection().use { watcher ->
                awaitCondition("the sweep's UPDATE waiting on the lock", SWEEP_WAIT_SECONDS) { sweepWaiting(watcher) }
            }
            val released = CountDownLatch(1)
            val waitingAtRelease = AtomicBoolean(false)
            val releaser =
                Thread {
                    Thread.sleep(HOLD_MS)
                    waitingAtRelease.set(connection().use { sweepWaiting(it) })
                    lock.rollback()
                    released.countDown()
                }.apply { start() }
            val (elapsedMs, ids) = replay(port, executionId)
            val servedWhileHeld = released.count == 1L
            releaser.join()
            StalledReplay(elapsedMs, ids, servedWhileHeld, waitingAtRelease.get())
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

    // ------------------------------------------------------------------ the application

    /** Spring's observation of each `@Scheduled` execution → the thread it ran on, per job. */
    class TickRecorder : ObservationHandler<ScheduledTaskObservationContext> {
        private val ticks = ConcurrentLinkedQueue<Pair<String, String>>()

        override fun supportsContext(context: Observation.Context): Boolean = context is ScheduledTaskObservationContext

        override fun onStart(context: ScheduledTaskObservationContext) {
            ticks += "${context.targetClass.name}.${context.method.name}" to Thread.currentThread().name
        }

        fun threadsOf(job: String): Set<String> = ticks.filter { it.first == job }.map { it.second }.toSet()

        fun jobs(): Set<String> = ticks.map { it.first }.toSet()
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
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to redis.getMappedPort(REDIS_PORT).toString(),
                "spring.data.redis.password" to "",
                "datapipelines.redis.host" to redis.host,
                "datapipelines.redis.port" to redis.getMappedPort(REDIS_PORT).toString(),
                "datapipelines.jwt.secret" to JWT_SECRET,
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

    private fun portOf(context: ConfigurableApplicationContext): Int = (context as WebServerApplicationContext).webServer.port

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun seedAdmin() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$ADMIN', '$ADMIN_EMAIL', 'Scheduler 316', 'test', 'scheduler-316', TRUE, TRUE)",
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN', 'workspace_admin')",
                )
            }
        }
    }

    private fun session(port: Int): RequestSpecification =
        given()
            .port(port)
            .header("Cookie", E2eSession.cookieHeader(SESSION))
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)

    private fun registerDatasource(port: Int) {
        session(port)
            .contentType(ContentType.JSON)
            .body(
                """{"name": "$DATASOURCE", "display_name": "316 scheduler", "dialect": "H2", """ +
                    """"jdbc_url": "$H2_URL", "username": "sa", "password": "sa"}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** A template and a pipeline, both RELEASED; the pipeline's id (the PersistenceRestartE2eTest setup). */
    private fun pipeline(port: Int): String {
        session(port)
            .contentType(ContentType.JSON)
            .body(
                """{"id": "$TEMPLATE", "dialect": "H2", "display_name": "s", "description": "316 scheduler.", """ +
                    """"imports": [], "body": "SELECT id FROM probe"}""",
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        val templateHash =
            session(port)
                .queryParam("name", TEMPLATE)
                .`when`()
                .get("/api/v1/templates")
                .then()
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        session(port)
            .contentType(ContentType.JSON)
            .header("If-Match", templateHash)
            .body("""{"name": "$TEMPLATE"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
        val created =
            session(port)
                .contentType(ContentType.JSON)
                .body(
                    """
                    {"schema_version": 1, "name": "$PIPELINE", "display_name": "s", "description": "316 scheduler.", "parameters": {},
                     "nodes": [{"id": "rows", "description": "rows", "type": "DQL", "source": "$DATASOURCE",
                                "template": {"id": "$TEMPLATE", "version": 1}, "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        val id = created.jsonPath().getString("data.id")
        session(port)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    /** One REST execution read to the end of its live stream: its id and the `id:` sequence the stream carried. */
    private fun executeToEnd(
        port: Int,
        pipelineId: String,
    ): Pair<String, List<Int>> {
        val body =
            http
                .send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                        .header("Cookie", E2eSession.cookieHeader(SESSION))
                        .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
        val executionId =
            E2eSse
                .parseEvents(body, mapper)
                .first { it.first == "execution_started" }
                .second["execution_id"]
                .asText()
        return executionId to ids(body)
    }

    /** rest-api §10.3's replay, read to its end: how long it took, and the `id:` sequence it served. */
    private fun replay(
        port: Int,
        executionId: String,
    ): Pair<Long, List<Int>> {
        val started = System.nanoTime()
        val body =
            http
                .send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/api/v1/executions/$executionId/events"))
                        .header("Cookie", E2eSession.cookieHeader(SESSION))
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(REPLAY_TIMEOUT_SECONDS))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) to ids(body)
    }

    private fun ids(body: String): List<Int> = body.lines().filter { it.startsWith("id:") }.map { it.removePrefix("id:").trim().toInt() }

    private fun awaitCondition(
        what: String,
        seconds: Long = AWAIT_SECONDS,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not within $seconds s: $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val REDIS_PORT = 6379
        const val SCHEDULER_BEAN = "taskScheduler"

        /** The spec's name (#316), a literal — never read back from the class under test. */
        const val JOBS_THREAD = "dp-scheduled"
        const val SWEEP = "co.datapipelines.web.config.StaleExecutionSweepScheduler.sweep"
        val SCHEDULED_METHODS =
            setOf(
                SWEEP,
                "co.datapipelines.web.config.DatasourcePoolReaperScheduler.reap",
                "co.datapipelines.web.config.ExecutionEventRetentionScheduler.retain",
            )
        val WRITERS = listOf("audit", "execution_events", "replay_log")

        /** The brief's held lock: three seconds. */
        const val HOLD_MS = 3_000L

        /** A replay of a handful of events, on a loaded box — far under the hold, which is the point. */
        const val REPLAY_BOUND_MS = 1_500L
        const val REPLAY_TIMEOUT_SECONDS = 30L
        const val SWEEP_WAIT_SECONDS = 30L
        const val AWAIT_SECONDS = 20L
        const val POLL_MS = 50L
        val RUN_ID: String = Integer.toHexString(SecureRandom().nextInt(0x10000))
        val DATASOURCE = "h2-316-$RUN_ID"
        val H2_URL = "jdbc:h2:mem:p316_$RUN_ID;DB_CLOSE_DELAY=-1"
        val TEMPLATE = "test/p316_$RUN_ID.sql"
        val PIPELINE = "test/p316_$RUN_ID"
        val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ADMIN: UUID = UUID.randomUUID()
        const val ADMIN_EMAIL = "scheduler-316@datapipelines.test"
        val JWT_SECRET: String = E2eSession.newSecret()
        val SESSION: String get() = E2eSession.jwt(JWT_SECRET, ADMIN.toString(), ADMIN_EMAIL)
        val ENCRYPTION_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val oidc = OidcDiscoveryStub()
    }
}
