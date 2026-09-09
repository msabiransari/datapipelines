package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
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
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The write-surface stamps, proven END TO END over the three surfaces the ruling names
 * (V20, owner ruling 2026-09-09; versioning §3.7): a REST write with an API key stamps
 * `api_key`, the SAME key writing through MCP's real `/mcp` endpoint stamps `mcp` (only the
 * tool knows the call is MCP — the credential cannot), and a session write stamps `session` —
 * while `created_by` names the PERSON on every path (the key's owner on the keyed ones).
 *
 * Every value is read back from the ROW, never echoed from the request. The MCP leg is the
 * load-bearing one: MCP is API-key-authenticated, so nothing about the credential
 * distinguishes it from a plain keyed REST write; only the tool's explicit stamp can, and
 * this test is what proves that stamp reaches the database.
 *
 * Also pins D4's half: a RELEASE stamps nothing new — the released row keeps the surface its
 * draft was written on, asserted by releasing the MCP-written draft and re-reading the row.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WriteSurfaceStampingE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val http: HttpClient = HttpClient.newHttpClient()

    /**
     * A calculator-only body: valid with no datasource and no template, so the write legs
     * prove the STAMP, not a fixture's fan dance.
     */
    private fun bodyJson(name: String): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Write Surface $name",
                "description" to "via-stamping probe",
                "parameters" to emptyMap<String, String>(),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "fq",
                            "description" to "A calculator, so nothing external must exist",
                            "type" to "CALCULATOR",
                            "kind" to "fiscal_quarter",
                            "inputs" to mapOf("date" to "2026-08-14", "fiscal_start" to "09-15"),
                            "context_key" to "run_fiscal_quarter",
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            ),
        )

    /** The version-1 row of [pipelineName], read straight from the table — the ruling's test shape. */
    private fun row(pipelineName: String): Map<String, String> =
        DriverManager
            .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        "SELECT v.created_via, v.updated_via, v.created_by::TEXT, u.display_name" +
                            "  FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                            "  JOIN users u ON u.id = v.created_by" +
                            " WHERE p.name = ? AND v.version = 1",
                    ).use { ps ->
                        ps.setString(1, pipelineName)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "no version row for $pipelineName" }
                            mapOf(
                                "created_via" to rs.getString(1),
                                "updated_via" to rs.getString(2),
                                "created_by" to rs.getString(3),
                                "by" to rs.getString(4),
                            )
                        }
                    }
            }

    @Test
    @Order(1)
    fun `a REST write with an API key stamps api_key and the key owner's id`() {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .body(bodyJson("test/via_rest_key"))
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode != 201) {
            throw AssertionError("keyed REST create failed (${response.statusCode}): ${response.body().asString()}")
        }

        val row = row("test/via_rest_key")
        row["created_via"] shouldBe "api_key"
        row["updated_via"] shouldBe "api_key"
        // The person, always: a key's writes are its OWNER's (the ruling), and the id is the
        // row's own created_by, not the request's.
        row["created_by"] shouldBe ADMIN_USER_ID
    }

    @Test
    @Order(2)
    fun `the same key writing through MCP stamps mcp`() {
        val body = mapper.readTree(bodyJson("test/via_mcp"))
        val rpc =
            mapper.writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 41,
                    "method" to "tools/call",
                    "params" to
                        mapOf(
                            "name" to "pipelines_create",
                            "arguments" to
                                mapOf(
                                    "name" to body["name"].asText(),
                                    "display_name" to body["display_name"].asText(),
                                    "description" to body["description"].asText(),
                                    // The MCP tool's schema takes `nodes` as a LIST argument.
                                    "nodes" to mapper.convertValue(body["nodes"], List::class.java),
                                ),
                        ),
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(rpc))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val result: JsonNode = mapper.readTree(response.body())
        check(!result.has("error")) { "pipelines_create over MCP failed: ${response.body()}" }

        // Identical credential, different door — only the tool's stamp tells them apart.
        val row = row("test/via_mcp")
        row["created_via"] shouldBe "mcp"
        row["updated_via"] shouldBe "mcp"
        row["created_by"] shouldBe ADMIN_USER_ID
    }

    @Test
    @Order(3)
    fun `a session write stamps session`() {
        // Session writes ride the CSRF double-submit pair (mintApiKey's shape): read the
        // dp_csrf cookie off any authenticated page, echo it in the header.
        val csrfRequest =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/settings"))
                .header("Cookie", "dp_session=${sessionJwt()}")
                .GET()
                .build()
        val csrfResponse = http.send(csrfRequest, HttpResponse.BodyHandlers.ofString())
        csrfResponse.statusCode() shouldBe 200
        val csrf =
            Regex("dp_csrf=([^;]+)")
                .find(
                    csrfResponse
                        .headers()
                        .map()
                        .entries
                        .joinToString("; ") { (k, v) -> "$k=$v" },
                )?.groupValues
                ?.get(1)
                ?: error("no dp_csrf cookie on /settings")

        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header("Cookie", "dp_session=${sessionJwt()}; dp_csrf=$csrf")
                .header("DP-CSRF-Token", csrf)
                .body(bodyJson("test/via_session"))
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode != 201) {
            throw AssertionError("session create failed (${response.statusCode}): ${response.body().asString()}")
        }

        val row = row("test/via_session")
        row["created_via"] shouldBe "session"
        row["updated_via"] shouldBe "session"
    }

    @Test
    @Order(4)
    fun `releasing stamps nothing new - the row keeps the surface its draft was written on`() {
        // D4: a release is always a human in a session — asserted, not recorded. Release the
        // MCP-written draft and re-read: the via columns keep the draft's stamps.
        val id = mcpPipelineId()
        val created =
            given()
                .port(port)
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/pipelines/$id")
                .thenReturn()
        created.statusCode shouldBe 200
        val draftHash = created.jsonPath().getString("data.body_hash")

        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .header("If-Match", draftHash)
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)

        val row = row("test/via_mcp")
        row["created_via"] shouldBe "mcp"
        row["updated_via"] shouldBe "mcp"
    }

    private var mcpId: String? = null

    private fun mcpPipelineId(): String =
        mcpId
            ?: DriverManager
                .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .use { connection ->
                    connection
                        .prepareStatement("SELECT id::TEXT FROM pipelines WHERE name = 'test/via_mcp'")
                        .use { ps ->
                            ps.executeQuery().use { rs ->
                                check(rs.next()) { "test/via_mcp not created" }
                                rs.getString(1).also { mcpId = it }
                            }
                        }
                }

    /** The JarSmoke shape verbatim: HS256 against the fixed test secret, the app's own claims. */
    private fun sessionJwt(): String {
        val now = Instant.now()
        val header = b64("""{"alg":"HS256","typ":"JWT"}""")
        val payload =
            b64(
                """{"sub":"$ADMIN_USER_ID","email":"e2e-via@datapipelines.test","name":"Via Probe",""" +
                    """"scopes":["read","execute","author","admin"],""" +
                    """"iss":"datapipelines","iat":${now.epochSecond},""" +
                    """"exp":${now.plusSeconds(3600).epochSecond},"active_workspace":"default"}""",
            )
        val signature =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(Base64.getDecoder().decode(JWT_SECRET), "HmacSHA256"))
                b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
            }
        return "$header.$payload.$signature"
    }

    private fun b64(raw: String): String = b64(raw.toByteArray(Charsets.UTF_8))

    private fun b64(raw: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    companion object {
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-via-stamping-key", arrayOf("admin"))

        /** FIXED, not random: the session leg mints its own JWT against this secret. */
        private const val JWT_SECRET = "dGVzdC1qd3Qtc2VjcmV0LWZvci12aWEtc3RhbXBpbmctMzI="

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
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

            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }

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
        @BeforeAll
        fun seedAuthRows() {
            // The default workspace is V4-seeded ('default', id …0001 — E2eClean's pinned
            // literal); the key pins it, the session JWT names it, and the user is made a
            // member so the session's workspace resolution has something to resolve.
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-via@datapipelines.test', 'Via Probe',
                                'test', 'e2e-via-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('defa0000-0000-0000-0000-000000000001', '$ADMIN_USER_ID', 'owner')",
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

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
