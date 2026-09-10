package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
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
import java.math.BigDecimal
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
 * Defect 100 — the guard that would have caught it: an exact-numeric expression whose
 * typmod the source engine drops must stage with its fractions intact.
 *
 * pgjdbc reports `precision=0 scale=0` for `SUM(fare)`, `AVG(fare)` and `fare/2` over a
 * `NUMERIC(10,2)` column — 0 is the driver's "unknown", not a declared integer scale. The
 * pre-fix mapping carried `scale: 0` into the envelope and H2 staged the column as
 * `DECIMAL(100000, 0)`, so 5.09 and 10.99 landed as 5 and 11 and every demo total came
 * back in whole dollars. The fix maps the driver-reported "unknown" to an exact-UNSIZED
 * `BIGDECIMAL` (precision and scale omitted) and stages it as `DECFLOAT(100000)`.
 *
 * The path under test is the real one, end to end: a real Postgres source, the executor's
 * introspection, the H2 staging writer (tempdb), and the result cursor. On the pre-fix
 * code this test is RED with `s` summing to `16`; post-fix the cents survive (`16.08`).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class UnsizedNumericStagingIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `an unsized source numeric stages with its cents`() {
        seedSource()
        seedAuthRows()
        registerDatasource()
        createTemplate()
        val pipelineId = createPipeline()

        val events =
            assertTimeoutPreemptively(EXECUTION_BUDGET) { consumeExecutionStream(pipelineId) }
        events.map { it.first } shouldContainExactly
            listOf(
                "execution_started",
                "node_started",
                "node_completed",
                "node_started",
                "node_completed",
                "pipeline_completed",
                "data_ready",
            )
        val executionId = events.last().second["execution_id"].asText()

        val result =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()

        result.jsonPath().getLong("data.total_rows") shouldBe 2L

        // GROUP BY fare, ORDER BY fare: row 0 is the 5.09 group, row 1 the 10.99 group.
        // Every aggregate keeps its fraction — s sums to 16.08 across the two rows, not 16.
        // (Pre-fix red: the staged DECIMAL(100000, 0) column turned these into 5 / 11.)
        val rows: List<List<Any?>> = result.jsonPath().get("data.rows")
        rows.map { it[0].toString() } shouldBe listOf("5.09", "10.99")
        val sums = rows.map { BigDecimal(it[0].toString()) }
        sums.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal("16.08")) shouldBe 0
        rows.map { BigDecimal(it[1].toString()) }.zip(listOf("5.09", "10.99")).forEach { (actual, expected) ->
            actual.compareTo(BigDecimal(expected)) shouldBe 0
        }
        rows.map { BigDecimal(it[2].toString()) }.zip(listOf("2.545", "5.495")).forEach { (actual, expected) ->
            actual.compareTo(BigDecimal(expected)) shouldBe 0
        }

        // The staged columns are exact (BIGDECIMAL — a JSON string carrying the exact
        // decimal), never approximate. The pre-fix envelope declared scale 0 here; the
        // exact-unsized encoding omits both metadata keys.
        val schema: List<Map<String, Any?>> = result.jsonPath().get("data.schema")
        val byName = schema.associateBy { it["name"] as String }
        listOf("s", "a", "h").forEach { column ->
            byName.getValue(column)["type"] shouldBe "BIGDECIMAL"
            byName.getValue(column).containsKey("precision") shouldBe false
            byName.getValue(column).containsKey("scale") shouldBe false
        }
    }

    /** The source fixture: the defect's exact table, on this suite's scratch database. */
    private fun seedSource() {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (fare NUMERIC(10,2))")
                statement.execute("INSERT INTO t VALUES (5.09), (10.99)")
            }
        }
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-unsized-numeric@datapipelines.test', 'E2E Unsized Numeric', 'test',
                            'e2e-unsized-numeric-sub', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING",
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

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "pg-unsized-numeric", "display_name": "Unsized Numeric Source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /**
     * The defect's exact query shape: SUM / AVG / division over a sized numeric, whose
     * typmod Postgres drops — pgjdbc reports `precision=0 scale=0` for all three.
     */
    private fun createTemplate() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "test/unsized_fares.sql", "dialect": "POSTGRES", "display_name": "Unsized Fares",
                 "description": "Aggregates whose typmod the engine drops.", "imports": [],
                 "body": "SELECT SUM(fare) AS s, AVG(fare) AS a, fare/2 AS h FROM t GROUP BY fare ORDER BY fare"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)

        // The tempdb reader: forces the Postgres aggregates through the H2 staging writer
        // and back out — the hop the truncation lived on. A single-node pipeline streams
        // its result without staging and would not exercise the defect.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "test/unsized_fares_report.sql", "dialect": "H2", "display_name": "Unsized Fares Report",
                 "description": "Reads the staged aggregates back out of tempdb.", "imports": [],
                 "body": "SELECT s, a, h FROM stg_fares ORDER BY s"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(): String {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    """
                    {"schema_version": 1, "name": "test/unsized_numeric", "display_name": "Unsized Numeric",
                     "description": "Stages typmod-less aggregates.", "parameters": {},
                     "settings": {"tempdb": {"engine": "H2"}},
                     "nodes": [
                       {"id": "stage_fares", "description": "Aggregate fares", "type": "DQL",
                        "source": "pg-unsized-numeric", "template": {"id": "test/unsized_fares.sql", "version": 1},
                        "output": {"target": "tempdb", "table": "stg_fares"}, "depends_on": []},
                       {"id": "report", "description": "Read the staged fares back", "type": "DQL",
                        "source": "tempdb", "template": {"id": "test/unsized_fares_report.sql", "version": 1},
                        "output": {"target": "caller"}, "depends_on": ["stage_fares"]}
                     ]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        return response.jsonPath().getString("data.id")
    }

    /** The TracerBullet stream reader: whole body, one JSON `data:` per `event:`. */
    private fun consumeExecutionStream(pipelineId: String): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("DP-Correlation-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return E2eSse.parseEvents(response.body(), mapper)
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"

        private val EXECUTION_BUDGET: Duration = Duration.ofMinutes(2)

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-unsized-numeric-key", arrayOf("read", "execute", "author"))

        private val random = SecureRandom()

        private val postgres get() = SharedE2e.postgres

        /** The pipeline's SOURCE database: this suite's own schema on the shared container. */
        private val source = SharedE2e.scratchDatabase("unsized_numeric_source")

        private val redis get() = SharedE2e.redis

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

            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
