package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 149 end to end, through the REAL application: a Postgres source staged by two independent
 * nodes into tempdb and joined to the caller, watched over the live SSE stream. What is
 * pinned is the production wiring — runner → tracker → pump → projection → live stream →
 * durable `execution_events` → Redis replay → the execution detail page — not a mocked
 * event list:
 *
 *  1. every stage node emits `node_progress` samples strictly between its `node_started` and
 *     `node_completed`, ending in a `completed` sample that says committed with the row count;
 *  2. the two writers OVERLAP on the wire: each has a live `writing` sample observed before the
 *     other's operation ended;
 *  3. the caller node reports `materialize → caller` with the joined row count;
 *  4. the §10.3 replay serves the same samples with the same ids and `observed_at`;
 *  5. the durable record holds every one of them;
 *  6. the detail page's Node Operations table names the destinations and the commits.
 *
 * Fixtures follow the house pattern (each suite owns its keys, users, scratch source).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class NodeProgressE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `node_progress flows from the runner to the live stream, the replay, the durable record and the detail page`() {
        ensureAuthSeeded()
        registerDatasource()
        createTemplates()
        val pipelineId = createPipeline()

        val events = assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) { streamToEnd(pipelineId) }
        val executionId = events.first { it.first == "execution_started" }.second["execution_id"].asText()
        val names = events.map { it.first }
        names.last() shouldBe "data_ready"

        // 1. Per stage node: samples between its lifecycle events, a committed terminal sample.
        SOURCES.forEach { id -> assertStageNodeSamples(events, id) }

        // 2. Overlap on the wire: each node's first live `writing` sample was observed before the
        //    OTHER node's operation ended.
        val endOf = SOURCES.associateWith { id -> observedAt(events, id, "completed") }
        SOURCES.forEach { id ->
            val other = SOURCES.first { it != id }
            val writing =
                events.first {
                    it.first == "node_progress" && it.second["node_id"].asText() == id &&
                        it.second["state"].asText() == "writing"
                }
            Instant.parse(writing.second["observed_at"].asText()).isBefore(endOf.getValue(other)).shouldBeTrue()
        }

        // 3. The caller node materializes the join into the result store.
        val joined = events.filter { it.first == "node_progress" && it.second["node_id"].asText() == "joined" }.map { it.second }
        joined.last()["operation"].asText() shouldBe "materialize"
        joined.last()["destination"]["kind"].asText() shouldBe "caller"
        joined.last()["rows_written"].asLong() shouldBe 2L * ROWS
        joined.last()["committed"].asBoolean() shouldBe true

        // 4. Replay (§10.3): the same samples with the same observation instants. Compared as
        //    a set and per node: the log's order between PARALLEL nodes is the persistence
        //    order, which §6.5 leaves non-deterministic — a node's OWN samples stay in sequence.
        val liveProgress = events.filter { it.first == "node_progress" }.map { it.second }
        val replayed = parseReplay(executionId).filter { it.first == "node_progress" }.map { it.second }
        replayed.size shouldBe liveProgress.size
        val key = { s: JsonNode -> Triple(s["node_id"].asText(), s["sequence"].asInt(), s["observed_at"].asText()) }
        replayed.map(key).toSet() shouldBe liveProgress.map(key).toSet()
        (SOURCES + "joined").forEach { id ->
            replayed.filter { it["node_id"].asText() == id }.map { it["sequence"].asInt() } shouldBe
                liveProgress.filter { it["node_id"].asText() == id }.map { it["sequence"].asInt() }
        }

        // 5. The durable record holds every sample.
        durableProgressCount(executionId) shouldBe liveProgress.size

        // 6. The detail page derives the Node Operations table from that record.
        val page = pageHtml("/executions/$executionId")
        page shouldContain "data-node-operations"
        SOURCES.forEach { id -> page shouldContain "tempdb.stg_$id" }
        page shouldContain "ds-badge-success\">committed</span>"
        page shouldContain "data-node-id=\"joined\" data-state=\"completed\""
    }

    /** Assertion 1 for one stage node: its samples sit between its lifecycle events, in sequence, ending committed. */
    private fun assertStageNodeSamples(
        events: List<Pair<String, JsonNode>>,
        id: String,
    ) {
        val samples = events.filter { it.first == "node_progress" && it.second["node_id"].asText() == id }.map { it.second }
        samples.size shouldBeGreaterThan 0
        val started = events.indexOfFirst { it.first == "node_started" && it.second["node_id"].asText() == id }
        val completed = events.indexOfFirst { it.first == "node_completed" && it.second["node_id"].asText() == id }
        val positions =
            events
                .withIndex()
                .filter {
                    it.value.first == "node_progress" && it.value.second["node_id"].asText() == id
                }.map { it.index }
        positions.all { it > started && it < completed }.shouldBeTrue()
        samples.map { it["sequence"].asInt() } shouldBe (1..samples.size).toList()
        val terminal = samples.last()
        terminal["state"].asText() shouldBe "completed"
        terminal["operation"].asText() shouldBe "stage"
        terminal["destination"]["kind"].asText() shouldBe "tempdb"
        terminal["destination"]["table"].asText() shouldBe "stg_$id"
        terminal["committed"].asBoolean() shouldBe true
        terminal["rows_written"].asLong() shouldBe ROWS.toLong()
        terminal["rows_fetched"].asLong() shouldBe ROWS.toLong()
        terminal["timings_ms"].fieldNames().asSequence().toList() shouldContainAll listOf("executing", "fetching", "writing")
        terminal["correlation_id"].asText().isNotBlank().shouldBeTrue()
        samples.dropLast(1).none { it.has("committed") }.shouldBeTrue()
    }

    private fun observedAt(
        events: List<Pair<String, JsonNode>>,
        nodeId: String,
        state: String,
    ): Instant =
        Instant.parse(
            events
                .first { it.first == "node_progress" && it.second["node_id"].asText() == nodeId && it.second["state"].asText() == state }
                .second["observed_at"]
                .asText(),
        )

    private fun streamToEnd(pipelineId: String): List<Pair<String, JsonNode>> {
        val response = HttpClient.newHttpClient().send(executeRequest(pipelineId), HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return E2eSse.parseEvents(response.body(), mapper)
    }

    private fun parseReplay(executionId: String): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/executions/$executionId/events"))
                .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Accept", "text/event-stream")
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return E2eSse.parseEvents(response.body(), mapper)
    }

    private fun pageHtml(path: String): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Accept", "text/html")
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return response.body()
    }

    private fun durableProgressCount(executionId: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT COUNT(*) FROM execution_events WHERE execution_id = ?::uuid AND event_type = 'node_progress'",
                ).use { ps ->
                    ps.setString(1, executionId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun executeRequest(pipelineId: String): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
            .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
            .build()

    private fun createTemplates() {
        SOURCES.forEachIndexed { i, id ->
            createTemplate("test/progress_$id.sql", "POSTGRES", "SELECT g AS n, ${i + 1} AS lane FROM generate_series(1, $ROWS) g")
        }
        createTemplate("test/progress_join.sql", "H2", SOURCES.joinToString(" UNION ALL ") { "SELECT n, lane FROM stg_$it" })
    }

    private fun createPipeline(): String =
        createPipeline(
            "test/node_progress",
            SOURCES.map { id ->
                mapOf(
                    "id" to id,
                    "description" to "Source $id",
                    "type" to "DQL",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to "test/progress_$id.sql", "version" to 1),
                    "output" to mapOf("target" to "tempdb", "table" to "stg_$id"),
                    "depends_on" to emptyList<String>(),
                )
            } +
                listOf(
                    mapOf(
                        "id" to "joined",
                        "description" to "Union the two",
                        "type" to "DQL",
                        "source" to "tempdb",
                        "template" to mapOf("id" to "test/progress_join.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to SOURCES,
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
        }
    }

    private fun registerDatasource() {
        val existing =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/datasources/$DATASOURCE")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Progress source", "dialect": "POSTGRES",
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
                .asSession(ADMIN_SESSION)
                .body(
                    """
                    {"id": "$id", "dialect": "$dialect", "display_name": "$id",
                     "description": "149 node progress fixture", "imports": [],
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
                    "display_name" to "Node progress",
                    "description" to "149 — two independent stage nodes and a caller",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
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
        private const val DATASOURCE = "pg-progress"
        private const val ADMIN_EMAIL = "e2e-progress-admin@datapipelines.test"

        /** Generous against a real runtime of seconds — it can only fire on a stream that stopped. */
        private const val SSE_BUDGET_MINUTES = 3L

        /**
         * The two independent stage nodes, and the rows each scans. 120 000 (the sibling
         * suite's number): seconds of staging, so the pump's first-entry samples and the
         * periodic ones both land while the nodes are really writing.
         */
        private val SOURCES = listOf("src_a", "src_b")
        private const val ROWS = 120_000

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret` and used to sign the session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, "$ADMIN_EMAIL")

        private var authSeeded = false
        private val authLock = Any()

        private val postgres get() = SharedE2e.postgres

        /** This suite's own scratch source database on the shared container. */
        private val source = SharedE2e.scratchDatabase("progress_source")

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

            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
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
