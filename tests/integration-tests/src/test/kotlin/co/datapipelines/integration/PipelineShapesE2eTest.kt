package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.mkammerer.argon2.Argon2Factory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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

@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class PipelineShapesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    // ---------------------------------------------------------------- Test 1: multi-node DAG

    @Test
    fun `multi-node DAG pipeline emits ordered events for all nodes`() {
        ensureAuthSeeded()
        seedSourceUsers()

        val sourceJdbcUrl = "jdbc:postgresql://${source.host}:${source.getMappedPort(POSTGRES_PORT)}/testdb"
        registerDatasource(sourceJdbcUrl)
        createDagTemplates()

        val pipelineId = createDagPipeline()
        val correlationId = UUID.randomUUID().toString()
        val events = consumeExecutionStream(pipelineId, ADMIN_KEY.plaintext, correlationId)

        assertDagEventOrder(events)
        val dataReady = events.last().second
        dataReady["total_rows"].asLong() shouldBe ACTIVE_EMAILS.size.toLong()
    }

    private fun createDagTemplates() {
        createTemplate(
            "dag_step_a.sql",
            "POSTGRES",
            "Step A",
            "SELECT id, email, name, created_at FROM users WHERE is_active = true ORDER BY created_at DESC",
        )
        createTemplate("dag_step_b.sql", "H2", "Step B", "SELECT * FROM step_a")
        createTemplate("dag_step_c.sql", "H2", "Step C", "SELECT * FROM step_b")
    }

    private fun createDagPipeline(): String =
        createPipeline(
            "dag_chain",
            "DAG Chain",
            listOf(
                mapOf(
                    "id" to "step_a",
                    "description" to "Fetch from source",
                    "type" to "DQL",
                    "source" to "pg-local",
                    "template" to mapOf("id" to "dag_step_a.sql", "version" to 1),
                    "output" to mapOf("target" to "tempdb", "table" to "step_a"),
                    "depends_on" to emptyList<String>(),
                ),
                mapOf(
                    "id" to "step_b",
                    "description" to "Read from step_a",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "template" to mapOf("id" to "dag_step_b.sql", "version" to 1),
                    "output" to mapOf("target" to "tempdb", "table" to "step_b"),
                    "depends_on" to listOf("step_a"),
                ),
                mapOf(
                    "id" to "step_c",
                    "description" to "Caller — read from step_b",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "template" to mapOf("id" to "dag_step_c.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to listOf("step_b"),
                ),
            ),
        )

    private fun assertDagEventOrder(events: List<Pair<String, JsonNode>>) {
        events.map { it.first } shouldContainExactly
            listOf(
                "execution_started",
                "node_started",
                "node_completed",
                "node_started",
                "node_completed",
                "node_started",
                "node_completed",
                "pipeline_completed",
                "data_ready",
            )
        val nodeStarted = events.filter { it.first == "node_started" }
        nodeStarted[0].second["node_id"].asText() shouldBe "step_a"
        nodeStarted[1].second["node_id"].asText() shouldBe "step_b"
        nodeStarted[2].second["node_id"].asText() shouldBe "step_c"

        val nodeCompleted = events.filter { it.first == "node_completed" }
        nodeCompleted[0].second["node_id"].asText() shouldBe "step_a"
        nodeCompleted[1].second["node_id"].asText() shouldBe "step_b"
        nodeCompleted[2].second["node_id"].asText() shouldBe "step_c"
    }

    // ---------------------------------------------------------------- Test 2: write-back (zero caller)

    @Test
    fun `write-back pipeline with zero caller nodes completes without data_ready`() {
        ensureAuthSeeded()
        val sourceJdbcUrl = "jdbc:postgresql://${source.host}:${source.getMappedPort(POSTGRES_PORT)}/testdb"
        registerDatasource(sourceJdbcUrl)

        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS writeback_target (id SERIAL PRIMARY KEY, name TEXT NOT NULL, value INT NOT NULL)",
                )
            }
        }

        createTemplate(
            "wb_insert.sql",
            "POSTGRES",
            "Writeback Insert",
            "INSERT INTO writeback_target (name, value) VALUES ('test', 42)",
        )

        val pipelineId =
            createPipeline(
                "writeback",
                "Writeback Pipeline",
                listOf(
                    mapOf(
                        "id" to "insert_data",
                        "description" to "DML insert into writeback table",
                        "type" to "DML",
                        "source" to "pg-local",
                        "template" to mapOf("id" to "wb_insert.sql", "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
            )

        val correlationId = UUID.randomUUID().toString()
        val events = consumeExecutionStream(pipelineId, ADMIN_KEY.plaintext, correlationId)

        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed")

        assertWritebackResult(events.first().second["execution_id"].asText())
    }

    private fun assertWritebackResult(executionId: String) {
        val resultResponse =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        resultResponse.jsonPath().getLong("data.total_rows") shouldBe 0L
        resultResponse.jsonPath().getList<Any>("data.rows") shouldBe emptyList<Any>()
        resultResponse.jsonPath().getList<Any>("data.schema") shouldBe emptyList<Any>()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT result_row_count FROM pipeline_executions WHERE execution_id = '$executionId'")
                    .use { rs ->
                        rs.next() shouldBe true
                        rs.getObject(1) shouldBe null
                    }
            }
        }
    }

    // ---------------------------------------------------------------- Test 3: cancellation

    @Test
    fun `cancellation aborts a running execution with execution_aborted event`() {
        ensureAuthSeeded()
        val sourceJdbcUrl = "jdbc:postgresql://${source.host}:${source.getMappedPort(POSTGRES_PORT)}/testdb"
        registerDatasource(sourceJdbcUrl)
        seedSourceUsers()

        createTemplate(
            "cancel_slow.sql",
            "POSTGRES",
            "Slow Query",
            "SELECT id, email, name, created_at FROM users, pg_sleep($CANCEL_SLEEP_SECONDS)" +
                " WHERE is_active = true ORDER BY created_at DESC",
        )

        val pipelineId =
            createPipeline(
                "cancel_test",
                "Cancel Test",
                listOf(
                    mapOf(
                        "id" to "slow_node",
                        "description" to "Slow caller node",
                        "type" to "DQL",
                        "source" to "pg-local",
                        "template" to mapOf("id" to "cancel_slow.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
            )

        val correlationId = UUID.randomUUID().toString()
        val executionId = executeAndCancel(pipelineId, correlationId)

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$executionId")
            .then()
            .statusCode(200)
            .body("data.status", org.hamcrest.Matchers.equalTo("ABORTED"))

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT status FROM pipeline_executions WHERE execution_id = '$executionId'")
                    .use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "ABORTED"
                    }
            }
        }
    }

    // ---------------------------------------------------------------- Test 4: scope denial

    @Test
    fun `admin scope sees other users executions, read scope does not`() {
        ensureAuthSeeded()
        val sourceJdbcUrl = "jdbc:postgresql://${source.host}:${source.getMappedPort(POSTGRES_PORT)}/testdb"
        registerDatasource(sourceJdbcUrl)
        seedSourceUsers()

        createTemplate(
            "scope_test.sql",
            "POSTGRES",
            "Scope Test Query",
            "SELECT id, email, name, created_at FROM users WHERE is_active = true ORDER BY created_at DESC",
        )

        val pipelineId =
            createPipeline(
                "scope_test",
                "Scope Test Pipeline",
                listOf(
                    mapOf(
                        "id" to "fetch",
                        "description" to "Fetch active users",
                        "type" to "DQL",
                        "source" to "pg-local",
                        "template" to mapOf("id" to "scope_test.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
            )

        val correlationId = UUID.randomUUID().toString()
        val executorEvents = consumeExecutionStream(pipelineId, EXECUTOR_KEY.plaintext, correlationId)
        executorEvents.last().first shouldBe "data_ready"

        val adminListing =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions")
                .then()
                .statusCode(200)
                .extract()
        val adminExecutionIds: List<String> = adminListing.jsonPath().getList("data.items.execution_id")
        (adminExecutionIds.any { it == executorEvents.first().second["execution_id"].asText() }) shouldBe true

        val readerListing =
            given()
                .port(port)
                .header(API_KEY_HEADER, READER_ONLY_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions")
                .then()
                .statusCode(200)
                .extract()
        val readerExecutionIds: List<String> = readerListing.jsonPath().getList("data.items.execution_id")
        (readerExecutionIds.none { it == executorEvents.first().second["execution_id"].asText() }) shouldBe true
    }

    // ------------------------------------------------------------ helpers

    private fun ensureAuthSeeded() {
        synchronized(authLock) {
            if (authSeeded) return
            authSeeded = true
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                for ((userId, email) in listOf(
                    ADMIN_USER_ID to "e2e-admin@datapipelines.test",
                    EXECUTOR_USER_ID to "e2e-executor@datapipelines.test",
                    READER_USER_ID to "e2e-reader@datapipelines.test",
                )) {
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$userId', '$email', '$email', 'test', 'sub-$userId', TRUE, ${userId == ADMIN_USER_ID})
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
            val insertSql =
                "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                    " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING"
            connection.prepareStatement(insertSql).use { ps ->
                val keyUsers =
                    listOf(
                        ADMIN_KEY to ADMIN_USER_ID,
                        EXECUTOR_KEY to EXECUTOR_USER_ID,
                        READER_ONLY_KEY to READER_USER_ID,
                    )
                for ((key, userId) in keyUsers) {
                    ps.setString(1, key.id)
                    ps.setObject(2, UUID.fromString(userId))
                    ps.setString(3, key.name)
                    ps.setString(4, key.hash)
                    ps.setArray(5, connection.createArrayOf("text", key.scopes))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun registerDatasource(sourceJdbcUrl: String) {
        val existing =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/pg-local")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
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
    }

    private fun seedSourceUsers() {
        DriverManager
            .getConnection(
                "jdbc:postgresql://${source.host}:${source.getMappedPort(POSTGRES_PORT)}/testdb",
                source.username,
                source.password,
            ).use { connection ->
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
                    statement.execute(
                        """
                        INSERT INTO users (email, name, is_active, created_at) VALUES
                            ('${ACTIVE_EMAILS[1]}', 'Older Active', TRUE,  NOW() - INTERVAL '2 days'),
                            ('inactive@datapipelines.test', 'Inactive', FALSE, NOW() - INTERVAL '1 day'),
                            ('${ACTIVE_EMAILS[0]}', 'Newer Active', TRUE,  NOW())
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
    }

    private fun createTemplate(
        id: String,
        dialect: String,
        displayName: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "$dialect", "display_name": "$displayName",
                 "description": "Auto-generated template for E2E test", "imports": [],
                 "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val bodyJson =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to displayName,
                    "description" to "E2E test pipeline — $displayName",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(bodyJson)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != 201) {
            val errorBody = response.body().asString()
            throw AssertionError("Pipeline creation failed (status=${response.statusCode()}): body=$bodyJson error=$errorBody")
        }
        return response.jsonPath().getString("data.id")
    }

    /**
     * T2 — the SSE stream is read to EOF, and EOF is the *only* completion signal: nothing here
     * bounds the wait. `SseEmitter` is created with `NEVER_TIMEOUT`, so a regression that never
     * emits a terminal event hangs this thread until the CI job itself is killed — a build that
     * times out at the outer level and reports nothing about which test wedged.
     * [assertTimeoutPreemptively] interrupts the read instead, so the regression FAILS, by name,
     * against a budget an order of magnitude above the real runtime (these executions finish in
     * seconds; the cancellation test's own `pg_sleep` is 15s).
     */
    private fun consumeExecutionStream(
        pipelineId: String,
        apiKey: String,
        correlationId: String,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            readExecutionStream(pipelineId, apiKey, correlationId)
        }

    private fun readExecutionStream(
        pipelineId: String,
        apiKey: String,
        correlationId: String,
    ): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, apiKey)
                .header("DP-Correlation-Id", correlationId)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200

        return E2eSse.parseEvents(response.body(), mapper)
    }

    /**
     * Starts an execution, reads SSE events until `execution_started` to extract the execution id,
     * sends `DELETE /api/v1/executions/{id}` to cancel, then continues reading until the stream
     * closes with `execution_aborted`. Returns the execution id.
     */
    private fun executeAndCancel(
        pipelineId: String,
        correlationId: String,
    ): String =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            readAndCancelExecutionStream(pipelineId, correlationId)
        }

    private fun readAndCancelExecutionStream(
        pipelineId: String,
        correlationId: String,
    ): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("DP-Correlation-Id", correlationId)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val client = HttpClient.newHttpClient()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.statusCode() shouldBe 200

        val reader = BufferedReader(InputStreamReader(response.body()))
        val events = mutableListOf<Pair<String, JsonNode>>()
        var executionId = ""

        reader.use { r -> executionId = readEventsAndCancel(r, events) }

        val eventNames = events.map { it.first }
        executionId shouldNotBe ""
        if (!events.any { it.first == "execution_aborted" }) {
            throw AssertionError("Expected execution_aborted event, got: $eventNames")
        }
        return executionId
    }

    private fun readEventsAndCancel(
        reader: BufferedReader,
        events: MutableList<Pair<String, JsonNode>>,
    ): String {
        var executionId = ""
        var currentEvent: String? = null
        var cancelSent = false
        for (line in reader.lines()) {
            when {
                line.startsWith("event:") -> {
                    currentEvent = line.removePrefix("event:").trim()
                }

                line.startsWith("data:") -> {
                    val payload = mapper.readTree(line.removePrefix("data:").trim())
                    events += (currentEvent ?: "unknown") to payload
                    if (currentEvent == "execution_started" && !cancelSent) {
                        cancelSent = true
                        executionId = payload["execution_id"].asText()
                        cancelExecution(executionId)
                    }
                }
            }
        }
        return executionId
    }

    private fun cancelExecution(executionId: String) {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .delete("/api/v1/executions/$executionId")
            .then()
            .statusCode(204)
    }

    // ---------------------------------------------------------------- companion

    companion object {
        private const val REDIS_PORT = 6379
        private const val POSTGRES_PORT = 5432
        private const val SECRET_BYTES = 32
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val CANCEL_SLEEP_SECONDS = 15

        /**
         * The bound on every SSE read here (T2). Generous against the real runtimes — seconds, and
         * 15s for the cancellation test's `pg_sleep` — so it can only fire on a stream that has
         * genuinely stopped producing, never on a slow container.
         */
        private const val SSE_BUDGET_MINUTES = 2L

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val EXECUTOR_USER_ID: String = UUID.randomUUID().toString()
        private val READER_USER_ID: String = UUID.randomUUID().toString()

        private val ACTIVE_EMAILS = listOf("newer@datapipelines.test", "older@datapipelines.test")

        private val random = SecureRandom()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-admin-key", arrayOf("admin"))
        private val EXECUTOR_KEY = E2eAuth.generateKey("e2e-executor-key", arrayOf("execute"))
        private val READER_ONLY_KEY = E2eAuth.generateKey("e2e-reader-key", arrayOf("read"))

        private var authSeeded = false
        private val authLock = Any()

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        @Container
        @JvmStatic
        private val source =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("testdb")
                .withUsername("postgres")
                .withPassword("postgres")

        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

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
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

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
