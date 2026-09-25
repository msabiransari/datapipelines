package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertAll
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
 * The authoring loop 117 exists for, END TO END through the REAL MCP protocol surface (117):
 * the agent created a template draft and needs to CHANGE it — before this round its only MCP
 * path was purge-and-recreate, and purge is refused the moment a pipeline pins the template,
 * which is exactly the state an agent is in mid-build.
 *
 * Every leg is a real `tools/call` JSON-RPC request over `POST /mcp` (the
 * [WriteSurfaceStampingE2eTest] harness), because the load-bearing claims are about the WIRE:
 * the first case reads `tools/list` and asserts `templates_update` advertises `expected_hash`
 * as a REQUIRED property there — a unit test that calls `call()` directly never exercises the
 * schema an agent's client validates against, and a schema the server parses but does not
 * advertise is the silent-dead-parameter class of failure.
 *
 * The ordered cases are one agent session:
 *
 * 1. **the wire advertises the tool with its required hash argument**;
 * 2. **create** lands v1 as a DRAFT;
 * 3. **templates_get** returns the hash the edit is based on;
 * 4. **update with a changed body** overwrites the sole draft IN PLACE (§5.2 — v1 was never
 *    released, so there is no released parent to copy from): same version, new hash, `draft` pointer;
 * 5. **templates_get** defaults to the working version, so a re-read shows the new hash and body;
 * 6. **templates_render** renders the working (draft) body — a pipeline pinning this draft
 *    dry-renders the new content, not the release;
 * 7. **a human releases** over REST — the verb no tool has;
 * 8. **the next write opens a NEW draft** — copy-on-write (§5.1), version 2;
 * 9. **an update carrying the OLD hash** is the catalogued `template.version.conflict` —
 *    the precondition, not a polite warning;
 * 10. **(132) the update is what renders AND runs once the caches are warm** — the shape the
 *    2026-09-14 acceptance run had and case 6 could not see, because case 6 never rendered
 *    BEFORE the update: render → update → render → execute a pipeline pinning `@1` → update
 *    → render → execute, in ONE method so nothing between the legs empties a cache. Before
 *    132 both render caches were keyed on `id@version` and held the first body for the life
 *    of the workspace engine; every later `templates_render` and `pipelines_execute` ran it.
 *
 * Cases 1–9 need no datasource: their bodies are plain SQL with nothing to render against.
 * Case 10 executes, so it registers an in-JVM H2 (`DB_CLOSE_DELAY=-1`) — no container beyond
 * the module's shared Postgres and Redis.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TemplatesUpdateGoldenPathE2eTest {
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
                // The MCP key: /mcp takes a key and refuses a session (§8.5); REST below takes the session (#215 B2).
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

    private fun updateArgs(
        body: String,
        expectedHash: String?,
    ): Map<String, Any?> =
        buildMap {
            put("id", TEMPLATE_ID)
            expectedHash?.let { put("expected_hash", it) }
            put("dialect", "H2")
            put("display_name", "Update golden path")
            put("description", "Expects: nothing (plain SQL).")
            put("body", body)
        }

    @Test
    @Order(1)
    fun `tools list advertises templates_update with expected_hash as a required property`() {
        val tools = mcp(1, "tools/list", emptyMap<String, Any>())["tools"]

        val update = tools.first { it["name"].asText() == "templates_update" }

        // The wire schema is what an agent's client validates against — a required hash the
        // schema omits is the silent-dead-argument failure this E2E exists to catch.
        withClue("the required list must name expected_hash") {
            update["inputSchema"]["required"]
                .map { it.asText() } shouldBe
                listOf("id", "expected_hash", "display_name", "description", "body")
            update["inputSchema"]["properties"]["expected_hash"].isObject shouldBe true
        }
    }

    @Test
    @Order(2)
    fun `templates_create lands version 1 as a DRAFT`() {
        val (payload, isError) =
            callTool(
                2,
                "templates_create",
                updateArgs(TEMPLATE_BODY_V1, expectedHash = null),
            )

        withClue("create must succeed to set the story up: $payload") { isError shouldBe false }
        payload["status"].asText() shouldBe "DRAFT"
        payload["version"].asInt() shouldBe 1
    }

    @Test
    @Order(3)
    fun `templates_get returns the hash the edit is based on`() {
        val (payload, isError) =
            callTool(3, "templates_get", mapOf("id" to TEMPLATE_ID))

        withClue("get must succeed: $payload") { isError shouldBe false }
        hashV1 = payload["body_hash"].asText()
        payload["version"].asInt() shouldBe 1
        hashV1.isNotBlank() shouldBe true
    }

    @Test
    @Order(4)
    fun `templates_update with a changed body overwrites the sole draft in place`() {
        // The template was never released: its v1 IS the draft, so §5.2 overwrites it in
        // place — same version number, new content hash. (Versions bump only when a write
        // opens a draft over a RELEASED parent — that branch is case 8.)
        val (payload, isError) =
            callTool(4, "templates_update", updateArgs(TEMPLATE_BODY_V2, expectedHash = hashV1))

        withClue("update must succeed: $payload") { isError shouldBe false }
        hashV2 = payload["body_hash"].asText()
        assertAll(
            { payload["status"].asText() shouldBe "DRAFT" },
            { payload["version"].asInt() shouldBe 1 },
            { payload["body"].asText() shouldBe TEMPLATE_BODY_V2 },
            { withClue("a new content hash is the whole point of the write") { hashV2 shouldNotBe hashV1 } },
            { payload["draft"]["version"].asInt() shouldBe 1 },
            { payload["draft"]["body_hash"].asText() shouldBe hashV2 },
        )
    }

    @Test
    @Order(5)
    fun `templates_get defaults to the working version - the draft the update just wrote`() {
        val (payload, isError) =
            callTool(5, "templates_get", mapOf("id" to TEMPLATE_ID))

        withClue("get must succeed: $payload") { isError shouldBe false }
        payload["version"].asInt() shouldBe 1
        payload["status"].asText() shouldBe "DRAFT"
        payload["body_hash"].asText() shouldBe hashV2
        payload["body"].asText() shouldBe TEMPLATE_BODY_V2
    }

    @Test
    @Order(6)
    fun `templates_render renders the working version - the draft body, not the release`() {
        val arguments =
            mapOf(
                "id" to TEMPLATE_ID,
                "context" to emptyMap<String, String>(),
            )
        val result = mcp(6, "tools/call", mapOf("name" to "templates_render", "arguments" to arguments))
        // §6.2.9 pins the return as the rendered SQL string — serialized as a JSON string on
        // the content text, so the client parses it back out.
        val rendered = mapper.readTree(result["content"][0]["text"].asText()).asText()

        rendered shouldBe TEMPLATE_BODY_V2
    }

    @Test
    @Order(7)
    fun `a human releases the draft over REST - the verb no tool has`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .header(IF_MATCH, hashV2)
            .body("""{"name": "$TEMPLATE_ID"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
            .body("data.status", org.hamcrest.Matchers.equalTo("RELEASED"))
    }

    @Test
    @Order(8)
    fun `the first write after the release opens a NEW draft - copy-on-write, version 2`() {
        val (payload, isError) =
            callTool(8, "templates_update", updateArgs(TEMPLATE_BODY_V3, expectedHash = hashV2))

        withClue("update must succeed: $payload") { isError shouldBe false }
        hashV3 = payload["body_hash"].asText()
        assertAll(
            { payload["status"].asText() shouldBe "DRAFT" },
            { payload["version"].asInt() shouldBe 2 },
            { payload["body"].asText() shouldBe TEMPLATE_BODY_V3 },
            { withClue("a draft's hash always differs from its released parent") { hashV3 shouldNotBe hashV2 } },
            { payload["draft"]["version"].asInt() shouldBe 2 },
        )
    }

    @Test
    @Order(9)
    fun `a second update carrying the OLD hash is the catalogued conflict`() {
        val (payload, isError) =
            callTool(9, "templates_update", updateArgs("SELECT 4 AS marker", expectedHash = hashV2))

        withClue("the refusal is a tool result with isError, not a JSON-RPC error") { isError shouldBe true }
        payload["error"]["code"].asText() shouldBe "template.version.conflict"
        withClue("the conflict carries the current state so the agent can rebase") {
            payload["error"]["details"]["current_body_hash"].asText() shouldBe hashV3
        }
    }

    @Test
    @Order(10)
    fun `a draft updated after it was rendered is what renders and runs next - both caches were warm`() {
        // Step 1 — create, then RENDER: the leg the 117 case never had. This is what parked the
        // first body in the registry LRU and the parsed-template cache.
        val (created, createError) = callTool(10, "templates_create", cacheArgs(CACHE_BODY_V1, expectedHash = null))
        withClue("create must succeed: $created") { createError shouldBe false }
        val hash1 = created["body_hash"].asText()
        renderCacheTemplate(11, version = null) shouldBe CACHE_BODY_V1

        // Step 2 — the update the agent made (same v1 draft, new body).
        val (updated, updateError) = callTool(12, "templates_update", cacheArgs(CACHE_BODY_V2, expectedHash = hash1))
        withClue("update must succeed: $updated") { updateError shouldBe false }
        val hash2 = updated["body_hash"].asText()
        updated["version"].asInt() shouldBe 1

        // Step 3 — the render the acceptance run saw return the OLD body, versionless and @1.
        withClue("templates_render (working version) after the update must be the new body") {
            renderCacheTemplate(13, version = null) shouldBe CACHE_BODY_V2
        }
        withClue("templates_render version=1 after the update must be the new body") {
            renderCacheTemplate(14, version = 1) shouldBe CACHE_BODY_V2
        }

        // Step 4 — a pipeline pinning @1 EXECUTES; the column alias only the new body emits is
        // the proof of which body ran, and the row value doubles it.
        registerH2Datasource()
        val (pipeline, pipelineError) =
            callTool(
                15,
                "pipelines_create",
                mapOf(
                    "name" to CACHE_PIPELINE,
                    "display_name" to "Draft cache golden path",
                    "description" to "Pins the draft template at version 1 while it is edited.",
                    "nodes" to
                        listOf(
                            mapOf(
                                "id" to "rows",
                                "type" to "DQL",
                                "source" to H2_DATASOURCE,
                                "description" to "Runs whatever the pinned draft says right now",
                                "template" to mapOf("id" to CACHE_TEMPLATE_ID, "version" to 1),
                                "depends_on" to emptyList<String>(),
                            ),
                        ),
                ),
            )
        withClue("pipeline create must succeed: $pipeline") { pipelineError shouldBe false }
        val pipelineId = pipeline["id"].asText()
        assertExecutedBody(executeCachePipeline(16, pipelineId), alias = "marker_v2", value = 2)

        // Step 5 — a second iteration of the same loop: update → render → execute.
        val (again, againError) = callTool(17, "templates_update", cacheArgs(CACHE_BODY_V3, expectedHash = hash2))
        withClue("second update must succeed: $again") { againError shouldBe false }
        withClue("the second iteration's render") { renderCacheTemplate(18, version = null) shouldBe CACHE_BODY_V3 }
        assertExecutedBody(executeCachePipeline(19, pipelineId), alias = "marker_v3", value = 3)
    }

    private fun cacheArgs(
        body: String,
        expectedHash: String?,
    ): Map<String, Any?> =
        buildMap {
            put("id", CACHE_TEMPLATE_ID)
            expectedHash?.let { put("expected_hash", it) }
            put("dialect", "H2")
            put("display_name", "Draft cache golden path")
            put("description", "Expects: nothing (plain SQL).")
            put("body", body)
        }

    /** `templates_render` on the cache template — versionless (working version) or pinned. */
    private fun renderCacheTemplate(
        id: Int,
        version: Int?,
    ): String {
        val arguments =
            buildMap {
                put("id", CACHE_TEMPLATE_ID)
                version?.let { put("version", it) }
                put("context", emptyMap<String, String>())
            }
        val result = mcp(id, "tools/call", mapOf("name" to "templates_render", "arguments" to arguments))
        withClue("render must succeed: ${result["content"][0]["text"].asText()}") { result.path("isError").asBoolean(false) shouldBe false }
        return mapper.readTree(result["content"][0]["text"].asText()).asText()
    }

    private fun executeCachePipeline(
        id: Int,
        pipelineId: String,
    ): JsonNode {
        val (payload, isError) = callTool(id, "pipelines_execute", mapOf("id" to pipelineId, "parameters" to emptyMap<String, Any>()))
        withClue("execute must succeed: $payload") { isError shouldBe false }
        payload["status"].asText() shouldBe "SUCCESS"
        return payload
    }

    /** The executed body is identified by the column alias only it emits, and by its literal. */
    private fun assertExecutedBody(
        payload: JsonNode,
        alias: String,
        value: Int,
    ) {
        withClue("the pipeline pinning @1 must run the draft's CURRENT body: $payload") {
            // H2 folds an unquoted alias to upper case; the identity is the name, not its case.
            payload["schema"].map { it["name"].asText().lowercase() } shouldBe listOf(alias)
            payload["rows"][0][0].asInt() shouldBe value
        }
    }

    /**
     * Registered GLOBAL (owner-less) and GRANTED to the key's workspace — the shape the demo
     * datasources the acceptance run used have. A workspace-OWNED datasource is refused
     * by `pipelines_create` over MCP with `pipeline.validation.unknown_datasource` while the
     * identical body lands over REST — measured while writing this case (132 handback, out of
     * its fence): the contract registry adapter reads the principal off
     * `SecurityContextHolder`, which the MCP tool call does not carry (its principal travels
     * in the transport context), so only owner-less datasources resolve there.
     */
    private fun registerH2Datasource() {
        // The in-memory database exists once a connection has opened it; DB_CLOSE_DELAY keeps it.
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Draft cache H2", "dialect": "H2", "global": true,
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
        // The grant is a session-only super-admin verb (D-R7) — no key can make it — so the
        // row is seeded the way the users and keys are.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (?, ?, ?)" +
                        " ON CONFLICT (datasource_name, workspace_id) DO NOTHING",
                ).use { ps ->
                    ps.setString(1, H2_DATASOURCE)
                    ps.setObject(2, UUID.fromString(WORKSPACE_ID))
                    ps.setObject(3, UUID.fromString(ADMIN_USER_ID))
                    ps.executeUpdate()
                }
        }
    }

    companion object {
        private const val TEMPLATE_ID = "test/tpl_update_e2e.sql"

        /** Case 10's own template, pipeline and datasource — created inside the case, warm from its first render. */
        private const val CACHE_TEMPLATE_ID = "test/tpl_cache_e2e.sql"
        private const val CACHE_PIPELINE = "test/tpl_cache_e2e"
        private const val H2_DATASOURCE = "h2-tpl-cache"

        /** The seeded key's workspace — the well-known default (`V4`). */
        private const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        private const val H2_JDBC_URL = "jdbc:h2:mem:tplcachedb;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        /** Each body emits an alias only it has, so the executed column list names the body that ran. */
        private const val CACHE_BODY_V1 = "SELECT 1 AS marker_v1"
        private const val CACHE_BODY_V2 = "SELECT 2 AS marker_v2"
        private const val CACHE_BODY_V3 = "SELECT 3 AS marker_v3"
        private const val IF_MATCH = "If-Match"

        /** Plain SQL, no interpolation: render is the identity, so the body IS the proof. */
        private const val TEMPLATE_BODY_V1 = "SELECT 1 AS marker"
        private const val TEMPLATE_BODY_V2 = "SELECT 2 AS marker"
        private const val TEMPLATE_BODY_V3 = "SELECT 3 AS marker"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        /** The key's own `service` identity (keys v2 A13). */
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret` and used to sign the session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()

        /** The admin's MCP key for the `/mcp` legs — pinned to `default`, acting as the admin (#215 PK4). */
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-tpl-update-key")
        private const val MCP_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, "e2e-tpl-update@datapipelines.test")

        private var hashV1: String = ""
        private var hashV2: String = ""
        private var hashV3: String = ""

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

            listOf("google").forEachIndexed { index, name ->
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
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-tpl-update@datapipelines.test', 'E2E Tpl Update',
                                'test', 'e2e-tpl-update-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    // #215 PK4: a super admin's MCP key with NO membership is a viewer, and the suite
                    // authors over MCP — so the admin is a workspace admin of `default` (an author there).
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$MCP_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin') ON CONFLICT DO NOTHING",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${ADMIN_KEY.id.lowercase()}@keys.invalid', '${ADMIN_KEY.name}', 'key', " +
                            "'${ADMIN_KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, ADMIN_KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_USER_ID)
                        ps.setString(4, ADMIN_KEY.name)
                        ps.setString(5, ADMIN_KEY.hash)
                        ps.setString(6, MCP_WORKSPACE)
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
