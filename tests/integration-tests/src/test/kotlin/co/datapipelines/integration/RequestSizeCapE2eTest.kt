package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID

/**
 * #279 on the WIRE: the platform's request-body cap (pipeline-contract §13.21) refuses a body
 * one byte over `datapipelines.web.max-request-bytes` with the §4.2 envelope — `413
 * request.body_too_large`, `details.limit_bytes` — on BOTH JSON surfaces, REST `/api/v1` and
 * MCP `/mcp` (a real `tools/call` body), while a request within the cap still gets its normal
 * answer on both. The cap runs at its documented FLOOR (§3.31's 65 536) so the over-cap bodies
 * are built, not streamed; a within-cap create still succeeds against it.
 *
 * The chunked arm drives `POST /api/v1/templates` with NO `Content-Length` (`Transfer-Encoding:
 * chunked` via java.net.http) past the cap: the counting wrapper cuts the read at the byte past
 * the cap and the advice answers the same 413 envelope — the path a declared-length header
 * cannot cover.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RequestSizeCapE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a body over the cap is 413 on rest and on mcp - a body within it gets the normal answer`() {
        ensureSeeded()

        // REST, over the cap: refused at the filter, the catalog's envelope, before any handler.
        val restOver = restPost(TEMPLATE_BODY_OVER_CAP)
        restOver.statusCode() shouldBe 413
        restOver.body().asString() shouldContain """"code":"request.body_too_large""""
        restOver.body().asString() shouldContain """"limit_bytes":$CAP"""

        // REST, within the cap: the same route answers normally (the valid create, 201).
        val restWithin = restPost(validTemplateBody())
        restWithin.statusCode() shouldBe 201

        // MCP, over the cap: a real tools/call body, refused by the same filter before the SDK parses.
        val mcpOver =
            mcpCall(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                    """"params":{"name":"executions_list","arguments":{"limit":200,"_pad":"$MCP_PAD"}}}""",
            )
        mcpOver.statusCode() shouldBe 413
        mcpOver.body().asString() shouldContain """"code":"request.body_too_large""""

        // MCP, within the cap: the transport's mapper (the constrained one) parses normally.
        val mcpWithin = mcpCall("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
        mcpWithin.statusCode() shouldBe 200
    }

    @Test
    fun `a chunked body over the cap is cut at the cap and answered with the same envelope`() {
        ensureSeeded()

        val bytes = TEMPLATE_BODY_OVER_CAP.toByteArray()
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:$port/api/v1/templates"))
                .header("Content-Type", "application/json")
                .header("Cookie", E2eSession.cookieHeader(jwt()))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                // An InputStream publisher of unknown length => Transfer-Encoding: chunked.
                .POST(HttpRequest.BodyPublishers.ofInputStream { bytes.inputStream() })
                .build()

        val answer = client.send(request, HttpResponse.BodyHandlers.ofString())

        answer.statusCode() shouldBe 413
        answer.body() shouldContain """"code":"request.body_too_large""""
        answer.body() shouldContain """"limit_bytes":$CAP"""
    }

    private fun jwt(): String = E2eSession.jwt(jwtSecret, authorId.toString(), "author@cap279.test", workspace = WS_NAME)

    private fun restPost(body: String): ExtractableResponse<Response> =
        given()
            .port(port)
            .asSession(jwt())
            .contentType(ContentType.JSON)
            .body(body)
            .`when`()
            .post("/api/v1/templates")
            .then()
            .extract()

    private fun mcpCall(body: String): ExtractableResponse<Response> =
        given()
            .port(port)
            .header("DP-API-Key", authorKey.plaintext)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body(body)
            .`when`()
            .post("/mcp")
            .then()
            .extract()

    private fun validTemplateBody(): String =
        """{"id": "$TEMPLATE_ID", "dialect": "H2", "display_name": "Cap E2E", "description": "Within the cap", """ +
            """"imports": [], "body": "SELECT 1"}"""

    companion object {
        /**
         * The suite's cap: §3.31's documented floor, so the binding still accepts it while the
         * over-cap bodies stay buildable.
         */
        const val CAP = 65_536L

        const val TEMPLATE_ID = "test/cap279_within.sql"

        /**
         * `CAP + 1` ASCII bytes total — byte-precise over the cap (ASCII: byte = char).
         * The fixed frame `{"_pad": ""}` is 12 characters, so the filler is `CAP + 1 − 12`.
         */
        val TEMPLATE_BODY_OVER_CAP = """{"_pad": "${"x".repeat(CAP.toInt() + 1 - 12)}"}"""

        /** The MCP call's pad: pushes the real tools/call body past [CAP]. */
        private val MCP_PAD: String = "x".repeat(CAP.toInt())

        const val WS_NAME = "cap279"

        private val wsId = UUID.randomUUID()
        private val authorId = UUID.randomUUID()
        private val keyIdentity = UUID.randomUUID()
        private val authorKey = E2eAuth.generateKey("cap279-mcp", ownerId = authorId.toString())

        private val jwtSecret = E2eSession.newSecret()

        private var seeded = false

        private fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager
                .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO workspaces (id, name, display_name) VALUES ('$wsId', '$WS_NAME', 'Cap 279')",
                        )
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                "('$authorId', 'author@cap279.test', 'author', 'test', 'cap279-author', TRUE, FALSE)",
                        )
                        statement.execute(
                            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$wsId', '$authorId', 'author')",
                        )
                        // The MCP key's own identity and key (keys v2 A13), as PerUserRateLimitE2eTest seeds one.
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                                "('$keyIdentity', '${authorKey.id.lowercase()}@keys.invalid', '${authorKey.name}', 'key', " +
                                "'${authorKey.id}', TRUE, FALSE, 'service')",
                        )
                        statement.execute(
                            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) VALUES " +
                                "('${authorKey.id}', '$keyIdentity', '$authorId', '${authorKey.name}', " +
                                "'${authorKey.hash}', '$wsId', 'mcp', 'author')",
                        )
                    }
                }
        }

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            // The cap under test (§3.31's floor): tiny enough that over-cap bodies are built.
            registry.add("datapipelines.web.max-request-bytes") { CAP.toString() }

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
