package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * **The scheduler, end to end** (#9 slice 1): every leg over the product's own HTTP surface, in a
 * context whose scheduler DISPATCHES (the module default is API mode — `config/application
 * .properties`), with a one-second tick so the story runs in seconds.
 *
 * What only this suite can prove, each leg chosen for what it would catch:
 *
 * - **Run now fires as the system identity** — the execution is `triggered_via = SCHEDULE`,
 *   `executed_by` is the `system` row, the run's trail reads recorded → claimed → started →
 *   finished, and nobody was attached to a stream (R2, record §5.4).
 * - **execution_events parity** — the scheduled execution's durable record is the interactive
 *   run's, row for row (event type, node, payload shape): a scheduled run is the ordinary path
 *   without an SSE consumer, not a second runner (record §5.4, the lane brief's gate).
 * - **R3** — a viewer lists the scheduled execution and reads its messages through the durable
 *   JSON read (§10.3A), while the admin's own interactive run stays invisible to them.
 * - **A demoted creator keeps the schedule running** — the author who created it becomes a
 *   viewer and the dispatcher still fires it, because it never ran as them (R2).
 * - **A refused run has a trail and no execution, and blocks** — the pipeline's release is
 *   discarded, Run now records a `not_started` / `pointer_null` run, the schedule blocks, a second
 *   Run now is `409 schedule.blocked`, and unblock re-validates and refuses.
 * - **Isolation** — another workspace's member cannot see the schedule (R9).
 * - **The system identity cannot authenticate** — it holds no key, and a validly signed session
 *   naming it is refused.
 *
 * The clock is the real one. Where a leg needs "an occurrence is due" without waiting for the
 * wall clock, it moves `next_due_at` back to the pattern's previous occurrence — the state the
 * dispatcher would find had it been down since then — and lets the real dispatcher take it.
 *
 * The context is closed after the class (`@DirtiesContext`): a DISPATCHING context left in the
 * cache would keep polling the shared `scheduled_tasks` for the rest of the JVM and take other
 * suites' runs — `SchedulerShutdownOrderE2eTest`'s among them, whose subject is which instance runs.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SchedulerE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `an author creates a schedule over a released pipeline - durably idempotent`() {
        seedAuthRows()
        seedH2()
        registerH2Datasource()
        createAndReleaseTemplate()
        pipelineId = createAndReleasePipeline(PIPELINE_NAME)

        val first = createSchedule(AUTHOR_SESSION, SCHEDULE_NAME, PIPELINE_NAME, YEARLY, idempotencyKey = "e2e-create-1")
        first.statusCode shouldBe 201
        first.header("ETag") shouldBe "\"1\""
        scheduleId = first.jsonPath().getString("data.id")
        first.jsonPath().getString("data.target_ref") shouldBe "pipeline:$PIPELINE_NAME"
        first.jsonPath().getString("data.condition") shouldBe "enabled"

        withClue("the same key and body answers the original — the key lives in Postgres (L1)") {
            val replay = createSchedule(AUTHOR_SESSION, SCHEDULE_NAME, PIPELINE_NAME, YEARLY, idempotencyKey = "e2e-create-1")
            replay.statusCode shouldBe 200
            replay.jsonPath().getString("data.id") shouldBe scheduleId
        }
        withClue("the same key with a different body is refused") {
            createSchedule(AUTHOR_SESSION, "test/sched_other", PIPELINE_NAME, YEARLY, idempotencyKey = "e2e-create-1")
                .jsonPath()
                .getString("error.code") shouldBe "idempotency.key_reused_for_different_request"
        }
        withClue("a viewer reads schedules but may not create one") {
            session(VIEWER_SESSION).get("/api/v1/schedules/$scheduleId").then().statusCode(200)
            createSchedule(VIEWER_SESSION, "test/sched_viewer", PIPELINE_NAME, YEARLY).statusCode shouldBe 403
        }
    }

    @Test
    @Order(2)
    fun `Run now fires as the system identity, and the run's trail tells the whole story`() {
        val accepted = session(AUTHOR_SESSION).post("/api/v1/schedules/$scheduleId/run")
        accepted.statusCode shouldBe 202
        accepted.jsonPath().getString("data.origin") shouldBe "manual"
        accepted.jsonPath().getString("data.requested_by") shouldBe AUTHOR_ID

        val run = awaitRun(scheduleId, accepted.jsonPath().getString("data.id"))

        run["state"].asText() shouldBe "succeeded"
        run["trail"].map { it["kind"].asText() } shouldContainExactly listOf("recorded", "claimed", "execution_started", "finished")
        run["prepared"]["version"].asInt() shouldBe 1
        run["prepared"]["body_sha256"].asText().length shouldBe SHA256_HEX_LENGTH
        scheduledExecutionId = run["execution_id"].asText()

        val execution = executionRow(scheduledExecutionId)
        withClue("R2 — the execution ran as the system identity, attributed to the schedule") {
            execution["triggered_via"] shouldBe "SCHEDULE"
            execution["executed_by"] shouldBe systemActorId()
            execution["executed_by_key_kind"].shouldBeNull()
            execution["status"] shouldBe "SUCCESS"
        }
    }

    @Test
    @Order(3)
    fun `the scheduled execution's durable record is the interactive run's, row for row`() {
        interactiveExecutionId = executeInteractively()

        val scheduled = durableRows(scheduledExecutionId)
        val interactive = durableRows(interactiveExecutionId)

        withClue("the same events, in the same order, about the same nodes, with the same payload shape") {
            scheduled.size shouldBeGreaterThan 0
            scheduled.map { it.shape } shouldContainExactly interactive.map { it.shape }
        }
        withClue("both runs report their node operations (node_progress is sampled, so compared as a set)") {
            progressNodes(scheduledExecutionId) shouldBe progressNodes(interactiveExecutionId)
        }
    }

    @Test
    @Order(4)
    fun `R3 - a viewer lists the scheduled run and reads its messages, never the admin's own run`() {
        val listed =
            session(VIEWER_SESSION)
                .get("/api/v1/executions?limit=200")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList<String>("data.items.execution_id")
        listed shouldContain scheduledExecutionId
        listed shouldNotContain interactiveExecutionId

        val events = session(VIEWER_SESSION).get("/api/v1/executions/$scheduledExecutionId/events?format=json&limit=500")
        events.statusCode shouldBe 200
        events.jsonPath().getList<String>("data.events.event") shouldContain "pipeline_completed"
        events.jsonPath().getBoolean("data.has_more") shouldBe false

        session(VIEWER_SESSION).get("/api/v1/executions/$interactiveExecutionId").statusCode shouldBe 404
        withClue("visibility is not ownership — a run the viewer may not cancel answers not-found, like a read") {
            session(VIEWER_SESSION).delete("/api/v1/executions/$scheduledExecutionId").statusCode shouldBe 404
        }
    }

    @Test
    @Order(5)
    fun `a creator demoted to viewer keeps their schedule firing - it never ran as them`() {
        val hourly = createSchedule(AUTHOR_SESSION, "test/sched_every_five", PIPELINE_NAME, EVERY_FIVE_MINUTES)
        hourly.statusCode shouldBe 201
        val everyFive = hourly.jsonPath().getString("data.id")

        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body("""{"role": "viewer"}""")
            .put("/api/v1/workspaces/$WORKSPACE/members/$AUTHOR_ID")
            .then()
            .statusCode(200)
        withClue("the demoted author can no longer operate it") {
            session(AUTHOR_SESSION).post("/api/v1/schedules/$everyFive/pause").statusCode shouldBe 403
        }

        // The occurrence is due: the state a dispatcher finds after being down since the pattern's last match.
        sql("UPDATE schedules SET next_due_at = $PREVIOUS_FIVE_MINUTE_BOUNDARY WHERE id = '$everyFive'")

        val cronRun =
            poll("a cron run of the demoted author's schedule to succeed") {
                runsOf(everyFive).firstOrNull { it["origin"].asText() == "cron" && it["state"].asText() == "succeeded" }
            }
        cronRun["requested_by"].isNull shouldBe true
        executionRow(cronRun["execution_id"].asText())["executed_by"] shouldBe systemActorId()

        // It would fire every five minutes for as long as a dispatching instance exists: delete it.
        val revision = session(ADMIN_SESSION).get("/api/v1/schedules/$everyFive").header("ETag")
        session(ADMIN_SESSION)
            .header("If-Match", revision)
            .delete("/api/v1/schedules/$everyFive")
            .then()
            .statusCode(204)
    }

    @Test
    @Order(6)
    fun `a refused run has a trail and no execution, blocks the schedule, and unblock re-validates`() {
        val refusedPipeline = "test/sched_rows_discarded"
        val refusedPipelineId = createAndReleasePipeline(refusedPipeline)
        val created = createSchedule(ADMIN_SESSION, "test/sched_refused", refusedPipeline, YEARLY)
        created.statusCode shouldBe 201
        val refusedSchedule = created.jsonPath().getString("data.id")

        // The only release is discarded: the pointer is NULL (versioning §3.4).
        session(ADMIN_SESSION).post("/api/v1/pipelines/$refusedPipelineId/versions/1/discard").then().statusCode(200)

        val accepted = session(ADMIN_SESSION).post("/api/v1/schedules/$refusedSchedule/run")
        accepted.statusCode shouldBe 202
        val run = awaitRun(refusedSchedule, accepted.jsonPath().getString("data.id"))

        run["state"].asText() shouldBe "not_started"
        run["reason"].asText() shouldBe "pointer_null"
        run["execution_id"].isNull shouldBe true
        run["trail"].map { it["kind"].asText() } shouldContainExactly listOf("recorded", "not_started")

        val schedule = session(ADMIN_SESSION).get("/api/v1/schedules/$refusedSchedule").jsonPath()
        schedule.getString("data.condition") shouldBe "blocked"
        schedule.getString("data.blocked.reason") shouldBe "pointer_null"
        schedule.getString("data.blocked.run_id") shouldBe run["id"].asText()

        session(ADMIN_SESSION).post("/api/v1/schedules/$refusedSchedule/run").jsonPath().getString("error.code") shouldBe "schedule.blocked"
        withClue("unblock re-validates first: the pointer is still NULL, so it is refused and the block stays") {
            session(ADMIN_SESSION).post("/api/v1/schedules/$refusedSchedule/unblock").jsonPath().getString("error.code") shouldBe
                "schedule.validation.payload_invalid"
            session(ADMIN_SESSION).get("/api/v1/schedules/$refusedSchedule").jsonPath().getString("data.condition") shouldBe "blocked"
        }
    }

    @Test
    @Order(7)
    fun `another workspace cannot see the schedule - isolation is per workspace (R9)`() {
        session(OUTSIDER_SESSION).get("/api/v1/schedules/$scheduleId").jsonPath().getString("error.code") shouldBe "schedule.not_found"
        session(OUTSIDER_SESSION).get("/api/v1/schedules").jsonPath().getList<String>("data.items.id") shouldNotContain scheduleId
        session(OUTSIDER_SESSION).post("/api/v1/schedules/$scheduleId/run").jsonPath().getString("error.code") shouldBe "schedule.not_found"
    }

    @Test
    @Order(8)
    fun `the system identity cannot authenticate - no key, and a signed session naming it is refused`() {
        val system = systemActorId()
        scalar("SELECT COUNT(*) FROM api_keys WHERE user_id = '$system' OR created_by = '$system'") shouldBe "0"

        val forged = E2eSession.jwt(JWT_SECRET, system, "system@system.invalid", WORKSPACE)
        session(forged).get("/api/v1/schedules").statusCode shouldBe 401
        session(forged).post("/api/v1/schedules/$scheduleId/run").statusCode shouldBe 401
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .body("{}")
            .post("/mcp")
            .statusCode shouldBe 401
    }

    // ------------------------------------------------------------------------------- helpers

    private fun session(jwt: String) = given().port(port).asSession(jwt)

    private fun createSchedule(
        jwt: String,
        name: String,
        pipeline: String,
        cron: String,
        idempotencyKey: String? = null,
    ): Response {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    "payload" to mapOf("pipeline" to pipeline, "version" to "current"),
                    "parameters" to mapOf("min_id" to MIN_ID),
                    "cron" to cron,
                    "timezone" to "UTC",
                ),
            )
        val request = session(jwt).contentType(ContentType.JSON).body(body)
        idempotencyKey?.let { request.header("Idempotency-Key", it) }
        return request.post("/api/v1/schedules")
    }

    private fun awaitRun(
        schedule: String,
        run: String,
    ): JsonNode =
        poll("run $run to reach a final state") {
            val body = mapper.readTree(session(ADMIN_SESSION).get("/api/v1/schedules/$schedule/runs/$run").asString())["data"]
            body.takeIf { it["state"].asText() !in ACTIVE_STATES }
        }

    private fun runsOf(schedule: String): List<JsonNode> =
        mapper.readTree(session(ADMIN_SESSION).get("/api/v1/schedules/$schedule/runs?limit=50").asString())["data"]["items"].toList()

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

    /** One interactive run of the same pipeline with the same parameters, over SSE, as the admin. */
    private fun executeInteractively(): String =
        assertTimeoutPreemptively(Duration.ofMinutes(2)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {"min_id": $MIN_ID}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            withClue("the interactive run's stream: ${response.body().take(DIAGNOSTIC_CHARS)}") {
                response.statusCode() shouldBe 200
                E2eSse
                    .parseEvents(response.body(), mapper)
                    .single { it.first == "execution_started" }
                    .second["execution_id"]
                    .asText()
            }
        }

    /** A durable event row reduced to what must match: its type, its node, and its payload's shape. */
    private data class RowShape(
        val shape: String,
    )

    private fun durableRows(executionId: String): List<RowShape> =
        rows("SELECT event_type, payload_json::text FROM execution_events WHERE execution_id = '$executionId' ORDER BY event_id")
            .filter { it.first != NODE_PROGRESS }
            .map { (type, payload) ->
                val json = mapper.readTree(payload)
                RowShape("$type node=${json["node_id"]?.asText()} keys=${json.fieldNames().asSequence().sorted().toList()}")
            }

    private fun progressNodes(executionId: String): Set<String> =
        rows("SELECT event_type, payload_json::text FROM execution_events WHERE execution_id = '$executionId' ORDER BY event_id")
            .filter { it.first == NODE_PROGRESS }
            .map { mapper.readTree(it.second)["node_id"].asText() }
            .toSet()

    private fun executionRow(executionId: String): Map<String, String?> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT triggered_via, executed_by::text, executed_by_key_kind, status FROM pipeline_executions " +
                            "WHERE execution_id = '$executionId'",
                    ).use { row ->
                        check(row.next()) { "no pipeline_executions row for $executionId" }
                        mapOf(
                            "triggered_via" to row.getString(1),
                            "executed_by" to row.getString(2),
                            "executed_by_key_kind" to row.getString(3),
                            "status" to row.getString(4),
                        )
                    }
            }
        }

    private fun systemActorId(): String = scalar("SELECT id::text FROM users WHERE kind = 'system'").shouldNotBeNull()

    /** The first column of the first row; the statement closes with its connection. */
    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery(query).use { row -> if (row.next()) row.getString(1) else null }
        }

    private fun rows(query: String): List<Pair<String, String>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { row ->
                    generateSequence { if (row.next()) row.getString(1) to row.getString(2) else null }.toList()
                }
            }
        }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun createAndReleaseTemplate() {
        val template =
            session(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to TEMPLATE_ID,
                            "dialect" to "H2",
                            "display_name" to "Scheduler rows",
                            "description" to "Rows at or above a threshold",
                            "imports" to emptyList<Any>(),
                            "body" to TEMPLATE_BODY,
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .header(IF_MATCH, template.jsonPath().getString("data.body_hash"))
            .body("""{"name": "$TEMPLATE_ID"}""")
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun createAndReleasePipeline(name: String): String {
        val created =
            session(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(pipelineBody(name))
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        val id = created.jsonPath().getString("data.id")
        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .header(IF_MATCH, created.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    private fun pipelineBody(name: String): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Scheduler E2E rows",
                "description" to "Rows at or above min_id",
                "parameters" to
                    mapOf(
                        "min_id" to mapOf("type" to "INTEGER", "required" to true, "description" to "The lowest id returned."),
                    ),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "rows",
                            "type" to "DQL",
                            "source" to H2_DATASOURCE,
                            "description" to "Reads the seeded rows",
                            "template" to mapOf("id" to TEMPLATE_ID, "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            ),
        )

    private fun registerH2Datasource() {
        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Scheduler E2E H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun seedH2() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE sched_rows (id INT PRIMARY KEY, label VARCHAR(64) NOT NULL)")
                (1..SEEDED_ROWS).forEach { statement.execute("INSERT INTO sched_rows VALUES ($it, 'row-$it')") }
            }
        }
    }

    private fun seedAuthRows() {
        sql(
            """
            INSERT INTO workspaces (id, name, display_name) VALUES
                ('$WORKSPACE_ID', '$WORKSPACE', 'Scheduler E2E'),
                ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'Scheduler E2E other')
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'sched-admin@e2e.test', 'Sched Admin', 'test', 'sched-admin-sub', TRUE, TRUE),
                ('$AUTHOR_ID', 'sched-author@e2e.test', 'Sched Author', 'test', 'sched-author-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'sched-viewer@e2e.test', 'Sched Viewer', 'test', 'sched-viewer-sub', TRUE, FALSE),
                ('$OUTSIDER_ID', 'sched-outsider@e2e.test', 'Sched Outsider', 'test', 'sched-outsider-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$AUTHOR_ID', 'author'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer'),
                ('$OTHER_WORKSPACE_ID', '$OUTSIDER_ID', 'author')
            """.trimIndent(),
        )
    }

    companion object {
        private const val IF_MATCH = "If-Match"
        private const val NODE_PROGRESS = "node_progress"
        private const val SHA256_HEX_LENGTH = 64
        private const val DIAGNOSTIC_CHARS = 2_000
        private const val POLL_BUDGET_SECONDS = 90L
        private const val POLL_MILLIS = 250L
        private val ACTIVE_STATES = setOf("queued", "starting", "running")

        /** Never due during the suite: the Run-now schedules must not also fire on their own. */
        private const val YEARLY = "0 3 1 1 *"

        /** Exactly the save guard's floor (300 s), so the demoted-creator leg can make one due. */
        private const val EVERY_FIVE_MINUTES = "*/5 * * * *"

        /** The every-five-minutes pattern's latest match at or before now — at most five minutes ago, inside the 600 s lateness. */
        private const val PREVIOUS_FIVE_MINUTE_BOUNDARY =
            "date_trunc('hour', now()) + floor(extract(minute FROM now()) / 5) * interval '5 minutes'"

        private const val WORKSPACE = "sched-e2e"
        private const val OTHER_WORKSPACE = "sched-e2e-other"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val OTHER_WORKSPACE_ID = UUID.randomUUID().toString()

        private val ADMIN_ID = UUID.randomUUID().toString()
        private val AUTHOR_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()
        private val OUTSIDER_ID = UUID.randomUUID().toString()

        private const val H2_DATASOURCE = "h2-sched-e2e"
        private const val H2_JDBC_URL = "jdbc:h2:mem:schede2e;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val SEEDED_ROWS = 5
        private const val MIN_ID = 2

        private const val TEMPLATE_ID = "test/sched_rows.sql"
        private const val PIPELINE_NAME = "test/sched_rows"
        private const val SCHEDULE_NAME = "test/sched_nightly"
        private const val TEMPLATE_BODY = "SELECT id, label FROM sched_rows WHERE id >= :min_id ORDER BY id"

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "sched-admin@e2e.test", WORKSPACE)
        private val AUTHOR_SESSION get() = E2eSession.jwt(JWT_SECRET, AUTHOR_ID, "sched-author@e2e.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "sched-viewer@e2e.test", WORKSPACE)
        private val OUTSIDER_SESSION get() = E2eSession.jwt(JWT_SECRET, OUTSIDER_ID, "sched-outsider@e2e.test", OTHER_WORKSPACE)

        private var pipelineId: String = ""
        private var scheduleId: String = ""
        private var scheduledExecutionId: String = ""
        private var interactiveExecutionId: String = ""

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

            // This context DISPATCHES (the module default is API mode), on a one-second clock.
            registry.add("datapipelines.scheduler.enabled") { "true" }
            registry.add("datapipelines.scheduler.tick-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.polling-interval-seconds") { "1" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
