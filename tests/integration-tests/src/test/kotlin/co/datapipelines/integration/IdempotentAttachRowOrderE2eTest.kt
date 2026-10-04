package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.mockito.Mockito.mockingDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #324 — the idempotent retry waits for the original's row like the first frame does; a
 * refused original releases its key; a crashed original still answers an id-free 410 (#403).
 *
 * The reservation (Redis, `SET NX`) precedes everything, but the original's LOG ENTRY is appended
 * only after the RUNNING row commits and the live send (#306's order). A retry landing inside that
 * start window used to be answered at once with `410 result.expired` (`reason =
 * event_log_expired`, the reservation's id in `details`) — while the original was milliseconds
 * from its insert, and `GET /executions/{id}` on that id answered 404. The fix: with no log, the
 * attach consults the ROW — absent or still RUNNING means FOLLOW (the follow's own ~15 s give-up
 * patience, above the 10 s lifecycle write bound, is the wait, on the streamer's scheduler); only
 * a TERMINAL row keeps the 410. And a follow that gives up having served nothing — an original
 * that never started, so the id does not resolve — completes with an ID-FREE 410
 * (`reason = original_not_started`).
 *
 * Proven on the wire, in the [ExecutionStartedRowOrderE2eTest] shape:
 *
 * 1. the SHARE MODE lock holds the RUNNING insert while admitting the row SELECTs; the original
 *    (with `Idempotency-Key`) and the retry (same key, same body) are sent on reader threads.
 *    Within [EARLY_WINDOW_MS] NEITHER response may arrive — pre-fix the retry arrived at once as
 *    the premature 410 (that red's body is recorded in the lane's evidence);
 * 2. the lock lifts: both responses are `200 text/event-stream`, both first frames are
 *    `event:execution_started`, and both carry the SAME `execution_id`; both streams drain to a
 *    terminal event;
 * 3. exactly ONE `pipeline_executions` row exists for the pipeline and `GET /executions/{id}` on
 *    the shared id is 200; the same key with a different body is still
 *    `409 idempotency.key_reused_for_different_request`.
 *
 * #403's second method proves a slot refusal releases the reservation before the 429 reaches
 * the client, another same-key refusal is immediate, and a retry after the slot frees starts a
 * new execution. A synthetic crash reservation preserves #404's exact id-free 410. The MCP
 * wire uses the existing JSON-RPC harness with its own key identity and repeats the refusal/retry.
 * A spy observes only the reserved id that cleanup removes before the response; Redis absence,
 * frames, statuses and execution reads remain independent wire/storage assertions.
 *
 * The lock-hold window stays well under the shipped lifecycle bound
 * (`datapipelines.executor.lifecycle-write-timeout-seconds`, 10 s) — past it the insert would be
 * given up as unconfirmed, a different (documented) degraded mode, not this test's subject. The
 * retry is sent only after the original's reservation is witnessed in Redis
 * (`idem:{user}:*` via `redis-cli --scan`): the `SET NX` runs on the original's request thread,
 * never behind the lock, so the retry cannot win the key and swap the labels.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // one slot/start-window fixture; splitting the REST/MCP witness would boot the app twice
class IdempotentAttachRowOrderE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @MockitoSpyBean(name = "idempotencyStore")
    private lateinit var idempotencyStore: Any

    @Test
    @Order(1)
    @Suppress("LongMethod", "SwallowedException") // the wire scenario IS the assertion, in order; the timeout IS the expected silence
    fun `a retry inside the start window waits for the original's row - both first frames carry one id`() {
        ensureAuthSeeded()
        registerDatasource()
        seedSourceUsers()
        createTemplate(TEMPLATE_ID, FAST_QUERY)
        val pipelineId = createPipeline(PIPELINE_NAME, TEMPLATE_ID, declareParameter = true)

        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE pipeline_executions IN SHARE MODE") }
                val readerExecutor = Executors.newFixedThreadPool(2) { r -> Thread(r, "e2e-324-reader").apply { isDaemon = true } }
                try {
                    val key = "e2e-324-${UUID.randomUUID()}"
                    val reservationKeysBefore = reservationKeys()
                    // One client per request — the fleet's rule (the cancel-load suite builds one
                    // per worker): a shared client multiplexes or pools across a live SSE stream,
                    // and a request sharing that path does not reliably carry its headers.
                    val originalFuture =
                        readerExecutor.submit<HttpResponse<InputStream>> {
                            HttpClient
                                .newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .build()
                                .send(executeRequest(pipelineId, key), HttpResponse.BodyHandlers.ofInputStream())
                        }
                    // The settle IS the reservation, witnessed: the original's `SET NX` runs on its
                    // request thread, not behind the lock, so the retry below cannot race it. The
                    // key names THIS run's user, so no other suite's reservation can stand in.
                    awaitReservation(reservationKeysBefore)
                    val retryFuture =
                        readerExecutor.submit<HttpResponse<InputStream>> {
                            HttpClient
                                .newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .build()
                                .send(executeRequest(pipelineId, key), HttpResponse.BodyHandlers.ofInputStream())
                        }

                    // (1) The window runs against BOTH requests: an SSE response flushes its
                    // headers with the first frame, and the retry's attach (fixed) returns a
                    // follow whose first send waits for the log. Pre-fix the retry arrived here
                    // at once as `410 result.expired` — that is the red; its body is printed in
                    // the clue.
                    val windowEnd = System.currentTimeMillis() + EARLY_WINDOW_MS
                    var earlyRetry: HttpResponse<InputStream>? = null
                    try {
                        earlyRetry = retryFuture.get(EARLY_WINDOW_MS, TimeUnit.MILLISECONDS)
                    } catch (e: TimeoutException) {
                        // Expected: the retry waits for the original's row.
                    }
                    val remaining = (windowEnd - System.currentTimeMillis()).coerceIn(1L, EARLY_WINDOW_MS)
                    var earlyOriginal: HttpResponse<InputStream>? = null
                    try {
                        earlyOriginal = originalFuture.get(remaining, TimeUnit.MILLISECONDS)
                    } catch (e: TimeoutException) {
                        // Expected: the original's first frame waits for the row to commit.
                    }
                    // An error body is finite and read ONLY here; a streaming 200 is not read —
                    // an eager read (a withClue string included) consumes the SSE stream whole.
                    val earlyRetryBody =
                        earlyRetry?.let {
                            if (it.statusCode() != 200) it.body().readBytes().toString(Charsets.UTF_8) else "(streaming 200)"
                        }
                    withClue(
                        "a response arrived while the RUNNING row could not commit — pre-fix the retry " +
                            "is answered at once with 410 result.expired: " +
                            "retry=${earlyRetry?.statusCode()} body=$earlyRetryBody; " +
                            "original=${earlyOriginal?.statusCode()}",
                    ) {
                        earlyOriginal shouldBe null
                        earlyRetry shouldBe null
                    }

                    // (2) The row commits; both frames land, carrying ONE id.
                    lock.commit()
                    val originalResponse = originalFuture.get(30, TimeUnit.SECONDS)
                    val retryResponse = retryFuture.get(30, TimeUnit.SECONDS)
                    originalResponse.statusCode() shouldBe 200
                    retryResponse.statusCode() shouldBe 200
                    originalResponse.headers().firstValue("Content-Type").orElse("") shouldContain "text/event-stream"
                    retryResponse.headers().firstValue("Content-Type").orElse("") shouldContain "text/event-stream"
                    val originalReader = BufferedReader(InputStreamReader(originalResponse.body()))
                    val retryReader = BufferedReader(InputStreamReader(retryResponse.body()))
                    val originalFirstLine = originalReader.readLine()
                    val retryFirstLine = retryReader.readLine()
                    withClue("the original's first frame after the row could commit") {
                        originalFirstLine shouldBe "event:execution_started"
                    }
                    withClue("the retry's first frame after the row could commit") {
                        retryFirstLine shouldBe "event:execution_started"
                    }
                    val originalId = readDataLine(originalReader)["execution_id"].asText()
                    val retryId = readDataLine(retryReader)["execution_id"].asText()
                    withClue(
                        "the retry's execution_id must EQUAL the original's (one execution, not two): " +
                            "original=$originalId retry=$retryId",
                    ) {
                        retryId shouldBe originalId
                    }

                    // (3) Both streams reach a terminal event.
                    drainToTerminal(originalReader, "original")
                    drainToTerminal(retryReader, "retry")

                    // (4) One row for the pipeline, and the id resolves.
                    rowCount(pipelineId) shouldBe 1L
                    val metadata =
                        given()
                            .port(port)
                            .asSession(ADMIN_SESSION)
                            .`when`()
                            .get("/api/v1/executions/$originalId")
                    withClue("GET /executions/$originalId answered ${metadata.statusCode()}") {
                        metadata.statusCode() shouldBe 200
                    }

                    // (5) Control — same key, different body is still the 409.
                    val control =
                        given()
                            .port(port)
                            .asSession(ADMIN_SESSION)
                            .header("Idempotency-Key", key)
                            .contentType(ContentType.JSON)
                            .body("""{"parameters": {"p": "other"}}""")
                            .`when`()
                            .post("/api/v1/pipelines/$pipelineId/execute")
                    withClue("same key, different body: ${control.body().asString()}") {
                        control.statusCode() shouldBe 409
                        control.body().jsonPath().getString("error.code") shouldBe
                            "idempotency.key_reused_for_different_request"
                    }
                } finally {
                    readerExecutor.shutdownNow()
                    // A red above must not leave the execution's insert (or the shared table) blocked.
                    runCatching { lock.rollback() }
                }
            }
        }
    }

    /**
     * #403 — a refused original releases immediately on REST and MCP; a same-key retry starts
     * once the slot frees. A crash without cleanup still follows to #404's exact id-free 410.
     */
    @Test
    @Order(2)
    @Suppress("LongMethod", "CyclomaticComplexMethod") // one wire scenario; waits and crash/MCP controls are assertions
    fun `a never-started refusal releases its key for retries while a crashed original still answers 410`() {
        ensureAuthSeeded()
        registerDatasource()
        seedSourceUsers()
        createTemplate(SLOT_TEMPLATE_ID, SLOT_QUERY)
        val pipelineId = createPipeline(SLOT_PIPELINE_NAME, SLOT_TEMPLATE_ID, declareParameter = false)

        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            fun redisKey(
                user: String,
                key: String,
            ): String {
                val hash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(key.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                return "idem:$user:$hash"
            }

            fun awaitAbsent(key: String) {
                val deadline = System.currentTimeMillis() + RESERVATION_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (redis.execInContainer("redis-cli", "EXISTS", key).stdout.trim() == "0") return
                    Thread.sleep(100)
                }
                throw AssertionError("the refused reservation remains in Redis: $key")
            }

            fun reservedId(key: String): String =
                mockingDetails(idempotencyStore)
                    .invocations
                    .last { it.method.name == "reserve" && it.arguments[1] == key }
                    .arguments[3]
                    .toString()

            fun metadata(id: String) =
                given()
                    .port(port)
                    .asSession(ADMIN_SESSION)
                    .`when`()
                    .get("/api/v1/executions/$id")
                    .statusCode()

            fun post(key: String) =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(executeRequest(pipelineId, key), HttpResponse.BodyHandlers.ofString())

            fun assertRefused(response: HttpResponse<String>) {
                withClue("the slot-refused POST must answer #404's exact 429: ${response.body()}") {
                    response.statusCode() shouldBe 429
                    response.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
                    mapper
                        .readTree(response.body())
                        .path("error")
                        .path("code")
                        .asText() shouldBe
                        "pipeline.execution.concurrency_limit"
                }
            }

            val slowKey = "e2e-403-slow-${UUID.randomUUID()}"
            val slowResponse =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(executeRequest(pipelineId, slowKey), HttpResponse.BodyHandlers.ofInputStream())
            slowResponse.statusCode() shouldBe 200
            val slowReader = BufferedReader(InputStreamReader(slowResponse.body()))
            slowReader.readLine() shouldBe "event:execution_started"
            readDataLine(slowReader)
            val slowRecord = reservationRecord(redisKey(ADMIN_USER_ID, slowKey))

            // The reservation is gone by the time the first 429 is observed.
            val doomedKey = "e2e-403-doomed-${UUID.randomUUID()}"
            assertRefused(post(doomedKey))
            val doomedId = reservedId(doomedKey)
            awaitAbsent(redisKey(ADMIN_USER_ID, doomedKey))
            metadata(doomedId) shouldBe 404

            // While the slot is held, the SAME key tries executing again; it never follows.
            val retryStart = System.currentTimeMillis()
            assertRefused(post(doomedKey))
            val refusedRetryMs = System.currentTimeMillis() - retryStart
            withClue("same-key refusal took ${refusedRetryMs}ms, the follow would wait at least $FOLLOW_GIVE_UP_FLOOR_MS") {
                (refusedRetryMs < FOLLOW_GIVE_UP_FLOOR_MS) shouldBe true
            }
            awaitAbsent(redisKey(ADMIN_USER_ID, doomedKey))

            drainToTerminal(slowReader, "the slot-holder")
            val restarted =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(executeRequest(pipelineId, doomedKey), HttpResponse.BodyHandlers.ofInputStream())
            restarted.statusCode() shouldBe 200
            val restartedReader = BufferedReader(InputStreamReader(restarted.body()))
            restartedReader.readLine() shouldBe "event:execution_started"
            val newId = readDataLine(restartedReader).path("execution_id").asText()
            newId shouldNotBe doomedId
            reservationRecord(redisKey(ADMIN_USER_ID, doomedKey)).path("executionId").asText() shouldBe newId
            metadata(newId) shouldBe 200
            drainToTerminal(restartedReader, "the same-key restarted execution")

            // Crash witness: matching requestHash from the SAME request, but no process to release.
            val crashKey = "e2e-403-crash-${UUID.randomUUID()}"
            val crashRecord = slowRecord.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            crashRecord.put("executionId", UUID.randomUUID().toString())
            redis
                .execInContainer(
                    "redis-cli",
                    "SET",
                    redisKey(ADMIN_USER_ID, crashKey),
                    mapper.writeValueAsString(crashRecord),
                    "EX",
                    "300",
                ).stdout
                .trim() shouldBe "OK"
            val crashStart = System.currentTimeMillis()
            val crash = post(crashKey)
            (System.currentTimeMillis() - crashStart >= FOLLOW_GIVE_UP_FLOOR_MS) shouldBe true
            withClue("the crash witness must preserve #404's exact id-free 410: ${crash.body()}") {
                crash.statusCode() shouldBe 410
                crash.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
                val error = mapper.readTree(crash.body()).path("error")
                error.path("code").asText() shouldBe "result.expired"
                error.path("details").path("reason").asText() shouldBe "original_not_started"
                crash.body() shouldNotContain "\"execution_id\""
                crash.body() shouldNotContain "event_log_expired"
            }

            // Existing /mcp JSON-RPC harness: its key acts as its own service identity.
            val issued =
                given()
                    .port(port)
                    .asSession(ADMIN_SESSION)
                    .contentType(ContentType.JSON)
                    .body("""{"name":"e2e-403-mcp","kind":"mcp","role":"author"}""")
                    .`when`()
                    .post("/api/v1/auth/api-keys")
                    .then()
                    .statusCode(201)
                    .extract()
                    .jsonPath()
            val credential = issued.getString("data.key")
            val keyId = issued.getString("data.id")
            val mcpUser =
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    connection.prepareStatement("SELECT user_id FROM api_keys WHERE id = ?").use { statement ->
                        statement.setString(1, keyId)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1)
                        }
                    }
                }

            fun tool(
                name: String,
                arguments: Map<String, Any>,
                key: String? = null,
            ): JsonNode {
                val builder =
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/mcp"))
                        .header("DP-API-Key", credential)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                if (key != null) builder.header("Idempotency-Key", key)
                val response =
                    HttpClient.newHttpClient().send(
                        builder
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(
                                        mapOf(
                                            "jsonrpc" to "2.0",
                                            "id" to 1,
                                            "method" to "tools/call",
                                            "params" to
                                                mapOf("name" to name, "arguments" to arguments),
                                        ),
                                    ),
                                ),
                            ).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                response.statusCode() shouldBe 200
                val result = mapper.readTree(response.body()).path("result")
                return mapper.readTree(result.path("content")[0].path("text").asText())
            }
            tool("templates_render", mapOf("id" to SLOT_TEMPLATE_ID, "context" to emptyMap<String, Any>())).has("error") shouldBe false
            val args = mapOf("id" to pipelineId, "parameters" to emptyMap<String, Any>())
            val mcpSlowKey = "e2e-403-mcp-slow-${UUID.randomUUID()}"
            val readers = Executors.newSingleThreadExecutor { r -> Thread(r, "e2e-403-mcp-holder").apply { isDaemon = true } }
            try {
                val holder = readers.submit<JsonNode> { tool("pipelines_execute", args, mcpSlowKey) }
                val deadline = System.currentTimeMillis() + RESERVATION_WAIT_MS
                var startedId = ""
                while (System.currentTimeMillis() < deadline) {
                    val stored = redis.execInContainer("redis-cli", "GET", redisKey(mcpUser, mcpSlowKey)).stdout.trim()
                    if (stored.isNotBlank()) {
                        startedId = mapper.readTree(stored).path("executionId").asText()
                        if (metadata(startedId) == 200) break
                    }
                    Thread.sleep(100)
                }
                startedId shouldNotBe ""
                metadata(startedId) shouldBe 200
                val mcpKey = "e2e-403-mcp-doomed-${UUID.randomUUID()}"
                val refused = tool("pipelines_execute", args, mcpKey)
                refused.path("error").path("code").asText() shouldBe "pipeline.execution.concurrency_limit"
                val mcpDoomedId = reservedId(mcpKey)
                awaitAbsent(redisKey(mcpUser, mcpKey))
                holder.get(60, TimeUnit.SECONDS).path("status").asText() shouldBe "SUCCESS"
                val mcpRetry = tool("pipelines_execute", args, mcpKey)
                mcpRetry.path("status").asText() shouldBe "SUCCESS"
                val mcpNewId = mcpRetry.path("execution_id").asText()
                mcpNewId shouldNotBe mcpDoomedId
                reservationRecord(redisKey(mcpUser, mcpKey)).path("executionId").asText() shouldBe mcpNewId
                metadata(mcpNewId) shouldBe 200
            } finally {
                readers.shutdownNow()
            }
        }
    }

    /**
     * #404 A.3 — the execute route's own fail-before-start refusal with NO `Idempotency-Key`: the
     * executor refuses the only per-user slot before any event, `failBeforeStart` completes the
     * stream with the error, and the wire answers the `429 pipeline.execution.concurrency_limit`
     * envelope as JSON under an SSE-only Accept — not the `401 auth.api_key.missing` the async
     * re-dispatch used to write. No reservation, no follow: the plain refusal, nothing else.
     *
     * A SECOND member holds the slot here, not the admin: the limit is per user, and the admin's
     * slot may still be held by the previous method's 30 s original, so this method depends on no
     * other's leftovers. Its own slot-holder is cancelled at the end and drained to its terminal
     * event, so it leaves nothing running.
     */
    @Test
    @Order(3)
    fun `an unkeyed execute refused the only slot before any event answers the 429 envelope`() {
        ensureAuthSeeded()
        ensureAuthSeeded(SECOND_USER_ID, SECOND_EMAIL)
        registerDatasource()
        seedSourceUsers()
        createTemplate(NO_KEY_TEMPLATE_ID, SLOT_QUERY)
        val pipelineId = createPipeline(NO_KEY_PIPELINE_NAME, NO_KEY_TEMPLATE_ID, declareParameter = false)

        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            // 1. The second member's slow original takes their only slot; its first frame proves it.
            val holderFuture =
                Executors
                    .newSingleThreadExecutor { r -> Thread(r, "e2e-404-holder-reader").apply { isDaemon = true } }
                    .submit<HttpResponse<InputStream>> {
                        HttpClient
                            .newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .build()
                            .send(executeRequest(pipelineId, null, SECOND_SESSION), HttpResponse.BodyHandlers.ofInputStream())
                    }
            val holder = holderFuture.get(30, TimeUnit.SECONDS)
            if (holder.statusCode() != 200) {
                throw AssertionError(
                    "the slot-holder's POST answered ${holder.statusCode()}: " + holder.body().readBytes().toString(Charsets.UTF_8),
                )
            }
            val holderReader = BufferedReader(InputStreamReader(holder.body()))
            val holderFirstLine = holderReader.readLine()
            withClue("the slot-holder's first frame was: $holderFirstLine") {
                holderFirstLine shouldBe "event:execution_started"
            }
            val holderExecutionId = readDataLine(holderReader).path("execution_id").asText()

            // 2. The unkeyed second execute: refused the slot inside the executor, before any event.
            val refused =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(executeRequest(pipelineId, null, SECOND_SESSION), HttpResponse.BodyHandlers.ofString())
            withClue("the unkeyed slot refusal must answer the 429 envelope (#404): ${refused.body()}") {
                refused.statusCode() shouldBe 429
                refused.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
                val error = mapper.readTree(refused.body()).path("error")
                error.path("code").asText() shouldBe "pipeline.execution.concurrency_limit"
                error.path("details").path("scope").asText() shouldBe "per_user"
                error.path("details").path("limit").asInt() shouldBe 1
            }
            withClue("the refused execute must not have started a second execution") {
                rowCount(pipelineId) shouldBe 1L
            }

            // 3. Release the slot: cancel the holder and drain it to its terminal event.
            given()
                .port(port)
                .asSession(SECOND_SESSION)
                .`when`()
                .delete("/api/v1/executions/$holderExecutionId")
                .then()
                .statusCode(204)
            drainToTerminal(holderReader, "the cancelled slot-holder")
        }
    }

    /** The stored reservation record at [key]: `{executionId, requestHash, expiresAt}`. */
    private fun reservationRecord(key: String): JsonNode {
        val stored = redis.execInContainer("redis-cli", "GET", key).stdout.trim()
        return mapper.readTree(stored)
    }

    /** The `data:` line following an already-read `event:` line. */
    private fun readDataLine(reader: BufferedReader): JsonNode {
        for (line in reader.lines()) {
            if (line.startsWith("data:")) return mapper.readTree(line.removePrefix("data:").trim())
        }
        throw AssertionError("the stream ended before the event's data line")
    }

    /** Reads to EOF; fails unless a terminal event was carried. Returns the event names, in order. */
    private fun drainToTerminal(
        reader: BufferedReader,
        label: String,
    ): List<String> {
        val seen = mutableListOf<String>()
        for (line in reader.lines()) {
            if (line.startsWith("event:")) seen += line.removePrefix("event:").trim()
        }
        withClue("$label stream reached no terminal event: $seen") {
            seen.any { it in TERMINAL_EVENTS } shouldBe true
        }
        return seen
    }

    private fun rowCount(pipelineId: String): Long =
        DriverManager
            .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { connection ->
                connection
                    .prepareStatement("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = ?")
                    .use { statement ->
                        statement.setObject(1, UUID.fromString(pipelineId))
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                    }
            }

    /** This run's reservation keys — `idem:{user}:*`, this class's user only. */
    private fun reservationKeys(): Set<String> =
        redis
            .execInContainer("redis-cli", "--scan", "--pattern", "idem:$ADMIN_USER_ID:*")
            .stdout
            .trim()
            .lines()
            .filter { it.isNotBlank() }
            .toSet()

    /** Waits until a NEW reservation key appears (the `SET NX` has run); returns it. */
    private fun awaitReservation(knownKeys: Set<String>): String {
        val deadline = System.currentTimeMillis() + RESERVATION_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            reservationKeys().firstOrNull { it !in knownKeys }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("the idempotency reservation never appeared in Redis under idem:$ADMIN_USER_ID:*")
    }

    // ------------------------------------------------------------ helpers

    private fun executeRequest(
        pipelineId: String,
        idempotencyKey: String?,
        session: String = ADMIN_SESSION,
    ): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header("Cookie", E2eSession.cookieHeader(session))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        return builder
            .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
            .build()
    }

    /** Seeds [userId] as an active admin and a workspace admin of the default workspace (idempotent). */
    private fun ensureAuthSeeded(
        userId: String = ADMIN_USER_ID,
        email: String = ADMIN_EMAIL,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$userId', '$email', '${email.substringBefore('@')}', 'test', 'sub-$userId', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                        ('$DEFAULT_WORKSPACE', '$userId', 'workspace_admin')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }
        }
    }

    private fun registerDatasource() {
        val existing =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/datasources/pg-local-324")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "pg-local-324", "display_name": "Source Postgres", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate(
        id: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "$id", "dialect": "POSTGRES", "display_name": "Order 324 Query",
                 "description": "Auto-generated template for E2E test", "imports": [],
                 "body": "$body"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(
        name: String,
        templateId: String,
        declareParameter: Boolean,
    ): String {
        val bodyJson = pipelineBodyJson(name, templateId, declareParameter)
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(bodyJson)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != 201) {
            throw AssertionError("Pipeline creation failed (status=${response.statusCode()}): body=$bodyJson")
        }
        return response.jsonPath().getString("data.id")
    }

    private fun pipelineBodyJson(
        name: String,
        templateId: String,
        declareParameter: Boolean,
    ): String {
        val parameters =
            if (declareParameter) {
                """{"p": {"type": "STRING", "required": false}}"""
            } else {
                "{}"
            }
        return mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Order 324 Pipeline",
                "description" to "E2E test pipeline — the idempotent retry's start window",
                "parameters" to mapper.readTree(parameters),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "source_node",
                            "description" to "Caller node",
                            "type" to "DQL",
                            "source" to "pg-local-324",
                            "template" to mapOf("id" to templateId, "version" to 1),
                            "output" to mapOf("target" to "caller"),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            ),
        )
    }

    private fun seedSourceUsers() {
        DriverManager
            .getConnection(source.jdbcUrl, source.username, source.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS users (
                            id SERIAL PRIMARY KEY,
                            email TEXT NOT NULL,
                            name TEXT NOT NULL,
                            is_active BOOLEAN NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute("TRUNCATE users")
                    statement.execute(
                        """
                        INSERT INTO users (email, name, is_active, created_at) VALUES
                            ('order324@datapipelines.test', 'Active', TRUE, NOW())
                        """.trimIndent(),
                    )
                }
            }
    }

    companion object {
        /** The main pipeline's query — fast, so the window scenario completes inside its budget. */
        private const val FAST_QUERY =
            "SELECT id, email, name, created_at FROM users WHERE is_active = true ORDER BY created_at DESC"

        private const val SLOT_SLEEP_SECONDS = 30

        /**
         * The slot-holder's query: the negative half needs the original to hold the class's only
         * per-user execution slot while the doomed key's follow waits out its ~15 s give-up.
         */
        private const val SLOT_QUERY =
            "SELECT id, email, name, created_at FROM users, pg_sleep($SLOT_SLEEP_SECONDS) WHERE is_active = true ORDER BY created_at DESC"

        private const val TEMPLATE_ID = "test/order_324.sql"
        private const val SLOT_TEMPLATE_ID = "test/order_324_slot.sql"
        private const val PIPELINE_NAME = "test/order_324"
        private const val SLOT_PIPELINE_NAME = "test/order_324_slot"
        private const val NO_KEY_TEMPLATE_ID = "test/order_404_no_key.sql"
        private const val NO_KEY_PIPELINE_NAME = "test/order_404_no_key"

        /**
         * How long the test holds the lock and demands silence from BOTH requests. Must stay well
         * under the shipped lifecycle bound (10 s) — see the class KDoc — and well over any honest
         * scheduling delay for the first frame of a healthy execution (milliseconds).
         */
        private const val EARLY_WINDOW_MS = 4_000L

        /** How long the original's `SET NX` may take to show up in Redis before the test gives up. */
        private const val RESERVATION_WAIT_MS = 10_000L

        /** The follow's give-up is 60 polls at a 250 ms fixed delay = 15 s; the floor proves the wait. */
        private const val FOLLOW_GIVE_UP_FLOOR_MS = 14_000L

        private const val SSE_BUDGET_MINUTES = 3L

        private val TERMINAL_EVENTS = setOf("pipeline_completed", "data_ready", "pipeline_failed", "execution_aborted")

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        private val random = SecureRandom()

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret`, signing the session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private const val ADMIN_EMAIL = "e2e-324-admin@datapipelines.test"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)

        /** #404 A.3's slot-holder: a second member, so the per-user slot is theirs alone. */
        private val SECOND_USER_ID: String = UUID.randomUUID().toString()
        private const val SECOND_EMAIL = "e2e-404-member@datapipelines.test"
        private val SECOND_SESSION get() = E2eSession.jwt(JWT_SECRET, SECOND_USER_ID, SECOND_EMAIL)

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        /** The pipeline's SOURCE database: a scratch database on the shared container. */
        private val source = SharedE2e.scratchDatabase("order324_source")

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(32).also { random.nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @DynamicPropertySource
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
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // The negative half's forceability: ONE per-user slot, so a second POST is refused
            // (inside the executor, after its reservation) before any event — the never-started
            // original R2 answers for.
            registry.add("datapipelines.executor.max-concurrent-executions-per-user") { "1" }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
