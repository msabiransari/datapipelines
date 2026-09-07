package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
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
 * Calculators over the REAL HTTP surface (calculators spec §8, audit B2): a CALCULATOR node's
 * value reaches a downstream DQL node's bind parameter, its stats report `context_key` /
 * `context_value` through `GET /executions/{id}`, a caller-supplied key SKIPS the node with
 * `provided_by: "caller"` (078 A5), and `GET /pipelines/{id}` lists the key as a derived
 * optional parameter.
 *
 * This module drives REST + SSE only — no suite here calls MCP over HTTP (there is no
 * `tools/call` helper anywhere in the module), so the execute/get legs go through
 * `POST /pipelines/{id}/execute`, `GET /executions/{id}` and `GET /pipelines/{id}` exactly as
 * PipelineCompositionE2eTest does. The datasource is an in-memory H2 — the app runs in this
 * JVM, so the test seeds it over the same JDBC URL the registered datasource uses
 * (`DB_CLOSE_DELAY=-1` keeps it alive across the app's pooled connections).
 *
 * The fixture is rigged so a wrong value cannot pass: `fiscal_quarter(2026-08-14, "09-15")`
 * computes 4 where the calendar quarter is 3, and the caller-supplied override is 1 — three
 * distinct quarter values, each with its own rows.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CalculatorSurfaceE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `executing without the key computes the value and reports it in node_stats`() {
        seedAuthRows()
        seedH2()
        registerH2Datasource()
        createTemplate(
            "test/calc_surface_rows.sql",
            "H2",
            "Calculator Surface Rows",
            "SELECT id, label FROM calc_quarters WHERE quarter = :run_fiscal_quarter ORDER BY id",
        )
        pipelineId =
            createPipeline(
                "test/calc_surface",
                "Calculator Surface",
                listOf(
                    mapOf(
                        "id" to "fq",
                        "description" to "Fiscal quarter of a fixed date, 09-15 start",
                        "type" to "CALCULATOR",
                        "kind" to "fiscal_quarter",
                        // A literal date, never "$current_date": a test whose expectation depends
                        // on the day it runs gets deleted (CalculatorNodeExecutionTest's lesson).
                        "inputs" to mapOf("date" to AS_OF, "fiscal_start" to "09-15"),
                        "context_key" to CONTEXT_KEY,
                        "depends_on" to emptyList<String>(),
                    ),
                    mapOf(
                        "id" to "fetch",
                        "description" to "Rows of the run quarter",
                        "type" to "DQL",
                        "source" to H2_DATASOURCE,
                        "template" to mapOf("id" to "test/calc_surface_rows.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to listOf("fq"),
                    ),
                ),
            )

        val events = execute(pipelineId(), emptyMap())
        events.last().first shouldBe "data_ready"
        val executionId = events.last().second["execution_id"].asText()

        // The computed value reached the downstream bind: 2026-08-14 on a 09-15 fiscal start
        // is quarter 4 — the calendar quarter is 3, so this could not have come from anywhere
        // but the calculator.
        assertResultRows(executionId, QUARTER_FOUR_ROWS)

        val fq = nodeStats(executionId).single { it["node_id"].asText() == "fq" }
        fq["status"].asText() shouldBe "SUCCESS"
        fq["context_key"].asText() shouldBe CONTEXT_KEY
        fq["context_value"].asText() shouldBe "4"
        fq["provided_by"] shouldBe null
    }

    @Test
    @Order(2)
    fun `supplying the calculator key skips the node and binds the supplied value`() {
        val events = execute(pipelineId(), mapOf(CONTEXT_KEY to SUPPLIED_QUARTER))
        events.last().first shouldBe "data_ready"
        val executionId = events.last().second["execution_id"].asText()

        // The SUPPLIED value drove the query: quarter-1 rows, not the computed quarter-4 rows.
        assertResultRows(executionId, QUARTER_ONE_ROWS)

        val fq = nodeStats(executionId).single { it["node_id"].asText() == "fq" }
        fq["status"].asText() shouldBe "SUCCESS"
        fq["provided_by"].asText() shouldBe "caller"
        fq["context_value"].asText() shouldBe SUPPLIED_QUARTER.toString()
    }

    @Test
    @Order(3)
    fun `the pipeline get lists the calculator key as a derived optional parameter`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/${pipelineId()}")
            .then()
            .statusCode(200)
            .body("data.parameters.$CONTEXT_KEY.type", org.hamcrest.Matchers.equalTo("INTEGER"))
            .body("data.parameters.$CONTEXT_KEY.required", org.hamcrest.Matchers.equalTo(false))
            .body("data.parameters.$CONTEXT_KEY.derived", org.hamcrest.Matchers.equalTo(true))
    }

    // ------------------------------------------------------------ helpers

    private fun pipelineId(): String = requireNotNull(pipelineId) { "Order(1) creates the pipeline this leg reads" }

    /** `GET /executions/{id}` → `data.node_stats`, parsed — the REST surface, not the DB. */
    private fun nodeStats(executionId: String): List<JsonNode> {
        val body =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        return mapper.readTree(body)["data"]["node_stats"].toList()
    }

    /** The execution's result rows equal [expected] `(id, label)` pairs, in `id` order. */
    private fun assertResultRows(
        executionId: String,
        expected: List<Pair<Int, String>>,
    ) {
        val resultResponse =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        resultResponse.jsonPath().getLong("data.total_rows") shouldBe expected.size.toLong()
        val rows: List<List<Any?>> = resultResponse.jsonPath().get("data.rows")
        rows.map { (it[0] as Number).toInt() } shouldContainExactly expected.map { it.first }
        rows.map { it[1] } shouldContainExactly expected.map { it.second }
    }

    /** Executes via the SSE endpoint, reading the stream to EOF (the completion signal). */
    private fun execute(
        pipelineId: String,
        parameters: Map<String, Any>,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("parameters" to parameters))))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun registerH2Datasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Calculator H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
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
                 "description": "Calculator surface E2E template", "imports": [],
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
                    "description" to "Calculator surface E2E pipeline",
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
            throw AssertionError("Pipeline creation failed (status=${response.statusCode()}): ${response.body().asString()}")
        }
        return response.jsonPath().getString("data.id")
    }

    /** The H2 seed runs before datasource registration: first connection creates the database. */
    private fun seedH2() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE calc_quarters (id INT PRIMARY KEY, label VARCHAR(255) NOT NULL, quarter INT NOT NULL)",
                )
                QUARTER_FOUR_ROWS.forEach { (id, label) ->
                    statement.execute("INSERT INTO calc_quarters (id, label, quarter) VALUES ($id, '$label', 4)")
                }
                QUARTER_ONE_ROWS.forEach { (id, label) ->
                    statement.execute("INSERT INTO calc_quarters (id, label, quarter) VALUES ($id, '$label', 1)")
                }
            }
        }
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-calc-surface@datapipelines.test', 'E2E Calculator Surface',
                            'test', 'e2e-calc-sub', TRUE, TRUE)
                    """.trimIndent(),
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001')",
                ).use { ps ->
                    ps.setString(1, ADMIN_KEY.id)
                    ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(3, ADMIN_KEY.name)
                    ps.setString(4, ADMIN_KEY.hash)
                    ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                    ps.executeUpdate()
                }
        }
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val SSE_BUDGET_MINUTES = 2L
        private const val API_KEY_HEADER = "DP-API-Key"

        private const val H2_DATASOURCE = "h2-calc"
        private const val H2_JDBC_URL = "jdbc:h2:mem:calcdb;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private const val CONTEXT_KEY = "run_fiscal_quarter"

        /** Fixed, never "today": 2026-08-14 on a 09-15 fiscal start is quarter 4, not 3. */
        private const val AS_OF = "2026-08-14"

        /** The caller override (078 A5) — deliberately not the computed 4, so the proof is observable. */
        private const val SUPPLIED_QUARTER = 1

        /** `calc_quarters` rows by quarter, in `id` order — computed and supplied disagree by design. */
        private val QUARTER_FOUR_ROWS = listOf(1 to "q4-alpha", 2 to "q4-beta")
        private val QUARTER_ONE_ROWS = listOf(3 to "q1-gamma")

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        private val random = SecureRandom()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-calc-surface-key", arrayOf("admin"))

        /** Set by Order(1), read by the later legs — the composition suite's pattern. */
        private var pipelineId: String? = null

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

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
