package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * **R1's second spike proof, through the real lifecycle** (scheduler design revision §7.2, A7):
 * a scheduled run that is executing when its instance shuts down ends `aborted` / `shutdown` —
 * a conclusive outcome — and is never lost; a run recorded while no instance dispatches waits,
 * `queued`, for the next one.
 *
 * Two instances share this module's Postgres and Redis, as two replicas would:
 * - **A** is this suite's own context, in API MODE (the module default): it serves REST, records
 *   runs and enqueues them, and never dispatches or reconciles by itself;
 * - **B** is booted inside the test with the scheduler ENABLED, so B's workers take the run.
 *
 * B is then closed — `ConfigurableApplicationContext.close()`, the path SIGTERM's shutdown hook
 * takes — while its execution sits in `pg_sleep` on the metadata Postgres (proved mid-query by
 * `pg_stat_activity`, so the stop cannot race ahead of the work). The lifecycle stops highest
 * phase first: B's admission gate closes and pauses polling, THEN the execution drain aborts the
 * execution as `ABORTED` / `shutdown`. Nothing reconciles on B any more; A's reconciler — a bean
 * in every context, ticked here by hand because A does not dispatch — reads the execution record
 * and records the run `aborted` / `shutdown`, which does not block the schedule.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SchedulerShutdownOrderE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var instanceA: ApplicationContext

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `the admission gate stops before the execution drain - the phases say so in every context`() {
        seedAuthRows()
        registerPostgresDatasource()
        slowPipelineId = createReleasedPipeline("test/shutdown_slow", "test/shutdown_slow.sql", SLOW_SQL)
        fastPipelineId = createReleasedPipeline("test/shutdown_fast", "test/shutdown_fast.sql", FAST_SQL)

        withClue("Spring stops the HIGHER phase first: the gate must outrank the drain") {
            phaseOf(ADMISSION) shouldBeGreaterThan phaseOf(DRAIN)
        }
    }

    @Test
    @Order(2)
    fun `a scheduled execution running when its instance stops ends aborted - shutdown, and is never lost`() {
        val schedule = createSchedule("test/shutdown_slow_daily", "test/shutdown_slow")
        val instanceB = bootDispatchingInstance()
        try {
            val runId = runNow(schedule)
            poll("B to launch the run") { run(schedule, runId).takeIf { it["state"].asText() == "running" } }
            poll("the scheduled statement to be mid-query") { liveSleeps().takeIf { it > 0 } }
        } finally {
            val started = System.nanoTime()
            instanceB.close() // SIGTERM's path: lifecycle stop by phase, then destroy.
            println("event=scheduler.shutdown_order.instance_closed close_ms=${(System.nanoTime() - started) / NANOS_PER_MILLI}")
        }

        val run = runsOf(schedule).single()
        val executionId = run["execution_id"].asText()
        withClue("the drain aborted the execution: the record is conclusive, not lost") {
            scalar("SELECT status FROM pipeline_executions WHERE execution_id = '$executionId'") shouldBe "ABORTED"
            scalar(
                "SELECT payload_json->>'reason' FROM execution_events WHERE execution_id = '$executionId' " +
                    "AND event_type = 'execution_aborted'",
            ) shouldBe "shutdown"
        }
        withClue("with no dispatching instance left, the run still reads running until a reconciler sees it") {
            run["state"].asText() shouldBe "running"
        }

        reconcileOnA() shouldBeGreaterThan 0

        val settled = run(schedule, run["id"].asText())
        settled["state"].asText() shouldBe "aborted"
        settled["reason"].asText() shouldBe "shutdown"
        settled["trail"].map { it["kind"].asText() } shouldContainExactly listOf("recorded", "claimed", "execution_started", "finished")
        withClue("an aborted run is conclusive: the schedule does not block (R6)") {
            schedule(schedule)["condition"].asText() shouldBe "enabled"
        }
        runsOf(schedule).size shouldBe 1
        scalar("SELECT COUNT(*) FROM pipeline_executions WHERE triggered_via = 'SCHEDULE' AND pipeline_id = '$slowPipelineId'") shouldBe "1"
    }

    @Test
    @Order(3)
    fun `a run recorded while no instance dispatches waits queued, and the next dispatching instance runs it`() {
        val schedule = createSchedule("test/shutdown_fast_daily", "test/shutdown_fast")
        val runId = runNow(schedule)

        // A records and enqueues; nothing on A dispatches. The run must simply wait.
        Thread.sleep(QUIET_MILLIS)
        run(schedule, runId)["state"].asText() shouldBe "queued"

        val instanceC = bootDispatchingInstance()
        try {
            val done =
                poll("the next dispatching instance to run it") { run(schedule, runId).takeIf { it["state"].asText() == "succeeded" } }
            done["trail"].map { it["kind"].asText() } shouldContainExactly listOf("recorded", "claimed", "execution_started", "finished")
        } finally {
            instanceC.close()
        }
        scalar("SELECT COUNT(*) FROM pipeline_executions WHERE triggered_via = 'SCHEDULE' AND pipeline_id = '$fastPipelineId'") shouldBe "1"
    }

    // ------------------------------------------------------------------------------- helpers

    /** A second replica against the same database and Redis, with the SAME secrets, dispatching. */
    private fun bootDispatchingInstance(): ConfigurableApplicationContext =
        SpringApplicationBuilder(DatapipelinesApplication::class.java).run(
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.data.redis.host=${redis.host}",
            "--spring.data.redis.port=${SharedE2e.redisPort}",
            "--spring.data.redis.password=",
            "--datapipelines.redis.host=${redis.host}",
            "--datapipelines.redis.port=${SharedE2e.redisPort}",
            "--datapipelines.jwt.secret=$JWT_SECRET",
            "--datapipelines.db.encryption-key=$ENCRYPTION_KEY",
            "--datapipelines.auth.oidc.providers[0].name=google",
            "--datapipelines.auth.oidc.providers[0].client-id=test-google-client-id",
            "--datapipelines.auth.oidc.providers[0].client-secret=test-google-client-secret",
            "--datapipelines.auth.oidc.providers[0].issuer-uri=${oidc.issuer}",
            "--datapipelines.auth.oidc.providers[0].display-name=Test google",
            "--datapipelines.auth.base-url=http://localhost:8080",
            "--datapipelines.scheduler.enabled=true",
            "--datapipelines.scheduler.tick-interval-seconds=1",
            "--datapipelines.scheduler.polling-interval-seconds=1",
        )

    /** A's reconciler, ticked by hand: the bean exists in API mode, only its recurring task does not run. */
    private fun reconcileOnA(): Int {
        val type = Class.forName("co.datapipelines.scheduler.RunReconciler")
        return type.getMethod("tick").invoke(instanceA.getBean(type)) as Int
    }

    private fun phaseOf(className: String): Int = (instanceA.getBean(Class.forName(className)) as SmartLifecycle).phase

    private fun session() = given().port(port).asSession(ADMIN_SESSION)

    private fun createSchedule(
        name: String,
        pipeline: String,
    ): String =
        session()
            .contentType(ContentType.JSON)
            .body(
                mapper.writeValueAsString(
                    mapOf(
                        "name" to name,
                        "payload" to mapOf("pipeline" to pipeline, "version" to "current"),
                        "cron" to "0 3 1 1 *",
                        "timezone" to "UTC",
                    ),
                ),
            ).post("/api/v1/schedules")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")

    private fun runNow(schedule: String): String =
        session()
            .post("/api/v1/schedules/$schedule/run")
            .then()
            .statusCode(202)
            .extract()
            .jsonPath()
            .getString("data.id")

    private fun run(
        schedule: String,
        run: String,
    ): JsonNode = mapper.readTree(session().get("/api/v1/schedules/$schedule/runs/$run").asString())["data"]

    private fun runsOf(schedule: String): List<JsonNode> =
        mapper.readTree(session().get("/api/v1/schedules/$schedule/runs").asString())["data"]["items"].toList()

    private fun schedule(id: String): JsonNode = mapper.readTree(session().get("/api/v1/schedules/$id").asString())["data"]

    private fun <T : Any> poll(
        what: String,
        probe: () -> T?,
    ): T =
        assertTimeoutPreemptively(Duration.ofSeconds(POLL_BUDGET_SECONDS), { "timed out waiting for $what" }) {
            var found: T? = null
            while (found == null) {
                found = probe()
                if (found == null) Thread.sleep(POLL_MILLIS)
            }
            found
        }

    private fun liveSleeps(): Int =
        scalar(
            "SELECT COUNT(*) FROM pg_stat_activity WHERE query LIKE '%pg_sleep($SLEEP_SECONDS)%' AND query NOT LIKE '%pg_stat_activity%'",
        )!!
            .toInt()

    /** The first column of the first row; the statement closes with its connection. */
    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery(query).use { row -> if (row.next()) row.getString(1) else null }
        }

    private fun createReleasedPipeline(
        name: String,
        templateId: String,
        sql: String,
    ): String {
        releaseTemplate(templateId, sql)
        val created =
            session()
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to name,
                            "nodes" to
                                listOf(
                                    mapOf(
                                        "id" to "read",
                                        "description" to "One read of known duration",
                                        "type" to "DQL",
                                        "source" to PG_DATASOURCE,
                                        "template" to mapOf("id" to templateId, "version" to 1),
                                        "output" to mapOf("target" to "caller"),
                                        "depends_on" to emptyList<String>(),
                                    ),
                                ),
                        ),
                    ),
                ).post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        val id = created.jsonPath().getString("data.id")
        session()
            .contentType(ContentType.JSON)
            .header(IF_MATCH, created.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    private fun releaseTemplate(
        templateId: String,
        sql: String,
    ) {
        val template =
            session()
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to templateId,
                            "dialect" to "POSTGRES",
                            "display_name" to "Shutdown order $templateId",
                            "description" to "A read of known duration",
                            "imports" to emptyList<Any>(),
                            "body" to sql,
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        session()
            .contentType(ContentType.JSON)
            .header(IF_MATCH, template.jsonPath().getString("data.body_hash"))
            .body("""{"name": "$templateId"}""")
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    /**
     * The metadata Postgres itself, as a datasource: the read has to be genuinely SLOW and H2 has
     * no sleep function (DatasourcePoolInvalidationE2eTest's lever). Testcontainers' URL carries
     * driver parameters the §5.6 guard refuses, so the bare form is registered.
     */
    private fun registerPostgresDatasource() {
        session()
            .contentType(ContentType.JSON)
            .body(
                mapper.writeValueAsString(
                    mapOf(
                        "name" to PG_DATASOURCE,
                        "display_name" to "Shutdown order Postgres",
                        "dialect" to "POSTGRES",
                        "jdbc_url" to postgres.jdbcUrl.substringBefore('?'),
                        "username" to postgres.username,
                        "password" to postgres.password,
                    ),
                ),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Shutdown order')",
                )
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_ID', 'sched-shutdown@e2e.test', 'Sched Shutdown', 'test', 'sched-shutdown-sub', TRUE, TRUE)
                    """.trimIndent(),
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                )
            }
        }
    }

    companion object {
        private const val IF_MATCH = "If-Match"
        private const val ADMISSION = "co.datapipelines.scheduler.SchedulerAdmission"
        private const val DRAIN = "co.datapipelines.web.config.ExecutionDrainLifecycle"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val POLL_BUDGET_SECONDS = 90L
        private const val POLL_MILLIS = 250L

        /** Several dispatcher ticks' worth of silence: long enough that a dispatching A would have run it. */
        private const val QUIET_MILLIS = 5_000L

        /** Far longer than the whole stop takes, so the drain — not the statement — ends the execution. */
        private const val SLEEP_SECONDS = 45
        private const val SLOW_SQL = "SELECT 'slept' AS v FROM (SELECT pg_sleep($SLEEP_SECONDS)) s"
        private const val FAST_SQL = "SELECT 'fast' AS v"

        private const val PG_DATASOURCE = "pg-shutdown-order"
        private const val WORKSPACE = "sched-shutdown"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "sched-shutdown@e2e.test", WORKSPACE)

        private var slowPipelineId: String = ""
        private var fastPipelineId: String = ""

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // Instance A stays in API mode (the module default): it never dispatches or reconciles by itself.
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
