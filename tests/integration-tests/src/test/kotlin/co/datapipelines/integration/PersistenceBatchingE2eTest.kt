package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * #266 — the batched persistence path over the REAL application, on the module's shared containers,
 * through the wire alone: this module compiles against `app` and nothing else (module-structure §4.2),
 * so every assertion here reads what a client, an operator or the database sees — HTTP, SQL, and the
 * actuator's `metrics` endpoint (exposed on the management port for this suite only). The writer-level
 * cases (a poison row, a paused store, saturation, the drain) run against the real classes in `web`'s
 * `PersistenceBatchingIntegrationTest`; the restart and the application's shutdown order in
 * `PersistenceRestartE2eTest`.
 *
 * The audit writer ships switched off (#266b; configuration §3.32): this suite switches it ON in its
 * property source, so D.2 proves the batched audit path — the default direct INSERT is
 * `AuditDirectByDefaultE2eTest`'s.
 *
 * - **D.1 order, real executions**: 32 REST executions at once — each one's `id:` sequence on its live
 *   stream, in the durable record and in the replay route is the same contiguous 1..N; no persistence
 *   failure of any kind is counted.
 * - **D.2 read your own write, under load**: the audit rows that DECIDE authorization are read back at
 *   once by the key that wrote them — `templates_render` then `pipelines_execute` of a draft (the
 *   render-freshness check reads the key's own `mcp.tool.called` row: 100 of 100 admitted) and a launch
 *   then `executions_cancel` (the same-credential rule reads the key's `mcp.execution.launched` row);
 *   the endpoint twin serves, then reads its execution back, 100 times. Those runs race a sub-millisecond
 *   commit against a client round trip, so they cannot catch an ack-before-commit; the FORCED case holds
 *   `audit_log`'s inserts (reads still pass) and proves the render returned only after its row committed.
 * - **D.5 cancel and terminal**: a real execution cancelled while `execution_events` is locked — the row
 *   reaches ABORTED within the writers' bound (the event writes gave up waiting; the terminal UPDATE is
 *   not blocked), no execution stays live, and once the lock lifts the terminal event is in the durable
 *   record and the replay.
 * - **D.9 no duplicate**: over the whole E2E database, no `(execution_id, event_id)` pair twice.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // one fixture, the acceptance list's cases share it
class PersistenceBatchingE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private val mapper = ObjectMapper()

    /** HTTP/1.1: one plain request per exchange, no h2c upgrade attempt shared across 32 concurrent callers. */
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private lateinit var pipelineId: String
    private lateinit var slowPipelineId: String
    private lateinit var endpointKey: String

    @BeforeAll
    fun fixture() {
        seedRows()
        DriverManager.getConnection(H2_URL, "sa", "sa").use { h2 ->
            h2.createStatement().execute("CREATE TABLE IF NOT EXISTS probe (id INT, label VARCHAR(20))")
            h2.createStatement().execute("INSERT INTO probe VALUES (1, 'one'), (2, 'two')")
        }
        registerDatasource()
        restTemplate(TEMPLATE_READ, "SELECT id, label FROM probe ORDER BY id")
        // SUM, not COUNT(*): H2 can answer a COUNT over SYSTEM_RANGE without iterating it.
        restTemplate(TEMPLATE_SLOW, "SELECT SUM(X) AS n FROM SYSTEM_RANGE(1, 4000000000)")
        pipelineId = restPipeline(PIPELINE_READ, TEMPLATE_READ)
        slowPipelineId = restPipeline(PIPELINE_SLOW, TEMPLATE_SLOW)
        publishEndpoint()
        endpointKey = mintEndpointKey()
    }

    // ------------------------------------------------------------------ D.1

    @Test
    @Order(1)
    fun `D1 - 32 REST executions at once - live stream, durable record and replay agree on 1 to N`() {
        val failuresBefore = meterSum("datapipelines.persistence.failures")
        val pool = Executors.newFixedThreadPool(CONCURRENT)
        val runs =
            (1..CONCURRENT)
                .map {
                    CompletableFuture.supplyAsync({ executeToEnd(pipelineId) }, pool)
                }.map { it.get(SIXTY_SECONDS * 2, TimeUnit.SECONDS) }
        pool.shutdown()

        runs.forEach { (executionId, liveIds) ->
            withClue("execution $executionId, live ids $liveIds") {
                liveIds shouldBe (1..liveIds.size).toList()
                durableIds(executionId) shouldBe liveIds
                replayIds(executionId) shouldBe liveIds
            }
        }
        withClue("no persistence failure of any kind — an event row ahead of its RUNNING row would be an FK refusal, counted") {
            meterSum("datapipelines.persistence.failures") shouldBe failuresBefore
        }
        // Non-vacuity: the running application routed these events through the batching writer
        // (multi-row group commits are proven over the real classes in web's integration test).
        val committedBatches = statistic(metric("datapipelines.persistence.batch.size", "store:execution_events"), "COUNT")
        println(
            "persistence-e2e D1 execution_events batches=$committedBatches max=${meterMax(
                "datapipelines.persistence.batch.size",
                "execution_events",
            )}",
        )
        withClue("the writer committed this suite's events") { (committedBatches > 0.0) shouldBe true }
    }

    // ------------------------------------------------------------------ D.2

    @Test
    @Order(2)
    fun `D2 - under load, an MCP key's render is read back by its own next execute - 100 of 100 admitted`() {
        mcpFixture()
        val admitted = AtomicInteger()
        withBackgroundLoad {
            repeat(READS) { n ->
                // templates_update re-arms the render-freshness check; the render's mcp.tool.called
                // row is what the NEXT call's check reads — the key's own write, read back at once.
                val (updated, updateError) =
                    callTool(
                        "templates_update",
                        mapOf(
                            "id" to MCP_TEMPLATE,
                            "display_name" to "266 probe",
                            "description" to "Expects: nothing.",
                            "body" to "SELECT id, label FROM probe WHERE id >= ${n % 2}",
                            "expected_hash" to templateHash,
                        ),
                    )
                withClue("update $n: $updated") { updateError shouldBe false }
                templateHash = updated["body_hash"].asText()
                val (_, renderError) = callTool("templates_render", mapOf("id" to MCP_TEMPLATE, "context" to emptyMap<String, Any>()))
                renderError shouldBe false
                val (run, runError) = callTool("pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))
                if (!runError && run["status"].asText() == "SUCCESS") {
                    admitted.incrementAndGet()
                } else {
                    println("persistence-e2e D2 refused iteration $n: $run")
                }
            }
        }
        admitted.get() shouldBe READS
        withClue("the audit writer committed multi-row batches while the reads ran") {
            (meterMax("datapipelines.persistence.batch.size", "audit") > 1.0) shouldBe true
        }
    }

    @Test
    @Order(3)
    fun `D2 - under load, a key cancels the execution it just launched - its launch row is already committed`() {
        // executions_cancel's same-credential rule reads the key's own mcp.execution.launched row
        // (McpCallAudit.calledByKey): a launch acknowledged before that row committed would read as
        // "a different credential" and be refused.
        val accepted = AtomicInteger()
        withBackgroundLoad {
            repeat(CANCELS) { n ->
                val launcher = Executors.newSingleThreadExecutor()
                val launch =
                    launcher.submit<Pair<JsonNode, Boolean>> {
                        callTool(
                            "pipelines_execute",
                            mapOf(
                                "id" to slowPipelineId,
                                "parameters" to emptyMap<String, Any>(),
                            ),
                        )
                    }
                val running = awaitRunningExecution()
                val (cancel, cancelError) = callTool("executions_cancel", mapOf("execution_id" to running))
                if (!cancelError &&
                    cancel["status"].asText() == "cancellation_requested"
                ) {
                    accepted.incrementAndGet()
                } else {
                    println("persistence-e2e D2 cancel $n refused: $cancel")
                }
                launch.get(SIXTY_SECONDS, TimeUnit.SECONDS)
                launcher.shutdown()
            }
        }
        accepted.get() shouldBe CANCELS
    }

    @Test
    @Order(4)
    fun `D2 - the endpoint twin - a served execution is read back by the same key at once, 100 of 100`() {
        val readable = AtomicInteger()
        withBackgroundLoad {
            repeat(READS) {
                val executionId =
                    given()
                        .port(port)
                        .header(API_KEY_HEADER, endpointKey)
                        .`when`()
                        .get("/api/$NAMESPACE/v1/probe")
                        .then()
                        .statusCode(200)
                        .extract()
                        .header("DP-Execution-Id")
                val status =
                    given()
                        .port(port)
                        .header(API_KEY_HEADER, endpointKey)
                        .`when`()
                        .get("/api/$NAMESPACE/v1/probe/executions/$executionId")
                        .then()
                        .extract()
                        .statusCode()
                if (status == 200) readable.incrementAndGet()
            }
        }
        readable.get() shouldBe READS
    }

    @Test
    @Order(6)
    fun `D2 - with audit_log inserts held, a render returns only once its row commits - its next execute is admitted`() {
        // FORCED, not raced: unheld, the audit writer commits well inside one client round trip, so a
        // row acknowledged BEFORE its commit would still be read back in time and the runs above could
        // not tell the two apart (#266 falsification F24). SHARE ROW EXCLUSIVE blocks every INSERT into
        // audit_log and admits every read — a render that returned before its row committed is refused
        // by the execute's freshness check.
        mcpFixture()
        val (updated, updateError) =
            callTool(
                "templates_update",
                mapOf(
                    "id" to MCP_TEMPLATE,
                    "display_name" to "266 probe",
                    "description" to "Expects: nothing.",
                    "body" to "SELECT id, label FROM probe WHERE id >= 0",
                    "expected_hash" to templateHash,
                ),
            )
        withClue("update: $updated") { updateError shouldBe false }
        templateHash = updated["body_hash"].asText()
        var renderMs = -1L
        val auditBatchesBefore = statistic(metric("datapipelines.persistence.batch.size", "store:audit"), "COUNT")
        holdingLock("audit_log") { release ->
            val releaser =
                Thread {
                    Thread.sleep(HELD_MS)
                    release()
                }.apply { start() }
            val t0 = System.nanoTime()
            val (_, renderError) = callTool("templates_render", mapOf("id" to MCP_TEMPLATE, "context" to emptyMap<String, Any>()))
            renderMs = (System.nanoTime() - t0) / NANOS_PER_MS
            renderError shouldBe false
            val (run, runError) = callTool("pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))
            withClue("the execute after a held render (the render took $renderMs ms): $run") {
                runError shouldBe false
                run["status"].asText() shouldBe "SUCCESS"
            }
            releaser.join()
        }
        withClue("the render waited for its row while the table was held ($HELD_MS ms), measured $renderMs ms") {
            (renderMs >= HELD_MS / 2) shouldBe true
        }
        // #266b: the direct INSERT (the default path) would wait on the held table just the same, so
        // without this the case could pass with the writer switched off and prove nothing about it.
        withClue("non-vacuity: the held rows were committed by the audit WRITER, the path this case proves") {
            (statistic(metric("datapipelines.persistence.batch.size", "store:audit"), "COUNT") > auditBatchesBefore) shouldBe true
        }
    }

    // ------------------------------------------------------------------ D.5

    @Test
    @Order(5)
    fun `D5 - cancelled while its event rows cannot commit, an execution still ends - bounded, ABORTED, not live`() {
        val live = startRestExecution(slowPipelineId)
        val executionId = live.awaitStarted()
        // The row, not just the event: execution_started reaches the stream before its RUNNING row
        // commits (#306 — the send order is outside this lane's fence), and a cancel in that gap is 404.
        awaitCondition { status(executionId) == "RUNNING" }
        var abortedAfterMs = -1L
        holdingLock("execution_events") { release ->
            val t0 = System.nanoTime()
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .`when`()
                .delete("/api/v1/executions/$executionId")
                .then()
                .statusCode(204)
            // The terminal UPDATE (pipeline_executions — not locked) lands once the event writes'
            // bounded wait (2 × record-max-wait-ms per store) has run out.
            awaitCondition(SIXTY_SECONDS) { status(executionId) == "ABORTED" }
            abortedAfterMs = (System.nanoTime() - t0) / NANOS_PER_MS
            withClue("the executor's finally ran: no execution is live (the drain's invariant)") {
                awaitCondition(SIXTY_SECONDS) { meterValue("datapipelines.executions.concurrent") == 0.0 }
            }
            release()
        }
        // Bounded, never hung — but per EMIT: each event the execution emits while the table is held
        // (its progress samples, then execution_aborted) waits 2 × record-max-wait-ms and moves on as
        // `indeterminate`, so the end is delayed by that many bounds (dag-executor §10.1). The
        // single-emit bound is `web`'s PersistenceBatchingIntegrationTest D5.
        withClue("the execution ended — bounded, never hung on the held table — measured $abortedAfterMs ms") {
            abortedAfterMs shouldBeLessThan BOUND_MS
        }
        println("persistence-e2e D5 aborted_after_ms=$abortedAfterMs")
        // Once the lock lifted, the late writes landed: the terminal event is durable and replayable.
        awaitCondition(SIXTY_SECONDS) { eventTypes(executionId).contains("execution_aborted") }
        replayNames(executionId).contains("execution_aborted") shouldBe true
        live.stop()
    }

    // ------------------------------------------------------------------ D.9

    @Test
    @Order(7)
    fun `D9 - no event id is ever stored twice for one execution, over the whole database`() {
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use {
            it.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT COUNT(*) FROM (SELECT execution_id, event_id FROM execution_events GROUP BY 1, 2 HAVING COUNT(*) > 1) d",
                    ).use { rows ->
                        rows.next()
                        rows.getInt(1) shouldBe 0
                    }
            }
        }
    }

    // ------------------------------------------------------------------ the load and the reads

    /** 16 threads running real REST executions back to back and 4 making MCP calls (audit rows) — the load the reads happen under. */
    private fun withBackgroundLoad(block: () -> Unit) {
        val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(LOAD_EXECUTORS + LOAD_MCP)
        repeat(LOAD_EXECUTORS) {
            pool.execute { while (!stop.get()) runCatching { executeToEnd(pipelineId) }.onFailure { println("persistence-e2e load: $it") } }
        }
        repeat(LOAD_MCP) {
            pool.execute {
                while (!stop.get()) {
                    runCatching {
                        callTool(
                            "pipelines_list",
                            mapOf("prefix" to ""),
                        )
                    }.onFailure { println("persistence-e2e load: $it") }
                }
            }
        }
        try {
            block()
        } finally {
            stop.set(true)
            pool.shutdown()
            pool.awaitTermination(SIXTY_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** One REST execution read to the end of its live stream: its id and the `id:` sequence the stream carried. */
    private fun executeToEnd(id: String): Pair<String, List<Int>> {
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$id/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() == 200) { "execute answered ${response.statusCode()}: ${response.body().take(300)}" }
        val lines = response.body().lines()
        val executionId =
            E2eSse
                .parseEvents(response.body(), mapper)
                .first { it.first == "execution_started" }
                .second["execution_id"]
                .asText()
        return executionId to lines.filter { it.startsWith("id:") }.map { it.removePrefix("id:").trim().toInt() }
    }

    /** The durable record's ids, through `GET /executions/{id}/events?format=json` (rest-api §10.3A). */
    private fun durableIds(executionId: String): List<Int> =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .queryParam("format", "json")
            .`when`()
            .get("/api/v1/executions/$executionId/events")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.events.event_id", Int::class.javaObjectType)

    /** The replay route's stream (rest-api §10.3), read to its end. */
    private fun replay(executionId: String): String =
        http
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/executions/$executionId/events"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    private fun replayIds(executionId: String): List<Int> =
        replay(executionId)
            .lines()
            .filter {
                it.startsWith("id:")
            }.map { it.removePrefix("id:").trim().toInt() }

    private fun replayNames(executionId: String): List<String> = E2eSse.parseEvents(replay(executionId), mapper).map { it.first }

    private fun status(executionId: String): String? =
        withConnection { connection ->
            connection.prepareStatement("SELECT status FROM pipeline_executions WHERE execution_id = ?::uuid").use { ps ->
                ps.setString(1, executionId)
                ps.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    private fun eventTypes(executionId: String): List<String> =
        withConnection { connection ->
            val sql = "SELECT event_type FROM execution_events WHERE execution_id = ?::uuid ORDER BY event_id"
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, executionId)
                ps.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use(block)

    // ------------------------------------------------------------------ meters, through the actuator

    /**
     * `/actuator/metrics/{name}` on the management port; null when THAT meter does not exist yet. The
     * endpoint itself must answer — a helper that read "unreachable" as zero would pass every
     * assertion made through it (the vacuous-green trap), so that fails here instead.
     */
    private fun metric(
        name: String,
        tag: String? = null,
    ): JsonNode? {
        // The management port sits behind the application's security chain: an authenticated read.
        fun get(url: String) =
            http.send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val root = get("http://localhost:$managementPort/actuator/metrics")
        check(root.statusCode() == 200) { "the metrics endpoint answered ${root.statusCode()} — the suite cannot read a meter" }
        val response = get("http://localhost:$managementPort/actuator/metrics/$name" + (tag?.let { "?tag=$it" } ?: ""))
        return if (response.statusCode() == 200) mapper.readTree(response.body()) else null
    }

    private fun statistic(
        node: JsonNode?,
        statistic: String,
    ): Double =
        node
            ?.get("measurements")
            ?.firstOrNull { it["statistic"].asText() == statistic }
            ?.get("value")
            ?.asDouble() ?: 0.0

    private fun meterSum(name: String): Double = statistic(metric(name), "COUNT")

    private fun meterMax(
        name: String,
        store: String,
    ): Double = statistic(metric(name, "store:$store"), "MAX")

    /** A gauge that must exist — its absence fails rather than reading as zero. */
    private fun meterValue(name: String): Double = statistic(checkNotNull(metric(name)) { "gauge $name is not registered" }, "VALUE")

    // ------------------------------------------------------------------ locks and waits

    /**
     * Holds `LOCK TABLE [table]` on a side connection: every INSERT into it waits (a store that has
     * stopped answering, for that table only — `pipeline_executions` stays writable). [block] gets
     * the release; the finally releases anyway.
     */
    private fun holdingLock(
        table: String,
        block: (release: () -> Unit) -> Unit,
    ) {
        val side: Connection =
            DriverManager.getConnection(
                SharedE2e.postgres.jdbcUrl,
                SharedE2e.postgres.username,
                SharedE2e.postgres.password,
            )
        side.autoCommit = false
        side.createStatement().execute("LOCK TABLE $table IN SHARE ROW EXCLUSIVE MODE")
        val released = AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                side.commit()
                side.close()
            }
        }
        try {
            block(release)
        } finally {
            release()
        }
    }

    private fun awaitCondition(
        seconds: Long = TEN_SECONDS,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $seconds s" }
            Thread.sleep(POLL_MS)
        }
    }

    // ------------------------------------------------------------------ MCP

    private var mcpPipelineId = ""
    private var templateHash = ""
    private val rpcId = AtomicInteger()

    private fun mcpFixture() {
        if (mcpPipelineId.isNotEmpty()) return
        val (created, createError) =
            callTool(
                "templates_create",
                mapOf(
                    "id" to MCP_TEMPLATE,
                    "dialect" to "H2",
                    "display_name" to "266 probe",
                    "description" to "Expects: nothing.",
                    "body" to "SELECT id, label FROM probe",
                ),
            )
        withClue("templates_create: $created") { createError shouldBe false }
        templateHash = created["body_hash"].asText()
        callTool("datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "PROBE")).second shouldBe false
        val (pipeline, pipelineError) =
            callTool(
                "pipelines_create",
                mapOf(
                    "name" to MCP_PIPELINE,
                    "display_name" to "266 MCP probe",
                    "description" to "Read-your-own-write under load.",
                    "parameters" to emptyMap<String, Any>(),
                    "nodes" to
                        listOf(
                            mapOf(
                                "id" to "rows",
                                "type" to "DQL",
                                "source" to DATASOURCE,
                                "description" to "The rows",
                                "template" to mapOf("id" to MCP_TEMPLATE, "version" to 1),
                                "depends_on" to emptyList<String>(),
                            ),
                        ),
                ),
            )
        withClue("pipelines_create: $pipeline") { pipelineError shouldBe false }
        mcpPipelineId = pipeline["id"].asText()
    }

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", MCP_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to rpcId.incrementAndGet(),
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val result = mapper.readTree(response.body())["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    /** The key's newest RUNNING execution, as its own `executions_list` shows it. */
    private fun awaitRunningExecution(): String {
        var found: String? = null
        awaitCondition(SIXTY_SECONDS) {
            // The tool answers a bare array of the key's visible runs (own, plus every scheduled run).
            val (list, _) = callTool("executions_list", mapOf("status" to "RUNNING", "pipeline_id" to slowPipelineId, "limit" to 1))
            found = list.firstOrNull()?.path("execution_id")?.asText()
            found != null
        }
        return found!!
    }

    // ------------------------------------------------------------------ REST

    /** A REST execution read as SSE on its own thread — the execution id from `execution_started`. */
    private inner class LiveExecution(
        private val thread: Thread,
        private val started: java.util.concurrent.CompletableFuture<String>,
    ) {
        fun awaitStarted(): String = started.get(SIXTY_SECONDS, TimeUnit.SECONDS)

        fun stop() = thread.interrupt()
    }

    private fun startRestExecution(id: String): LiveExecution {
        val started = java.util.concurrent.CompletableFuture<String>()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$id/execute"))
                .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val thread =
            Thread({
                runCatching {
                    val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
                    var event = ""
                    response.body().forEach { line ->
                        when {
                            line.startsWith("event:") -> {
                                event = line.removePrefix("event:").trim()
                            }

                            line.startsWith("data:") && event == "execution_started" -> {
                                started.complete(mapper.readTree(line.removePrefix("data:").trim())["execution_id"].asText())
                            }
                        }
                    }
                }.onFailure { started.completeExceptionally(it) }
            }, "persistence-e2e-live").apply {
                isDaemon = true
                start()
            }
        return LiveExecution(thread, started)
    }

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """{"name": "$DATASOURCE", "display_name": "266 probe", "dialect": "H2", """ +
                    """"jdbc_url": "$H2_URL", "username": "sa", "password": "sa"}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** A template created and RELEASED — a released pipeline pins released templates only. */
    private fun restTemplate(
        id: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """{"id": "$id", "dialect": "H2", "display_name": "$id", "description": "266 fixture.", "imports": [], "body": "$body"}""",
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        val hash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .queryParam("name", id)
                .`when`()
                .get("/api/v1/templates")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    /** A pipeline created and RELEASED; its id. */
    private fun restPipeline(
        name: String,
        template: String,
    ): String {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    """
                    {"schema_version": 1, "name": "$name", "display_name": "$name", "description": "266 fixture.", "parameters": {},
                     "nodes": [{"id": "rows", "description": "rows", "type": "DQL", "source": "$DATASOURCE",
                                "template": {"id": "$template", "version": 1}, "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        val id = created.jsonPath().getString("data.id")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
        return id
    }

    private fun publishEndpoint() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"path": "/$NAMESPACE/v1/probe", "pipeline": "$PIPELINE_READ", "timeout_seconds": 60}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
    }

    private fun mintEndpointKey(): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"name": "266-serving-$RUN_ID", "kind": "endpoint", "bindings": ["/$NAMESPACE"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.key")

    private fun seedRows() {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$ADMIN_UUID', 'e2e-266-$RUN_ID@datapipelines.test', 'E2E 266', 'test', 'e2e-266-$RUN_ID', TRUE, TRUE)",
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_UUID', 'workspace_admin')",
                )
                // Keys v2 (A13): the MCP key acts as its own `service` identity, an AUTHOR — its reads
                // of its own runs are its own, not a workspace admin's read-all.
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$KEY_IDENTITY', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', " +
                        "'${MCP_KEY.id}', TRUE, FALSE, 'service')",
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'author')",
                ).use { ps ->
                    ps.setString(1, MCP_KEY.id)
                    ps.setObject(2, KEY_IDENTITY)
                    ps.setObject(3, ADMIN_UUID)
                    ps.setString(4, MCP_KEY.name)
                    ps.setString(5, MCP_KEY.hash)
                    ps.setObject(6, DEFAULT_WORKSPACE)
                    ps.executeUpdate()
                }
        }
    }

    companion object {
        private val RUN_ID = Integer.toHexString(SecureRandom().nextInt(0x10000))
        private val DATASOURCE = "h2-266-$RUN_ID"
        private val H2_URL = "jdbc:h2:mem:p266_$RUN_ID;DB_CLOSE_DELAY=-1"
        private val TEMPLATE_READ = "test/p266_read_$RUN_ID.sql"
        private val TEMPLATE_SLOW = "test/p266_slow_$RUN_ID.sql"
        private val PIPELINE_READ = "test/p266_read_$RUN_ID"
        private val PIPELINE_SLOW = "test/p266_slow_$RUN_ID"
        private val MCP_TEMPLATE = "test/p266_mcp_$RUN_ID.sql"
        private val MCP_PIPELINE = "test/p266_mcp_$RUN_ID"
        private val NAMESPACE = "p266$RUN_ID"
        private const val API_KEY_HEADER = "DP-API-Key"

        private const val CONCURRENT = 32
        private const val LOAD_EXECUTORS = 16
        private const val LOAD_MCP = 4
        private const val READS = 100
        private const val CANCELS = 10
        private const val TEN_SECONDS = 10L
        private const val SIXTY_SECONDS = 60L
        private const val POLL_MS = 20L
        private const val NANOS_PER_MS = 1_000_000L

        /** How long the forced D.2 case holds audit_log — under record-max-wait-ms (2 s), so the render's own batch commits. */
        private const val HELD_MS = 1_000L

        /** Several emits' worth of 2 × record-max-wait-ms (2 s) — bounded, not the single-emit bound. */
        private const val BOUND_MS = 45_000L

        private val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        private val ADMIN_UUID: UUID = UUID.randomUUID()
        private val KEY_IDENTITY: UUID = UUID.randomUUID()
        private val MCP_KEY = E2eAuth.generateKey("e2e-266-mcp-key-$RUN_ID")
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_UUID.toString(), "e2e-266-$RUN_ID@datapipelines.test")
        private val random = SecureRandom()
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            // #266b: the audit writer ships OFF (configuration §3.32); D.2's read-your-own-write cases
            // prove the BATCHED audit path, so this suite switches it on — explicitly, never by default.
            registry.add("datapipelines.persistence.audit.enabled") { "true" }
            // This suite reads the persistence meters; health stays exposed as in production.
            registry.add("management.endpoints.web.exposure.include") { "health,metrics" }
            // The load is the point: the per-user budgets would refuse it before persistence saw it.
            registry.add("datapipelines.rate-limit.requests-per-second") { "100000" }
            registry.add("datapipelines.rate-limit.requests-per-minute") { "10000000" }
            registry.add("datapipelines.executor.max-concurrent-executions-per-user") { "100" }
            registry.add("datapipelines.endpoints.key-request-budget.max-requests") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add(
                "datapipelines.db.encryption-key",
            ) { Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) }) }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
