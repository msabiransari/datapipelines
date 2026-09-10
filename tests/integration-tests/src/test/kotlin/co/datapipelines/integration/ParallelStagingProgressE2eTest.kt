package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
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
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 108 §B/§D end to end — its own suite rather than another case on `PipelineShapesE2eTest`,
 * because adding it there pushed that class past detekt's `LargeClass` threshold. The scaffolding
 * below (auth seeding, datasource registration, template and pipeline creation) is the house
 * pattern every E2E suite here carries: each one owns its fixtures, so a suite can be read, run
 * and deleted on its own.
 *
 * Its own key ids and user ids, and its own scratch source database, so it shares no row with any
 * sibling suite and the shared container's cleaning rule keeps holding.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ParallelStagingProgressE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /**
     * 108 §B/§D end to end: three independent source nodes STAGE AT THE SAME TIME, and the
     * execution's row shows which node is running while it is still running.
     *
     * Both claims share one (expensive) execution, and each is the other's non-vacuity floor: an
     * execution fast enough to make the overlap trivial is too fast to be caught mid-flight, and
     * one slow enough to poll is one where serialized staging would be obvious.
     *
     * **The overlap assertion is on TIMESTAMPS, not on a green suite.** Before 108 §B,
     * `H2Staging.stage` held the per-execution mutex across the whole source drain — the network
     * wait included — so two source nodes staged strictly one after the other and their
     * `[started_at, completed_at]` intervals were disjoint by construction. The assertion is
     * pairwise intersection of the three, which that shape cannot produce.
     *
     * **The progress assertion polls a DIFFERENT surface from the one that writes it.** The
     * executor writes `node_stats_json`; this reads `GET /api/v1/executions/{id}` while the stream
     * is still open. A test asserting on the executor's own in-memory collector would pass with the
     * persistence missing entirely, which is the whole feature.
     *
     * The execution id comes from the STREAM's own `execution_started`, not from a correlation-id
     * lookup in the listing. The first attempt did the latter and sampled 31 times without ever
     * seeing a RUNNING row — a lookup that is one more thing to be wrong, on the exact surface the
     * test is trying to hold still.
     */
    @Test
    fun `three independent source nodes stage concurrently and the row shows progress while running`() {
        ensureAuthSeeded()
        registerDatasource()
        createParallelStagingTemplates()

        val pipelineId = createParallelStagingPipeline()
        val runningSnapshots = CopyOnWriteArrayList<JsonNode>()

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                streamAndPollProgress(pipelineId, runningSnapshots)
            }

        val completed = events.single { it.first == "pipeline_completed" }.second
        val stats = completed["node_stats"].associateBy { it["node_id"].asText() }

        // 1. The three sources overlapped. Pairwise, because "some two overlapped" would pass on a
        //    run where one node was serialized behind the other two.
        val windows = PARALLEL_SOURCES.map { id -> windowOf(stats.getValue(id)) }
        windows.forEachIndexed { i, a ->
            windows.drop(i + 1).forEach { b ->
                (a.first <= b.second && b.first <= a.second) shouldBe true
            }
        }

        // 2. The row said RUNNING, with one of the source nodes named, while the execution was
        //    still going. The poll is time-based, so the claim is that it saw progress AT ALL.
        runningSnapshots.any { snapshot ->
            snapshot.any { it["status"].asText() == "RUNNING" && it["node_id"].asText() in PARALLEL_SOURCES }
        } shouldBe true
    }

    /**
     * Opens the execution stream, starts polling the row the moment `execution_started` names it,
     * and returns every event once the stream closes.
     */
    private fun streamAndPollProgress(
        pipelineId: String,
        into: MutableList<JsonNode>,
    ): List<Pair<String, JsonNode>> {
        val response = HttpClient.newHttpClient().send(executeRequest(pipelineId), HttpResponse.BodyHandlers.ofInputStream())
        response.statusCode() shouldBe 200

        val events = mutableListOf<Pair<String, JsonNode>>()
        val poller = Executors.newSingleThreadExecutor()
        val polling = AtomicBoolean(true)
        try {
            BufferedReader(InputStreamReader(response.body())).use { reader ->
                readEvents(reader, events) { executionId -> poller.submit { pollUntilStopped(executionId, polling, into) } }
            }
        } finally {
            polling.set(false)
            poller.shutdownNow()
        }
        return events
    }

    private fun executeRequest(pipelineId: String): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
            .build()

    /** Reads SSE lines into [events], calling [onStarted] once with the id `execution_started` names. */
    private fun readEvents(
        reader: BufferedReader,
        events: MutableList<Pair<String, JsonNode>>,
        onStarted: (String) -> Unit,
    ) {
        var currentEvent: String? = null
        reader.lines().forEach { line ->
            if (line.startsWith("event:")) {
                currentEvent = line.removePrefix("event:").trim()
            } else if (line.startsWith("data:")) {
                val payload = mapper.readTree(line.removePrefix("data:").trim())
                events += (currentEvent ?: "unknown") to payload
                if (currentEvent == "execution_started") onStarted(payload["execution_id"].asText())
            }
        }
    }

    private fun pollUntilStopped(
        executionId: String,
        polling: AtomicBoolean,
        into: MutableList<JsonNode>,
    ) {
        while (polling.get()) {
            runCatching { nodeStatsOf(executionId) }.getOrNull()?.let(into::add)
            Thread.sleep(PROGRESS_POLL_MS)
        }
    }

    /** The node stats on one execution's row, or null while it carries none yet. */
    private fun nodeStatsOf(executionId: String): JsonNode? {
        val detail =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .thenReturn()
        if (detail.statusCode() != 200) return null
        val body = mapper.readTree(detail.body().asString())["data"]
        // Only a row that is still RUNNING can prove anything about live progress: a terminal row
        // carries node stats too, and counting those would make the assertion vacuous.
        if (body["status"].asText() != "RUNNING") return null
        return body["node_stats"]?.takeIf { !it.isNull && it.size() > 0 }
    }

    /** The `[started_at, completed_at]` window of one node's stats, in epoch millis. */
    private fun windowOf(stat: JsonNode): Pair<Long, Long> =
        java.time.Instant
            .parse(stat["started_at"].asText())
            .toEpochMilli() to
            java.time.Instant
                .parse(stat["completed_at"].asText())
                .toEpochMilli()

    private fun createParallelStagingTemplates() {
        // Each source node scans the SAME seeded table with a different cross-join width, so the
        // three take comparably long and none is trivially instant — an instant node cannot
        // overlap with anything and would make the assertion vacuous.
        PARALLEL_SOURCES.forEachIndexed { i, id ->
            createTemplate(
                "test/parallel_$id.sql",
                "POSTGRES",
                "SELECT g AS n, ${i + 1} AS lane FROM generate_series(1, $PARALLEL_ROWS) g",
            )
        }
        createTemplate(
            "test/parallel_join.sql",
            "H2",
            PARALLEL_SOURCES.joinToString(" UNION ALL ") { "SELECT lane, COUNT(*) AS c FROM stg_$it GROUP BY lane" },
        )
    }

    private fun createParallelStagingPipeline(): String =
        createPipeline(
            "test/parallel_staging",
            PARALLEL_SOURCES.map { id ->
                mapOf(
                    "id" to id,
                    "description" to "Source $id",
                    "type" to "DQL",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to "test/parallel_$id.sql", "version" to 1),
                    "output" to mapOf("target" to "tempdb", "table" to "stg_$id"),
                    "depends_on" to emptyList<String>(),
                )
            } +
                listOf(
                    mapOf(
                        "id" to "joined",
                        "description" to "Join the three",
                        "type" to "DQL",
                        "source" to "tempdb",
                        "template" to mapOf("id" to "test/parallel_join.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        // Data flow only: the join reads all three staged tables. No edge here
                        // exists to serialise anything — that is the rule 108 §B restated.
                        "depends_on" to PARALLEL_SOURCES,
                    ),
                ),
        )

    // ------------------------------------------------------------------ fixtures

    private fun ensureAuthSeeded() {
        synchronized(authLock) {
            if (authSeeded) return
            authSeeded = true
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', '$ADMIN_EMAIL', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
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
    }

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
                {"name": "$DATASOURCE", "display_name": "Parallel source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
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
                     "description": "108 parallel staging fixture", "imports": [],
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

    private fun createPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to "Parallel staging",
                    "description" to "108 §B/§D — three independent source nodes",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(body)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != 201) {
            throw AssertionError("Pipeline creation failed (${response.statusCode()}): ${response.body().asString()}")
        }
        return response.jsonPath().getString("data.id")
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DATASOURCE = "pg-parallel"
        private const val ADMIN_EMAIL = "e2e-parallel-admin@datapipelines.test"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"

        /** Generous against a real runtime of seconds — it can only fire on a stream that stopped. */
        private const val SSE_BUDGET_MINUTES = 3L

        /**
         * The three independent source nodes, and the rows each scans.
         *
         * 120 000: long enough that a node is catchable mid-flight by a 25 ms poll and that
         * serialized staging would visibly triple the execution, short enough to stay inside
         * [SSE_BUDGET_MINUTES] on a box running other lanes — where a first attempt at 400 000
         * blew the budget outright.
         */
        private val PARALLEL_SOURCES = listOf("src_a", "src_b", "src_c")
        private const val PARALLEL_ROWS = 120_000
        private const val PROGRESS_POLL_MS = 25L

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-parallel-admin-key", arrayOf("admin"))

        private var authSeeded = false
        private val authLock = Any()

        private val postgres get() = SharedE2e.postgres

        /** This suite's own scratch source database on the shared container. */
        private val source = SharedE2e.scratchDatabase("parallel_source")

        private val redis get() = SharedE2e.redis

        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

        private const val SECRET_BYTES = 32

        /**
         * The shared containers, wired into this context. Copied from the sibling suites rather
         * than shared, which is the house pattern here: each E2E owns its context configuration,
         * and a shared one would make any suite's property a every suite's property.
         */
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

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

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
