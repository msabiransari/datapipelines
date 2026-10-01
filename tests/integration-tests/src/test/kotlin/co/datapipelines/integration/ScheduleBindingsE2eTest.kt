package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * **The bindings, end to end** (#9 slice 3): a pipeline with DATE parameters bound to TODAY and
 * YESTERDAY, composed over a child that inherits the frozen reference (A13), fired by the REAL
 * dispatcher on the product's HTTP surface.
 *
 * The clock is [SchedulerClockTestConfiguration]'s (#342): the suite's context replaces the
 * `schedulerClock` bean with one pinned a few seconds after midnight New York, so the scenario
 * holds at any wall-clock moment. The schedule fires at 23:45 America/New_York, and the test
 * moves `next_due_at` back to YESTERDAY's 23:45 — yesterday computed from the PINNED now, never
 * from the JVM clock, so a real-midnight crossing mid-suite cannot re-pick the occurrence. At
 * the pinned instant yesterday's 23:45 is the latest occurrence the pattern has produced, which
 * is exactly what the latest-occurrence catch-up fires (`missed_run_policy = latest`); before
 * the pin (#342) a run in the 23:45–24:00 New York window made today's 23:45 the latest, and
 * the dispatcher fired that one instead. The run's frozen `reference_at` is therefore 23:45 on
 * day D while its `started_at` — stamped by the ledger from the SAME pinned clock — is 00:00:05
 * on D+1: the brief's scenario by construction. (The execution's own start, which `$current_date`
 * reads, is the executor's real clock — see the Order-2 assertion.) TODAY resolves to D — the
 * SCHEDULE'S logical day, not the start's — and YESTERDAY to D−1 (a calendar day, through DST
 * or not). The resolved values are read from the run's own `reference_at`, never re-derived in
 * the test's arithmetic.
 *
 * The literal leg rides along: `note` is a plain STRING parameter whose value is the text
 * `"TODAY"` — it must arrive as the string (§9.5 [3]: keywords live only in explicit bindings).
 *
 * **The child's reference is asserted through the execution rows**: the child's
 * `parameters_json` carries the date resolved on the parent's frozen reference, and the carrier
 * itself (childRequest copying `ctx.reference`) is pinned by `SubPipelineExecutionRunnerTest` —
 * no `pipeline_executions` column exposes a reference, so rows cannot show it directly.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(SchedulerClockTestConfiguration::class)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduleBindingsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `the bound schedule fires late, on the occurrence the test made due`() {
        seedAuthRows()
        createAndReleasePipeline(CHILD_NAME, childBody())
        createAndReleasePipeline(PARENT_NAME, parentBody())

        val created = createSchedule()
        check(created.statusCode == 201) { "schedule create → ${created.statusCode}: ${created.body().asString().take(600)}" }
        scheduleId = created.jsonPath().getString("data.id")
        created.jsonPath().getString("data.payload.parameter_bindings.as_of_date.name") shouldBe "TODAY"

        // A viewer cannot smuggle bindings past the permission row: the PUT is 403 before anything.
        withClue("a viewer's PUT with bindings is 403") {
            session(VIEWER_SESSION)
                .contentType(ContentType.JSON)
                .header("If-Match", created.header("ETag"))
                .body(createdScheduleBody())
                .put("/api/v1/schedules/$scheduleId")
                .then()
                .statusCode(403)
        }

        // The occurrence is due: yesterday's 23:45 New York, on the clock the dispatcher sees —
        // the pinned one (#342), not the JVM's, so the pin decides which occurrence is latest.
        // Its actual start is on the next day (the pinned now is just past midnight).
        val due =
            LocalDate
                .ofInstant(SchedulerClockTestConfiguration.SCHEDULER_NOW, NY_ZONE)
                .minusDays(1)
                .atTime(23, 45)
                .atZone(NY_ZONE)
                .toInstant()
        sql("UPDATE schedules SET next_due_at = '$due' WHERE id = '$scheduleId'")

        val run =
            poll("the bound run to finish") {
                runsOf(scheduleId).firstOrNull { it["state"].asText() == "succeeded" }
            }
        withClue("the run the dispatcher fired was the occurrence we made due") {
            Instant.parse(run["reference_at"].asText()) shouldBe due
            listOf("catch_up", "cron").contains(run["origin"].asText()) shouldBe true
        }

        // The frozen reference is late on day D; the run's start is on D+1 — the brief's
        // scenario, pinned by construction: the ledger stamps `started_at` from the same
        // controlled clock the dispatcher used for the occurrence, and the pin sits just past
        // midnight (#342).
        logicalDay = LocalDate.ofInstant(Instant.parse(run["reference_at"].asText()), NY_ZONE)
        val startDay = LocalDate.ofInstant(Instant.parse(run["started_at"].asText()), NY_ZONE)
        withClue("the scenario is the one the brief names: frozen on D, started on D+1") {
            startDay shouldBe logicalDay.plusDays(1)
        }
        finishedRun = run
    }

    @Test
    @Order(2)
    fun `the run resolved TODAY to the logical day, carried it to the child, and kept the literal`() {
        val run = finishedRun.shouldNotBeNull()

        // The snapshot shows what the run executed with: TODAY = D, YESTERDAY = D−1, and the
        // literal STRING "TODAY" untouched.
        val resolved = run["prepared"]["resolved_parameters"]
        resolved.shouldNotBeNull()
        resolved["as_of_date"].asText() shouldBe logicalDay.toString()
        resolved["previous_date"].asText() shouldBe logicalDay.minusDays(1).toString()
        resolved["note"].asText() shouldBe "TODAY"

        // The root execution's durable row records the whole bound Context; the three inputs are
        // in it with exactly the resolved values.
        val rootExecution = run["execution_id"].asText()
        val rootParameters = mapper.readTree(executionParameters(rootExecution))
        withClue("root parameters_json: $rootParameters") {
            rootParameters["as_of_date"].asText() shouldBe logicalDay.toString()
            rootParameters["previous_date"].asText() shouldBe logicalDay.minusDays(1).toString()
            rootParameters["note"].asText() shouldBe "TODAY"
            // §5.1's "two todays": the pipeline's own $current_date is still the ACTUAL start's
            // date — the frozen reference changes bindings, never the Context. The actual start
            // is the execution row's own stamp, which is the executor's real clock, NOT the
            // scheduler's pinned one (#342): anchoring there keeps the assertion about the
            // semantics (bindings frozen, Context not) rather than about two clocks agreeing on
            // the calendar day.
            rootParameters["current_date"].asText() shouldBe executionStartedDate(rootExecution).toString()
        }

        // The child executed with the date resolved on the PARENT's frozen reference — the same
        // logical day, carried through the composition (A13; the carrier itself is pinned at the
        // unit seam, see the class KDoc).
        val childExecution =
            scalar("SELECT execution_id::text FROM pipeline_executions WHERE parent_execution_id = '$rootExecution'")
                .shouldNotBeNull()
        val childParameters = mapper.readTree(executionParameters(childExecution))
        withClue("child parameters_json: $childParameters") {
            childParameters["as_of_date"].asText() shouldBe logicalDay.toString()
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private fun session(jwt: String) = given().port(port).asSession(jwt)

    private fun createSchedule(): Response {
        val request =
            session(AUTHOR_SESSION)
                .contentType(ContentType.JSON)
                .body(createdScheduleBody())
        return request.post("/api/v1/schedules")
    }

    private fun createdScheduleBody(): String =
        mapper.writeValueAsString(
            mapOf(
                "name" to SCHEDULE_NAME,
                "payload" to
                    mapOf(
                        "pipeline" to PARENT_NAME,
                        "version" to "current",
                        "parameter_bindings" to
                            mapOf(
                                "as_of_date" to mapOf("source" to "keyword", "name" to "TODAY"),
                                "previous_date" to mapOf("source" to "keyword", "name" to "YESTERDAY"),
                            ),
                    ),
                "parameters" to mapOf("note" to "TODAY"),
                "cron" to CRON,
                "timezone" to ZONE,
                "missed_run_policy" to "latest",
            ),
        )

    private fun runsOf(schedule: String): List<JsonNode> =
        mapper.readTree(session(ADMIN_SESSION).get("/api/v1/schedules/$schedule/runs?limit=50").asString())["data"]["items"].toList()

    /** The execution row's `parameters_json` — what the request that started it carried. */
    private fun executionParameters(executionId: String): String =
        scalar("SELECT parameters_json::text FROM pipeline_executions WHERE execution_id = '$executionId'").shouldNotBeNull()

    /** The execution's own start date in UTC — the executor's stamp, not the scheduler's clock. */
    private fun executionStartedDate(executionId: String): LocalDate =
        LocalDate.parse(
            scalar("SELECT (started_at AT TIME ZONE 'UTC')::date::text FROM pipeline_executions WHERE execution_id = '$executionId'")
                .shouldNotBeNull(),
        )

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

    private fun createAndReleasePipeline(
        name: String,
        body: String,
    ) {
        val created = session(ADMIN_SESSION).contentType(ContentType.JSON).body(body).post("/api/v1/pipelines")
        check(created.statusCode == 201) { "pipeline create $name → ${created.statusCode}: ${created.body().asString().take(600)}" }
        val id = created.jsonPath().getString("data.id")
        val released =
            session(ADMIN_SESSION)
                .header(IF_MATCH, created.jsonPath().getString("data.body_hash"))
                .post("/api/v1/pipelines/$id/release")
        check(released.statusCode == 200) { "pipeline release $name → ${released.statusCode}: ${released.body().asString().take(600)}" }
    }

    /**
     * The parent: DATE parameters bound by the schedule, one STRING parameter whose value is the
     * LITERAL text "TODAY", and a PIPELINE node mapping `as_of_date` into the child (§12.9's
     * exact reference form).
     */
    private fun parentBody(): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to PARENT_NAME,
                "display_name" to "Bindings parent",
                "description" to "Resolves its bindings, passes the date down",
                "parameters" to
                    mapOf(
                        "as_of_date" to mapOf("type" to "DATE", "required" to true, "description" to "The logical day."),
                        "previous_date" to mapOf("type" to "DATE", "required" to true, "description" to "The day before."),
                        "note" to mapOf("type" to "STRING", "description" to "A literal, keyword-shaped text."),
                    ),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "comp",
                            "type" to "PIPELINE",
                            "description" to "Runs the child with the resolved date",
                            "pipeline" to mapOf("name" to CHILD_NAME, "version" to 1),
                            "parameters" to mapOf("as_of_date" to "\${as_of_date}"),
                        ),
                    ),
            ),
        )

    /** The child: one DATE parameter, read by a calculator node — no datasource needed. */
    private fun childBody(): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to CHILD_NAME,
                "display_name" to "Bindings child",
                "description" to "Reads the date its parent resolved",
                "parameters" to
                    mapOf(
                        "as_of_date" to mapOf("type" to "DATE", "required" to true, "description" to "The inherited day."),
                    ),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "fq",
                            "type" to "CALCULATOR",
                            "kind" to "fiscal_quarter",
                            "context_key" to "run_fiscal_quarter",
                            "inputs" to mapOf("date" to "\$as_of_date", "fiscal_start" to "01-01"),
                        ),
                    ),
            ),
        )

    private fun seedAuthRows() {
        sql(
            """
            INSERT INTO workspaces (id, name, display_name) VALUES
                ('$WORKSPACE_ID', '$WORKSPACE', 'Bindings E2E')
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'bind-admin@e2e.test', 'Bind Admin', 'test', 'bind-admin-sub', TRUE, TRUE),
                ('$AUTHOR_ID', 'bind-author@e2e.test', 'Bind Author', 'test', 'bind-author-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'bind-viewer@e2e.test', 'Bind Viewer', 'test', 'bind-viewer-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$AUTHOR_ID', 'author'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')
            """.trimIndent(),
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    /** The first column of the first row; the statement closes with its connection. */
    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery(query).use { row -> if (row.next()) row.getString(1) else null }
        }

    private companion object {
        private const val IF_MATCH = "If-Match"
        private const val POLL_BUDGET_SECONDS = 90L
        private const val POLL_MILLIS = 250L

        /** 23:45 in the schedule's zone: the occurrence the brief's scenario freezes. */
        private const val CRON = "45 23 * * *"
        private const val ZONE = "America/New_York"
        private val NY_ZONE: ZoneId = ZoneId.of(ZONE)

        private const val WORKSPACE = "bind-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val AUTHOR_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()

        private const val CHILD_NAME = "test/bind_child"
        private const val PARENT_NAME = "test/bind_parent"
        private const val SCHEDULE_NAME = "test/bind_nightly"

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "bind-admin@e2e.test", WORKSPACE)
        private val AUTHOR_SESSION get() = E2eSession.jwt(JWT_SECRET, AUTHOR_ID, "bind-author@e2e.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "bind-viewer@e2e.test", WORKSPACE)

        private var scheduleId: String = ""
        private var finishedRun: JsonNode? = null
        private var logicalDay: LocalDate = LocalDate.now()

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

            // This context DISPATCHES (the module default is API mode), on a one-second clock,
            // with a 48 h catch-up window: yesterday's 23:45 must always still be catchable,
            // whatever wall-clock moment the suite runs at.
            registry.add("datapipelines.scheduler.enabled") { "true" }
            registry.add("datapipelines.scheduler.tick-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.polling-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.catch-up-max-age-seconds") { "172800" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
