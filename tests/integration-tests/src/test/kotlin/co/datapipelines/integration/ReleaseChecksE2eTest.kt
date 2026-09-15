package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
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
import java.util.Base64
import java.util.UUID

/**
 * 140 — release checks end to end, over the REAL wire (the [McpSaveWorkspaceDatasourceE2eTest]
 * harness: JSON-RPC per MCP leg, RestAssured per REST leg, an in-JVM H2 datasource the
 * workspace owns):
 *
 * 1. a pipeline created over MCP carrying FIVE checks — value-with-tolerance, range, rows,
 *    a parameter-bound rows check (proves `:name` binds from declared parameters), and a
 *    two-column value check — passes §12.11 save validation;
 * 2. `pipelines_run_checks` over MCP returns one run per check with the SERVER's `observed`
 *    values: the three clean shapes `pass`, the bound check `pass`, the two-column one
 *    `error` with the reason named — the agent never supplied an observed value anywhere;
 * 3. `pipelines_update` breaks one expectation (the value check's expected moves to a wrong
 *    number) → the next run is `fail` for it, `error` for the two-column one;
 * 4. release over REST is refused `409 pipeline.check.failed`, `details.checks` naming BOTH
 *    failing checks with expected and observed;
 * 5. release with `override_checks_reason` (≥ 10 chars) releases, and the
 *    `pipeline.version.released` audit row carries `checks_overridden` and
 *    `override_reason` — asserted at the TABLE, because an in-memory fake cannot see a
 *    skipped audit hop;
 * 6. the version's latest-run read (`GET …/checks`) then shows `via = release` runs;
 * 7. a check-less pipeline releases exactly as before (checks are opt-in).
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReleaseChecksE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val http: HttpClient = HttpClient.newHttpClient()

    /** One JSON-RPC call over /mcp with the seeded API key; the parsed response tree back. */
    private fun mcp(
        id: Int,
        method: String,
        params: Map<String, Any?>,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $method: ${response.body()}") { body.has("error") shouldBe false }
        return body["result"]
    }

    /** A tools/call result: the single text block, parsed back into JSON (the tool payload). */
    private fun callTool(
        id: Int,
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val result = mcp(id, "tools/call", mapOf("name" to name, "arguments" to arguments))
        val payload = mapper.readTree(result["content"][0]["text"].asText())
        return payload to result.path("isError").asBoolean(false)
    }

    @Test
    @Order(1)
    fun `the fixture - an owned H2 datasource, a template, and the pipeline with five checks`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Release checks (140)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)

        val (template, templateError) =
            callTool(
                1,
                "templates_create",
                mapOf(
                    "id" to TEMPLATE_ID,
                    "dialect" to "H2",
                    "display_name" to "Release checks (140)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 1 AS n",
                ),
            )
        withClue("template create must succeed: $template") { templateError shouldBe false }

        // 101/D58: templates lock first — the pipeline's release needs this pin RELEASED.
        val (templateRead, readError) = callTool(9, "templates_get", mapOf("id" to TEMPLATE_ID))
        withClue("templates_get must succeed: $templateRead") { readError shouldBe false }
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .header("If-Match", templateRead["body_hash"].asText())
            .contentType(ContentType.JSON)
            .body("""{"name": "$TEMPLATE_ID"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)

        val (created, createError) =
            callTool(2, "pipelines_create", pipelineArguments(checks(CHECK_VALUE_EXPECTED)))
        withClue("pipelines_create with checks must succeed: $created") { createError shouldBe false }
        withClue("a create lands a DRAFT (D55): $created") { created["draft"].isObject shouldBe true }
        pipelineId = created["id"].asText()
        draftHash = created["draft"]["body_hash"].asText()
        version = created["draft"]["version"].asInt()
    }

    @Test
    @Order(2)
    fun `pipelines_run_checks - the server's observed values, pass and error verdicts`() {
        val (payload, isError) = callTool(3, "pipelines_run_checks", mapOf("id" to pipelineId))

        withClue("run_checks must succeed: $payload") { isError shouldBe false }
        payload["version"].asInt() shouldBe version
        val runs = payload["runs"].associateBy { it["check_id"].asText() }
        runs.size shouldBe 5
        withClue("value-with-tolerance passes with the server's observed: $runs") {
            runs.getValue("share_matches")["verdict"].asText() shouldBe "pass"
            runs.getValue("share_matches")["observed"].asText().toDouble() shouldBe 74.62
        }
        runs.getValue("count_in_range")["verdict"].asText() shouldBe "pass"
        runs.getValue("three_rows")["verdict"].asText() shouldBe "pass"
        withClue("the bound check binds the declared parameter's default: $runs") {
            runs.getValue("bound_threshold")["verdict"].asText() shouldBe "pass"
        }
        withClue("two columns for a value check is error, with the reason named: $runs") {
            runs.getValue("two_columns")["verdict"].asText() shouldBe "error"
            runs
                .getValue("two_columns")["message"]
                .asText()
                .lowercase()
                .contains("column") shouldBe true
        }
    }

    @Test
    @Order(3)
    fun `a broken expectation turns the value check fail on the next run`() {
        val (updated, updateError) =
            callTool(
                4,
                "pipelines_update",
                pipelineArguments(checks(CHECK_VALUE_BROKEN)) +
                    mapOf("id" to pipelineId, "expected_hash" to draftHash),
            )
        withClue("pipelines_update must succeed: $updated") { updateError shouldBe false }
        draftHash = updated["draft"]["body_hash"].asText()

        val (payload, isError) = callTool(5, "pipelines_run_checks", mapOf("id" to pipelineId))
        withClue("run_checks must succeed: $payload") { isError shouldBe false }
        val runs = payload["runs"].associateBy { it["check_id"].asText() }
        withClue("the broken expectation is a fail, not an error: $runs") {
            runs.getValue("share_matches")["verdict"].asText() shouldBe "fail"
            runs.getValue("share_matches")["observed"].asText().toDouble() shouldBe 74.62
        }
    }

    @Test
    @Order(4)
    fun `release over REST is refused pipeline check failed with every failing check in details`() {
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .header("If-Match", draftHash)
            .`when`()
            .post("/api/v1/pipelines/$pipelineId/release")
            .then()
            .statusCode(409)
            .body("error.code", equalTo("pipeline.check.failed"))
            .body("error.details.checks.size()", equalTo(2))
            .body(
                "error.details.checks.collect { it.check_id }.toSet()",
                equalTo(setOf("share_matches", "two_columns")),
            ).body("error.details.checks.find { it.check_id == 'share_matches' }.observed", equalTo("74.62"))

        // And the draft is still a draft: the flip never happened.
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/$pipelineId")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("DRAFT"))
    }

    @Test
    @Order(5)
    fun `release with override_checks_reason releases - and the audit row carries the ids and the reason`() {
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .header("If-Match", draftHash)
            .queryParam("override_checks_reason", OVERRIDE_REASON)
            .`when`()
            .post("/api/v1/pipelines/$pipelineId/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))

        // Asserted at the TABLE, not through a fake: the release's audit event carries the
        // overridden ids and the reason, and the gate's own runs are recorded via=release.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val audit =
                    statement.executeQuery(
                        "SELECT event, details_json FROM audit_log" +
                            " WHERE event = 'pipeline.version.released'" +
                            " AND details_json::text LIKE '%$pipelineId%' ORDER BY timestamp DESC LIMIT 1",
                    )
                withClue("the release is audited") { audit.next() shouldBe true }
                val details = audit.getString("details_json")
                withClue("the audit carries the overridden ids and the reason: $details") {
                    details.contains("checks_overridden") shouldBe true
                    details.contains("share_matches") shouldBe true
                    details.contains("two_columns") shouldBe true
                    details.contains(OVERRIDE_REASON) shouldBe true
                }
                val gateRuns =
                    statement.executeQuery(
                        "SELECT check_id, verdict, via FROM pipeline_check_runs" +
                            " WHERE pipeline_id = '$pipelineId' AND version = $version AND via = 'release'",
                    )
                val seen = mutableMapOf<String, String>()
                while (gateRuns.next()) {
                    seen[gateRuns.getString("check_id")] = gateRuns.getString("verdict")
                }
                withClue("the gate's fresh run is recorded via=release: $seen") {
                    seen["share_matches"] shouldBe "fail"
                    seen["two_columns"] shouldBe "error"
                    seen["count_in_range"] shouldBe "pass"
                }
            }
        }
    }

    @Test
    @Order(6)
    fun `the latest-run read shows the release runs beside every definition`() {
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/$pipelineId/versions/$version/checks")
            .then()
            .statusCode(200)
            .body("data.checks.size()", equalTo(5))
            .body("data.checks.find { it.check_id == 'share_matches' }.latest_run.verdict", equalTo("fail"))
            .body("data.checks.find { it.check_id == 'share_matches' }.latest_run.via", equalTo("release"))
            .body("data.checks.find { it.check_id == 'three_rows' }.latest_run.observed.rows", equalTo(3))
    }

    @Test
    @Order(7)
    fun `a check-less pipeline releases exactly as before - checks are opt-in`() {
        val (created, createError) =
            callTool(6, "pipelines_create", pipelineArguments(emptyList(), name = "test/checks_140_plain"))
        withClue("plain create must succeed: $created") { createError shouldBe false }
        val plainId = created["id"].asText()
        val plainHash = created["draft"]["body_hash"].asText()

        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .header("If-Match", plainHash)
            .`when`()
            .post("/api/v1/pipelines/$plainId/release")
            .then()
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))

        // The gate never ran: no via=release rows for the plain pipeline.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val rows =
                    statement.executeQuery(
                        "SELECT count(*) FROM pipeline_check_runs WHERE pipeline_id = '$plainId'",
                    )
                rows.next()
                rows.getLong(1) shouldBe 0L
            }
        }
    }

    private fun checks(valueExpected: Double): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "id" to "share_matches",
                "name" to "Rideshare share from the raw rollup",
                "datasource" to DATASOURCE,
                "sql" to "SELECT 74.62 AS share",
                "expected" to mapOf("kind" to "value", "value" to valueExpected, "tolerance" to 0.01),
            ),
            mapOf(
                "id" to "count_in_range",
                "name" to "Zone count in the expected band",
                "datasource" to DATASOURCE,
                "sql" to "SELECT 5 AS n",
                "expected" to mapOf("kind" to "range", "min" to 1, "max" to 10),
            ),
            mapOf(
                "id" to "three_rows",
                "name" to "The calendar spine has three rows",
                "datasource" to DATASOURCE,
                "sql" to "SELECT 1 AS d UNION ALL SELECT 2 UNION ALL SELECT 3",
                "expected" to mapOf("kind" to "rows", "rows" to 3),
            ),
            mapOf(
                "id" to "bound_threshold",
                "name" to "The threshold parameter binds",
                "datasource" to DATASOURCE,
                "sql" to "SELECT 42 AS n WHERE :threshold = 40",
                "expected" to mapOf("kind" to "rows", "rows" to 1),
            ),
            mapOf(
                "id" to "two_columns",
                "name" to "A value check whose SQL returns two columns",
                "datasource" to DATASOURCE,
                "sql" to "SELECT 1 AS a, 2 AS b",
                "expected" to mapOf("kind" to "value", "value" to 1),
            ),
        )

    private fun pipelineArguments(
        checks: List<Map<String, Any?>>,
        name: String = PIPELINE,
    ): Map<String, Any?> =
        mutableMapOf<String, Any?>(
            "name" to name,
            "display_name" to "Release checks (140)",
            "description" to "Carries the release checks this lane gates on.",
            "parameters" to
                mapOf(
                    "threshold" to mapOf("type" to "INTEGER", "required" to false, "default" to 40),
                ),
            "nodes" to
                listOf(
                    mapOf(
                        "id" to "rows",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "description" to "One row",
                        "template" to mapOf("id" to TEMPLATE_ID, "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
        ).also { args ->
            if (checks.isNotEmpty()) args["checks"] = checks
        }

    companion object {
        private const val TEMPLATE_ID = "test/checks_140.sql"
        private const val PIPELINE = "test/checks_140"
        private const val DATASOURCE = "h2-checks-140"
        private const val H2_JDBC_URL = "jdbc:h2:mem:checks140;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val CHECK_VALUE_EXPECTED = 74.62
        private const val CHECK_VALUE_BROKEN = 1.0
        private const val OVERRIDE_REASON = "Rollup lags one day; verified by hand against the source."

        private const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-140-key", arrayOf("read", "execute", "author"))

        private var pipelineId: String = ""
        private var draftHash: String = ""
        private var version: Int = 0

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val random = SecureRandom()
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
            registry.add("datapipelines.jwt.secret") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @BeforeAll
        fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ADMIN_USER_ID', 'e2e-140@datapipelines.test', 'E2E 140', 'test', 'e2e-140-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, ADMIN_KEY.id)
                        ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                        ps.setString(3, ADMIN_KEY.name)
                        ps.setString(4, ADMIN_KEY.hash)
                        ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                        ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                        ps.executeUpdate()
                    }
            }
        }
    }
}
