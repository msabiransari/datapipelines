package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The #143/#130 load harness: N concurrent executions against ONE application while the
 * subject execution is cancelled mid-flight, counting how often the subject's LIVE stream
 * misses the `execution_aborted` terminal frame or never closes.
 *
 * ## The measured quantities, per trial
 *
 * - **delivered** — `execution_aborted` arrived on the subject's live stream (not the replay,
 *   not the durable record: the delivery the browser witness showed missing).
 * - **closed** — the live stream ENDED (EOF) within [CLOSE_BUDGET_MILLIS] of the 204, the
 *   property whose absence hung `HttpClient.send` in #130's re-subscribe arm.
 * - **serverStatus / replayHasAbort** — the witness's own control quantities: the server
 *   reached ABORTED and the §10.3 log carries the event, so a miss is a DELIVERY gap, not an
 *   abort gap.
 *
 * ## The load shape (the 150 browser witness, minus the browser)
 *
 * Three executions run against one JVM: the subject (fast branch 10k rows, slow branch 2M,
 * cancel mid-slow) and two load workers running short executions back-to-back for the
 * subject's whole lifetime. Counting the miss rate over many trials is the point: the
 * failure is a race, and a single green run proves nothing about it.
 *
 * Trials are configured with `-Dsse157.trials` (default [DEFAULT_TRIALS]); the count is
 * printed as `sse157-harness SUMMARY` and the test fails when any live stream missed its
 * terminal frame.
 *
 * A trial is three-valued (#176): CONCLUSIVE (the cancel was accepted — the stream must
 * deliver and close), INCONCLUSIVE (the cancel answered 409 — the subject ended before
 * the DELETE landed, so the trial measured nothing; it retries with a fresh execution),
 * or an error (anything else). Only conclusive trials count, every planned trial must
 * reach a verdict (the non-vacuity floor), and inconclusive retries are bounded so a box
 * where the subject always wins fails loudly instead of spinning or passing vacuously.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ExecutionStreamCancelLoadE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /** `patient` keeps the connection open past the editor's fallback (see the trial comment). */
    private val patientClient = System.getenv("SSE157_CLIENT") == "patient"

    @Test
    fun `every cancelled execution's live stream delivers execution_aborted and closes`() {
        seedFixtures()
        val trials =
            System.getProperty("sse157.trials")?.toInt()
                ?: System.getenv("SSE157_TRIALS")?.toInt()
                ?: DEFAULT_TRIALS
        val misses = mutableListOf<TrialOutcome>()
        var conclusive = 0
        var inconclusive = 0
        var attempt = 0

        while (conclusive < trials) {
            check(inconclusive <= trials) {
                "more trials inconclusive ($inconclusive) than planned ($trials) — the subject ends before the " +
                    "cancel lands, so the harness is measuring nothing on this box; that is a broken fixture, not a pass"
            }
            val outcome = runTrial(attempt++)
            if (outcome == null) {
                // runTrial printed the detail (the 409, the attempts, the subject's status).
                inconclusive++
                continue
            }
            conclusive++
            println(
                "sse157-harness trial=$conclusive/$trials delivered=${outcome.delivered} closed=${outcome.closed} " +
                    "abortMs=${outcome.abortLatencyMs ?: "-"} closeMs=${outcome.closeLatencyMs ?: "-"} " +
                    "serverStatus=${outcome.serverStatus} replayHasAbort=${outcome.replayHasAbort}",
            )
            if (!outcome.delivered || !outcome.closed) misses += outcome
        }

        // The non-vacuity floor (#176): EVERY planned trial reached a conclusive verdict —
        // a green with fewer proves nothing about the stream.
        println("sse157-harness SUMMARY trials=$trials conclusive=$conclusive inconclusive=$inconclusive misses=${misses.size}")
        misses shouldBe emptyList()
    }

    /**
     * One cancel-under-load trial; the subject's live stream is read incrementally.
     * NULL is the inconclusive verdict (#176): the cancel answered 409 because the subject
     * reached a terminal state first — nothing about delivery was measured.
     */
    private fun runTrial(index: Int): TrialOutcome? {
        val load = LoadWorkers()
        load.start()
        val reader = SubjectReader()
        try {
            reader.start()
            // Event-synchronised, never a sleep: the cancel fires when the slow branch is
            // genuinely mid-flight (its first progress sample), which is the witness state.
            if (!reader.slowBranchLive.await(SLOW_BRANCH_BUDGET_MILLIS, TimeUnit.MILLISECONDS)) {
                error("trial $index: the slow branch never went live — fixture broken, not the stream")
            }
            val executionId = checkNotNull(reader.executionId.get()) { "execution_started never carried an id" }

            val (cancelStatus, cancelAttempts) = cancelSubject(executionId, reader)
            if (cancelStatus == 409) {
                // Already terminal on the FIRST accepted cancel: the subject ended before
                // the DELETE landed. The trial measured nothing about delivery —
                // inconclusive, retried by the caller with a fresh execution; NOT a miss
                // and NOT an error (#176). Print the subject's terminal state so a storm
                // of these is diagnosable from the log alone.
                println(
                    "sse157-harness trial attempt=$index INCONCLUSIVE (cancel answered 409 " +
                        "after $cancelAttempts attempt(s); subject ended first: status=${executionStatus(executionId)})",
                )
                return null
            }
            if (cancelStatus != 204) error("trial $index: cancel answered $cancelStatus")

            // The client model is the editor's own contract (sse.js SseHandler.cancel): after
            // the DELETE, the page keeps listening for execution_aborted for exactly its 5 s
            // fallback window and then aborts the stream. A frame that arrives later is a
            // delivery MISS — that is the #143 witness quantity. Patient mode (SSE157_CLIENT=
            // patient) keeps the connection open for the full budgets instead, proving
            // end-to-end delivery plus the launcher's EOF.
            val clientWindow = if (patientClient) ABORT_BUDGET_MILLIS else FALLBACK_WINDOW_MILLIS
            val delivered = reader.terminal.await(clientWindow, TimeUnit.MILLISECONDS)
            val closed =
                if (delivered) {
                    reader.eof.await(CLOSE_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                } else {
                    false
                }

            val serverStatus = executionStatus(executionId)
            val replayHasAbort = replayCarriesAbort(executionId)

            return TrialOutcome(
                delivered = delivered,
                closed = closed,
                abortLatencyMs = reader.terminalAtMillis.takeIf { it > 0 }?.let { it - reader.cancelAtMillis },
                closeLatencyMs = reader.eofAtMillis.takeIf { it > 0 }?.let { it - reader.cancelAtMillis },
                serverStatus = serverStatus,
                replayHasAbort = replayHasAbort,
            )
        } finally {
            reader.stop()
            load.stop()
        }
    }

    /**
     * The cancel round-trip: status to attempts. Retries ONLY a 429 — a rate-limited
     * request never reached the app, so the retry is request hygiene, not a second
     * cancel. The pre-#176 loop used `return@repeat` here, which is a CONTINUE: every
     * trial issued up to three DELETEs, and under load the redundant retry landed after
     * the abort completed and answered 409 — that, not a fast subject, was the
     * loaded-gate red.
     */
    private fun cancelSubject(
        executionId: String,
        reader: SubjectReader,
    ): Pair<Int, Int> {
        var cancelStatus = -1
        var cancelAttempts = 0
        while (cancelAttempts < CANCEL_RETRIES) {
            cancelAttempts++
            reader.cancelAtMillis = System.currentTimeMillis()
            cancelStatus =
                given()
                    .port(port)
                    .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                    .`when`()
                    .delete("/api/v1/executions/$executionId")
                    .then()
                    .extract()
                    .statusCode()
            if (cancelStatus != 429) break
            Thread.sleep(RATE_WINDOW_MILLIS)
        }
        return cancelStatus to cancelAttempts
    }

    /** The subject's server-side status — the trial's control read. */
    private fun executionStatus(executionId: String): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$executionId")
            .then()
            .extract()
            .jsonPath()
            .getString("data.status")

    /** The §10.3 replay — the witness's own control: did the SERVER emit the abort at all? */
    private fun replayCarriesAbort(executionId: String): Boolean {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/executions/$executionId/events"))
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("Accept", "text/event-stream")
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return E2eSse.parseEvents(response.body(), mapper).any { it.first == "execution_aborted" }
    }

    /**
     * The subject's incremental SSE reader: a line reader over the live response, collecting
     * events until EOF. Latches expose the three moments the trial synchronises on — slow
     * branch live, terminal seen, EOF seen — so every wait is on an event, never a sleep.
     */
    private inner class SubjectReader {
        val executionId = AtomicReference<String?>(null)
        val slowBranchLive = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val eof = CountDownLatch(1)

        @Volatile var cancelAtMillis = 0L

        @Volatile var terminalAtMillis = 0L

        @Volatile var eofAtMillis = 0L
        private var currentEvent = ""
        private var thread: Thread? = null

        fun start() {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/${checkNotNull(branchPipelineId)}/execute"))
                    .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val thread =
                Thread({
                    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() != 200) {
                        val bodyText =
                            response
                                .body()
                                .readAllBytes()
                                .decodeToString()
                                .take(300)
                        error("subject execute answered ${response.statusCode()}: $bodyText")
                    }
                    BufferedReader(InputStreamReader(response.body())).use { body ->
                        while (true) {
                            val line = body.readLine() ?: break
                            when {
                                line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> onEvent(mapper.readTree(line.removePrefix("data:").trim()))
                            }
                        }
                    }
                    eofAtMillis = System.currentTimeMillis()
                    eof.countDown()
                }, "sse157-subject")
            thread.isDaemon = true
            this.thread = thread
            thread.start()
        }

        private fun onEvent(payload: JsonNode) {
            val name = currentEvent
            when {
                name == "execution_started" && executionId.get() == null -> {
                    executionId.set(payload["execution_id"].asText())
                }

                name == "node_progress" && payload["node_id"].asText() == "src_slow" -> {
                    slowBranchLive.countDown()
                }

                name in TERMINAL_EVENTS -> {
                    terminalAtMillis = System.currentTimeMillis()
                    terminal.countDown()
                }
            }
        }

        fun stop() {
            // Interrupting the reader aborts the blocked read; the execution behind it is
            // already terminal by now (the trial only returns after terminal/EOF or timeout).
            thread?.interrupt()
        }
    }

    /**
     * Two workers running short executions back-to-back for as long as the subject lives —
     * the concurrent-load half of the harness shape.
     */
    private inner class LoadWorkers {
        private val stopFlag = AtomicBoolean(false)
        private val workers = mutableListOf<Thread>()

        fun start() {
            repeat(LOAD_WORKERS) { workerId ->
                val thread =
                    Thread({
                        val client = HttpClient.newHttpClient()
                        while (!stopFlag.get()) {
                            val request =
                                HttpRequest
                                    .newBuilder(
                                        URI.create(
                                            "http://localhost:$port/api/v1/pipelines/${checkNotNull(fastPipelineId)}/execute",
                                        ),
                                    ).header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "text/event-stream")
                                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                                    .build()
                            runCatching {
                                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                                val names = E2eSse.parseEvents(response.body(), mapper).map { it.first }
                                if (response.statusCode() != 200 ||
                                    !names.containsAll(listOf("execution_started", "pipeline_completed", "data_ready"))
                                ) {
                                    println(
                                        "sse157-harness load-worker=$workerId status=${response.statusCode()} " +
                                            "short stream: $names body=${response.body().take(200)}",
                                    )
                                }
                            }.onFailure { if (!stopFlag.get()) println("sse157-harness load-worker=$workerId failed: $it") }
                            // Paced, not hot: the §12.1 per-user budget (100 rps) is shared with
                            // the subject's own calls, and a harness that rate-limits its own
                            // cancel measures the limiter, not the stream.
                            Thread.sleep(LOAD_PACE_MILLIS)
                        }
                    }, "sse157-load-$workerId")
                thread.isDaemon = true
                workers += thread
                thread.start()
            }
        }

        fun stop() {
            stopFlag.set(true)
            workers.forEach { it.join(FAST_DRAIN_MILLIS) }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun seedFixtures() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .createStatement()
                .execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', 'SSE157 harness', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, '$DEFAULT_WORKSPACE') ON CONFLICT (id) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, ADMIN_KEY.id)
                    ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(3, ADMIN_KEY.name)
                    ps.setString(4, ADMIN_KEY.hash)
                    ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                    ps.execute()
                }
        }
        registerDatasource()
        createTemplate("test/sse157_fast.sql", "POSTGRES", "SELECT g AS n FROM generate_series(1, $FAST_ROWS) g")
        createTemplate("test/sse157_slow.sql", "POSTGRES", "SELECT g AS n FROM generate_series(1, $SLOW_ROWS) g")
        createTemplate("test/sse157_join.sql", "H2", "SELECT COUNT(*) AS c FROM stg_fast")
        createTemplate("test/sse157_caller.sql", "POSTGRES", "SELECT g AS n FROM generate_series(1, $LOAD_ROWS) g")
        createBranchingPipeline()
        createFastPipeline()
    }

    /** The two pipelines' UUIDs, read off the create responses and used in every execute URL. */
    private var branchPipelineId: String? = null
    private var fastPipelineId: String? = null

    private fun registerDatasource() {
        val existing =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/$DATASOURCE")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "SSE157 source", "dialect": "POSTGRES",
                 "jdbc_url": "${postgres.jdbcUrl.substringBefore(
                    "?",
                )}", "username": "${postgres.username}", "password": "${postgres.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate(
        id: String,
        dialect: String,
        body: String,
    ) {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    """
                    {"id": "$id", "dialect": "$dialect", "display_name": "$id",
                     "description": "sse157 harness fixture", "imports": [],
                     "body": ${mapper.writeValueAsString(body)}}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/templates")
                .thenReturn()
        // 409 is fine: the shared container survives across suites, so a re-run finds its own
        // fixtures already there.
        if (response.statusCode() !in setOf(201, 409)) {
            throw AssertionError("Template $id failed (${response.statusCode()}): ${response.body().asString()}")
        }
    }

    /** The 150 witness shape: fast 10k root, slow 2M root, join over the fast staging table. */
    private fun createBranchingPipeline() {
        val nodes =
            """[
              { "id": "src_fast", "type": "DQL", "source": "$DATASOURCE", "template": { "id": "test/sse157_fast.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_fast" }, "depends_on": [] },
              { "id": "src_slow", "type": "DQL", "source": "$DATASOURCE", "template": { "id": "test/sse157_slow.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg_slow" }, "depends_on": [] },
              { "id": "joined", "type": "DQL", "source": "tempdb", "template": { "id": "test/sse157_join.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": ["src_fast", "src_slow"] }
            ]"""
        branchPipelineId = createPipeline(BRANCH_PIPELINE, nodes)
    }

    /** The load workers' short pipeline — one caller-output node, done in about a second. */
    private fun createFastPipeline() {
        val nodes =
            """[
              { "id": "load_read", "type": "DQL", "source": "$DATASOURCE", "template": { "id": "test/sse157_caller.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": [] }
            ]"""
        fastPipelineId = createPipeline(FAST_PIPELINE, nodes)
    }

    /** Creates the pipeline (409 → already there, so fetch its id) and returns its UUID. */
    private fun createPipeline(
        name: String,
        nodes: String,
    ): String {
        val first =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body("""{"name": "$name", "nodes": $nodes}""")
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (first.statusCode() == 201) return first.jsonPath().getString("data.id")
        if (first.statusCode() != 409) {
            throw AssertionError("Pipeline $name failed (${first.statusCode()}): ${first.body().asString()}")
        }
        val listed =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .queryParam("limit", 100)
                .`when`()
                .get("/api/v1/pipelines")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()

        @Suppress("UNCHECKED_CAST")
        val items = listed.getList("data.items", Map::class.java)
        return items
            .firstOrNull { it["name"] == name }
            ?.let { it["id"].toString() }
            ?: error("pipeline $name neither created nor listed — fixture state lost")
    }

    // ------------------------------------------------------------------ types & constants

    data class TrialOutcome(
        val delivered: Boolean,
        val closed: Boolean,
        val abortLatencyMs: Long?,
        val closeLatencyMs: Long?,
        val serverStatus: String?,
        val replayHasAbort: Boolean,
    )

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEFAULT_TRIALS = 20
        private const val BRANCH_PIPELINE = "test/sse157_branch"
        private const val FAST_PIPELINE = "test/sse157_fast"
        private const val DATASOURCE = "sse157_src"
        private const val FAST_ROWS = 10_000
        private const val SLOW_ROWS = 2_000_000
        private const val LOAD_ROWS = 2_000
        private const val LOAD_WORKERS = 2

        /** Paced load: two workers at this cadence stay well inside the shared §12.1 budget. */
        private const val LOAD_PACE_MILLIS = 150L

        /**
         * The editor's own cancel fallback (sse.js `SseHandler.cancel`): the page listens for
         * `execution_aborted` 5 s after the DELETE and then aborts the stream. The faithful
         * client's delivery window.
         */
        private const val FALLBACK_WINDOW_MILLIS = 5_000L

        /** Bounded retry for the trial's own 429 (a fixed 1 s window the limiter shares). */
        private const val CANCEL_RETRIES = 3
        private const val RATE_WINDOW_MILLIS = 1_100L

        /** How long the slow branch may take to show its first progress sample. */
        private const val SLOW_BRANCH_BUDGET_MILLIS = 60_000L

        /** The cancel contract's own latency bound (§8.3.1) — the local path is immediate. */
        private const val ABORT_BUDGET_MILLIS = 45_000L

        /** EOF after the terminal: the launcher's `finally` close, which #130 hung without. */
        private const val CLOSE_BUDGET_MILLIS = 45_000L

        private const val FAST_DRAIN_MILLIS = 30_000L

        private val TERMINAL_EVENTS = setOf("pipeline_completed", "pipeline_failed", "execution_aborted", "data_ready")

        private const val ADMIN_USER_ID = "a1570000-0000-0000-0000-000000000003"
        private const val ADMIN_EMAIL = "sse157-harness@datapipelines.test"
        private val ADMIN_KEY = E2eAuth.generateKey("sse157-harness", arrayOf("read", "execute", "author"))

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redisPort }
            registry.add("datapipelines.jwt.secret") { SECRET }
            registry.add("datapipelines.db.encryption-key") { SECRET }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { OIDC.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // The collision, forced (the house rule: synchronise on the event, never win a
            // race). At the shipped 5 s default the pre-fix abort latency (~one full grace)
            // knife-edges against the 5 s fallback window — missing only when load pushes the
            // unwind past the window, which is exactly the 150 gate witness. A harness whose
            // pass depends on winning that race proves nothing, so the app under test runs the
            // operator-legal 8 s grace: pre-fix the terminal then lands at ~8 s, structurally
            // outside the window; the fix bounds the abort by the re-issue horizon at ANY grace.
            registry.add("datapipelines.executor.cancel-grace-seconds") { "8" }
        }

        private val redisPort: Int get() = SharedE2e.redisPort

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private val OIDC = OidcDiscoveryStub()

        @AfterAll
        @JvmStatic
        fun closeOidc() {
            OIDC.close()
        }
    }
}
