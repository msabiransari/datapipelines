package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #841 (G0) — the writer protocol at the WIRE, over the real application and the shared test Postgres (brief §C layer
 * (c), the contention-test table): every guarded boundary that exhausts the production lock wait (5 s) answers its
 * family's busy code — HTTP 409 on REST, an `isError` result on MCP — with the release message, details exactly
 * `{lock_wait_seconds}` and nothing written (endpoint, datasource and schedule writers, and the template, parameter set,
 * visualization and dashboard draft writes of the shared release guard); and three semantic witnesses show the guard
 * deciding an outcome:
 *
 * - a template purge queued behind a pin save is refused `template.in_use` once the pin committed;
 * - an endpoint publish waits on the registry key, and a second publish of the same path is refused after the winner
 *   committed (`endpoint.path_conflict`, never a 500);
 * - a schedule create whose target pipeline loses its current version while the create waits is refused at
 *   validation (`schedule.validation.target_not_released`), never saved — the validation runs UNDER the guard.
 *
 * The stand-in for a conflicting writer is a raw connection holding one lock in an open transaction — the workspace row
 * (`FOR NO KEY UPDATE`, as a release in flight holds it), the datasource's prerequisite key, or the endpoint registry
 * key. Waits are observed on a fresh connection (`pg_blocking_pids` names the holder), never timed.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class G0WriterContentionE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newFixedThreadPool(3)
    private var rpcId = 0

    // ---- holders and the waiter probe -------------------------------------------------------------------------

    private class Holder(
        val connection: Connection,
        val pid: Int,
    ) : AutoCloseable {
        fun run(
            sql: String,
            vararg args: Any,
        ) = apply {
            connection.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.execute()
            }
        }

        override fun close() {
            if (!connection.isClosed) {
                connection.rollback()
                connection.close()
            }
        }
    }

    private fun holder(): Holder {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        val pid =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use { it.next().let { _ -> it.getInt(1) } }
            }
        return Holder(connection, pid)
    }

    private fun holdWorkspace(): Holder = holder().run("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE", WORKSPACE_ID)

    private fun holdDatasourceKey(name: String): Holder =
        holder().run("SELECT pg_advisory_xact_lock(?, ?)", DATASOURCE_LOCK_SPACE, nameHash(name))

    /**
     * The guard's advisory sub-key for a canonical name (`JdbcReleaseMutationGuard.nameHash`: the first four bytes of its
     * SHA-256) — restated because this module compiles against `app` only; a drift makes the writer never wait (RED).
     */
    private fun nameHash(name: String): Int =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))).int

    private fun holdRegistryKey(): Holder = holder().run("SELECT pg_advisory_xact_lock(?)", ENDPOINT_REGISTRY_KEY)

    /**
     * Backends blocked by [pid], directly or behind another waiter — on a fresh connection (inside a transaction the view
     * would be frozen). Transitive because a row lock queues: the FIRST waiter takes the tuple lock and waits on the
     * holder, a later one waits on that first waiter, so `pg_blocking_pids` names the waiter ahead, not the holder.
     */
    private fun waitersOn(pid: Int): Int = countOf(WAITERS_SQL, pid)

    /** One `count(*)` [sql] bound to [args], on a fresh connection of its own. */
    private fun countOf(
        sql: String,
        vararg args: Any,
    ): Int =
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            .queryForObject(sql, Int::class.java, *args) ?: 0

    /** Polls until [count] backends wait on [holder]; RED when [writer] answered first (it never waited). */
    private fun awaitWaiters(
        holder: Holder,
        writer: CompletableFuture<*>,
        count: Int = 1,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while (waitersOn(holder.pid) < count) {
            withClue("the writer answered without waiting on the held lock: ${if (writer.isDone) writer.join() else ""}") {
                writer.isDone shouldBe false
            }
            withClue("no $count waiter(s) on the holder within the probe window") { (System.nanoTime() < deadline) shouldBe true }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
    }

    /**
     * [writer] fired against [holder]'s lock, held until the writer gives up: the production bound, then the refusal. A
     * writer still waiting when the named allowance ends waits UNBOUNDED — the holder is released and the answer it then
     * gives is reported, so that defect reads as what it is, never as a bare timeout.
     */
    private fun <T> exhausts(
        holder: Holder,
        writer: () -> T,
    ): T =
        holder.use {
            val answer = CompletableFuture.supplyAsync({ writer() }, pool)
            awaitWaiters(it, answer)
            try {
                answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (unbounded: TimeoutException) {
                it.close()
                val late = answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                throw AssertionError(
                    "the writer did not give up within the ${WRITER_TIMEOUT_SECONDS}s allowance (an unbounded wait); once the " +
                        "holder released, it answered: ${if (late is Response) "${late.statusCode} ${late.asString()}" else late}",
                    unbounded,
                )
            }
        }

    private fun assertRestBusy(
        response: Response,
        code: String,
    ) {
        withClue(response.asString()) { response.statusCode shouldBe 409 }
        val error = mapper.readTree(response.asString())["error"]
        error["code"].asText() shouldBe code
        error["message"].asText() shouldBe BUSY_MESSAGE
        error["details"] shouldBe mapper.readTree("""{"lock_wait_seconds": $DEFAULT_LOCK_WAIT_SECONDS}""")
    }

    private fun assertMcpBusy(
        answer: Pair<JsonNode, Boolean>,
        code: String,
    ) {
        val (payload, isError) = answer
        withClue(payload) { isError shouldBe true }
        payload["error"]["code"].asText() shouldBe code
        payload["error"]["message"].asText() shouldBe BUSY_MESSAGE
        payload["error"]["details"] shouldBe mapper.readTree("""{"lock_wait_seconds": $DEFAULT_LOCK_WAIT_SECONDS}""")
    }

    // ---- wire helpers -----------------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val id = synchronized(this) { ++rpcId }
        val body =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "method" to "tools/call",
                "params" to mapOf("name" to name, "arguments" to arguments),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val tree = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { tree.has("error") shouldBe false }
        val result = tree["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun rest(): RequestSpecification = given().port(port).asSession(SESSION)

    private fun template(id: String): Pair<JsonNode, Boolean> =
        callTool(
            "templates_create",
            mapOf(
                "id" to id,
                "dialect" to "H2",
                "display_name" to "G0",
                "description" to "Expects: nothing (plain SQL).",
                "body" to "SELECT 1 AS n",
            ),
        )

    private fun pipelineArguments(
        name: String,
        templateId: String,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "G0 writer contention",
            "description" to "One node pinning a template.",
            "nodes" to
                listOf(
                    mapOf(
                        "id" to "n0",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "description" to "One row",
                        "template" to mapOf("id" to templateId, "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
        )

    /** A pipeline created over MCP and released (with its pinned template) over REST; its id. */
    private fun releasedPipeline(
        name: String,
        templateId: String,
    ): String {
        val (created, isError) = callTool("pipelines_create", pipelineArguments(name, templateId))
        withClue("pipelines_create must succeed: $created") { isError shouldBe false }
        val id = created["id"].asText()
        val released =
            rest()
                .header("If-Match", created["draft"]["body_hash"].asText())
                .queryParam("release_pinned_templates", "true")
                .post("/api/v1/pipelines/$id/release")
        withClue(released.asString()) { released.statusCode shouldBe 200 }
        return id
    }

    private fun publish(path: String): Response =
        rest().contentType(ContentType.JSON).body("""{"path": "$path", "pipeline": "$PIPELINE"}""").post("/api/v1/endpoints")

    private fun createSchedule(
        name: String,
        pipeline: String,
    ): Response =
        rest()
            .contentType(ContentType.JSON)
            .body(
                mapper.writeValueAsString(
                    mapOf(
                        "name" to name,
                        "payload" to mapOf("pipeline" to pipeline, "version" to "current"),
                        "cron" to "0 3 * * *",
                        "timezone" to "UTC",
                    ),
                ),
            ).post("/api/v1/schedules")

    private fun registerDatasource(name: String) {
        rest()
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$name", "display_name": "G0 writer contention", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun grantees(datasource: String): Int =
        countOf(
            "SELECT count(*) FROM datasource_workspaces WHERE datasource_name = ? AND workspace_id = ?::uuid",
            datasource,
            OTHER_WORKSPACE_ID,
        )

    // ---- the fixture ------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - an H2 datasource, a released pipeline and its released template`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        registerDatasource(DATASOURCE)
        template(TEMPLATE).second shouldBe false
        releasedPipeline(PIPELINE, TEMPLATE)
        // Non-vacuity of the probe: nobody waits on a fresh holder before any case starts.
        holdWorkspace().use { waitersOn(it.pid) shouldBe 0 }
    }

    // ---- busy at every guarded boundary -----------------------------------------------------------------------

    @Test
    @Order(2)
    fun `endpoint publish over REST - endpoint write busy, nothing published`() {
        assertRestBusy(exhausts(holdWorkspace()) { publish("/g0/v1/busy") }, "endpoint.write.busy")
        rest().queryParam("path", "/g0/v1/busy").get("/api/v1/endpoints").statusCode shouldBe 404
    }

    @Test
    @Order(3)
    fun `endpoint publish over MCP - endpoint write busy, nothing published`() {
        assertMcpBusy(
            exhausts(holdWorkspace()) {
                callTool("endpoints_create", mapOf("path" to "/g0/v1/mcp", "pipeline" to PIPELINE))
            },
            "endpoint.write.busy",
        )
        rest().queryParam("path", "/g0/v1/mcp").get("/api/v1/endpoints").statusCode shouldBe 404
    }

    @Test
    @Order(4)
    fun `datasource grant over REST - datasource write busy on the held prerequisite key, nothing granted`() {
        val response = exhausts(holdDatasourceKey(DATASOURCE)) { rest().post("/api/v1/datasources/$DATASOURCE/grants/$OTHER_WORKSPACE") }
        assertRestBusy(response, "datasource.write.busy")
        grantees(DATASOURCE) shouldBe 0
    }

    @Test
    @Order(5)
    fun `datasource delete over REST - datasource write busy on the held key, the datasource stays`() {
        registerDatasource(DATASOURCE_DOOMED)
        assertRestBusy(
            exhausts(holdDatasourceKey(DATASOURCE_DOOMED)) {
                rest().delete("/api/v1/datasources/$DATASOURCE_DOOMED")
            },
            "datasource.write.busy",
        )
        rest().get("/api/v1/datasources/$DATASOURCE_DOOMED").statusCode shouldBe 200
    }

    @Test
    @Order(6)
    fun `schedule create over REST - schedule write busy, nothing saved`() {
        assertRestBusy(exhausts(holdWorkspace()) { createSchedule("g0/busy", PIPELINE) }, "schedule.write.busy")
        rest()
            .get("/api/v1/schedules")
            .jsonPath()
            .getList<String>("data.items.name")
            .contains("g0/busy") shouldBe false
    }

    @Test
    @Order(7)
    fun `template draft create over MCP - template release busy, nothing created`() {
        assertMcpBusy(exhausts(holdWorkspace()) { template(TEMPLATE_BUSY) }, "template.release.busy")
        callTool("templates_get", mapOf("id" to TEMPLATE_BUSY)).second shouldBe true
    }

    @Test
    @Order(8)
    fun `template sole-draft purge over MCP - template release busy, the draft stays`() {
        template(TEMPLATE_PURGED).second shouldBe false
        assertMcpBusy(
            exhausts(holdWorkspace()) { callTool("templates_purge_draft", mapOf("id" to TEMPLATE_PURGED)) },
            "template.release.busy",
        )
        callTool("templates_get", mapOf("id" to TEMPLATE_PURGED)).second shouldBe false
    }

    // ---- semantic witnesses -----------------------------------------------------------------------------------

    @Test
    @Order(9)
    fun `a purge queued behind a pin save is refused template in_use once the pin committed`() {
        template(TEMPLATE_PINNED).second shouldBe false
        val (pinned, refused) =
            holdWorkspace().use { holder ->
                val pin =
                    CompletableFuture.supplyAsync(
                        { callTool("pipelines_create", pipelineArguments("test/g0/pinner", TEMPLATE_PINNED)) },
                        pool,
                    )
                awaitWaiters(holder, pin)
                val purge = CompletableFuture.supplyAsync({ callTool("templates_purge_draft", mapOf("id" to TEMPLATE_PINNED)) }, pool)
                awaitWaiters(holder, purge, count = 2)
                holder.connection.commit()
                pin.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS) to purge.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        withClue("the pin save lands first: ${pinned.first}") { pinned.second shouldBe false }
        withClue("the purge sees the committed pin: ${refused.first}") { refused.second shouldBe true }
        refused.first["error"]["code"].asText() shouldBe "template.in_use"
        callTool("templates_get", mapOf("id" to TEMPLATE_PINNED)).second shouldBe false
    }

    @Test
    @Order(10)
    fun `an endpoint publish waits on the registry key and a second publish of the path is refused after the winner commits`() {
        val (first, second) =
            holdRegistryKey().use { holder ->
                val winner = CompletableFuture.supplyAsync({ publish("/g0/v1/rows") }, pool)
                awaitWaiters(holder, winner)
                val loser = CompletableFuture.supplyAsync({ publish("/g0/v1/rows") }, pool)
                holder.connection.commit()
                winner.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS) to loser.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        withClue(first.asString()) { first.statusCode shouldBe 201 }
        withClue(second.asString()) { second.statusCode shouldBe 409 }
        second.jsonPath().getString("error.code") shouldBe "endpoint.path_conflict"
    }

    @Test
    @Order(11)
    fun `endpoint unpublish over REST - endpoint write busy, the endpoint stays - then delete and recreate succeed`() {
        val refused = exhausts(holdWorkspace()) { rest().queryParam("path", "/g0/v1/rows").delete("/api/v1/endpoints") }
        assertRestBusy(refused, "endpoint.write.busy")
        rest().queryParam("path", "/g0/v1/rows").get("/api/v1/endpoints").statusCode shouldBe 200

        rest().queryParam("path", "/g0/v1/rows").delete("/api/v1/endpoints").statusCode shouldBe 204
        withClue("the path is free again once its row is gone") { publish("/g0/v1/rows").statusCode shouldBe 201 }
    }

    @Test
    @Order(12)
    fun `a schedule whose target loses its current version while the create waits is refused at validation, never saved`() {
        val target = releasedPipeline(PIPELINE_WITHDRAWN, TEMPLATE)
        val response =
            holdWorkspace().use { holder ->
                // The conflicting writer, under the workspace guard: the target stops having a version to follow.
                holder.run("UPDATE pipelines SET current_version = NULL WHERE id = ?::uuid", target)
                val create = CompletableFuture.supplyAsync({ createSchedule("g0/withdrawn", PIPELINE_WITHDRAWN) }, pool)
                awaitWaiters(holder, create)
                holder.connection.commit()
                create.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        // Validated BEFORE the guard, the create would have seen the version, then waited and saved it: a 201.
        withClue(response.asString()) { response.statusCode shouldBe 400 }
        response.jsonPath().getString("error.code") shouldBe "schedule.validation.target_not_released"
    }

    // ---- busy on the remaining release families (the shared release guard's other callers) -------------------

    @Test
    @Order(13)
    fun `parameter set draft create over MCP - parameter release busy, nothing created`() {
        val arguments =
            mapOf(
                "name" to PARAMETER_SET,
                "display_name" to "G0 filters",
                "parameters" to
                    listOf(
                        mapOf(
                            "name" to "min_amount",
                            "label" to "Minimum amount",
                            "type" to "DECIMAL",
                            "precision" to 12,
                            "scale" to 2,
                            "kind" to "INPUT",
                            "required" to false,
                            "default_value" to 0,
                        ),
                    ),
            )
        assertMcpBusy(exhausts(holdWorkspace()) { callTool("parameter_sets_create", arguments) }, "parameter.release.busy")
        rowsNamed("parameter_sets", PARAMETER_SET) shouldBe 0
    }

    @Test
    @Order(14)
    fun `visualization draft create over MCP - visualization release busy, nothing created`() {
        val document = mapper.readValue(VISUALIZATION_DOCUMENT, Map::class.java)
        assertMcpBusy(
            exhausts(holdWorkspace()) {
                callTool("visualizations_create", document.mapKeys { it.key as String })
            },
            "visualization.release.busy",
        )
        rowsNamed("visualizations", VISUALIZATION) shouldBe 0
    }

    @Test
    @Order(15)
    fun `dashboard draft create over MCP - dashboard release busy, nothing created`() {
        val document = mapper.readValue(DASHBOARD_DOCUMENT, Map::class.java)
        assertMcpBusy(
            exhausts(holdWorkspace()) { callTool("dashboards_create", document.mapKeys { it.key as String }) },
            "dashboard.release.busy",
        )
        rowsNamed("dashboards", DASHBOARD) shouldBe 0
    }

    @Test
    @Order(16)
    fun `endpoint publish over REST on the held registry key - endpoint write busy within the bound, nothing published`() {
        // The repository's own registry lock (taken again inside the insert) is unbounded: only the guard's statement
        // turns a held registry key into the bounded busy refusal.
        assertRestBusy(exhausts(holdRegistryKey()) { publish("/g0/v1/registry") }, "endpoint.write.busy")
        rest().queryParam("path", "/g0/v1/registry").get("/api/v1/endpoints").statusCode shouldBe 404
    }

    @Test
    @Order(17)
    fun `template create from the catalog modal - the session form refuses with the busy message, nothing created`() {
        val refused =
            exhausts(holdWorkspace()) {
                rest()
                    .contentType(ContentType.URLENC)
                    .formParam("name", TEMPLATE_UI)
                    .formParam("type", "sql")
                    .formParam("dialect", "h2")
                    .formParam("body", "SELECT 1 AS n")
                    .post("/partials/templates")
            }
        // The modal's inline refusal (the form-level 400 every refusal on this surface renders), carrying the message.
        withClue(refused.asString()) { refused.statusCode shouldBe 400 }
        withClue(refused.asString()) { refused.asString().contains(BUSY_MESSAGE) shouldBe true }
        callTool("templates_get", mapOf("id" to TEMPLATE_UI)).second shouldBe true
    }

    /** Rows named [name] in this workspace's [table] — one of the three artifact tables, named by the test itself. */
    private fun rowsNamed(
        table: String,
        name: String,
    ): Int {
        require(table in ARTIFACT_TABLES) { "not an artifact table: $table" }
        return countOf("SELECT count(*) FROM $table WHERE workspace_id = ?::uuid AND name = ?", WORKSPACE_ID, name)
    }

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val TEMPLATE = "test/g0/shared.sql"
        private const val TEMPLATE_BUSY = "test/g0/busy.sql"
        private const val TEMPLATE_PURGED = "test/g0/purged.sql"
        private const val TEMPLATE_PINNED = "test/g0/pinned.sql"
        private const val TEMPLATE_UI = "test/g0/modal.sql"
        private const val PIPELINE = "test/g0/rows"
        private const val PIPELINE_WITHDRAWN = "test/g0/withdrawn"
        private const val PARAMETER_SET = "test/g0/filters"
        private const val VISUALIZATION = "test/g0/chart"
        private const val DASHBOARD = "test/g0/board"
        private val ARTIFACT_TABLES = setOf("parameter_sets", "visualizations", "dashboards")
        private val VISUALIZATION_DOCUMENT =
            """
            {"name": "$VISUALIZATION", "display_name": "G0 chart", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()
        private val DASHBOARD_DOCUMENT =
            """
            {"name": "$DASHBOARD", "display_name": "G0 board",
             "sources": [{"name": "rows_source", "pipeline": {"name": "$PIPELINE", "version": 1}}],
             "visualizations": [{"name": "rows_chart", "type": "visualization", "visualization": {"name": "$VISUALIZATION", "version": 1},
                                 "inputs": {"revenue": {"source": "rows_source"}}}],
             "layout": {"grid": [{"name": "rows_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()
        private const val DATASOURCE = "h2-g0-841"
        private const val DATASOURCE_DOOMED = "h2-g0-841-doomed"
        private const val H2_JDBC_URL = "jdbc:h2:mem:g0841;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "g0-841"
        private const val OTHER_WORKSPACE = "g0-841-other"

        /**
         * `PrerequisiteNamespace.DATASOURCE.lockSpace` and `EndpointRegistryLock.KEY`, as the guard issues them (restated:
         * see [nameHash]; a drift makes the writer never wait, which is RED, not a silent pass).
         */
        private const val DATASOURCE_LOCK_SPACE = 841_001
        private const val ENDPOINT_REGISTRY_KEY = 740_001L

        private const val BUSY_MESSAGE = "Another change in this workspace is in progress. Nothing was written; try again in a moment."

        /** The production default of `datapipelines.release.dependencies.lock-wait-seconds`. */
        private const val DEFAULT_LOCK_WAIT_SECONDS = 5

        /** The probe gives up before the writer's own lock wait would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private val WAITERS_SQL =
            """
            WITH RECURSIVE waiting(pid) AS (
                SELECT a.pid FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid))
                UNION
                SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
            )
            SELECT count(*) FROM waiting
            """.trimIndent()
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val OTHER_WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-841-g0-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-841-g0@datapipelines.test", WORKSPACE)

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
            registry.add("datapipelines.redis.password") { "" }
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
            // API mode: this suite saves schedules but must never dispatch other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'G0 841')," +
                            " ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'G0 841 other')",
                    )
                    // A super admin (instance datasources, grants) who is also this workspace's admin.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-841-g0@datapipelines.test', 'E2E 841', 'test', 'e2e-841-g0-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES" +
                            " ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', '${KEY.id}'," +
                            " TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_ID)
                        ps.setString(4, KEY.name)
                        ps.setString(5, KEY.hash)
                        ps.setString(6, WORKSPACE_ID)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
