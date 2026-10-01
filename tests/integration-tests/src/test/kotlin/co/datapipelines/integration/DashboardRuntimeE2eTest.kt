package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * **The dashboard runtime over the real stack (#10 L2, rest-api §23.3; Dashboards §5).**
 *
 * A REAL Postgres (metadata + a scratch source database), a real Redis, the real executor and the real HTTP layer. The
 * released visualizations and dashboards are SEEDED by SQL — the release gate is untouched and nothing can release a
 * visualization before L4 (spec §18 premise 12); the source pipelines are created through REST and flipped to RELEASED
 * by SQL. Every scenario asserts at the DATABASE, not only on the wire (the coroutine-cancellation lesson: a row stuck
 * `RUNNING` is invisible on a stream that ended).
 *
 * | Spec scenario | Test |
 * |---|---|
 * | golden path, D50 delegated act, no stored result | `golden`, `promoter` |
 * | 2 — sharing: three sources, one shared → exactly two executions | `sharing` |
 * | 4 — mixed outcomes: a failed source errors its dependents, the refresh is PARTIAL | `mixed` |
 * | 7 — isolation: a non-read-only pin is refused; a hidden dashboard is 404 | `unsafe pin`, `promoter` |
 * | 15 — timeouts by stage: the earliest deadline wins | `an occurrence deadline`, `the refresh's own deadline` |
 * | 18 — abort: returns without waiting, cannot touch another principal's or instance's refresh | `abort` |
 * | caps: `result_too_large` inside the stream | `result cap` |
 * | admission: 429 + `Retry-After`, no row | `saturation` |
 * | the non-cancellable finish: the row is closed when the client is simply gone | `disconnect` |
 * | the sweeper reaps a RUNNING row, retention deletes finished ones and keeps executions | `sweeper`, `retention` |
 *
 * Non-vacuity: every scenario counts the executions it launched and the frames it received; the final test asserts the
 * refusal codes the walk exists for, by multiset.
 *
 * The context is configured small on purpose (2 refreshes per workspace, 1 s admission wait, 4 KiB per source, a 2 s
 * disconnect grace, 1 s heartbeat) so saturation, the cap and the grace are reachable in seconds.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk over one fixture, as the sibling E2Es are
class DashboardRuntimeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    /**
     * The housekeeping beans are reached by NAME and REFLECTION: this module compiles against `:modules:app` alone
     * (module-structure §4.2), so it cannot import `web`'s types — and the beans' being wired in the running application
     * is part of what is proven.
     */
    @Autowired
    private lateinit var context: ApplicationContext

    private fun housekeeping(
        bean: String,
        method: String,
    ): Int = context.getBean(bean).let { it.javaClass.getMethod(method).invoke(it) as Int }

    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()
    private val refusals = CopyOnWriteArrayList<String>()

    // ------------------------------------------------------------------------------------------ 1 fixture

    @Test
    @Order(1)
    fun `fixture - two workspaces, five people, a source database, ten pipelines and the released boards`() {
        seedPeople()
        registerDatasource()
        createTemplates()
        releaseTemplates()
        PIPELINES.forEach { (name, spec) -> createPipeline(name, spec) }
        releasePipelines()
        seedVisualizations()
        seedBoards()
        stub.address.port shouldNotBe 0
    }

    // ------------------------------------------------------------------------------------------ 2 golden

    @Test
    @Order(2)
    fun `golden - a viewer refreshes a dashboard and runs a source they could run themselves - and the DATABASE agrees`() {
        val cid = configurationId(GOLDEN, VIEWER)
        val config = get("/api/v1/dashboards/$GOLDEN/runtime/config", VIEWER)
        config.statusCode shouldBe 200
        config.jsonPath().getString("data.dashboard.status") shouldBe "RELEASED"
        config.jsonPath().getString("data.visualizations[0].bindings.'cells.x'") shouldBe "x"
        config.jsonPath().getInt("data.budgets.max_bytes_per_source") shouldBe SOURCE_CAP

        val refreshId = uuid()
        val frames = refresh(GOLDEN, VIEWER, refreshBody(cid, refreshId))
        assertGoldenFrames(frames)
        val started =
            frames
                .of("source_started")
                .single()
                .data["execution_id"]
                .asText()

        assertGoldenDatabase(refreshId, started)

        // The run is the viewer's own: visible, but cancellable ONLY through its refresh, and it has no result to page.
        get("/api/v1/executions/$started", VIEWER).statusCode shouldBe 200
        refused(get("/api/v1/executions/$started/result", VIEWER), 404) shouldBe "result.execution_not_found"
        refused(delete("/api/v1/executions/$started", VIEWER), 404) shouldBe "result.execution_not_found"

        goldenRefreshId = refreshId
    }

    private fun assertGoldenFrames(frames: List<Frame>) {
        withClue(frames.names().toString()) {
            frames.names().first() shouldBe "refresh_started"
            frames.names().last() shouldBe "refresh_completed"
            frames.ids() shouldBe (1..frames.size).toList() // monotonic, per refresh, from 1
            frames.of("source_started").size shouldBe 1
            frames
                .of("source_completed")
                .single()
                .data["rows"]
                .asInt() shouldBe 2
            frames
                .of("visualization_data")
                .single()
                .data["bindings"]["cells.x"]
                .map { it.asInt() } shouldBe listOf(1, 2)
            frames
                .of("refresh_completed")
                .single()
                .data["status"]
                .asText() shouldBe "COMPLETED"
        }
    }

    private fun assertGoldenDatabase(
        refreshId: String,
        started: String,
    ) {
        val row = rows("SELECT status, (finished_at IS NOT NULL)::text AS done FROM dashboard_refreshes WHERE id = '$refreshId'").single()
        row["status"] shouldBe "COMPLETED"
        row["done"] shouldBe "true"
        rows(
            "SELECT execution_id::text AS e, shared::text AS shared FROM dashboard_refresh_executions WHERE refresh_id = '$refreshId'",
        ).single().let {
            it["e"] shouldBe started
            it["shared"] shouldBe "false"
        }
        val execution =
            rows(
                "SELECT triggered_via, executed_by::text AS by, status, result_row_count::text AS rc " +
                    "FROM pipeline_executions WHERE execution_id = '$started'",
            ).single()
        execution["triggered_via"] shouldBe "DASHBOARD"
        execution["by"] shouldBe VIEWER_ID
        execution["status"] shouldBe "SUCCESS"
        execution["rc"] shouldBe null // a dashboard run stores NO result

        val audit =
            rows(
                "SELECT user_id::text AS u, details_json::text AS d FROM audit_log WHERE event = 'dashboard.refresh' AND " +
                    "details_json ->> 'refresh_id' = '$refreshId'",
            )
        withClue("one awaited audit row per refresh") { audit.size shouldBe 1 }
        audit.single()["u"] shouldBe VIEWER_ID
        audit.single()["d"]!! shouldContain "COMPLETED"
    }

    @Test
    @Order(3)
    fun `the refresh record - a person lists and reads their own, another viewer sees none, the admin sees all`() {
        val own = get("/api/v1/dashboards/$GOLDEN/refreshes", VIEWER)
        own.jsonPath().getList<String>("data.items.refresh_id") shouldContain goldenRefreshId
        own.jsonPath().getList<Map<String, Any?>>("data.items")[0]["executions"] shouldBe null // the list carries no links

        val read = get("/api/v1/dashboards/$GOLDEN/refreshes/$goldenRefreshId", VIEWER)
        read.statusCode shouldBe 200
        read.jsonPath().getString("data.status") shouldBe "COMPLETED"
        read.jsonPath().getList<Any>("data.executions").size shouldBe 1 // a viewer reads executions

        get("/api/v1/dashboards/$GOLDEN/refreshes", OTHER_VIEWER).jsonPath().getList<Any>("data.items") shouldBe emptyList()
        refused(get("/api/v1/dashboards/$GOLDEN/refreshes/$goldenRefreshId", OTHER_VIEWER), 404) shouldBe "dashboard.refresh.not_found"
        get("/api/v1/dashboards/$GOLDEN/refreshes", ADMIN).jsonPath().getList<String>("data.items.refresh_id") shouldContain goldenRefreshId
        refused(get("/api/v1/dashboards/$SHARED/refreshes/$goldenRefreshId", ADMIN), 404) shouldBe "dashboard.refresh.not_found"
    }

    // ------------------------------------------------------------------------------------------ 3 sharing / mixed

    @Test
    @Order(4)
    fun `2 - sharing - three sources with two identical are exactly TWO executions for three consumers`() {
        val refreshId = uuid()
        val frames = refresh(SHARED, VIEWER, refreshBody(configurationId(SHARED, VIEWER), refreshId))

        frames.of("visualization_data").map { it.data["name"].asText() } shouldContainExactlyInAnyOrder listOf("va", "vb", "vc")
        val executions = frames.of("source_started").associate { it.data["source"].asText() to it.data["execution_id"].asText() }
        executions.getValue("a") shouldBe executions.getValue("b") // the shared invocation
        executions.getValue("c") shouldNotBe executions.getValue("a") // a distinct one stays distinct
        val launched = rows("SELECT execution_id::text AS e FROM pipeline_executions WHERE correlation_id = '$refreshId'")
        withClue("non-vacuity: the executions the database recorded for this refresh") { launched.size shouldBe 2 }
        rows("SELECT DISTINCT execution_id FROM dashboard_refresh_executions WHERE refresh_id = '$refreshId'").size shouldBe 2
        rows(
            "SELECT source_name, shared::text AS shared FROM dashboard_refresh_executions WHERE refresh_id = " +
                "'$refreshId' ORDER BY source_name",
        ).map { it["source_name"] to it["shared"] } shouldContainExactly listOf("a" to "true", "b" to "true", "c" to "false")
    }

    @Test
    @Order(5)
    fun `4 - mixed outcomes - a failed source errors the target that reads it, the other target is served, the refresh is PARTIAL`() {
        val refreshId = uuid()
        val frames = refresh(MIXED, VIEWER, refreshBody(configurationId(MIXED, VIEWER), refreshId))

        val failed = frames.of("source_failed").single()
        failed.data["source"].asText() shouldBe "bad"
        failed.data["error"]["message"].asText() shouldNotBe "" // a fixed message, never a driver's text
        withClue("a driver's text never reaches the stream") { failed.data.toString() shouldNotContain "division" }
        frames.of("visualization_data").map { it.data["name"].asText() } shouldContainExactly listOf("vok")
        frames
            .of("visualization_status")
            .last { it.data["name"].asText() == "vbad" }
            .data["state"]
            .asText() shouldBe "error"
        frames
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "PARTIAL"
        rows("SELECT status FROM dashboard_refreshes WHERE id = '$refreshId'").single()["status"] shouldBe "PARTIAL"
        rows(
            "SELECT e.status FROM pipeline_executions e JOIN dashboard_refresh_executions l ON l.execution_id = e.execution_id " +
                "WHERE l.refresh_id = '$refreshId' AND l.source_name = 'bad'",
        ).single()["status"] shouldBe "FAILED"
    }

    // ------------------------------------------------------------------------------------------ 4 parameters

    @Test
    @Order(6)
    fun `parameters - the pinned set evaluates, the revision climbs per client instance, and the selection reaches the source`() {
        val cid = configurationId(PARAMETERISED, VIEWER)
        val evaluate = { instance: String ->
            post(
                "/api/v1/dashboards/$PARAMETERISED/runtime/parameters",
                """{"configuration_id":"$cid","instance_id":"$instance","selections":{},"intent":"bootstrap"}""",
                VIEWER,
            )
        }
        val first = evaluate(INSTANCE)
        first.statusCode shouldBe 200
        first.jsonPath().getBoolean("data.valid") shouldBe true
        first.jsonPath().getString("data.values.country") shouldBe "USA"
        first.jsonPath().getInt("data.parameter_revision") shouldBe 1
        // The dashboard's `parameter_state` (D20) hides and disables the control whatever the engine says; the VALUE still
        // travels (every parameter, hidden ones included — D23).
        first.jsonPath().getBoolean("data.overrides_applied.country.visible") shouldBe false
        first.jsonPath().getBoolean("data.overrides_applied.country.enabled") shouldBe false
        first.jsonPath().getList<String>("data.parents") shouldBe emptyList()
        evaluate(INSTANCE).jsonPath().getInt("data.parameter_revision") shouldBe 2
        evaluate(uuid()).jsonPath().getInt("data.parameter_revision") shouldBe 1 // another client instance counts alone

        val refreshId = uuid()
        val frames = refresh(PARAMETERISED, VIEWER, refreshBody(cid, refreshId, selections = """{"country":"CAN"}"""))
        frames
            .of("visualization_data")
            .single()
            .data["bindings"]["cells.c"]
            .map { it.asText() } shouldBe listOf("CAN")
        rows("SELECT selections_json::text AS s FROM dashboard_refreshes WHERE id = '$refreshId'").single()["s"]!! shouldContain "CAN"

        refused(
            post(
                "/api/v1/dashboards/$PARAMETERISED/runtime/visualizations",
                refreshBody(cid, uuid(), selections = """{"country":{"x":1}}"""),
                VIEWER,
            ),
            400,
        ) shouldBe
            "dashboard.validation.body_invalid"
        refused(
            post(
                "/api/v1/dashboards/$PARAMETERISED/runtime/parameters",
                """{"configuration_id":"$cid","instance_id":"$INSTANCE","selections":{"nope":1},"intent":"retry"}""",
                VIEWER,
            ),
            400,
        ) shouldBe "parameter.evaluate.unknown_parameter"
    }

    // ------------------------------------------------------------------------------------------ 5 refusals

    @Test
    @Order(7)
    fun `refusals before the stream - stale configuration, a reused or malformed refresh id, bad scope - each names its field or code`() {
        val cid = configurationId(GOLDEN, VIEWER)
        val stale = post("/api/v1/dashboards/$GOLDEN/runtime/visualizations", refreshBody("0".repeat(64), uuid()), VIEWER)
        refused(stale, 409) shouldBe "dashboard.runtime.configuration_stale"
        stale.jsonPath().getString("error.details.configuration_id") shouldBe cid

        val reused = post("/api/v1/dashboards/$GOLDEN/runtime/visualizations", refreshBody(cid, goldenRefreshId), VIEWER)
        refused(reused, 400) shouldBe "dashboard.validation.body_invalid"
        reused.jsonPath().getString("error.details.path") shouldBe "refresh_id"
        reused.jsonPath().getString("error.details.reason") shouldBe "reused"

        val v1 = post("/api/v1/dashboards/$GOLDEN/runtime/visualizations", refreshBody(cid, "00000000-0000-1000-8000-000000000000"), VIEWER)
        refused(v1, 400) shouldBe "dashboard.validation.body_invalid"
        v1.jsonPath().getString("error.details.reason") shouldBe "malformed" // a v1 UUID is not a client-minted v4
        refused(
            post("/api/v1/dashboards/$GOLDEN/runtime/visualizations", refreshBody(cid, uuid(), scope = "targets"), VIEWER),
            400,
        ) shouldBe
            "dashboard.validation.empty_targets"
        refused(
            post(
                "/api/v1/dashboards/$GOLDEN/runtime/visualizations",
                refreshBody(cid, uuid(), scope = "targets", targets = """["nope"]"""),
                VIEWER,
            ),
            400,
        ) shouldBe
            "dashboard.validation.target_not_visualization"
        refused(post("/api/v1/dashboards/$GOLDEN/runtime/visualizations", "", VIEWER), 400) shouldBe "dashboard.validation.body_invalid"
        // A dashboard that is not there — or a foreign one — is one 404.
        refused(get("/api/v1/dashboards/${UUID.randomUUID()}/runtime/config", VIEWER), 404) shouldBe "dashboard.not_found"
        // still ONE row for the reused id
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id IN ('$goldenRefreshId')").single()["n"] shouldBe "1"
    }

    @Test
    @Order(8)
    fun `7 - a pinned pipeline that is not read-only is dependency_missing at config AND at refresh - it never runs`() {
        val config = get("/api/v1/dashboards/$UNSAFE/runtime/config", VIEWER)
        refused(config, 409) shouldBe "dashboard.runtime.dependency_missing"
        config.jsonPath().getString("error.details.reason") shouldBe "not_read_only"
        config.jsonPath().getString("error.details.dependency") shouldBe "source"
        val before = rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE triggered_via = 'DASHBOARD'").single()["n"]!!.toInt()

        refused(post("/api/v1/dashboards/$UNSAFE/runtime/visualizations", refreshBody("x".repeat(64), uuid()), VIEWER), 409) shouldBe
            "dashboard.runtime.dependency_missing"
        rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE triggered_via = 'DASHBOARD'").single()["n"]!!.toInt() shouldBe before
    }

    // ------------------------------------------------------------------------------------------ 6 caps and time

    @Test
    @Order(9)
    fun `the result cap - a source over its byte cap fails result_too_large INSIDE the stream, at the budget stage`() {
        val frames = refresh(BIG, VIEWER, refreshBody(configurationId(BIG, VIEWER), uuid()))

        frames
            .of("source_failed")
            .single()
            .data["error"]["code"]
            .asText() shouldBe "dashboard.refresh.result_too_large"
        frames.of("visualization_status").last().let {
            it.data["state"].asText() shouldBe "error"
            it.data["stage"].asText() shouldBe "budget"
        }
        frames.of("visualization_data").size shouldBe 0
        frames
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "FAILED"
    }

    @Test
    @Order(10)
    fun `15 - an occurrence deadline earlier than the refresh's ends its source at once - the earliest deadline wins`() {
        val started = System.nanoTime()
        val frames =
            refresh(HUNG_SHORT_OCCURRENCE, VIEWER, refreshBody(configurationId(HUNG_SHORT_OCCURRENCE, VIEWER), uuid()), budgetSeconds = 30)
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)

        frames
            .of("source_failed")
            .single()
            .data["error"]["code"]
            .asText() shouldBe "pipeline.execution.timeout"
        frames
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "FAILED" // its target failed; the refresh itself did not time out
        withClue("the occurrence's 2 s, not the refresh's 25 s") { (seconds < HALF_OF_REFRESH_DEADLINE) shouldBe true }
    }

    @Test
    @Order(11)
    fun `15 - the refresh's own deadline ends it TIMED_OUT - the row is closed and the hung execution is stopped`() {
        val refreshId = uuid()
        val frames =
            refresh(HUNG_SHORT_REFRESH, VIEWER, refreshBody(configurationId(HUNG_SHORT_REFRESH, VIEWER), refreshId), budgetSeconds = 30)

        frames
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "TIMED_OUT"
        rows("SELECT status FROM dashboard_refreshes WHERE id = '$refreshId'").single()["status"] shouldBe "TIMED_OUT"
        awaitExecutionsEnded(refreshId)
    }

    // ------------------------------------------------------------------------------------------ 7 abort

    @Test
    @Order(12)
    fun `18 - abort returns without waiting, reaches the running refresh, and cannot touch another person's or instance's`() {
        val cid = configurationId(HUNG_LONG, VIEWER)
        val refreshId = uuid()
        val live = openRefresh(HUNG_LONG, VIEWER, refreshBody(cid, refreshId, instance = INSTANCE))
        live.await("source_started")
        val executionId =
            live
                .frames()
                .first { it.event == "source_started" }
                .data["execution_id"]
                .asText()

        // Not theirs: another person, and the right person on another client instance — the same 404, and nothing is stopped.
        refused(
            post("/api/v1/dashboards/$HUNG_LONG/runtime/refreshes/$refreshId/abort", """{"instance_id":"$INSTANCE"}""", OTHER_VIEWER),
            404,
        ) shouldBe
            "dashboard.refresh.not_found"
        refused(
            post("/api/v1/dashboards/$HUNG_LONG/runtime/refreshes/$refreshId/abort", """{"instance_id":"${uuid()}"}""", VIEWER),
            404,
        ) shouldBe
            "dashboard.refresh.not_found"
        rows("SELECT status FROM dashboard_refreshes WHERE id = '$refreshId'").single()["status"] shouldBe "RUNNING"

        val began = System.nanoTime()
        val abort = post("/api/v1/dashboards/$HUNG_LONG/runtime/refreshes/$refreshId/abort", """{"instance_id":"$INSTANCE"}""", VIEWER)
        val answered = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        abort.statusCode shouldBe 202
        withClue("the abort answers without waiting for the refresh to end") { (answered < ABORT_ANSWER_MILLIS) shouldBe true }

        live.done(30)
        live
            .frames()
            .last()
            .data["status"]
            .asText() shouldBe "ABORTED"
        rows("SELECT status FROM dashboard_refreshes WHERE id = '$refreshId'").single()["status"] shouldBe "ABORTED"
        awaitExecutionsEnded(refreshId)
        rows("SELECT status FROM pipeline_executions WHERE execution_id = '$executionId'").single()["status"] shouldNotBe "RUNNING"
        // A finished refresh is not abortable — idempotent from the client's view.
        refused(
            post("/api/v1/dashboards/$HUNG_LONG/runtime/refreshes/$refreshId/abort", """{"instance_id":"$INSTANCE"}""", VIEWER),
            404,
        ) shouldBe
            "dashboard.refresh.not_found"
        // …and the executions route never cancelled a dashboard run for anyone.
        refused(delete("/api/v1/executions/$executionId", ADMIN), 404) shouldBe "result.execution_not_found"
    }

    @Test
    @Order(13)
    fun `admission - a workspace at its refresh cap is refused 429 with Retry-After, and NO row is written for the refusal`() {
        val cid = configurationId(HUNG_LONG, VIEWER)
        val first = openRefresh(HUNG_LONG, VIEWER, refreshBody(cid, uuid()))
        val second = openRefresh(HUNG_LONG, OTHER_VIEWER, refreshBody(cid, uuid()))
        first.await("source_started")
        second.await("source_started")
        val before = rows("SELECT COUNT(*) AS n FROM dashboard_refreshes").single()["n"]!!.toInt()
        val rejected = uuid()

        val answer = post("/api/v1/dashboards/$HUNG_LONG/runtime/visualizations", refreshBody(cid, rejected), ADMIN)

        refused(answer, 429) shouldBe "dashboard.refresh.saturated"
        answer.header("Retry-After") shouldBe "1"
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$rejected'").single()["n"] shouldBe "0"
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes").single()["n"]!!.toInt() shouldBe before

        listOf(first to VIEWER, second to OTHER_VIEWER).forEach { (live, who) ->
            post("/api/v1/dashboards/$HUNG_LONG/runtime/refreshes/${live.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", who)
            // An abort as the owner needs the owner's instance id; the second used the default too (refreshBody's).
            live.done(30)
        }
        // Room again once they ended: the same refresh is admitted.
        refresh(
            GOLDEN,
            ADMIN,
            refreshBody(configurationId(GOLDEN, ADMIN), uuid()),
        ).of("refresh_completed").single().data["status"].asText() shouldBe
            "COMPLETED"
    }

    @Test
    @Order(14)
    fun `the finish is not on the normal path - a client that simply vanishes still gets its row closed, after the grace`() {
        val refreshId = uuid()
        val live = openRefresh(HUNG_LONG, VIEWER, refreshBody(configurationId(HUNG_LONG, VIEWER), refreshId))
        live.await("source_started")

        live.close() // no abort call: the connection just goes

        val ended = awaitRefreshStatus(refreshId, timeoutSeconds = 40)
        withClue("the refresh was aborted by the disconnect grace and its row is CLOSED, asserted at the database") {
            ended shouldBe
                "ABORTED"
        }
        rows("SELECT (finished_at IS NOT NULL)::text AS done FROM dashboard_refreshes WHERE id = '$refreshId'").single()["done"] shouldBe
            "true"
        awaitExecutionsEnded(refreshId)
        rows(
            "SELECT COUNT(*) AS n FROM audit_log WHERE event = 'dashboard.refresh' AND details_json ->> 'refresh_id' = '$refreshId'",
        ).single()["n"] shouldBe
            "1"
    }

    // ------------------------------------------------------------------------------------------ 8 the promoter's lens

    @Test
    @Order(15)
    fun `the promoter - a lens-admitted dashboard refreshes WITHOUT pipeline execute, a hidden one is 404, and it names no execution`() {
        // The delegated act (D50): she holds no `pipeline.execute` at all.
        refused(post("/api/v1/pipelines/${pipelineId("dbr/pipelines/small")}/execute", "{}", PROMOTER), 403) shouldBe "auth.role_required"

        val shown = get("/api/v1/dashboards/$PROMO_SHOWN/runtime/config", PROMOTER)
        withClue(shown.asString().take(EXCERPT)) { shown.statusCode shouldBe 200 }
        val refreshId = uuid()
        val frames = refresh(PROMO_SHOWN, PROMOTER, refreshBody(shown.jsonPath().getString("data.configuration_id"), refreshId))
        frames
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "COMPLETED"
        rows("SELECT executed_by::text AS by FROM pipeline_executions WHERE correlation_id = '$refreshId'").single()["by"] shouldBe
            PROMOTER_ID

        val record = get("/api/v1/dashboards/$PROMO_SHOWN/refreshes/$refreshId", PROMOTER)
        record.statusCode shouldBe 200
        record.jsonPath().getList<Any>("data.executions") shouldBe null // she reads no execution.read; the record names none for her
        rows("SELECT COUNT(*) AS n FROM dashboard_refresh_executions WHERE refresh_id = '$refreshId'").single()["n"] shouldBe "1"
        frames.filter { it.event.startsWith("source_") }.forEach { it.data.has("execution_id") shouldBe false }

        val failed = refresh(PROMO_FAILED, PROMOTER, refreshBody(configurationId(PROMO_FAILED, PROMOTER), uuid()))
        failed
            .of("source_failed")
            .single()
            .data
            .has("execution_id") shouldBe false

        val hidden = get("/api/v1/dashboards/$PROMO_HIDDEN/runtime/config", PROMOTER)
        val absent = get("/api/v1/dashboards/${UUID.randomUUID()}/runtime/config", PROMOTER)
        refused(hidden, 404) shouldBe "dashboard.not_found"
        refused(absent, 404) shouldBe "dashboard.not_found"
        withClue("a hidden dashboard answers exactly as an absent one") { hidden.asString().contains("promo_hidden") shouldBe false }
        refused(get("/api/v1/dashboards/$PROMO_HIDDEN/refreshes", PROMOTER), 404) shouldBe "dashboard.not_found"
        // The admin's whole view reaches it.
        get("/api/v1/dashboards/$PROMO_HIDDEN/runtime/config", ADMIN).statusCode shouldBe 200
    }

    @Test
    @Order(16)
    fun `delegated parameter evaluation returns the unchanged bounded evaluator response while standalone evaluation stays refused`() {
        val dashboardConfig = get("/api/v1/dashboards/$PARAMETERISED/runtime/config", PROMOTER)
        withClue(dashboardConfig.asString().take(EXCERPT)) { dashboardConfig.statusCode shouldBe 200 }
        val id = parameterSetId()
        val configurationId = dashboardConfig.jsonPath().getString("data.configuration_id")
        val instance = uuid()
        val runtime =
            post(
                "/api/v1/dashboards/$PARAMETERISED/runtime/parameters",
                """{"configuration_id":"$configurationId","instance_id":"$instance","selections":{},"intent":"bootstrap"}""",
                PROMOTER,
            )
        withClue(runtime.asString().take(EXCERPT)) { runtime.statusCode shouldBe 200 }

        val standalone = post("/api/v1/parameter-sets/$id/evaluate", """{"selections":{}}""", PROMOTER)
        refused(standalone, 403) shouldBe "auth.role_required"
        val direct = post("/api/v1/parameter-sets/$id/evaluate", """{"selections":{}}""", ADMIN)
        direct.statusCode shouldBe 200
        runtime.jsonPath().getString("data.name") shouldBe PARAMETER_SET
        runtime.jsonPath().getString("data.valid") shouldBe direct.jsonPath().getString("data.valid")
        runtime.jsonPath().getMap<String, Any>("data.values") shouldBe direct.jsonPath().getMap("data.values")
        runtime.jsonPath().getList<Map<String, Any>>("data.parameters") shouldBe direct.jsonPath().getList("data.parameters")

        // Exercise the real runtime response with a post-release selector failure. This synthetic test mutation uses
        // only the lane's local Postgres fixture; it is restored before the next scenario.
        val original =
            rows(
                "SELECT body_json::text AS body FROM parameter_set_versions WHERE parameter_set_id = '$id' AND version = 1",
            ).single()["body"]!!
        try {
            sql(
                "UPDATE parameter_set_versions SET body_json = jsonb_set(body_json, '{parameters,0,source}', " +
                    "'{\"template\":{\"id\":\"dbr/templates/boom.sql\",\"version\":1},\"datasource\":\"dbr-src\"}'::jsonb) " +
                    "WHERE parameter_set_id = '$id' AND version = 1",
            )
            val diagnostic =
                post(
                    "/api/v1/dashboards/$PARAMETERISED/runtime/parameters",
                    """{"configuration_id":"$configurationId","instance_id":"${uuid()}","selections":{},"intent":"retry"}""",
                    PROMOTER,
                )
            withClue(diagnostic.asString().take(EXCERPT)) { diagnostic.statusCode shouldBe 200 }
            val message = diagnostic.jsonPath().getString("data.parameters[0].state.errors[0].message")
            message shouldContain "division by zero"
            message.length shouldBeLessThanOrEqual 200
        } finally {
            sql(
                "UPDATE parameter_set_versions SET body_json = '${original.replace("'", "''")}'::jsonb " +
                    "WHERE parameter_set_id = '$id' AND version = 1",
            )
        }
    }

    @Test
    @Order(17)
    fun `stream projection rechecks a changed role immediately while dashboard execute remains admitted`() {
        fun changeRole(role: String) {
            val response =
                given()
                    .port(port)
                    .asSession(ADMIN)
                    .contentType(ContentType.JSON)
                    .body("""{"role":"$role"}""")
                    .put("/api/v1/workspaces/$WORKSPACE/members/$OTHER_VIEWER_ID")
            withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        }

        changeRole("author")
        val refreshId = uuid()
        val config = configurationId(ROLE_CHANGE_BOARD, OTHER_VIEWER)
        val live = openRefresh(ROLE_CHANGE_BOARD, OTHER_VIEWER, refreshBody(config, refreshId))
        live.await("source_started")
        val first = live.frames().first { it.event == "source_started" }
        val executionId = first.data.path("execution_id").asText()
        executionId shouldNotBe ""

        // WorkspaceService invalidates this instance's membership cache on the role update. Other instances converge
        // at AuthCache's 60-second TTL; this E2E uses the mutating instance and expects the next write immediately.
        changeRole("promoter")
        get("/api/v1/dashboards/$ROLE_CHANGE_BOARD/runtime/config", OTHER_VIEWER).statusCode shouldBe 200
        refused(post("/api/v1/pipelines/${pipelineId("dbr/pipelines/small")}/execute", "{}", OTHER_VIEWER), 403) shouldBe
            "auth.role_required"
        live.await("source_completed")
        live.done(30)
        val completed = live.frames().first { it.event == "source_completed" }
        completed.data.has("execution_id") shouldBe false
        rows("SELECT COUNT(*) AS n FROM dashboard_refresh_executions WHERE refresh_id = '$refreshId' AND execution_id = '$executionId'")
            .single()["n"] shouldBe "1"
        changeRole("viewer")
    }

    @Test
    @Order(18)
    fun `removing workspace A membership revokes its stream while workspace B stays usable and refresh completes`() {
        val sourceLock = DriverManager.getConnection(source.jdbcUrl, source.username, source.password)
        val refreshId = uuid()
        try {
            sourceLock.autoCommit = false
            sourceLock.createStatement().use { it.execute("SELECT pg_advisory_xact_lock($CONTROLLED_SOURCE_LOCK)") }

            val config = configurationId(REVOKED_BOARD, OTHER_VIEWER)
            val live = openRefresh(REVOKED_BOARD, OTHER_VIEWER, refreshBody(config, refreshId))
            live.await("source_started")

            val removed = delete("/api/v1/workspaces/$WORKSPACE/members/$OTHER_VIEWER_ID", SUPER)
            removed.statusCode shouldBe 204
            get("/api/v1/dashboards/$REVOKED_BOARD/runtime/config", OTHER_VIEWER).statusCode shouldBe 404

            val workspaceBProfile =
                given()
                    .port(port)
                    .asSession(OTHER_VIEWER)
                    .header("DP-Workspace", WORKSPACE_B)
                    .get("/api/v1/auth/me")
            withClue(workspaceBProfile.asString().take(EXCERPT)) { workspaceBProfile.statusCode shouldBe 200 }
            workspaceBProfile.jsonPath().getString("data.role") shouldBe "viewer"

            sourceLock.commit()
            live.done(30)

            withClue("SSE comments=${live.comments()} frames=${live.frames().names()}") {
                live.comments() shouldContain "revoked"
            }
            live.frames().names() shouldContainExactly listOf("refresh_started", "visualization_status", "source_started")
            live.frames().of("source_completed").size shouldBe 0
            live.frames().of("visualization_data").size shouldBe 0
            awaitRefreshStatus(refreshId, timeoutSeconds = 30) shouldBe "COMPLETED"
            awaitExecutionsEnded(refreshId)
        } finally {
            runCatching { sourceLock.rollback() }
            sourceLock.close()
        }
    }

    @Test
    @Order(20)
    fun `runtime refuses draft and discarded visualization set and transform pins before source work`() {
        val before = rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE triggered_via = 'DASHBOARD'").single()["n"]
        get("/api/v1/dashboards/$TRANSFORM_PIN_BOARD/runtime/config", VIEWER).statusCode shouldBe 200
        listOf("visualization", "parameter_set", "transform").forEach { dependency ->
            listOf("DRAFT", "DISCARDED").forEach { status ->
                assertRuntimePinRejected(dependency, status)
            }
        }
        rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE triggered_via = 'DASHBOARD'").single()["n"] shouldBe before
    }

    private fun assertRuntimePinRejected(
        dependency: String,
        status: String,
    ) {
        setRuntimePinStatus(dependency, status)
        try {
            val dashboard = if (dependency == "transform") TRANSFORM_PIN_BOARD else PARAMETERISED
            val config = get("/api/v1/dashboards/$dashboard/runtime/config", VIEWER)
            config.statusCode shouldBe 409
            config.jsonPath().getString("error.code") shouldBe "dashboard.runtime.dependency_missing"
            config.jsonPath().getString("error.details.dependency") shouldBe dependency
            config.jsonPath().getString("error.details.reason") shouldBe "not_released"
            val refresh =
                post(
                    "/api/v1/dashboards/$dashboard/runtime/visualizations",
                    refreshBody("0".repeat(64), uuid()),
                    VIEWER,
                )
            refresh.statusCode shouldBe 409
            refresh.jsonPath().getString("error.code") shouldBe "dashboard.runtime.dependency_missing"
            refresh.jsonPath().getString("error.details.dependency") shouldBe dependency
            refresh.jsonPath().getString("error.details.reason") shouldBe "not_released"
        } finally {
            setRuntimePinStatus(dependency, "RELEASED")
        }
    }

    @Test
    @Order(21)
    fun `runtime refuses missing set visualization and transform versions before source work`() {
        val original =
            rows(
                "SELECT body_json::text AS body FROM dashboard_versions WHERE dashboard_id = '$PARAMETERISED' AND version = 1",
            ).single()["body"]!!
        val originalTransform =
            rows(
                "SELECT body_json::text AS body FROM visualization_versions WHERE visualization_id = " +
                    "(SELECT id FROM visualizations WHERE name = '$VIZ_TRANSFORM' AND workspace_id = '$WORKSPACE_ID') AND version = 1",
            ).single()["body"]!!
        val before = rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE triggered_via = 'DASHBOARD'").single()["n"]
        listOf("parameter_set", "visualization", "transform").forEach { dependency ->
            val dashboard = if (dependency == "transform") TRANSFORM_PIN_BOARD else PARAMETERISED
            if (dependency == "transform") {
                sql(updateTransformPinVersionSql("99"))
            } else {
                val expression =
                    if (dependency == "parameter_set") {
                        "{parameter_set,version}"
                    } else {
                        "{visualizations,0,visualization,version}"
                    }
                sql(
                    "UPDATE dashboard_versions SET body_json = jsonb_set(body_json, '$expression', '99'::jsonb) " +
                        "WHERE dashboard_id = '$dashboard' AND version = 1",
                )
            }
            try {
                assertMissingRuntimePinRejected(dashboard, dependency)
            } finally {
                if (dependency == "transform") {
                    sql(restoreTransformPinSql(originalTransform))
                } else {
                    sql(
                        "UPDATE dashboard_versions SET body_json = '${original.replace(
                            "'",
                            "''",
                        )}'::jsonb WHERE dashboard_id = '$dashboard' AND version = 1",
                    )
                }
            }
        }
        rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE triggered_via = 'DASHBOARD'").single()["n"] shouldBe before
    }

    private fun assertMissingRuntimePinRejected(
        dashboard: String,
        dependency: String,
    ) {
        val config = get("/api/v1/dashboards/$dashboard/runtime/config", VIEWER)
        config.statusCode shouldBe 409
        config.jsonPath().getString("error.code") shouldBe "dashboard.runtime.dependency_missing"
        config.jsonPath().getString("error.details.dependency") shouldBe dependency
        config.jsonPath().getString("error.details.reason") shouldBe "not_found"
        val refresh =
            post(
                "/api/v1/dashboards/$dashboard/runtime/visualizations",
                refreshBody("0".repeat(64), uuid()),
                VIEWER,
            )
        refresh.statusCode shouldBe 409
        refresh.jsonPath().getString("error.code") shouldBe "dashboard.runtime.dependency_missing"
        refresh.jsonPath().getString("error.details.dependency") shouldBe dependency
    }

    private fun updateTransformPinVersionSql(version: String): String =
        "UPDATE visualization_versions SET body_json = jsonb_set(body_json, '{transform,template,version}', " +
            "'$version'::jsonb) WHERE visualization_id = (SELECT id FROM visualizations " +
            "WHERE name = '$VIZ_TRANSFORM' AND workspace_id = '$WORKSPACE_ID') AND version = 1"

    private fun restoreTransformPinSql(body: String): String =
        "UPDATE visualization_versions SET body_json = '${body.replace("'", "''")}'::jsonb " +
            "WHERE visualization_id = (SELECT id FROM visualizations WHERE name = '$VIZ_TRANSFORM' " +
            "AND workspace_id = '$WORKSPACE_ID') AND version = 1"

    // ------------------------------------------------------------------------------------------ 9 housekeeping

    @Test
    @Order(18)
    fun `the sweeper closes a RUNNING refresh an instance crash left - and only that one`() {
        val stale = uuid()
        val young = uuid()
        insertRefresh(stale, status = "RUNNING", startedAgo = "3 hours")
        insertRefresh(young, status = "RUNNING", startedAgo = "5 seconds")

        val closed = housekeeping("dashboardRefreshSweeper", "sweepOnce")

        withClue("the sweeper reported the row it reaped") { closed shouldBe 1 }
        rows(
            "SELECT status, summary_json ->> 'reason' AS reason, (finished_at IS NOT NULL)::text AS done FROM " +
                "dashboard_refreshes WHERE id = '$stale'",
        ).single()
            .let {
                it["status"] shouldBe "TIMED_OUT"
                it["reason"] shouldBe "deadline_passed"
                it["done"] shouldBe "true"
            }
        rows("SELECT status FROM dashboard_refreshes WHERE id = '$young'").single()["status"] shouldBe "RUNNING"
        rows("UPDATE dashboard_refreshes SET status = 'ABORTED', finished_at = NOW() WHERE id = '$young' RETURNING id").size shouldBe 1
    }

    @Test
    @Order(19)
    fun `retention deletes finished refreshes and their links - never the execution, never a RUNNING refresh`() {
        val old = uuid()
        val recent = uuid()
        val running = uuid()
        val execution = UUID.randomUUID().toString()
        insertExecution(execution)
        insertRefresh(old, status = "COMPLETED", startedAgo = "30 days", finishedAgo = "30 days")
        insertRefresh(recent, status = "COMPLETED", startedAgo = "1 hour", finishedAgo = "1 hour")
        insertRefresh(running, status = "RUNNING", startedAgo = "30 days")
        sql(
            "INSERT INTO dashboard_refresh_executions (refresh_id, source_name, execution_id, shared) VALUES " +
                "('$old', 's', '$execution', FALSE)",
        )

        val purged = housekeeping("dashboardRefreshRetention", "retainOnce")

        purged shouldBeGreaterThanOrEqual 1
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$old'").single()["n"] shouldBe "0"
        rows("SELECT COUNT(*) AS n FROM dashboard_refresh_executions WHERE refresh_id = '$old'").single()["n"] shouldBe "0" // the cascade
        // never the execution
        rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE execution_id = '$execution'").single()["n"] shouldBe "1"
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$recent'").single()["n"] shouldBe "1"
        // RUNNING is never retained away
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$running'").single()["n"] shouldBe "1"
        sql("DELETE FROM dashboard_refreshes WHERE id IN ('$recent', '$running')")
    }

    @Test
    @Order(22)
    fun `non-vacuity - every refusal the walk exists for happened, counted by code`() {
        println("event=dashboard_runtime_e2e.refusals total=${refusals.size} ${refusals.groupingBy { it }.eachCount()}")
        refusals.groupingBy { it }.eachCount() shouldBe
            mapOf(
                "result.execution_not_found" to 3,
                "dashboard.refresh.not_found" to 5, // record test 2, abort test 3
                "dashboard.validation.body_invalid" to 4, // invalid selection, reused id, v1 id, empty body
                "parameter.evaluate.unknown_parameter" to 1,
                "dashboard.runtime.configuration_stale" to 1,
                "dashboard.validation.empty_targets" to 1,
                "dashboard.validation.target_not_visualization" to 1,
                "dashboard.not_found" to 4,
                "dashboard.runtime.dependency_missing" to 2,
                "dashboard.refresh.saturated" to 1,
                "auth.role_required" to 3, // promoter direct execute, denied standalone evaluation, role-change control
            )
    }

    // ================================================================================================ helpers

    private var goldenRefreshId: String = ""

    private data class Frame(
        val event: String,
        val id: Int,
        val data: JsonNode,
    )

    private fun List<Frame>.names(): List<String> = map { it.event }

    private fun List<Frame>.ids(): List<Int> = map { it.id }

    private fun List<Frame>.of(event: String): List<Frame> = filter { it.event == event }

    /** A refresh stream read in the background — a hung refresh has to be observed WHILE it runs. */
    private inner class LiveRefresh(
        val refreshId: String,
        private val response: HttpResponse<InputStream>,
    ) {
        private val seen = CopyOnWriteArrayList<Frame>()
        private val seenComments = CopyOnWriteArrayList<String>()
        private val finished = CompletableFuture<Unit>()

        init {
            Thread {
                runCatching { parse(response.body(), { seen += it }, { seenComments += it }) }
                finished.complete(Unit)
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun frames(): List<Frame> = seen.toList()

        fun comments(): List<String> = seenComments.toList()

        fun await(event: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STREAM_WAIT_SECONDS)
            while (seen.none { it.event == event }) {
                check(System.nanoTime() < deadline) { "no '$event' frame within $STREAM_WAIT_SECONDS s; saw ${seen.map { it.event }}" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        fun done(seconds: Long) {
            finished.get(seconds, TimeUnit.SECONDS)
        }

        fun close() {
            runCatching { response.body().close() }
        }
    }

    private fun parse(
        input: InputStream,
        onFrame: (Frame) -> Unit,
        onComment: (String) -> Unit = {},
    ) {
        var event: String? = null
        var id = 0
        BufferedReader(InputStreamReader(input)).forEachLine { line ->
            when {
                line.startsWith(":") -> onComment(line.removePrefix(":").trim())
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("id:") -> id = line.removePrefix("id:").trim().toInt()
                line.startsWith("data:") -> onFrame(Frame(event ?: "", id, mapper.readTree(line.removePrefix("data:").trim())))
            }
        }
    }

    private fun refreshRequest(
        dashboard: String,
        session: String,
        body: String,
    ): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port/api/v1/dashboards/$dashboard/runtime/visualizations"))
            .header("Cookie", E2eSession.cookieHeader(session))
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    /** Opens the stream and returns as soon as its headers arrive — the frames are read in the background. */
    private fun openRefresh(
        dashboard: String,
        session: String,
        body: String,
    ): LiveRefresh {
        val refreshId = mapper.readTree(body)["refresh_id"].asText()
        val response = http.send(refreshRequest(dashboard, session, body), HttpResponse.BodyHandlers.ofInputStream())
        // The body is read ONLY on a refusal: a clue string built eagerly would swallow the first frames of a healthy stream.
        if (response.statusCode() != 200) error("status ${response.statusCode()}: ${response.body().readNBytes(EXCERPT).decodeToString()}")
        return LiveRefresh(refreshId, response)
    }

    /** A refresh read to its last frame. */
    private fun refresh(
        dashboard: String,
        session: String,
        body: String,
        budgetSeconds: Long = STREAM_WAIT_SECONDS,
    ): List<Frame> {
        val live = openRefresh(dashboard, session, body)
        live.done(budgetSeconds)
        return live.frames().also {
            withClue("the stream must end with refresh_completed: ${it.names()}") {
                it.last().event shouldBe
                    "refresh_completed"
            }
        }
    }

    private fun refreshBody(
        configurationId: String,
        refreshId: String,
        selections: String = "{}",
        scope: String = "all",
        targets: String = "[]",
        instance: String = INSTANCE,
    ): String =
        """{"configuration_id":"$configurationId","instance_id":"$instance","refresh_id":"$refreshId","parameter_revision":1,""" +
            """"selections":$selections,"scope":"$scope","targets":$targets}"""

    private fun configurationId(
        dashboard: String,
        session: String,
    ): String {
        val response = get("/api/v1/dashboards/$dashboard/runtime/config", session)
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        return response.jsonPath().getString("data.configuration_id")
    }

    private fun awaitRefreshStatus(
        refreshId: String,
        timeoutSeconds: Long,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val status = rows("SELECT status FROM dashboard_refreshes WHERE id = '$refreshId'").single()["status"]!!
            if (status != "RUNNING") return status
            Thread.sleep(POLL_MILLIS)
        }
        return "RUNNING"
    }

    /** Every execution of the refresh is out of RUNNING — the hung statement was cancelled, not orphaned. */
    private fun awaitExecutionsEnded(refreshId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXECUTION_END_SECONDS)
        while (System.nanoTime() < deadline) {
            val running =
                rows(
                    "SELECT COUNT(*) AS n FROM pipeline_executions WHERE correlation_id = '$refreshId' AND status = 'RUNNING'",
                ).single()["n"]!!.toInt()
            if (running == 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("an execution of refresh $refreshId is still RUNNING after $EXECUTION_END_SECONDS s")
    }

    private fun refused(
        response: Response,
        status: Int,
    ): String {
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe status }
        return response.jsonPath().getString("error.code").also { refusals += it }
    }

    private fun get(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).get(path)

    private fun delete(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).delete(path)

    private fun post(
        path: String,
        body: String,
        session: String,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .body(body)
            .post(path)

    private fun uuid(): String = UUID.randomUUID().toString()

    // ---------------------------------------------------------------------------------------------- fixture

    private fun seedPeople() {
        sql(
            "INSERT INTO workspaces (id, name, display_name) VALUES " +
                "('$WORKSPACE_ID', '$WORKSPACE', 'Dashboard runtime E2E'), " +
                "('$WORKSPACE_B_ID', '$WORKSPACE_B', 'Dashboard runtime E2E positive control')",
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$SUPER_ID', 'dr-super@e2e.test', 'DR Super', 'test', 'dr-super-sub', TRUE, TRUE),
                ('$ADMIN_ID', 'dr-admin@e2e.test', 'DR Admin', 'test', 'dr-admin-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'dr-viewer@e2e.test', 'DR Viewer', 'test', 'dr-viewer-sub', TRUE, FALSE),
                ('$OTHER_VIEWER_ID', 'dr-viewer2@e2e.test', 'DR Viewer 2', 'test', 'dr-viewer2-sub', TRUE, FALSE),
                ('$PROMOTER_ID', 'dr-promoter@e2e.test', 'DR Promoter', 'test', 'dr-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$OTHER_VIEWER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter'),
                ('$WORKSPACE_B_ID', '$OTHER_VIEWER_ID', 'viewer')
            """.trimIndent(),
        )
    }

    private fun registerDatasource() {
        val created =
            // A workspace admin cannot register a datasource on this server (member-datasources-enabled=false): the
            // instance's super admin does — the datasource is the deployment's, exactly as production has it.
            given()
                .port(port)
                .asSession(SUPER)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"dbr-src","display_name":"Runtime source","dialect":"POSTGRES","jdbc_url":"${source.jdbcUrl}",""" +
                        """"username":"${source.username}","password":"${source.password}"}""",
                ).post("/api/v1/datasources")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
    }

    private fun createTemplates() {
        PIPELINES.values.map { it.template to it.sql }.distinct().forEach { (id, sql) ->
            val response =
                given()
                    .port(port)
                    .asSession(ADMIN)
                    .contentType(ContentType.JSON)
                    .body(
                        mapper.writeValueAsString(
                            mapOf(
                                "id" to id,
                                "dialect" to "POSTGRES",
                                "display_name" to id,
                                "description" to "",
                                "imports" to emptyList<String>(),
                                "body" to sql,
                            ),
                        ),
                    ).post("/api/v1/templates")
            withClue("template $id: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 201 }
        }
    }

    /** L4 is not installed in this lane; seed released template rows just like pipeline releases below. */
    private fun releaseTemplates() {
        sql(
            "UPDATE template_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND template_id IN (SELECT id FROM templates WHERE workspace_id = '$WORKSPACE_ID')",
        )
    }

    private fun createPipeline(
        name: String,
        spec: PipelineSpec,
    ) {
        val node =
            mapOf(
                "id" to "read",
                "description" to "the dashboard source",
                "type" to "DQL",
                "source" to "dbr-src",
                "template" to mapOf("id" to spec.template, "version" to 1),
                "output" to
                    if (spec.writeBack) {
                        mapOf("target" to "datasource", "datasource" to "dbr-src", "table" to "dbr_target", "mode" to "append")
                    } else {
                        mapOf("target" to "caller")
                    },
                "depends_on" to emptyList<String>(),
            )
        val body =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to name,
                "description" to "dashboard runtime E2E source",
                "parameters" to spec.parameters,
                "nodes" to listOf(node),
            )
        val response =
            given()
                .port(
                    port,
                ).asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(body))
                .post("/api/v1/pipelines")
        withClue("pipeline $name: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 201 }
    }

    private fun releasePipelines() {
        PIPELINES.keys.forEach { name ->
            sql(
                "UPDATE pipeline_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                    "WHERE version = 1 AND pipeline_id = (SELECT id FROM pipelines " +
                    "WHERE name = '$name' AND workspace_id = " +
                    "'$WORKSPACE_ID')",
            )
            sql(
                "UPDATE pipelines SET current_version = 1 WHERE name = '$name' AND " +
                    "workspace_id = '$WORKSPACE_ID'",
            )
        }
        hiddenHash =
            rows(
                "SELECT v.body_hash AS h FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id WHERE p.name " +
                    "= '$HIDDEN_PIPELINE' AND v.version = 1",
            ).single()["h"]!!
    }

    private fun pipelineId(name: String): String =
        rows("SELECT id::text AS i FROM pipelines WHERE name = '$name' AND workspace_id = '$WORKSPACE_ID'").single()["i"]!!

    private fun parameterSetId(): String =
        rows("SELECT id::text AS i FROM parameter_sets WHERE name = '$PARAMETER_SET' AND workspace_id = '$WORKSPACE_ID'").single()["i"]!!

    private fun setRuntimePinStatus(
        dependency: String,
        status: String,
    ) {
        require(dependency == "visualization" || dependency == "parameter_set" || dependency == "transform")
        require(status == "DRAFT" || status == "DISCARDED" || status == "RELEASED")
        val stamp = runtimeStatusStamp(status)
        if (dependency == "transform") {
            sql(
                "UPDATE template_versions SET status = '$status', $stamp WHERE template_id = " +
                    "(SELECT id FROM templates WHERE name = 'dbr/templates/small.sql' AND " +
                    "workspace_id = '$WORKSPACE_ID') AND version = 1",
            )
            return
        }
        val table = if (dependency == "visualization") "visualization_versions" else "parameter_set_versions"
        val foreignKey = if (dependency == "visualization") "visualization_id" else "parameter_set_id"
        val family = if (dependency == "visualization") "visualizations" else "parameter_sets"
        val name = if (dependency == "visualization") VIZ_C else PARAMETER_SET
        sql(
            "UPDATE $table SET status = '$status', $stamp WHERE $foreignKey = " +
                "(SELECT id FROM $family WHERE name = '$name' AND workspace_id = '$WORKSPACE_ID') AND version = 1",
        )
    }

    private fun runtimeStatusStamp(status: String): String =
        when (status) {
            "DRAFT" -> {
                "released_at = NULL, released_by = NULL, discarded_at = NULL, discarded_by = NULL"
            }

            "DISCARDED" -> {
                "released_at = COALESCE(released_at, NOW()), released_by = COALESCE(released_by, '$ADMIN_ID'), " +
                    "discarded_at = NOW(), discarded_by = '$ADMIN_ID'"
            }

            else -> {
                "released_at = COALESCE(released_at, NOW()), released_by = COALESCE(released_by, '$ADMIN_ID'), " +
                    "discarded_at = NULL, discarded_by = NULL"
            }
        }

    private fun seedVisualizations() {
        listOf(
            VIZ_X to
                """{"display_name":"X","renderer":{"kind":"table","version":"1"},"inputs":{"main":{"columns":[{"name":"x",""" +
                """"type":"INTEGER","nullable":false}]}},"config":{},"bindings":{"cells.x":"x"}}""",
            VIZ_C to
                """{"display_name":"C","renderer":{"kind":"table","version":"1"},"inputs":{"main":{"columns":[{"name":"c",""" +
                """"type":"STRING","nullable":false}]}},"config":{},"bindings":{"cells.c":"c"}}""",
            VIZ_TRANSFORM to
                """{"display_name":"Transform pin","renderer":{"kind":"table","version":"1"},"inputs":{"main":{"columns":[""" +
                """{"name":"x","type":"INTEGER","nullable":false}]}},""" +
                """"transform":{"template":{"name":"dbr/templates/small.sql","version":1},"inputs":{"rows":"main"}},""" +
                """"config":{},"bindings":{"cells.x":"x"}}""",
        ).forEach { (name, body) -> seedArtifact("visualizations", "visualization_versions", "visualization_id", uuid(), name, body) }
    }

    private fun seedBoards() {
        val setId = createParameterSet()
        val boards =
            listOf(
                Board(GOLDEN, "golden", listOf(src("s1", "small")), listOf(occ("v1", VIZ_X, "s1"))),
                Board(
                    SHARED,
                    "shared",
                    listOf(src("a", "small"), src("b", "small"), src("c", "small_b")),
                    listOf(occ("va", VIZ_X, "a"), occ("vb", VIZ_X, "b"), occ("vc", VIZ_X, "c")),
                ),
                Board(
                    MIXED,
                    "mixed",
                    listOf(src("ok", "small"), src("bad", "boom")),
                    listOf(occ("vok", VIZ_X, "ok"), occ("vbad", VIZ_X, "bad")),
                ),
                Board(
                    PARAMETERISED,
                    "parameterised",
                    listOf(src("s1", "by_country", """{"country":{"parameter":"country"}}""")),
                    listOf(occ("vc", VIZ_C, "s1")),
                    set = setId,
                    parameterState =
                        """{"dashboard":{"visible":"inherit","enabled":"inherit"},"""" +
                            """parameters":{"country":{"visible":"force_false","enabled":"force_false"}}}""",
                ),
                Board(UNSAFE, "unsafe", listOf(src("w", "writer")), listOf(occ("vw", VIZ_X, "w"))),
                Board(BIG, "big", listOf(src("s1", "big")), listOf(occ("vbig", VIZ_X, "s1"))),
                Board(
                    HUNG_SHORT_OCCURRENCE,
                    "hung_occurrence",
                    listOf(src("s1", "hang")),
                    listOf(occ("vh", VIZ_X, "s1", timeout = 2)),
                    refreshSeconds = 25,
                ),
                Board(HUNG_SHORT_REFRESH, "hung_refresh", listOf(src("s1", "hang")), listOf(occ("vh", VIZ_X, "s1")), refreshSeconds = 3),
                Board(HUNG_LONG, "hung_long", listOf(src("s1", "hang")), listOf(occ("vh", VIZ_X, "s1"))),
                Board(PROMO_SHOWN, "promo_shown", listOf(src("s1", "small")), listOf(occ("v1", VIZ_X, "s1"))),
                Board(PROMO_HIDDEN, "promo_hidden", listOf(src("s1", "hidden")), listOf(occ("v1", VIZ_X, "s1"))),
                Board(PROMO_FAILED, "promo_failed", listOf(src("s1", "boom")), listOf(occ("v1", VIZ_X, "s1"))),
                Board(TRANSFORM_PIN_BOARD, "transform_pin", listOf(src("s1", "small")), listOf(occ("v1", VIZ_TRANSFORM, "s1"))),
                Board(ROLE_CHANGE_BOARD, "role_change", listOf(src("s1", "slow")), listOf(occ("v1", VIZ_X, "s1"))),
                Board(REVOKED_BOARD, "revoked_member", listOf(src("s1", "controlled")), listOf(occ("v1", VIZ_X, "s1"))),
            )
        boards.forEach { board ->
            val body =
                mapper.createObjectNode().apply {
                    put("display_name", board.slug)
                    board.set?.let { set<JsonNode>("parameter_set", mapper.readTree("""{"name":"$PARAMETER_SET","version":1}""")) }
                    set<JsonNode>("sources", mapper.readTree(board.sources.joinToString(",", "[", "]")))
                    set<JsonNode>("visualizations", mapper.readTree(board.visualizations.joinToString(",", "[", "]")))
                    set<JsonNode>("layout", mapper.readTree("{}"))
                    board.refreshSeconds?.let { set<JsonNode>("timeouts", mapper.readTree("""{"refresh_seconds":$it}""")) }
                    board.parameterState?.let { set<JsonNode>("parameter_state", mapper.readTree(it)) }
                }
            seedArtifact("dashboards", "dashboard_versions", "dashboard_id", board.id, "dbr/boards/${board.slug}", body.toString())
        }
    }

    private data class Board(
        val id: String,
        val slug: String,
        val sources: List<String>,
        val visualizations: List<String>,
        val set: String? = null,
        val refreshSeconds: Int? = null,
        val parameterState: String? = null,
    )

    private fun src(
        name: String,
        pipeline: String,
        parameters: String = "{}",
    ) = """{"name":"$name","pipeline":{"name":"dbr/pipelines/$pipeline","version":1},"parameters":$parameters}"""

    private fun occ(
        name: String,
        visualization: String,
        source: String,
        timeout: Int? = null,
    ) = """{"name":"$name","type":"visualization","visualization":{"name":"$visualization","version":1},"""" +
        """inputs":{"main":{"source":"$source"}}""" +
        (timeout?.let { ""","timeout_seconds":$it""" } ?: "") + "}"

    private fun createParameterSet(): String {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to PARAMETER_SET,
                    "display_name" to "Country",
                    "description" to "",
                    "parameters" to
                        listOf(
                            mapOf(
                                "name" to "country",
                                "label" to "Country",
                                "type" to "STRING",
                                "kind" to "SELECT",
                                "cardinality" to "SINGLE",
                                "required" to true,
                                "source" to
                                    mapOf(
                                        "constants" to
                                            listOf(
                                                mapOf("value" to "USA", "display_value" to "United States", "is_default" to true),
                                                mapOf("value" to "CAN", "display_value" to "Canada", "is_default" to false),
                                            ),
                                    ),
                                "presentation" to mapOf("control" to "dropdown"),
                            ),
                        ),
                ),
            )
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/v1/parameter-sets")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        val released =
            given()
                .port(
                    port,
                ).asSession(
                    ADMIN,
                ).header("If-Match", created.jsonPath().getString("data.body_hash"))
                .body("")
                .post("/api/v1/parameter-sets/$id/release")
        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe 200 }
        return id
    }

    @Suppress("LongParameterList") // one row pair per family table
    private fun seedArtifact(
        table: String,
        versions: String,
        fk: String,
        id: String,
        name: String,
        body: String,
    ) {
        sql(
            "INSERT INTO $table (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', '$name', '$name', '', 1, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO $versions ($fk, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$id', 1, '${body.replace("'", "''")}'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
    }

    private fun insertExecution(id: String) {
        sql(
            "INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, parameters_json, executed_by," +
                "triggered_via, root_execution_id) " +
                "VALUES ('$id', '${pipelineId("dbr/pipelines/small")}', 1, 'SUCCESS', '{}'::jsonb, '$VIEWER_ID', 'DASHBOARD', '$id')",
        )
    }

    private fun insertRefresh(
        id: String,
        status: String,
        startedAgo: String,
        finishedAgo: String? = null,
    ) {
        sql(
            "INSERT INTO dashboard_refreshes (id, dashboard_id, dashboard_version, workspace_id, instance_id, principal_user_id, scope, " +
                "parameter_revision, status, started_at, finished_at) VALUES ('$id', '$GOLDEN', 1, '$WORKSPACE_ID', '${uuid()}'," +
                "'$VIEWER_ID', 'ALL', 1, " +
                "'$status', NOW() - INTERVAL '$startedAgo', ${finishedAgo?.let {
                    "NOW() - INTERVAL '$it'"
                } ?: (if (status == "RUNNING") "NULL" else "NOW()")})",
        )
    }

    // ------------------------------------------------------------------------------------------------- SQL

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    /** Each row as column → text (null stays null). */
    private fun rows(query: String): List<Map<String, String?>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
                }
            }
        }

    private class PipelineSpec(
        val template: String,
        val sql: String,
        val parameters: Map<String, Any> = emptyMap(),
        val writeBack: Boolean = false,
    )

    private companion object {
        const val EXCERPT = 600
        const val STREAM_WAIT_SECONDS = 60L
        const val EXECUTION_END_SECONDS = 30L
        const val POLL_MILLIS = 100L
        const val HALF_OF_REFRESH_DEADLINE = 13L
        const val ABORT_ANSWER_MILLIS = 2_500L
        const val SOURCE_CAP = 4_096
        const val CONTROLLED_SOURCE_LOCK = 343_343_343L

        const val WORKSPACE = "dbrun"
        const val WORKSPACE_B = "dbrun-b"
        const val HIDDEN_PIPELINE = "dbr/pipelines/hidden"
        const val PARAMETER_SET = "dbr/sets/country"
        const val VIZ_X = "dbr/charts/x"
        const val VIZ_C = "dbr/charts/c"
        const val VIZ_TRANSFORM = "dbr/charts/transform_pin"
        const val INSTANCE = "11111111-1111-4111-8111-111111111111"

        val PIPELINES: Map<String, PipelineSpec> =
            linkedMapOf(
                "dbr/pipelines/small" to PipelineSpec("dbr/templates/small.sql", "SELECT 1 AS x UNION ALL SELECT 2 AS x"),
                "dbr/pipelines/small_b" to PipelineSpec("dbr/templates/small_b.sql", "SELECT 10 AS x UNION ALL SELECT 20 AS x"),
                "dbr/pipelines/boom" to PipelineSpec("dbr/templates/boom.sql", "SELECT 1 / 0 AS x"),
                "dbr/pipelines/slow" to PipelineSpec("dbr/templates/slow.sql", "SELECT 1 AS x FROM pg_sleep(3)"),
                "dbr/pipelines/controlled" to
                    PipelineSpec(
                        "dbr/templates/controlled.sql",
                        "SELECT 1 AS x FROM (SELECT pg_advisory_xact_lock($CONTROLLED_SOURCE_LOCK)) AS held",
                    ),
                "dbr/pipelines/hang" to PipelineSpec("dbr/templates/hang.sql", "SELECT 1 AS x FROM pg_sleep(120)"),
                "dbr/pipelines/big" to PipelineSpec("dbr/templates/big.sql", "SELECT g AS x FROM generate_series(1, 5000) g"),
                "dbr/pipelines/writer" to PipelineSpec("dbr/templates/writer.sql", "SELECT 1 AS x", writeBack = true),
                "dbr/pipelines/by_country" to
                    PipelineSpec(
                        "dbr/templates/by_country.sql",
                        "SELECT CAST(:country AS TEXT) AS c",
                        parameters = mapOf("country" to mapOf("type" to "STRING", "required" to true)),
                    ),
                HIDDEN_PIPELINE to PipelineSpec("dbr/templates/hidden.sql", "SELECT 3 AS x"),
            )

        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val WORKSPACE_B_ID = UUID.randomUUID().toString()
        private val SUPER_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()
        private val OTHER_VIEWER_ID = UUID.randomUUID().toString()
        private val PROMOTER_ID = UUID.randomUUID().toString()

        val GOLDEN = UUID.randomUUID().toString()
        val SHARED = UUID.randomUUID().toString()
        val MIXED = UUID.randomUUID().toString()
        val PARAMETERISED = UUID.randomUUID().toString()
        val UNSAFE = UUID.randomUUID().toString()
        val BIG = UUID.randomUUID().toString()
        val HUNG_SHORT_OCCURRENCE = UUID.randomUUID().toString()
        val HUNG_SHORT_REFRESH = UUID.randomUUID().toString()
        val HUNG_LONG = UUID.randomUUID().toString()
        val PROMO_SHOWN = UUID.randomUUID().toString()
        val PROMO_HIDDEN = UUID.randomUUID().toString()
        val PROMO_FAILED = UUID.randomUUID().toString()
        val TRANSFORM_PIN_BOARD = UUID.randomUUID().toString()
        val ROLE_CHANGE_BOARD = UUID.randomUUID().toString()
        val REVOKED_BOARD = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SUPER get() = E2eSession.jwt(JWT_SECRET, SUPER_ID, "dr-super@e2e.test", WORKSPACE)
        private val ADMIN get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "dr-admin@e2e.test", WORKSPACE)
        private val VIEWER get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "dr-viewer@e2e.test", WORKSPACE)
        private val OTHER_VIEWER get() = E2eSession.jwt(JWT_SECRET, OTHER_VIEWER_ID, "dr-viewer2@e2e.test", WORKSPACE)
        private val PROMOTER get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "dr-promoter@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis

        /**
         * The runtime's SOURCE database: created ONCE, when the companion initialises. This was a `get()`
         * accessor until 2026-10-01 — every read ran `scratchDatabase`, which DROPS the database `WITH (FORCE)`
         * and recreates it, terminating every live connection to it (the app's pooled source connections
         * among them: `FATAL: terminating connection due to administrator command`, SQLSTATE 57P01). The
         * membership-revocation case reads it three times in one line; whether the pool then handed the
         * refresh a dead connection depended on Hikari's 500 ms alive-bypass window — green here, red on
         * CI run 36872021211. `ScratchDatabaseOnceGuardTest` refuses the accessor shape tree-wide.
         */
        private val source = SharedE2e.scratchDatabase("dbrun_source")
        private val oidc = OidcDiscoveryStub()

        /** The hash the stub higher environment holds for the HIDDEN pipeline: the promoter's lens admits only what is newer. */
        @Volatile private var hiddenHash: String = "unset"

        /** The stub higher environment: it already serves the HIDDEN pipeline at its released hash. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat","authoring_enabled":false,""" +
                                """"workspace":"$WORKSPACE","templates":[],"datasources":[],"parameter_sets":[],"pipelines":[""" +
                                """{"name":"$HIDDEN_PIPELINE","current_version":1,"body_hash":"$hiddenHash"}]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }
        }

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
            registry.add("datapipelines.scheduler.enabled") { "false" }
            // The promoter lens reads the stub higher environment.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "dbr-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
            // The runtime, configured small so saturation, the cap and the grace are reachable in seconds.
            registry.add("datapipelines.dashboards.admission.max-concurrent-refreshes-per-workspace") { "2" }
            registry.add("datapipelines.dashboards.admission.max-wait-seconds") { "1" }
            registry.add("datapipelines.dashboards.results.max-bytes-per-source") { "$SOURCE_CAP" }
            registry.add("datapipelines.dashboards.results.max-bytes-per-refresh") { "16384" }
            registry.add("datapipelines.sse.disconnect-grace-seconds") { "2" }
            registry.add("datapipelines.sse.heartbeat-interval-seconds") { "1" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
