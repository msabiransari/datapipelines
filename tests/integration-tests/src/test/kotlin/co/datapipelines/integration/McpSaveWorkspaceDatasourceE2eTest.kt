package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
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
 * A pipeline saved over MCP may read the workspace's OWN datasources (134) — the shape a real
 * customer has and the acceptance run never did.
 *
 * What 132 measured out of its fence (its handback, "Deviations", 2026-09-14): a
 * workspace-OWNED H2 datasource registered over REST by a key, then the SAME pipeline body —
 * `POST /api/v1/pipelines` with that key was `201`, `pipelines_create` over `/mcp` with that key
 * was `pipeline.validation.unknown_datasource`. The contract registry adapter resolved the
 * datasource through the Spring Security thread-local principal; the MCP SDK runs a sync tool
 * handler on a `boundedElastic` scheduler thread (`McpStatelessServerFeatures.AsyncToolSpecification
 * .fromSync`, `immediateExecution=false`), where that thread-local is EMPTY, so every MCP save
 * validated as "no principal" and could see only owner-less datasources. The demo datasources
 * are owner-less + granted (the shape [TemplatesUpdateGoldenPathE2eTest] case 10 registers),
 * which is why nothing before this class saw it.
 *
 * Both halves of the measurement, over the REAL wire (a `tools/call` JSON-RPC request per leg,
 * the [WriteSurfaceStampingE2eTest] harness):
 *
 * 1. a workspace-OWNED datasource — registered over REST with no `global` and no `workspace`,
 *    so it binds to the key's active workspace; the read-back proves the fixture's premise;
 * 2. the body saved over REST is `201` (the half that always worked);
 * 3. the same body saved over MCP succeeds with the `draft` pointer (the half 132 saw refused);
 * 4. `pipelines_execute` over MCP runs what MCP saved — the executor is told the workspace
 *    explicitly and already worked; asserting it here is what makes the two halves agree;
 * 5. the negative, 112's isolation rule: a datasource owned by ANOTHER workspace stays
 *    `unknown_datasource` over BOTH surfaces — the fix widens MCP to what the caller may see,
 *    not to everything.
 *
 * The datasources are in-JVM H2 (`DB_CLOSE_DELAY=-1`) — no container beyond the module's
 * shared Postgres and Redis. Falsified by reverting the adapter to its thread-local read: case 3
 * is red with exactly `pipeline.validation.unknown_datasource`.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpSaveWorkspaceDatasourceE2eTest {
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
    fun `the fixture - a datasource the key's workspace OWNS, and one another workspace owns`() {
        // The in-memory databases exist once a connection has opened them; DB_CLOSE_DELAY keeps them.
        DriverManager.getConnection(OWNED_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        DriverManager.getConnection(FOREIGN_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }

        // No `global`, no `workspace`: D8 binds the datasource to the caller's ACTIVE workspace.
        registerDatasource(ADMIN_KEY.plaintext, OWNED_DATASOURCE, OWNED_JDBC_URL, "Owned by default (134)")
        registerDatasource(FOREIGN_KEY.plaintext, FOREIGN_DATASOURCE, FOREIGN_JDBC_URL, "Owned by the other workspace (134)")

        // The premise, read back: OWNED, not global — the shape the demo datasources do not have.
        datasourceWorkspace(OWNED_DATASOURCE) shouldBe "default"
        withClue("the other workspace's datasource is invisible to the key's workspace (design §3)") {
            given()
                .port(port)
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/$FOREIGN_DATASOURCE")
                .then()
                .statusCode(404)
        }

        // The template both bodies pin — workspace-local, unaffected by the datasource rule.
        val (created, createError) =
            callTool(
                1,
                "templates_create",
                mapOf(
                    "id" to TEMPLATE_ID,
                    "dialect" to "H2",
                    "display_name" to "Workspace datasource golden path",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to TEMPLATE_BODY,
                ),
            )
        withClue("template create must succeed: $created") { createError shouldBe false }
    }

    @Test
    @Order(2)
    fun `over REST the body reading the OWNED datasource saves - 201`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .body(restBody(REST_PIPELINE, OWNED_DATASOURCE))
            .`when`()
            .post("/api/v1/pipelines")
            .then()
            .statusCode(201)
    }

    @Test
    @Order(3)
    fun `over MCP the SAME body reading the OWNED datasource saves - the draft pointer, not unknown_datasource`() {
        val (payload, isError) = callTool(2, "pipelines_create", mcpArguments(MCP_PIPELINE, OWNED_DATASOURCE))

        withClue("pipelines_create over MCP must see the workspace's own datasource: $payload") { isError shouldBe false }
        withClue("a create lands a DRAFT (D55) and says so: $payload") { payload["draft"].isObject shouldBe true }
        mcpPipelineId = payload["id"].asText()
    }

    @Test
    @Order(4)
    fun `pipelines_execute over MCP runs what MCP saved - the two halves agree`() {
        // 139's render-before-you-run check is live at the entry point now, so the loop runs
        // it: render the pinned draft template, then execute. (Before 139 this leg executed an
        // unrendered draft and passed — the exact miss the check exists to catch.)
        val (rendered, renderError) =
            callTool(
                30,
                "templates_render",
                mapOf("id" to TEMPLATE_ID, "version" to 1, "context" to emptyMap<String, Any>()),
            )
        withClue("render must succeed: $rendered") { renderError shouldBe false }

        val (payload, isError) = callTool(3, "pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))

        withClue("execute must succeed: $payload") { isError shouldBe false }
        payload["status"].asText() shouldBe "SUCCESS"
        // H2 folds an unquoted alias to upper case; the identity is the name, not its case.
        payload["schema"].map { it["name"].asText().lowercase() } shouldBe listOf("answer")
        payload["rows"][0][0].asInt() shouldBe ANSWER
    }

    @Test
    @Order(5)
    fun `a datasource ANOTHER workspace owns stays unknown_datasource over both surfaces`() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .body(restBody("${REST_PIPELINE}_foreign", FOREIGN_DATASOURCE))
            .`when`()
            .post("/api/v1/pipelines")
            .then()
            .statusCode(400)
            .body("error.code", org.hamcrest.Matchers.equalTo(UNKNOWN_DATASOURCE))

        val (payload, isError) = callTool(4, "pipelines_create", mcpArguments("${MCP_PIPELINE}_foreign", FOREIGN_DATASOURCE))
        withClue("the refusal is a tool result with isError, not a JSON-RPC error: $payload") { isError shouldBe true }
        payload["error"]["code"].asText() shouldBe UNKNOWN_DATASOURCE
    }

    private fun registerDatasource(
        key: String,
        name: String,
        jdbcUrl: String,
        displayName: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header("DP-API-Key", key)
            .body(
                """
                {"name": "$name", "display_name": "$displayName", "dialect": "H2",
                 "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** The bound workspace's name as the detail reports it (rest-api §9.7A; null = global). */
    private fun datasourceWorkspace(name: String): String? =
        given()
            .port(port)
            .header("DP-API-Key", ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/datasources/$name")
            .then()
            .statusCode(200)
            .extract()
            .path<String?>("data.workspace")

    private fun restBody(
        name: String,
        datasource: String,
    ): String =
        """
        {"schema_version": 1, "name": "$name", "display_name": "Workspace datasource (134)",
         "description": "Reads a datasource the workspace owns.", "parameters": {},
         "nodes": [{"id": "rows", "description": "The answer", "type": "DQL",
                    "source": "$datasource", "template": {"id": "$TEMPLATE_ID", "version": 1},
                    "depends_on": []}]}
        """.trimIndent()

    private fun mcpArguments(
        name: String,
        datasource: String,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "Workspace datasource (134)",
            "description" to "Reads a datasource the workspace owns.",
            "nodes" to
                listOf(
                    mapOf(
                        "id" to "rows",
                        "type" to "DQL",
                        "source" to datasource,
                        "description" to "The answer",
                        "template" to mapOf("id" to TEMPLATE_ID, "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
        )

    companion object {
        private const val TEMPLATE_ID = "test/ws_ds_e2e.sql"
        private const val REST_PIPELINE = "test/ws_ds_rest"
        private const val MCP_PIPELINE = "test/ws_ds_mcp"
        private const val OWNED_DATASOURCE = "h2-ws-owned-134"
        private const val FOREIGN_DATASOURCE = "h2-ws-foreign-134"
        private const val UNKNOWN_DATASOURCE = "pipeline.validation.unknown_datasource"

        /** The seeded key's workspace — the well-known default (`V4`). */
        private const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        private const val OWNED_JDBC_URL = "jdbc:h2:mem:wsowned134;DB_CLOSE_DELAY=-1"
        private const val FOREIGN_JDBC_URL = "jdbc:h2:mem:wsforeign134;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private const val ANSWER = 42
        private const val TEMPLATE_BODY = "SELECT $ANSWER AS answer"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-134-default-key", arrayOf("read", "execute", "author"))

        /** The OTHER workspace, its member and its key — the negative's owner. */
        private val FOREIGN_WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val FOREIGN_USER_ID: String = UUID.randomUUID().toString()
        private val FOREIGN_KEY = E2eAuth.generateKey("e2e-134-foreign-key", arrayOf("read", "author"))

        private var mcpPipelineId: String = ""

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

            registry.add("datapipelines.jwt.secret") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
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
                    // 186: the foreign user is a super admin because the fixture registers an
                    // in-process H2 datasource, which is super-admin-only now; the suite's subject
                    // (cross-workspace visibility) is grant-based and unaffected.
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ADMIN_USER_ID', 'e2e-134-default@datapipelines.test', 'E2E 134 Default', 'test',
                             'e2e-134-default-sub', TRUE, TRUE),
                            ('$FOREIGN_USER_ID', 'e2e-134-foreign@datapipelines.test', 'E2E 134 Foreign', 'test',
                             'e2e-134-foreign-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name)" +
                            " VALUES ('$FOREIGN_WORKSPACE_ID', 'lane134-other', 'Lane 134 other')",
                    )
                    // The foreign key's issuer must be able to act in its pinned workspace (D-R12).
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$FOREIGN_WORKSPACE_ID', '$FOREIGN_USER_ID', 'workspace_admin')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        val keys =
                            listOf(
                                Triple(ADMIN_KEY, ADMIN_USER_ID, WORKSPACE_ID),
                                Triple(FOREIGN_KEY, FOREIGN_USER_ID, FOREIGN_WORKSPACE_ID),
                            )
                        for ((key, owner, workspace) in keys) {
                            ps.setString(1, key.id)
                            ps.setObject(2, UUID.fromString(owner))
                            ps.setString(3, key.name)
                            ps.setString(4, key.hash)
                            ps.setArray(5, connection.createArrayOf("text", key.scopes))
                            ps.setObject(6, UUID.fromString(workspace))
                            ps.executeUpdate()
                        }
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
