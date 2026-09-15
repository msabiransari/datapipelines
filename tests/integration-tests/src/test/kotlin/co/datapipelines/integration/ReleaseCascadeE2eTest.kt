package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
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
 * 142 — the release cascade end to end, over the REAL wire (the [ReleaseChecksE2eTest]
 * harness: JSON-RPC per MCP leg, RestAssured per REST leg, a real Postgres for the metadata
 * and the audit table, an in-JVM H2 datasource the workspace owns):
 *
 * 1. two templates created over MCP land as DRAFT v1 (D55); pipeline A pins both, pipeline B
 *    pins the first — the shared-object shape;
 * 2. releasing A over REST WITHOUT `release_pinned_templates` is refused
 *    `409 pipeline.release.template_not_released` exactly as before 142 — the first pin at
 *    the top level — and `details.pins_not_released` names BOTH draft pins;
 * 3. **atomicity, falsified:** releasing A WITH the flag but a STALE `If-Match` makes the
 *    pipeline flip fail AFTER the two templates flipped inside the same transaction. The
 *    answer is `409 pipeline.version.conflict`, both templates are still DRAFT, A is still a
 *    draft, and the audit table carries no released event for any of the three — asserted at
 *    the TABLE, because an in-memory fake cannot see a skipped hop;
 * 4. releasing A with the flag and the right hash releases both templates at their pinned
 *    version and A itself, and the audit log holds three events: one
 *    `template.version.released` per template carrying `cascade_from_pipeline_id` /
 *    `cascade_from_version`, then A's `pipeline.version.released` with `templates_released`;
 * 5. B is untouched and now pins a RELEASED version: it releases WITHOUT the flag, its event
 *    carries an empty `templates_released`, and the shared template was released exactly once.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReleaseCascadeE2eTest {
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

    private fun createTemplate(
        id: Int,
        templateId: String,
    ) {
        val (payload, isError) =
            callTool(
                id,
                "templates_create",
                mapOf(
                    "id" to templateId,
                    "dialect" to "H2",
                    "display_name" to "Release cascade (142)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 1 AS n",
                ),
            )
        withClue("templates_create must succeed: $payload") { isError shouldBe false }
        withClue("a create lands a DRAFT (D55): $payload") { payload["status"].asText() shouldBe "DRAFT" }
    }

    private fun createPipeline(
        id: Int,
        name: String,
        templateIds: List<String>,
    ): Pair<String, String> {
        val (created, createError) =
            callTool(
                id,
                "pipelines_create",
                mapOf(
                    "name" to name,
                    "display_name" to "Release cascade (142)",
                    "description" to "Pins draft templates the release will cascade to.",
                    "nodes" to
                        templateIds.mapIndexed { index, templateId ->
                            // At most one node answers the caller (§12): the rest stage to tempdb.
                            val node =
                                mutableMapOf<String, Any?>(
                                    "id" to "n$index",
                                    "type" to "DQL",
                                    "source" to DATASOURCE,
                                    "description" to "One row",
                                    "template" to mapOf("id" to templateId, "version" to 1),
                                    "depends_on" to emptyList<String>(),
                                )
                            if (index < templateIds.lastIndex) node["output"] = mapOf("target" to "tempdb", "table" to "step_$index")
                            node
                        },
                ),
            )
        withClue("pipelines_create must succeed: $created") { createError shouldBe false }
        withClue("a create lands a DRAFT (D55): $created") { created["draft"].isObject shouldBe true }
        return created["id"].asText() to created["draft"]["body_hash"].asText()
    }

    private fun release(
        pipelineId: String,
        hash: String,
        cascade: Boolean,
    ): ValidatableResponse {
        val request =
            given()
                .port(port)
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .header("If-Match", hash)
        if (cascade) request.queryParam("release_pinned_templates", "true")
        return request.`when`().post("/api/v1/pipelines/$pipelineId/release").then()
    }

    private fun templateStatus(templateId: String): String =
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .queryParam("name", templateId)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .path("data.status")

    private fun pipelineStatus(pipelineId: String): String =
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/$pipelineId")
            .then()
            .statusCode(200)
            .extract()
            .path("data.status")

    /** Every audit row of [event] whose details mention [needle], oldest first — read at the TABLE. */
    private fun auditRows(
        event: String,
        needle: String,
    ): List<JsonNode> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val rows =
                    statement.executeQuery(
                        "SELECT details_json FROM audit_log WHERE event = '$event'" +
                            " AND details_json::text LIKE '%$needle%' ORDER BY timestamp ASC",
                    )
                val out = mutableListOf<JsonNode>()
                while (rows.next()) out.add(mapper.readTree(rows.getString("details_json")))
                out
            }
        }

    @Test
    @Order(1)
    fun `the fixture - an owned H2 datasource, two DRAFT templates, pipeline A pinning both and B pinning one`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Release cascade (142)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)

        createTemplate(1, TEMPLATE_ONE)
        createTemplate(2, TEMPLATE_TWO)
        val a = createPipeline(3, PIPELINE_A, listOf(TEMPLATE_ONE, TEMPLATE_TWO))
        pipelineA = a.first
        hashA = a.second
        val b = createPipeline(4, PIPELINE_B, listOf(TEMPLATE_ONE))
        pipelineB = b.first
        hashB = b.second
    }

    @Test
    @Order(2)
    fun `without the flag the release is refused template_not_released, naming both draft pins`() {
        release(pipelineA, hashA, cascade = false)
            .statusCode(409)
            .body("error.code", equalTo("pipeline.release.template_not_released"))
            // The pre-142 shape, byte for byte: the FIRST offending pin at the top level.
            .body("error.details.template_id", equalTo(TEMPLATE_ONE))
            .body("error.details.template_version", equalTo(1))
            .body("error.details.template_status", equalTo("DRAFT"))
            .body("error.message", equalTo("Template '$TEMPLATE_ONE' version 1 is not released; release the template first."))
            // 142: EVERY non-released pin, so a client can decide to retry with the flag.
            .body("error.details.pins_not_released.size()", equalTo(2))
            .body("error.details.pins_not_released.collect { it.template_id }", equalTo(listOf(TEMPLATE_ONE, TEMPLATE_TWO)))
            .body("error.details.pins_not_released.collect { it.template_status }.toSet()", equalTo(setOf("DRAFT")))

        templateStatus(TEMPLATE_ONE) shouldBe "DRAFT"
        templateStatus(TEMPLATE_TWO) shouldBe "DRAFT"
        pipelineStatus(pipelineA) shouldBe "DRAFT"
    }

    @Test
    @Order(3)
    fun `atomicity falsified - a stale hash after the templates flipped rolls everything back and audits nothing`() {
        // The stale hash is the injected failure: the service releases both templates FIRST
        // (inside the metadata transaction), then the pipeline's flip statement matches no
        // row at this hash and throws — and the TransactionTemplate rolls the templates back.
        release(pipelineA, STALE_HASH, cascade = true)
            .statusCode(409)
            .body("error.code", equalTo("pipeline.version.conflict"))
            .body("error.details.current_body_hash", equalTo(hashA))

        withClue("no template is left released with the pipeline still a draft") {
            templateStatus(TEMPLATE_ONE) shouldBe "DRAFT"
            templateStatus(TEMPLATE_TWO) shouldBe "DRAFT"
            pipelineStatus(pipelineA) shouldBe "DRAFT"
        }
        withClue("the audit log carries nothing for a rolled-back cascade") {
            auditRows("template.version.released", TEMPLATE_ONE).size shouldBe 0
            auditRows("template.version.released", TEMPLATE_TWO).size shouldBe 0
            auditRows("pipeline.version.released", pipelineA).size shouldBe 0
        }
    }

    @Test
    @Order(4)
    fun `with the flag both templates release at their pinned version, then the pipeline - three audit events`() {
        release(pipelineA, hashA, cascade = true)
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
            .body("data.version", equalTo(1))

        templateStatus(TEMPLATE_ONE) shouldBe "RELEASED"
        templateStatus(TEMPLATE_TWO) shouldBe "RELEASED"
        pipelineStatus(pipelineA) shouldBe "RELEASED"

        // The released version is the PINNED one — v1 — and the templates' pointers name it.
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .queryParam("name", TEMPLATE_ONE)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .body("data.version", equalTo(1))
            .body("data.status", equalTo("RELEASED"))

        // Three events, asserted at the TABLE: each template's own event reconstructs "who
        // released X v1 and why" alone; the pipeline's names what it cascaded to.
        listOf(TEMPLATE_ONE, TEMPLATE_TWO).forEach { templateId ->
            val events = auditRows("template.version.released", templateId)
            withClue("exactly one template event for $templateId: $events") { events.size shouldBe 1 }
            val details = events.single()
            details["template_id"].asText() shouldBe templateId
            details["version"].asInt() shouldBe 1
            details["via"].asText() shouldBe "api_key"
            details["cascade_from_pipeline_id"].asText() shouldBe pipelineA
            details["cascade_from_version"].asInt() shouldBe 1
        }
        val pipelineEvents = auditRows("pipeline.version.released", pipelineA)
        withClue("exactly one pipeline event: $pipelineEvents") { pipelineEvents.size shouldBe 1 }
        val released = pipelineEvents.single()["templates_released"]
        withClue("the pipeline event lists both cascaded templates: $released") {
            released.map { it["template_id"].asText() to it["version"].asInt() } shouldBe
                listOf(TEMPLATE_ONE to 1, TEMPLATE_TWO to 1)
        }
    }

    @Test
    @Order(5)
    fun `the second draft pipeline now pins a RELEASED version - it releases without the flag, cascading nothing`() {
        pipelineStatus(pipelineB) shouldBe "DRAFT"

        release(pipelineB, hashB, cascade = false)
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))

        val events = auditRows("pipeline.version.released", pipelineB)
        events.size shouldBe 1
        events.single()["templates_released"].size() shouldBe 0
        // The shared template was released ONCE — by A's cascade, never again by B.
        auditRows("template.version.released", TEMPLATE_ONE).size shouldBe 1
    }

    companion object {
        private const val TEMPLATE_ONE = "test/cascade_142_one.sql"
        private const val TEMPLATE_TWO = "test/cascade_142_two.sql"
        private const val PIPELINE_A = "test/cascade_142_a"
        private const val PIPELINE_B = "test/cascade_142_b"
        private const val DATASOURCE = "h2-cascade-142"
        private const val H2_JDBC_URL = "jdbc:h2:mem:cascade142;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val STALE_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        private const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-142-key", arrayOf("read", "execute", "author"))

        private var pipelineA: String = ""
        private var hashA: String = ""
        private var pipelineB: String = ""
        private var hashB: String = ""

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
                            ('$ADMIN_USER_ID', 'e2e-142@datapipelines.test', 'E2E 142', 'test', 'e2e-142-sub', TRUE, TRUE)
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
