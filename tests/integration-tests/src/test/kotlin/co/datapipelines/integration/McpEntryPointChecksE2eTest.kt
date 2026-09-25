package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
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
 * The three 139 entry-point checks, over the REAL wire (one `tools/call` JSON-RPC request per
 * leg, the [McpSaveWorkspaceDatasourceE2eTest] harness): each check shows both halves —
 * refused with the catalogued code, the ONE call the refusal names, then accepted — and the
 * update-then-refuse-again arm of the render-freshness check.
 *
 * 1. **table_not_learned**: `pipelines_create` naming `trips` is refused with
 *    `details.tables` naming the clearing call; `datasources_get_columns`; create passes.
 * 2. **template_unrendered**: executing the draft is refused (`last_render: null`);
 *    `templates_render`; execute runs. A `templates_update` re-arms the refusal until the
 *    next render.
 * 3. **door_unacknowledged**: a two-raw-DATE-parameters body is refused naming the pair;
 *    `door_acknowledged: true` passes it; a period parameter needs no flag.
 *
 * Falsified per check by disabling the call site in the tool (reversible edit): the check's
 * refused leg goes red with the missing refusal, the accepted legs stay green. The RELEASED
 * pin/version exemptions have no MCP leg — a release is a human action — so those arms live
 * in `EntryPointChecksTest` (mcp-server), which is also where the `tempdb` and `${}` arms run.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpEntryPointChecksE2eTest {
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
    fun `the fixture - an H2 datasource with a trips table, and a template reading it`() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { connection ->
            connection.createStatement().execute("CREATE TABLE IF NOT EXISTS trips (id INT, zone VARCHAR(20))")
            connection.createStatement().execute("INSERT INTO trips VALUES (1, 'manhattan')")
        }

        registerDatasource(ADMIN_SESSION, DATASOURCE, H2_JDBC_URL, "Entry-point checks (139)")

        val (created, createError) =
            callTool(
                1,
                "templates_create",
                mapOf(
                    "id" to TEMPLATE_ID,
                    "dialect" to "H2",
                    "display_name" to "Entry-point checks (139)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to TEMPLATE_BODY,
                ),
            )
        withClue("template create must succeed: $created") { createError shouldBe false }
        templateHash = created["body_hash"].asText()
        withClue("a created template lands DRAFT — the version check B guards") {
            created["status"].asText() shouldBe "DRAFT"
        }
    }

    @Test
    @Order(2)
    fun `A - create naming a never-learned table is refused, naming the clearing call`() {
        val (payload, isError) = callTool(2, "pipelines_create", pipelineArguments(PIPELINE, parameters = emptyMap()))

        withClue("the refusal is a tool result with isError, not a JSON-RPC error: $payload") { isError shouldBe true }
        payload["error"]["code"].asText() shouldBe TABLE_NOT_LEARNED
        val table = payload["error"]["details"]["tables"][0]
        table["datasource"].asText() shouldBe DATASOURCE
        // H2 folds unquoted identifiers to upper case; the refusal names the table as the
        // CATALOG spells it — which is exactly the spelling the clearing call must use.
        table["table"].asText() shouldBe "TRIPS"
        table["clearing_call"].asText() shouldBe "datasources_get_columns"
    }

    @Test
    @Order(3)
    fun `A - the named call is datasources_get_columns`() {
        val (payload, isError) =
            callTool(3, "datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "TRIPS"))
        withClue("columns read must succeed: $payload") { isError shouldBe false }
    }

    @Test
    @Order(4)
    fun `A - the SAME create now passes - a draft, not a refusal`() {
        val (payload, isError) = callTool(4, "pipelines_create", pipelineArguments(PIPELINE, parameters = emptyMap()))

        withClue("create must pass once the table is learned: $payload") { isError shouldBe false }
        withClue("a create lands a DRAFT (D55): $payload") { payload["draft"].isObject shouldBe true }
        mcpPipelineId = payload["id"].asText()
    }

    @Test
    @Order(5)
    fun `B - executing the draft with an unrendered template is refused, last_render null`() {
        val (payload, isError) =
            callTool(5, "pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))

        withClue("the run must be refused: $payload") { isError shouldBe true }
        payload["error"]["code"].asText() shouldBe TEMPLATE_UNRENDERED
        val template = payload["error"]["details"]["templates"][0]
        template["id"].asText() shouldBe TEMPLATE_ID
        template["version"].asInt() shouldBe 1
        withClue("last_render states the key never rendered it: $payload") { template["last_render"].isNull shouldBe true }
    }

    @Test
    @Order(6)
    fun `B - the named call is templates_render`() {
        val (payload, isError) = callTool(6, "templates_render", mapOf("id" to TEMPLATE_ID, "context" to emptyMap<String, Any>()))
        withClue("render must succeed: $payload") { isError shouldBe false }
    }

    @Test
    @Order(7)
    fun `B - the SAME execute now runs - SUCCESS over the real H2 datasource`() {
        val (payload, isError) =
            callTool(7, "pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))

        withClue("execute must succeed after the render: $payload") { isError shouldBe false }
        payload["status"].asText() shouldBe "SUCCESS"
        payload["rows"][0][0].asInt() shouldBe 1
    }

    @Test
    @Order(8)
    fun `B - a templates_update re-arms the refusal until the next render`() {
        val (updated, updateError) =
            callTool(
                8,
                "templates_update",
                mapOf(
                    "id" to TEMPLATE_ID,
                    "expected_hash" to templateHash,
                    "display_name" to "Entry-point checks (139), edited",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "$TEMPLATE_BODY WHERE zone = 'manhattan'",
                ),
            )
        withClue("template update must succeed: $updated") { updateError shouldBe false }

        val (refused, refusedError) =
            callTool(9, "pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))
        withClue("the update must re-arm the refusal: $refused") { refusedError shouldBe true }
        refused["error"]["code"].asText() shouldBe TEMPLATE_UNRENDERED
        val stale = refused["error"]["details"]["templates"][0]
        withClue("this time the key HAS a render — but an older one: $refused") { stale["last_render"].isNull shouldBe false }

        callTool(10, "templates_render", mapOf("id" to TEMPLATE_ID, "context" to emptyMap<String, Any>()))

        val (payload, isError) =
            callTool(11, "pipelines_execute", mapOf("id" to mcpPipelineId, "parameters" to emptyMap<String, Any>()))
        withClue("execute must succeed after the fresh render: $payload") { isError shouldBe false }
        payload["status"].asText() shouldBe "SUCCESS"
    }

    @Test
    @Order(9)
    fun `C - a raw-date door is refused, the flag is accepted, a period parameter needs no flag`() {
        val dates = mapOf("start_date" to DATE_PARAM, "end_date" to DATE_PARAM)

        val (refused, refusedError) =
            callTool(12, "pipelines_create", pipelineArguments(DOOR_PIPELINE, dates))
        withClue("the raw-date door must be refused: $refused") { refusedError shouldBe true }
        refused["error"]["code"].asText() shouldBe DOOR_UNACKNOWLEDGED
        val named = refused["error"]["details"]["parameters"].map { it.asText() }.toSet()
        named shouldBe setOf("start_date", "end_date")

        val (accepted, acceptedError) =
            callTool(13, "pipelines_create", pipelineArguments(DOOR_PIPELINE, dates) + mapOf("door_acknowledged" to true))
        withClue("the flag must be accepted: $accepted") { acceptedError shouldBe false }

        val (period, periodError) =
            callTool(14, "pipelines_create", pipelineArguments(PERIOD_PIPELINE, dates + mapOf("year" to YEAR_PARAM)))
        withClue("a period parameter needs no flag: $period") { periodError shouldBe false }
        withClue("the three creates named three different pipelines") {
            setOf(accepted["id"].asText(), period["id"].asText()).size shouldBe 2
        }
    }

    // ---------------------------------------------------------------------------------

    private fun registerDatasource(
        session: String,
        name: String,
        jdbcUrl: String,
        displayName: String,
    ) {
        io.restassured.RestAssured
            .given()
            .port(port)
            .contentType(io.restassured.http.ContentType.JSON)
            .asSession(session)
            .body(
                """
                {"name": "$name", "display_name": "$displayName", "dialect": "H2",
                 "jdbc_url": "$jdbcUrl", "username": "sa", "password": "sa"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun pipelineArguments(
        name: String,
        parameters: Map<String, Any?>,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "Entry-point checks (139)",
            "description" to "The three entry-point checks, over the real wire.",
            "parameters" to parameters,
            "nodes" to
                listOf(
                    mapOf(
                        "id" to "rows",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "description" to "The count",
                        "template" to mapOf("id" to TEMPLATE_ID, "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
        )

    companion object {
        /**
         * Run-unique names: the shared metadata Postgres persists across runs (only the audit
         * learning is fresh — the key id is), so a second run of this class must not collide
         * with the first run's pipelines, template or datasource.
         */
        private val RUN_ID = Integer.toHexString(java.security.SecureRandom().nextInt(0x10000))
        private val TEMPLATE_ID = "test/entry_139_$RUN_ID.sql"
        private val DATASOURCE = "h2-entry-139-$RUN_ID"
        private val PIPELINE = "test/entry_139_$RUN_ID"
        private val DOOR_PIPELINE = "test/entry_139_door_$RUN_ID"
        private val PERIOD_PIPELINE = "test/entry_139_period_$RUN_ID"

        private const val TABLE_NOT_LEARNED = "pipeline.validation.table_not_learned"
        private const val TEMPLATE_UNRENDERED = "pipeline.execution.template_unrendered"
        private const val DOOR_UNACKNOWLEDGED = "pipeline.validation.door_unacknowledged"

        private const val TEMPLATE_BODY = "SELECT count(*) AS n FROM trips"
        private val DATE_PARAM = mapOf("type" to "DATE", "required" to false)
        private val YEAR_PARAM = mapOf("type" to "INTEGER", "required" to false)

        /** The seeded key's workspace — the well-known default (`V4`). */
        private const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000000001"
        private const val H2_JDBC_URL = "jdbc:h2:mem:entry139;DB_CLOSE_DELAY=-1"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-139-entry-key")

        /** The key's own `service` identity (keys v2 A13). */
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret`; REST is a session's surface (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, "e2e-139-entry@datapipelines.test")

        private var mcpPipelineId: String = ""
        private var templateHash: String = ""

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
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ADMIN_USER_ID', 'e2e-139-entry@datapipelines.test', 'E2E 139 Entry', 'test',
                             'e2e-139-entry-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    // Keys v2 (A13): the key acts as its own identity and holds the role chosen at
                    // creation — its member is a workspace admin, so the subset rule allows that role.
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_USER_ID', 'workspace_admin')",
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
                            " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, ADMIN_KEY.id)
                        ps.setObject(2, UUID.fromString(KEY_IDENTITY))
                        ps.setObject(3, UUID.fromString(ADMIN_USER_ID))
                        ps.setString(4, ADMIN_KEY.name)
                        ps.setString(5, ADMIN_KEY.hash)
                        ps.setObject(6, UUID.fromString(WORKSPACE_ID))
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
