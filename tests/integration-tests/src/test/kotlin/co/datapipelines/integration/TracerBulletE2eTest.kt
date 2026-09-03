package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * The P7 tracer bullet: DEVELOPMENT.md §8 driven over HTTP against the FULL application
 * (auth chain live, engine live, recorder live), with real infrastructure —
 *
 * - Postgres #1: the metadata DB (real Flyway V1 on startup),
 * - Redis: result store, idempotency, event log,
 * - Postgres #2: the source datasource, with a small seeded `users` table.
 *
 * The bootstrap admin and its API keys are seeded DIRECTLY via SQL (no OIDC login is
 * involved in a programmatic walkthrough); key hashes are Argon2id, computed in setup
 * with the same pinned `argon2-jvm` library and parameters auth's `Argon2SecretHasher`
 * uses — see this module's build.gradle.kts for why the auth class itself is not
 * referenced (the §4.2 dependency table is mechanically enforced). auth's bounded
 * `SecretHasher` bean stays the only hasher bean; nothing here registers one (auth.md §12).
 *
 * The assertions that make this a tracer bullet rather than a CRUD sweep: the SSE
 * stream carries the documented event ordering with the request's correlation id on
 * every event and the client-requested TTL honored; the result cursor returns exactly
 * the seeded rows; the metadata DB holds the completed `pipeline_executions` row
 * (`triggered_via = REST`) and the `execution_events` rows; and the live auth chain
 * answers 401 to no credential and 403 to an insufficient one.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TracerBulletE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `the DEVELOPMENT §8 walkthrough end to end`() {
        // The walkthrough's subject is a FRESH deployment: its `pg-local` datasource and
        // `active_users` content are the §8 doc examples, names earlier suites (PipelineShapes
        // registers its own `pg-local`) legitimately reuse — so the walkthrough resets the
        // shared database to the freshly-migrated state before it starts, exactly what its
        // former per-suite container guaranteed.
        E2eClean.beforeSeeding()
        seedAuthRows()
        registerDatasourceAndProbe()
        createTemplate()
        val pipelineId = createPipeline()
        createdPipelineId = pipelineId

        // §8.4 — execute, consuming the SSE stream to its terminal events.
        val correlationId = UUID.randomUUID().toString()
        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(pipelineId, ADMIN_KEY.plaintext, correlationId)
            }
        val executionId = assertStreamContract(events, correlationId)

        assertResultCursor(executionId)
        assertExecutionMetadata(executionId, correlationId)
        assertDurableRows(executionId, correlationId, events.size)
    }

    /** §8.1 + §9.6 — register the datasource against source Postgres #2 and probe it. */
    private fun registerDatasourceAndProbe() {
        // The JDBC URL is built clean: the container's `jdbcUrl` carries `?loggerLevel=OFF`,
        // and `loggerLevel` is a datasources §5.6 refused key — the fail-closed validator
        // is right to reject it.
        val sourceJdbcUrl = source.jdbcUrl
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "pg-local", "display_name": "Source Postgres", "dialect": "POSTGRES",
                 "jdbc_url": "$sourceJdbcUrl", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .post("/api/v1/datasources/pg-local/test")
            .then()
            .statusCode(200)
            .body("data.connected", org.hamcrest.Matchers.equalTo(true))
    }

    /**
     * §8.2 — the template. NOTE: DEVELOPMENT.md §8.2's example omits `display_name`,
     * but templates' frozen TemplateDraft requires it (reported to the orchestrator
     * as doc drift); the walkthrough body below includes it.
     */
    private fun createTemplate() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "active_users.sql", "dialect": "POSTGRES", "display_name": "Active Users",
                 "description": "Get all active users. Declares no parameters.", "imports": [],
                 "body": "SELECT id, email, name, created_at FROM users WHERE is_active = true ORDER BY created_at DESC"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    /** §8.3 — the single caller-node pipeline; returns its server-assigned id. */
    private fun createPipeline(): String {
        val pipelineResponse =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    """
                    {"schema_version": 1, "name": "active_users", "display_name": "Active Users",
                     "description": "List all active users from local PG", "parameters": {},
                     "nodes": [{"id": "fetch_active_users", "description": "Fetch active users", "type": "DQL",
                                "source": "pg-local", "template": {"id": "active_users.sql", "version": 1},
                                "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        return pipelineResponse.jsonPath().getString("data.id")
    }

    /**
     * The SSE contract (§6.4/§6.5): the documented event ordering, the request's
     * correlation id on every event, the seeded row count, and the client-requested
     * TTL honored. Returns the execution id from `data_ready`.
     */
    private fun assertStreamContract(
        events: List<Pair<String, JsonNode>>,
        correlationId: String,
    ): String {
        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        events.forEach { (_, payload) -> payload["correlation_id"].asText() shouldBe correlationId }

        val dataReady = events.last().second
        dataReady["total_rows"].asLong() shouldBe ACTIVE_EMAILS.size.toLong()
        // DP-Result-TTL-Seconds: 120 honored (within the configured 60..3600 clamp).
        dataReady["ttl_seconds"].asLong() shouldBe REQUESTED_TTL_SECONDS
        return dataReady["execution_id"].asText()
    }

    /** §7.2 — the result cursor returns exactly the seeded active rows, in order. */
    private fun assertResultCursor(executionId: String) {
        val resultResponse =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        resultResponse.jsonPath().getLong("data.total_rows") shouldBe ACTIVE_EMAILS.size.toLong()
        val rows: List<List<Any?>> = resultResponse.jsonPath().get("data.rows")
        rows.map { it[1] } shouldContainExactly ACTIVE_EMAILS
    }

    /** §10.2 — the execution record. */
    private fun assertExecutionMetadata(
        executionId: String,
        correlationId: String,
    ) {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$executionId")
            .then()
            .statusCode(200)
            .body("data.status", org.hamcrest.Matchers.equalTo("SUCCESS"))
            .body("data.triggered_via", org.hamcrest.Matchers.equalTo("REST"))
            .body("data.correlation_id", org.hamcrest.Matchers.equalTo(correlationId))
    }

    /** The durable record, straight from the metadata DB (metadata-db §4.6/§4.7). */
    private fun assertDurableRows(
        executionId: String,
        correlationId: String,
        eventCount: Int,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT status, triggered_via, correlation_id::text, result_row_count " +
                            "FROM pipeline_executions WHERE execution_id = '$executionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "SUCCESS"
                        rs.getString(2) shouldBe "REST"
                        rs.getString(3) shouldBe correlationId
                        rs.getLong(4) shouldBe ACTIVE_EMAILS.size.toLong()
                    }
                statement
                    .executeQuery("SELECT count(*) FROM execution_events WHERE execution_id = '$executionId'")
                    .use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBeGreaterThanOrEqual eventCount
                    }
            }
        }
    }

    @Test
    @Order(2)
    fun `an unauthenticated api call is 401 auth-api_key-missing`() {
        given()
            .port(port)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(401)
            .body("error.code", org.hamcrest.Matchers.equalTo("auth.api_key.missing"))
    }

    @Test
    @Order(3)
    fun `a read-scope-only key is 403 on execute`() {
        // The auth chain is live: the key authenticates (scope `read`), and the §7.6
        // matrix requires `execute` for POST /pipelines/{id}/execute.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, READ_ONLY_KEY.plaintext)
            .body("""{"parameters": {}}""")
            .`when`()
            .post("/api/v1/pipelines/$createdPipelineId/execute")
            .then()
            .statusCode(403)
            .body("error.code", org.hamcrest.Matchers.equalTo("auth.scope.insufficient"))
    }

    /**
     * The §9.7 introspection endpoints over HTTP against the live source Postgres: the happy
     * path (real JDBC metadata through the whole stack), the catalogued not-found envelope,
     * and the §7.6 scope gate on the new INTROSPECT_DATASOURCE operation.
     */
    @Test
    @Order(4)
    fun `schema introspection endpoints serve metadata, not-found, and scope denial`() {
        // Happy path — schemas: the flow's entry point; user schemas listed, system schemas out.
        // The payload is a page (v1.9): {"schemas": [...], "truncated": false}.
        val schemasJson =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/pg-local/schemas")
                .then()
                .statusCode(200)
                .body("data.truncated", org.hamcrest.Matchers.equalTo(false))
                .extract()
                .jsonPath()
        val schemaNames: List<String> = schemasJson.get("data.schemas")
        schemaNames shouldContainExactly listOf("public")

        // Happy path — tables: the seeded users table, no system catalogs, not truncated.
        val tables =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/pg-local/tables")
                .then()
                .statusCode(200)
                .body("data.truncated", org.hamcrest.Matchers.equalTo(false))
                .extract()
                .jsonPath()
        val tableNames: List<String> = tables.get("data.tables.name")
        tableNames shouldContainExactly listOf("users")
        val schemas: List<String> = tables.get("data.tables.schema")
        schemas.forEach { schema ->
            (schema == "pg_catalog" || schema == "information_schema") shouldBe false
        }

        // Happy path — columns for the table tables() returned.
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/datasources/pg-local/tables/users/columns")
            .then()
            .statusCode(200)
            .body("data.size()", org.hamcrest.Matchers.greaterThan(0))
            .body("data[0].warnings", org.hamcrest.Matchers.notNullValue())

        // Unknown datasource — the catalogued §13.8 not-found envelope, not a 500.
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/datasources/no-such-datasource/tables")
            .then()
            .statusCode(404)
            .body("error.code", org.hamcrest.Matchers.equalTo("datasource.not_found"))

        // Scope denial — introspection is `author` (§7.6); a read-scope key is refused.
        given()
            .port(port)
            .header(API_KEY_HEADER, READ_ONLY_KEY.plaintext)
            .`when`()
            .get("/api/v1/datasources/pg-local/tables")
            .then()
            .statusCode(403)
            .body("error.code", org.hamcrest.Matchers.equalTo("auth.scope.insufficient"))
    }

    /**
     * Reads the SSE stream to its end, returning (event name, payload) pairs.
     *
     * Deliberately a line reader, not a general SSE parser: `ExecutionStream` writes
     * one single-line JSON `data:` per `event:` (rest-api §6.3), heartbeats arrive as
     * `: heartbeat` comments this skips, and `id:` lines are gap-detection metadata the
     * ordering assertion does not need.
     *
     * The stream is consumed to EOF, not to `data_ready`: the emitter sends an event
     * before its bookkeeping lands, and the launcher closes the stream only after the
     * execution row is fully recorded (`result_row_count` included) — so end-of-stream,
     * not the terminal event line, is the client-visible completion signal the cursor
     * assertions below may rely on.
     */
    private fun consumeExecutionStream(
        pipelineId: String,
        apiKey: String,
        correlationId: String,
    ): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, apiKey)
                .header("DP-Correlation-Id", correlationId)
                .header("DP-Result-TTL-Seconds", REQUESTED_TTL_SECONDS.toString())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        // ofString, not ofLines: the ofLines line-iterator has a JDK race that reports
        // a clean server-side close as `IOException: closed` from hasNext(). The whole
        // stream is a handful of small events, so buffering it costs nothing.
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200

        return E2eSse.parseEvents(response.body(), mapper)
    }

    /**
     * Seeds the source table (container #2) and the auth rows (container #1). Called at
     * the start of the walkthrough: the Spring context — and with it Flyway — is up by
     * the first test method, and both containers were started before that.
     */
    private fun seedAuthRows() {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE users (
                        id SERIAL PRIMARY KEY,
                        email TEXT NOT NULL,
                        name TEXT NOT NULL,
                        is_active BOOLEAN NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO users (email, name, is_active, created_at) VALUES
                        ('${ACTIVE_EMAILS[1]}', 'Older Active', TRUE,  NOW() - INTERVAL '2 days'),
                        ('inactive@datapipelines.test', 'Inactive', FALSE, NOW() - INTERVAL '1 day'),
                        ('${ACTIVE_EMAILS[0]}', 'Newer Active', TRUE,  NOW())
                    """.trimIndent(),
                )
            }
        }

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'tracer-e2e-admin@datapipelines.test', 'E2E Admin', 'test', 'e2e-admin-sub', TRUE, TRUE)
                    """.trimIndent(),
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001')",
                ).use { ps ->
                    for (key in listOf(ADMIN_KEY, READ_ONLY_KEY)) {
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                        ps.setString(3, key.name)
                        ps.setString(4, key.hash)
                        ps.setArray(5, connection.createArrayOf("text", key.scopes))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val REQUESTED_TTL_SECONDS = 120L
        private const val SSE_BUDGET_MINUTES = 2L
        private const val API_KEY_HEADER = "DP-API-Key"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        /** Active emails in the order the template returns them (`created_at DESC`). */
        private val ACTIVE_EMAILS = listOf("newer@datapipelines.test", "older@datapipelines.test")

        /** Set by the walkthrough; read by the 403 test (ordered after it). */
        private var createdPipelineId: String? = null

        private val random = SecureRandom()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-admin-key", arrayOf("admin"))
        private val READ_ONLY_KEY = E2eAuth.generateKey("e2e-read-key", arrayOf("read"))

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        /**
         * The pipeline's SOURCE database: a scratch database on the shared container — its
         * orders/users fixture tables are this suite's own schema, deliberately separate
         * from the migrated metadata database.
         */
        private val source = SharedE2e.scratchDatabase("tracer_source")

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

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

            // Generated per run — no literal secret in any test fixture (HIGH-2).
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

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
