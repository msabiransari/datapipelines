package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
 * **#356 — the abort that arrives BEFORE the refresh row exists.**
 *
 * The window is forced, never raced: `max-wait-seconds` is raised to 60 for this context alone (the sibling
 * `DashboardRuntimeE2eTest` pins it at 1 for its saturation case, which is why this walk lives in its own class), so a
 * third start whose workspace's two refresh places are both held blocks INSIDE `admit` — after the start marker is
 * registered, before `insertRunning` writes the row — for as long as the test needs. The positive in-window signal is
 * the marker itself in Redis (`dp:refresh-start:{workspace}:{refresh}`), not a sleep.
 *
 * Everything is real HTTP/SSE against the real stack (a real Postgres, a real Redis, the real executor) and every
 * scenario asserts at the DATABASE, not only on the wire. The falsifications of each leg are logged in the handback:
 * the window case answers 404 and the refresh completes on the unmodified runtime; a distinguishing message planted on
 * the marker path turns the byte-equality case red; an unbounded marker store turns the bound case red.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk over one fixture, as the sibling E2Es are
class DashboardRefreshAbortWindowE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    /** The admission counters by NAME (module-structure §4.2: this module compiles against `:modules:app` alone). */
    private fun refreshesInWorkspace(): Int =
        context.getBean("refreshAdmission").let { bean ->
            bean.javaClass.getMethod("refreshesIn", UUID::class.java).invoke(bean, UUID.fromString(WORKSPACE_ID)) as Int
        }

    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    @Test
    @Order(1)
    fun `fixture - one workspace, four people, a source database, a hung board and a fast board`() {
        seedPeople()
        registerDatasource()
        createAndReleaseTemplate(FAST_TEMPLATE, FAST_SQL)
        createAndReleaseTemplate(HUNG_TEMPLATE, HUNG_SQL)
        createAndReleasePipeline("dbr/pipelines/afast", FAST_TEMPLATE)
        createAndReleasePipeline("dbr/pipelines/ahang", HUNG_TEMPLATE)
        seedBoard(FAST, "afast", "afast")
        seedBoard(HUNG, "ahang", "ahang")
    }

    @Test
    @Order(2)
    fun `the window - an abort before the row is a 202 and the released start ends ABORTED before any source ran`() {
        val cid = configurationId(HUNG, OWNER)
        val holder1 = openRefresh(HUNG, OWNER, refreshBody(cid, uuid()))
        holder1.await("source_started")
        val holder2 = openRefresh(HUNG, OWNER, refreshBody(cid, uuid()))
        holder2.await("source_started")
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE status = 'RUNNING' AND workspace_id = '$WORKSPACE_ID'")
            .single()["n"] shouldBe "2" // the two places are held: the next start waits INSIDE admit

        val heldId = uuid()
        val held = startInBackground(HUNG, OWNER, refreshBody(cid, heldId, instance = INSTANCE))
        awaitStartMarker(heldId) // the in-flight start is registered: the window is open, deterministically
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$heldId'").single()["n"] shouldBe "0"

        val began = System.nanoTime()
        val abort = post("/api/v1/dashboards/$HUNG/runtime/refreshes/$heldId/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
        withClue("the abort answers without waiting for the start (the marker path)") {
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began) shouldBe 0L
        }
        abort.statusCode shouldBe 202
        abort.jsonPath().getString("data.refresh_id") shouldBe heldId
        abort.jsonPath().getString("data.status") shouldBe "abort_requested"

        // Release: freeing a place admits the held start, whose row is inserted and whose job-start
        // check finds the recorded intent — ABORTED before any source runs.
        post("/api/v1/dashboards/$HUNG/runtime/refreshes/${holder2.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
            .statusCode shouldBe 202
        val live = held.get(90, TimeUnit.SECONDS)
        live.await("refresh_started")
        live.done(60)
        val frames = live.frames()
        frames.first().event shouldBe "refresh_started"
        frames.last().event shouldBe "refresh_completed"
        frames.last().data["status"].asText() shouldBe "ABORTED"
        // No source frame of any kind: the abort was honoured before the fan-out.
        frames.filter { it.event.startsWith("source_") }.shouldBeEmpty()
        frames.filter { it.event == "visualization_data" }.shouldBeEmpty()

        val row =
            rows(
                "SELECT status, (finished_at IS NOT NULL)::text AS done, summary_json::text AS summary " +
                    "FROM dashboard_refreshes WHERE id = '$heldId'",
            ).single()
        row["status"] shouldBe "ABORTED"
        row["done"] shouldBe "true"
        row["summary"] shouldContain "ABORTED"
        rows("SELECT COUNT(*) AS n FROM dashboard_refresh_executions WHERE refresh_id = '$heldId'").single()["n"] shouldBe "0"
        rows("SELECT COUNT(*) AS n FROM pipeline_executions WHERE correlation_id = '$heldId'").single()["n"] shouldBe "0"
        rows(
            "SELECT COUNT(*) AS n FROM audit_log WHERE event = 'dashboard.refresh' AND details_json ->> 'refresh_id' = '$heldId'",
        ).single()["n"] shouldBe "1" // one awaited audit row per refresh — the aborted-at-start refresh is no exception

        // Nothing leaked: both places come back and the next refresh is admitted at once.
        post("/api/v1/dashboards/$HUNG/runtime/refreshes/${holder1.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
            .statusCode shouldBe 202
        awaitNoRunningRefresh()
        awaitEveryPlaceReturned()
        refresh(FAST, OWNER, refreshBody(configurationId(FAST, OWNER), uuid()))
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "COMPLETED"
    }

    @Test
    @Order(3)
    fun `the four no-cases are the identical 404 - and the marker path's no is byte-equal to the row path's`() {
        val cid = configurationId(HUNG, OWNER)
        val holder1 = openRefresh(HUNG, OWNER, refreshBody(cid, uuid()))
        holder1.await("source_started")
        val holder2 = openRefresh(HUNG, OWNER, refreshBody(cid, uuid()))
        holder2.await("source_started")

        val heldId = uuid()
        val held = startInBackground(HUNG, OWNER, refreshBody(cid, heldId, instance = INSTANCE))
        awaitStartMarker(heldId)

        // (1) another person against the in-flight start; (2) the owner on another client instance;
        // (3) an unknown id — three independent 404s while the window is open.
        val byStranger =
            post("/api/v1/dashboards/$HUNG/runtime/refreshes/$heldId/abort", """{"instance_id":"$INSTANCE"}""", STRANGER)
        val byWrongInstance =
            post("/api/v1/dashboards/$HUNG/runtime/refreshes/$heldId/abort", """{"instance_id":"${uuid()}"}""", OWNER)
        val unknownId = uuid()
        val unknown =
            post("/api/v1/dashboards/$HUNG/runtime/refreshes/$unknownId/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
        listOf(byStranger to 1, byWrongInstance to 2, unknown to 3).forEach { (response, n) ->
            withClue("no-case $n") {
                response.statusCode shouldBe 404
                response.jsonPath().getString("error.code") shouldBe "dashboard.refresh.not_found"
            }
        }
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$heldId'").single()["n"] shouldBe "0"
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE status = 'RUNNING' AND id = '${holder1.refreshId}'")
            .single()["n"] shouldBe "1" // nothing was stopped by the refused aborts

        // Release: the start (no intent was ever recorded for it) admits and runs.
        post("/api/v1/dashboards/$HUNG/runtime/refreshes/${holder2.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
            .statusCode shouldBe 202
        val live = held.get(90, TimeUnit.SECONDS)
        live.await("source_started")

        // (4) the same stranger against the SAME id, now a RUNNING row: byte-equal to (1) — the marker
        // path's no and the row path's no are indistinguishable on the wire.
        val againstRow =
            post("/api/v1/dashboards/$HUNG/runtime/refreshes/$heldId/abort", """{"instance_id":"$INSTANCE"}""", STRANGER)
        againstRow.statusCode shouldBe 404
        normalize(byStranger.body().asString(), heldId) shouldBe normalize(againstRow.body().asString(), heldId)
        // (5) the finished id: abort it as the owner, then again — the second is the finished-refresh
        // idempotence, byte-equal to (4): same id, different path, same bytes.
        post("/api/v1/dashboards/$HUNG/runtime/refreshes/$heldId/abort", """{"instance_id":"$INSTANCE"}""", OWNER).statusCode shouldBe 202
        live.done(60)
        val finished =
            post("/api/v1/dashboards/$HUNG/runtime/refreshes/$heldId/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
        finished.statusCode shouldBe 404
        normalize(finished.body().asString(), heldId) shouldBe normalize(againstRow.body().asString(), heldId)
        // (3) again, normalized: the unknown id's body is the others' with only the id substituted.
        normalize(unknown.body().asString(), unknownId) shouldBe normalize(againstRow.body().asString(), heldId)

        post("/api/v1/dashboards/$HUNG/runtime/refreshes/${holder1.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
            .statusCode shouldBe 202
        awaitNoRunningRefresh()
    }

    @Test
    @Order(4)
    fun `the bound - a principal at the marker cap is refused the start, and none of the earlier markers is lost`() {
        val cid = configurationId(HUNG, OWNER)
        // Both places are HELD so nothing admits: nine concurrent starts of one principal register
        // nine stable markers (an admitted start's marker is gone in milliseconds — only a start
        // blocked in the window keeps its own); the NINTH is refused the saturated 429 by the bound.
        val holder1 = openRefresh(HUNG, OWNER, refreshBody(cid, uuid()))
        holder1.await("source_started")
        val holder2 = openRefresh(HUNG, OWNER, refreshBody(cid, uuid()))
        holder2.await("source_started")

        val held =
            (1..MARKER_BOUND)
                .map { uuid() }
                .map { id -> id to startInBackground(HUNG, OWNER, refreshBody(cid, id, instance = INSTANCE)) }
        held.forEach { (id, _) -> awaitStartMarker(id) }

        val refusedId = uuid()
        val refused = post("/api/v1/dashboards/$HUNG/runtime/visualizations", refreshBody(cid, refusedId, instance = INSTANCE), OWNER)
        refused.statusCode shouldBe 429
        refused.jsonPath().getString("error.code") shouldBe "dashboard.refresh.saturated"
        refused.header("Retry-After") shouldNotBe null
        rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE id = '$refusedId'").single()["n"] shouldBe "0"

        // The eight markers still stand: aborting each DURING its window is a 202 — the bound cost
        // the older starts nothing.
        held.forEach { (id, _) ->
            post("/api/v1/dashboards/$HUNG/runtime/refreshes/$id/abort", """{"instance_id":"$INSTANCE"}""", OWNER).statusCode shouldBe 202
        }

        // Release: each start is admitted, its row inserted and its recorded intent honoured at job
        // start — ABORTED before any source runs — and each ending frees its place for the next.
        post("/api/v1/dashboards/$HUNG/runtime/refreshes/${holder2.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
            .statusCode shouldBe 202
        post("/api/v1/dashboards/$HUNG/runtime/refreshes/${holder1.refreshId}/abort", """{"instance_id":"$INSTANCE"}""", OWNER)
            .statusCode shouldBe 202

        fun terminal(id: String): Boolean {
            val status = rows("SELECT status FROM dashboard_refreshes WHERE id = '$id'").singleOrNull()?.get("status")
            return status != null && status != "RUNNING"
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STREAM_WAIT_SECONDS)
        while (held.any { (id, _) -> !terminal(id) }) {
            check(System.nanoTime() < deadline) { "not every held start ended within $STREAM_WAIT_SECONDS s" }
            Thread.sleep(POLL_MILLIS)
        }
        held.forEach { (_, future) -> future.get(90, TimeUnit.SECONDS).done(60) }
        awaitNoRunningRefresh()
        awaitEveryPlaceReturned()
    }

    // ================================================================================================ helpers

    private inner class LiveRefresh(
        val refreshId: String,
        private val response: HttpResponse<InputStream>,
    ) {
        private val seen = CopyOnWriteArrayList<Frame>()
        private val finished = CompletableFuture<Unit>()

        init {
            Thread {
                runCatching { parse(response.body(), { seen += it }) }
                finished.complete(Unit)
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun frames(): List<Frame> = seen.toList()

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
    }

    private data class Frame(
        val event: String,
        val data: com.fasterxml.jackson.databind.JsonNode,
    )

    private fun parse(
        input: InputStream,
        onFrame: (Frame) -> Unit,
    ) {
        var event: String? = null
        BufferedReader(InputStreamReader(input)).forEachLine { line ->
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> onFrame(Frame(event ?: "", mapper.readTree(line.removePrefix("data:").trim())))
            }
        }
    }

    /** The start is issued on its own thread: a start held inside admit does not answer until it is released. */
    private fun startInBackground(
        dashboard: String,
        session: String,
        body: String,
    ): CompletableFuture<LiveRefresh> {
        val refreshId = mapper.readTree(body)["refresh_id"].asText()
        return CompletableFuture.supplyAsync {
            val response = http.send(refreshRequest(dashboard, session, body), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                error("status ${response.statusCode()}: ${response.body().readNBytes(EXCERPT).decodeToString()}")
            }
            LiveRefresh(refreshId, response)
        }
    }

    /** The POSITIVE in-window signal: the start's marker exists in Redis, the row does not. No sleep decides this. */
    private fun awaitStartMarker(refreshId: String) {
        val key = "dp:refresh-start:$WORKSPACE_ID:$refreshId"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MARKER_WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val result = SharedE2e.redis.execInContainer("redis-cli", "EXISTS", key)
            if (result.stdout.trim() == "1") return
            Thread.sleep(POLL_MILLIS)
        }
        error("no start marker for $refreshId within $MARKER_WAIT_SECONDS s — the start never reached its registration")
    }

    /**
     * The places come back in the engine launch's `finally`, AFTER the engine ended the row — so a poll that saw no
     * RUNNING row can still see the counter one release behind it. Awaited, like the row; the deadline is the leak.
     */
    private fun awaitEveryPlaceReturned() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STREAM_WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (refreshesInWorkspace() == 0) return
            Thread.sleep(POLL_MILLIS)
        }
        withClue("every admitted place was returned within $STREAM_WAIT_SECONDS s") { refreshesInWorkspace() shouldBe 0 }
    }

    private fun awaitNoRunningRefresh() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STREAM_WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val running =
                rows("SELECT COUNT(*) AS n FROM dashboard_refreshes WHERE workspace_id = '$WORKSPACE_ID' AND status = 'RUNNING'")
                    .single()["n"]!!
                    .toInt()
            if (running == 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("a refresh of the workspace is still RUNNING after $STREAM_WAIT_SECONDS s")
    }

    /**
     * Both bodies with their refresh id and the per-request correlation id substituted, so only a
     * real difference in the error's own words can remain (the envelope's `correlation_id` is request
     * metadata, never error content — it differs on every call by design).
     */
    private fun normalize(
        body: String,
        refreshId: String,
    ): String =
        body
            .replace(refreshId, "<id>")
            .replace(Regex("\"correlation_id\"\\s*:\\s*\"[0-9a-f-]+\""), "\"correlation_id\":\"<cid>\"")

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

    private fun openRefresh(
        dashboard: String,
        session: String,
        body: String,
    ): LiveRefresh {
        val refreshId = mapper.readTree(body)["refresh_id"].asText()
        val response = http.send(refreshRequest(dashboard, session, body), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) error("status ${response.statusCode()}: ${response.body().readNBytes(EXCERPT).decodeToString()}")
        return LiveRefresh(refreshId, response)
    }

    private fun refresh(
        dashboard: String,
        session: String,
        body: String,
    ): List<Frame> {
        val live = openRefresh(dashboard, session, body)
        live.done(60)
        return live.frames().also {
            withClue("the stream must end with refresh_completed: ${it.map { f -> f.event }}") {
                it.last().event shouldBe "refresh_completed"
            }
        }
    }

    private fun List<Frame>.of(event: String): List<Frame> = filter { it.event == event }

    private fun refreshBody(
        configurationId: String,
        refreshId: String,
        instance: String = INSTANCE,
    ): String =
        """{"configuration_id":"$configurationId","instance_id":"$instance","refresh_id":"$refreshId","parameter_revision":1,""" +
            """"selections":{},"scope":"all","targets":[]}"""

    private fun configurationId(
        dashboard: String,
        session: String,
    ): String {
        val response = get("/api/v1/dashboards/$dashboard/runtime/config", session)
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        return response.jsonPath().getString("data.configuration_id")
    }

    private fun get(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).get(path)

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
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Abort window E2E')",
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$SUPER_ID', 'dw-super@e2e.test', 'DW Super', 'test', 'dw-super-sub', TRUE, TRUE),
                ('$ADMIN_ID', 'dw-admin@e2e.test', 'DW Admin', 'test', 'dw-admin-sub', TRUE, FALSE),
                ('$OWNER_ID', 'dw-owner@e2e.test', 'DW Owner', 'test', 'dw-owner-sub', TRUE, FALSE),
                ('$STRANGER_ID', 'dw-stranger@e2e.test', 'DW Stranger', 'test', 'dw-stranger-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$OWNER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$STRANGER_ID', 'viewer')
            """.trimIndent(),
        )
    }

    private fun registerDatasource() {
        val source = SharedE2e.scratchDatabase("abortw_source")
        val created =
            given()
                .port(port)
                .asSession(SUPER)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"dw-src","display_name":"Abort window source","dialect":"POSTGRES","jdbc_url":"${source.jdbcUrl}",""" +
                        """"username":"${source.username}","password":"${source.password}"}""",
                ).post("/api/v1/datasources")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
    }

    private fun createAndReleaseTemplate(
        id: String,
        sql: String,
    ) {
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
        sql(
            "UPDATE template_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND template_id = (SELECT id FROM templates WHERE workspace_id = '$WORKSPACE_ID' AND name = '$id')",
        )
    }

    private fun createAndReleasePipeline(
        name: String,
        template: String,
    ) {
        val node =
            mapOf(
                "id" to "read",
                "description" to "the dashboard source",
                "type" to "DQL",
                "source" to "dw-src",
                "template" to mapOf("id" to template, "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to emptyList<String>(),
            )
        val body =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to name,
                "description" to "abort window E2E source",
                "parameters" to emptyMap<String, Any>(),
                "nodes" to listOf(node),
            )
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(body))
                .post("/api/v1/pipelines")
        withClue("pipeline $name: ${created.asString().take(EXCERPT)}") { created.statusCode shouldBe 201 }
        sql(
            "UPDATE pipeline_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND pipeline_id = (SELECT id FROM pipelines WHERE name = '$name' AND workspace_id = '$WORKSPACE_ID')",
        )
        sql("UPDATE pipelines SET current_version = 1 WHERE name = '$name' AND workspace_id = '$WORKSPACE_ID'")
    }

    private fun seedBoard(
        id: String,
        slug: String,
        pipeline: String,
    ) {
        val visualizationId = uuid()
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$visualizationId', '$WORKSPACE_ID', 'dw/charts/$slug', '$slug', '', 1, '$ADMIN_ID')",
        )
        val visualizationBody = VISUALIZATION_BODY.replace("'", "''")
        val visualizationInsert =
            "INSERT INTO visualization_versions " +
                "(visualization_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$visualizationId', 1, '$visualizationBody'::jsonb, 'RELEASED', " +
                "'seeded-$visualizationId', NOW(), '$ADMIN_ID', '$ADMIN_ID')"
        sql(visualizationInsert)
        val body =
            mapper.createObjectNode().apply {
                put("display_name", slug)
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "sources",
                    mapper.readTree(
                        """[{"name":"s1","pipeline":{"name":"dbr/pipelines/$pipeline","version":1},"parameters":{}}]""",
                    ),
                )
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "visualizations",
                    mapper.readTree(
                        """[{"name":"v","type":"visualization","visualization":{"name":"dw/charts/$slug","version":1},""" +
                            """"inputs":{"main":{"source":"s1"}}}]""",
                    ),
                )
                set<com.fasterxml.jackson.databind.JsonNode>("layout", mapper.readTree("{}"))
            }
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', 'dw/boards/$slug', '$slug', '', 1, '$ADMIN_ID')",
        )
        val dashboardBody = body.toString().replace("'", "''")
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$id', 1, '$dashboardBody'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
    }

    private fun sql(statement: String) {
        val jdbcUrl = SharedE2e.postgres.jdbcUrl
        DriverManager.getConnection(jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun rows(query: String): List<Map<String, String?>> =
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection
                    .createStatement()
                    .use { statement ->
                        statement.executeQuery(query).use { rs ->
                            val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                            buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
                        }
                    }
            }

    private companion object {
        const val EXCERPT = 600
        const val STREAM_WAIT_SECONDS = 60L
        const val MARKER_WAIT_SECONDS = 30L
        const val POLL_MILLIS = 100L
        const val MARKER_BOUND = 8

        const val WORKSPACE = "abortw"
        const val INSTANCE = "22222222-2222-4222-8222-222222222222"
        const val FAST_TEMPLATE = "dbr/templates/afast.sql"
        const val HUNG_TEMPLATE = "dbr/templates/ahang.sql"
        const val FAST_SQL = "SELECT 1 AS x UNION ALL SELECT 2 AS x"
        const val HUNG_SQL = "SELECT 1 AS x FROM pg_sleep(120)"
        const val VISUALIZATION_BODY =
            """{"display_name":"V","renderer":{"kind":"table","version":"1"},""" +
                """"inputs":{"main":{"columns":[{"name":"x","type":"INTEGER","nullable":false}]}},"config":{},"bindings":{"cells.x":"x"}}"""

        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val SUPER_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val OWNER_ID = UUID.randomUUID().toString()
        private val STRANGER_ID = UUID.randomUUID().toString()

        val FAST = UUID.randomUUID().toString()
        val HUNG = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()

        val SUPER get() = E2eSession.jwt(JWT_SECRET, SUPER_ID, "dw-super@e2e.test", WORKSPACE)
        val ADMIN get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "dw-admin@e2e.test", WORKSPACE)
        val OWNER get() = E2eSession.jwt(JWT_SECRET, OWNER_ID, "dw-owner@e2e.test", WORKSPACE)
        val STRANGER get() = E2eSession.jwt(JWT_SECRET, STRANGER_ID, "dw-stranger@e2e.test", WORKSPACE)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { "100" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
            // The window: two places per workspace, and a start blocked INSIDE admission waits a
            // minute — far past anything this walk needs, so the barrier never expires mid-case.
            registry.add("datapipelines.dashboards.admission.max-concurrent-refreshes-per-workspace") { "2" }
            registry.add("datapipelines.dashboards.admission.max-wait-seconds") { "60" }
            // The marker bound IS the stream cap; eight leaves the window cases far under it while
            // the bound case itself fires nine concurrent starts of one principal.
            registry.add("datapipelines.sse.max-streams-per-user") { "$MARKER_BOUND" }
            registry.add("datapipelines.sse.disconnect-grace-seconds") { "2" }
            registry.add("datapipelines.sse.heartbeat-interval-seconds") { "1" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            // nothing of this class outlives its context
        }
    }
}
