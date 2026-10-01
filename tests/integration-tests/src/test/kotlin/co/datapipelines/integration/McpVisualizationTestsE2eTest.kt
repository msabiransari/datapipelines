package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.DriverManager
import java.util.UUID
import javax.imageio.ImageIO

/**
 * **The two visualization test tools through `/mcp` with a real key (#353, L4b)** — what an agent runs, on the real
 * transport and the real dispatcher (the catalog row, the key role, the audit row): `docs_get {"name":"dashboards"}`
 * serves the test loop; `visualizations_test_start` answers the preview URL the agent's browser opens with no key;
 * `visualizations_test_submit` answers GREEN and the single-use upload, which the agent POSTs over plain HTTP — and
 * the MCP key itself is refused there (an `mcp` key reaches `/mcp` only). A promoter key is refused by its role; a
 * revoked key's submit is the transport's refusal, and its session's preview dies with it (the starter re-check); a
 * session user cannot submit a key's session (a session is its starter's).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpVisualizationTestsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private var rpc = 0

    private lateinit var id: String

    @Test
    @Order(1)
    fun `the manual's dashboards guide serves the test loop through docs_get`() {
        val (payload, isError) = callTool(AUTHOR_KEY.plaintext, "docs_get", mapOf("name" to "dashboards"))
        isError shouldBe false
        val text = payload.toString()
        text shouldContain "Proving a visualization"
        text shouldContain "visualizations_test_start"
        text shouldContain "visualizations_test_submit"
        text shouldContain "Upload one screenshot"
    }

    @Test
    @Order(2)
    fun `start, preview, submit and upload - the whole loop an agent runs, the key used only on mcp`() {
        val (created, createError) = callTool(AUTHOR_KEY.plaintext, "visualizations_create", DOCUMENT)
        withClue(created.toString()) { createError shouldBe false }
        id = created["id"].asText()

        val (started, startError) = callTool(AUTHOR_KEY.plaintext, "visualizations_test_start", mapOf("id" to id))
        withClue(started.toString()) { startError shouldBe false }
        started["cases"].map { it.asText() } shouldBe listOf("one month")
        val previewUrl = started["preview_url"].asText()
        previewUrl shouldStartWith "http://localhost:8080/visualizations/$id/preview?session="
        val page = given().port(port).get(local(previewUrl))
        withClue(page.asString().take(EXCERPT)) { page.statusCode shouldBe 200 }

        val sessionId = started["session_id"].asText()
        // A session user — even this key's creator — cannot submit the key's session: a session is its starter's.
        val asPerson =
            given()
                .port(port)
                .asSession(CREATOR)
                .contentType(ContentType.JSON)
                .body("""{"cases":[{"name":"one month","verdict":"green"}]}""")
                .post("/api/v1/visualizations/$id/tests/sessions/$sessionId/results")
        asPerson.statusCode shouldBe 404
        asPerson.jsonPath().getString("error.code") shouldBe "visualization.test.session_not_found"

        val (submitted, submitError) =
            callTool(
                AUTHOR_KEY.plaintext,
                "visualizations_test_submit",
                mapOf(
                    "id" to id,
                    "session_id" to sessionId,
                    "cases" to listOf(mapOf("name" to "one month", "verdict" to "green", "notes" to "one bar")),
                    "environment" to mapOf("browser" to "chromium", "theme" to "light"),
                ),
            )
        withClue(submitted.toString()) { submitError shouldBe false }
        submitted["status"].asText() shouldBe "GREEN"
        submitted["mechanical"]["ok"].asBoolean() shouldBe true
        val upload = submitted["upload"]
        upload["header"].asText() shouldBe "DP-Upload-Token"

        // The MCP key is NOT a credential on the upload route (an `mcp` key reaches `/mcp` only) …
        val withKey =
            uploadRequest(
                local(upload["url"].asText()),
                upload["token"].asText(),
            ).header("DP-API-Key", AUTHOR_KEY.plaintext).post()
        withKey.statusCode shouldBe 403
        withKey.jsonPath().getString("error.details.reason") shouldBe "mcp_key_off_surface"
        // … the upload capability alone is.
        val stored = uploadRequest(local(upload["url"].asText()), upload["token"].asText()).post()
        withClue(stored.asString().take(EXCERPT)) { stored.statusCode shouldBe 201 }
        // Both tool calls wrote the audit row the dispatcher writes for a mutating tool (polled: the sink may batch).
        val writes =
            "SELECT count(*) FROM audit_log WHERE event = 'mcp.tool.write' AND key_id = '${AUTHOR_KEY.id}'" +
                " AND details_json ->> 'tool' IN ('visualizations_test_start', 'visualizations_test_submit')"
        val deadline = System.currentTimeMillis() + AUDIT_WAIT_MS
        while (scalar(writes) != "2" && System.currentTimeMillis() < deadline) Thread.onSpinWait()
        scalar(writes) shouldBe "2"
    }

    @Test
    @Order(3)
    fun `a promoter key is refused by its role on both tools`() {
        listOf(
            "visualizations_test_start" to mapOf("id" to id),
            "visualizations_test_submit" to mapOf("id" to id, "session_id" to UUID.randomUUID().toString(), "cases" to emptyList<Any>()),
        ).forEach { (tool, args) ->
            val (payload, isError) = callTool(PROMOTER_KEY.plaintext, tool, args)
            withClue("$tool $payload") {
                isError shouldBe true
                payload["error"]["code"].asText() shouldBe "auth.role_required"
            }
        }
    }

    @Test
    @Order(4)
    fun `a revoked key - its submit is the key refusal, and its open session's preview dies with it`() {
        val (started, _) = callTool(REVOKED_KEY.plaintext, "visualizations_test_start", mapOf("id" to id))
        val previewUrl = local(started["preview_url"].asText())
        given().port(port).get(previewUrl).statusCode shouldBe 200

        val revoked = given().port(port).asSession(CREATOR).delete("/api/v1/auth/api-keys/${REVOKED_KEY.id}")
        revoked.statusCode shouldBe 204

        val submit =
            raw(
                REVOKED_KEY.plaintext,
                "tools/call",
                mapOf(
                    "name" to "visualizations_test_submit",
                    "arguments" to
                        mapOf(
                            "id" to id,
                            "session_id" to started["session_id"].asText(),
                            "cases" to listOf(mapOf("name" to "one month", "verdict" to "green")),
                        ),
                ),
            )
        submit.statusCode shouldBe 401
        submit.jsonPath().getString("error.code") shouldBe "auth.api_key.invalid"
        // DECISION 3: the starter is the key's identity, deactivated by the revocation — the capability is void.
        val dead = given().port(port).get(previewUrl)
        dead.statusCode shouldBe 404
        dead.asString() shouldBe given().port(port).get("/visualizations/$id/preview?session=AAAA").asString()
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private fun local(url: String): String = url.removePrefix("http://localhost:8080")

    private fun uploadRequest(
        path: String,
        token: String,
    ) = UploadRequest(path, token)

    private inner class UploadRequest(
        private val path: String,
        private val token: String,
    ) {
        private val headers = mutableMapOf("DP-Upload-Token" to token)

        fun header(
            name: String,
            value: String,
        ): UploadRequest = also { headers[name] = value }

        fun post(): Response =
            given()
                .port(port)
                .headers(headers.toMap())
                .contentType("image/png")
                .body(png())
                .post(path)
    }

    private fun raw(
        key: String,
        method: String,
        params: Map<String, Any?>,
    ): Response =
        given()
            .port(port)
            .header("DP-API-Key", key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body(mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to ++rpc, "method" to method, "params" to params)))
            .post("/mcp")

    private fun callTool(
        key: String,
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val response = raw(key, "tools/call", mapOf("name" to name, "arguments" to arguments))
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        val body =
            mapper.readTree(
                response
                    .asString()
                    .substringAfter("data:")
                    .trim()
                    .ifEmpty { response.asString() },
            )
        withClue("JSON-RPC error: $body") { body.has("error") shouldBe false }
        val result = body["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun png(): ByteArray {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            firstColumn(connection.createStatement().executeQuery(query))
        }

    /** The first row's first column, or null — the result set closed with its statement. */
    private fun firstColumn(rs: java.sql.ResultSet): String? = rs.use { if (it.next()) it.getString(1) else null }

    private companion object {
        const val EXCERPT = 800
        const val WORKSPACE = "mt-e2e"
        const val AUDIT_WAIT_MS = 5_000L

        val DOCUMENT: Map<String, Any?> =
            ObjectMapper().readValue(
                """
                {"name": "test/charts/mcp_evidence", "display_name": "MCP evidence", "description": "",
                 "renderer": {"kind": "plotly", "version": "4"},
                 "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                    {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
                 "config": {"data": [{"type": "bar", "x": [], "y": []}]},
                 "bindings": {"data[0].x": "month", "data[0].y": "amount"},
                 "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                      "assertions": [{"kind": "rendered"}]}]}}
                """.trimIndent(),
                object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {},
            )

        val WORKSPACE_ID = UUID.randomUUID().toString()
        val CREATOR_ID = UUID.randomUUID().toString()
        val AUTHOR_KEY = E2eAuth.generateKey("mt-author-key")
        val PROMOTER_KEY = E2eAuth.generateKey("mt-promoter-key")
        val REVOKED_KEY = E2eAuth.generateKey("mt-revoked-key")

        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        val CREATOR get() = E2eSession.jwt(JWT_SECRET, CREATOR_ID, "mt-creator@e2e.test", WORKSPACE)

        val postgres get() = SharedE2e.postgres
        val redis get() = SharedE2e.redis
        val oidc = OidcDiscoveryStub()

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
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { st ->
                    st.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'MCP tests E2E')")
                    st.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$CREATOR_ID', 'mt-creator@e2e.test', 'MT Creator', 'test', 'mt-creator-sub', TRUE, FALSE)",
                    )
                    st.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES " +
                            "('$WORKSPACE_ID', '$CREATOR_ID', 'workspace_admin')",
                    )
                }
                listOf(AUTHOR_KEY to "author", PROMOTER_KEY to "promoter", REVOKED_KEY to "author").forEach { (key, role) ->
                    val identity = UUID.randomUUID()
                    connection.createStatement().use { st ->
                        st.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                                "('$identity', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}', " +
                                "TRUE, FALSE, 'service')",
                        )
                    }
                    connection
                        .prepareStatement(
                            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                                " VALUES (?, ?, ?, ?, ?, ?, 'mcp', ?)",
                        ).use { ps ->
                            ps.setString(1, key.id)
                            ps.setObject(2, identity)
                            ps.setObject(3, UUID.fromString(CREATOR_ID))
                            ps.setString(4, key.name)
                            ps.setString(5, key.hash)
                            ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                            ps.setString(7, role)
                            ps.executeUpdate()
                        }
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun closeOidc() {
            oidc.close()
        }
    }
}
