package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Gate 6 of the roles design (§4, 177): **D11's execution visibility, as behaviour**, on
 * every surface the runs are read through — the REST list and read, the executions screen,
 * and the three MCP tools.
 *
 * The fixture is four runs of one pipeline in one workspace: Alice's (an author, a session
 * run), Vera's (a viewer, a session run), a run a PUBLISHED ENDPOINT started before V34 with a
 * key Alice owned (`executed_by = alice`, `executed_by_key_kind = endpoint` — past runs keep
 * their attribution, PK9), and one started since #215 by an API key that acts as its OWN
 * identity (`executed_by` = the key's `service` user, record C5). The record's §2 row then
 * says, and this suite holds it to:
 *
 * - **own**: Alice lists exactly her own session run; Vera's and the endpoint's are not hers
 *   — the endpoint's not even though she owns the key (the run is the endpoint's, D11) — and
 *   neither is the identity's, though Wanda created that key;
 * - **the key's own (#215 A12)**: the API key reads the run it started, and only that one;
 * - the **404 rule**: another member's run is *not found*, never 403, so a run's existence is
 *   not an oracle (auth.md §11A.1);
 * - **all**: a workspace admin lists all three, the endpoint run included, and reads Vera's;
 * - **none**: a promoter is refused the list, the read, the screen and the tools by ROLE
 *   (`auth.role_required`) — before any row is consulted;
 * - the **MCP twins** answer the same way for the same people's keys, except that an admin's MCP
 *   key is capped at author (#215 PK4, record C3) and so sees only its own runs over MCP.
 *
 * Beside `WorkspaceSurfacesE2eTest`, whose session-JWT and seeding shape this follows.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ExecutionsVisibilityTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `an author lists and reads exactly her own session runs - another member's and the endpoint's are not found`() {
        ensureSeeded()
        listedFor(ALICE) shouldContainExactlyInAnyOrder listOf(RUN_ALICE)

        session(ALICE)
            .get("/api/v1/executions/$RUN_ALICE")
            .then()
            .statusCode(200)
            .body("data.executed_by", Matchers.equalTo(ALICE))
        // The 404 rule: Vera's run and the endpoint's are NOT FOUND, never 403.
        session(
            ALICE,
        ).get("/api/v1/executions/$RUN_VERA").then().statusCode(404).body("error.code", Matchers.equalTo("result.execution_not_found"))
        session(ALICE).get("/api/v1/executions/$RUN_ENDPOINT").then().statusCode(404)
        // …and the result cursor and the screen's detail agree with the metadata read.
        session(ALICE).get("/api/v1/executions/$RUN_VERA/result").then().statusCode(404)
        session(ALICE)
            .accept("text/html")
            .get("/executions/$RUN_VERA")
            .then()
            .statusCode(404)
    }

    @Test
    fun `a viewer lists and reads her own run and nobody else's`() {
        ensureSeeded()
        listedFor(VERA) shouldContainExactlyInAnyOrder listOf(RUN_VERA)
        session(VERA).get("/api/v1/executions/$RUN_VERA").then().statusCode(200)
        session(VERA).get("/api/v1/executions/$RUN_ALICE").then().statusCode(404)
    }

    @Test
    fun `a workspace admin lists every run of the workspace, the endpoint-key run included, and reads any of them`() {
        ensureSeeded()
        listedFor(WANDA) shouldContainExactlyInAnyOrder listOf(RUN_ALICE, RUN_VERA, RUN_ENDPOINT, RUN_IDENTITY)
        session(WANDA).get("/api/v1/executions/$RUN_VERA").then().statusCode(200)
        session(WANDA)
            .get("/api/v1/executions/$RUN_ENDPOINT")
            .then()
            .statusCode(200)
            .body("data.executed_by_key_kind", Matchers.equalTo("endpoint"))
        session(WANDA)
            .accept("text/html")
            .get("/executions")
            .then()
            .statusCode(200)
    }

    @Test
    fun `an API key's run is its identity's - the key reads it, its creator does not, an admin does`() {
        ensureSeeded()
        // C5: attributed to the key's own identity, so it is nobody's OWN run but the key's —
        // not even Wanda's, who created the key (she sees it as an admin); the 404 rule, never 403.
        session(ALICE).get("/api/v1/executions/$RUN_IDENTITY").then().statusCode(404)
        session(WANDA)
            .get("/api/v1/executions/$RUN_IDENTITY")
            .then()
            .statusCode(200)
            .body("data.executed_by", Matchers.equalTo(KEY_IDENTITY))
        // A12: api_caller holds execution.read for the runs the key started — this one…
        withKey(API_KEY.plaintext)
            .get("/api/v1/executions/$RUN_IDENTITY")
            .then()
            .statusCode(200)
            .body("data.executed_by_key_kind", Matchers.equalTo("endpoint"))
        // …and no other: another run is not found, never 403.
        withKey(API_KEY.plaintext).get("/api/v1/executions/$RUN_ALICE").then().statusCode(404)
    }

    @Test
    fun `a promoter is refused every execution read by role - REST, the screen and its partial`() {
        ensureSeeded()
        refusedByRole(session(PAM).get("/api/v1/executions"))
        refusedByRole(session(PAM).get("/api/v1/executions/$RUN_ALICE"))
        refusedByRole(session(PAM).get("/api/v1/executions/$RUN_ALICE/result"))
        refusedByRole(session(PAM).accept("text/html").get("/executions"))
        refusedByRole(session(PAM).accept("text/html").get("/executions/$RUN_ALICE"))
        refusedByRole(session(PAM).header("HX-Request", "true").get("/partials/recent-executions"))
        // The dashboard itself renders for a promoter — without the runs panel.
        val dashboard =
            session(PAM)
                .accept("text/html")
                .get("/dashboard")
                .then()
                .statusCode(200)
                .extract()
                .asString()
        dashboard shouldNotContain "/partials/recent-executions"
    }

    @Test
    fun `the MCP tools answer the same way for the same people's keys - an admin's key capped at author`() {
        ensureSeeded()
        // executions_list: own for the author, all for the admin, refused for the promoter.
        toolIds(tool("executions_list", ALICE_KEY.plaintext)) shouldContainExactlyInAnyOrder listOf(RUN_ALICE)
        // PK4 / C3: Wanda's MCP key is capped at author, so over MCP she sees her OWN runs (none);
        // the admin's all-runs view is her session's.
        toolIds(tool("executions_list", WANDA_KEY.plaintext)) shouldContainExactlyInAnyOrder emptyList()
        tool("executions_list", PAM_KEY.plaintext) shouldContain "auth.key_issuer_role_lost"
        // executions_get: the 404 rule for another member's run; the admin reads it.
        tool("executions_get", ALICE_KEY.plaintext, """{"execution_id":"$RUN_VERA"}""") shouldContain "execution_not_found"
        tool("executions_get", ALICE_KEY.plaintext, """{"execution_id":"$RUN_ENDPOINT"}""") shouldContain "execution_not_found"
        tool("executions_get", WANDA_KEY.plaintext, """{"execution_id":"$RUN_VERA"}""") shouldContain "execution_not_found"
        tool("executions_get", PAM_KEY.plaintext, """{"execution_id":"$RUN_ALICE"}""") shouldContain "auth.key_issuer_role_lost"
        // executions_get_result: the same door, on the result.
        tool("executions_get_result", ALICE_KEY.plaintext, """{"execution_id":"$RUN_VERA"}""") shouldContain "execution_not_found"
        tool("executions_get_result", PAM_KEY.plaintext, """{"execution_id":"$RUN_ALICE"}""") shouldContain "auth.key_issuer_role_lost"
    }

    // ------------------------------------------------------------------ drivers

    private fun listedFor(userId: String): List<String> =
        session(userId)
            .get("/api/v1/executions")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList<String>("data.items.execution_id")

    private fun refusedByRole(response: io.restassured.response.Response) {
        response.then().statusCode(403).body("error.code", Matchers.equalTo("auth.role_required"))
    }

    private fun tool(
        name: String,
        key: String,
        arguments: String = "{}",
    ): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""")
            .`when`()
            .post("/mcp")
            .then()
            .extract()
            .asString()

    /** The tool's JSON rides inside a text content block, quotes escaped — unescape before reading it. */
    private fun toolIds(body: String): List<String> = EXECUTION_ID.findAll(unescape(body)).map { it.groupValues[1] }.toList()

    private fun unescape(body: String): String = body.replace("\\\"", "\"")

    private fun withKey(key: String): RequestSpecification =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .accept("application/json")

    private fun session(userId: String): RequestSpecification =
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(userId, "$userId@acme.test"))
            .cookie(CSRF_COOKIE, CSRF)
            .header(CSRF_HEADER, CSRF)
            .accept("application/json")

    companion object {
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val CSRF = "executions-visibility-csrf"
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val SECRET_BYTES = 32
        private const val TOKEN_TTL_SECONDS = 3600L

        private const val WS_ACME = "aca00000-0000-0000-0000-000000000006"
        private const val ALICE = "aaa00000-0000-0000-0000-000000000006"
        private const val VERA = "eee00000-0000-0000-0000-000000000006"
        private const val WANDA = "ada00000-0000-0000-0000-000000000006"
        private const val PAM = "bbb00000-0000-0000-0000-000000000006"
        private const val PIPE_ID = "a1b00000-0000-0000-0000-000000000006"
        private const val RUN_ALICE = "e1000000-0000-0000-0000-000000000006"
        private const val RUN_VERA = "e2000000-0000-0000-0000-000000000006"
        private const val RUN_ENDPOINT = "e3000000-0000-0000-0000-000000000006"
        private const val RUN_IDENTITY = "e4000000-0000-0000-0000-000000000006"
        private const val KEY_IDENTITY = "5e500000-0000-0000-0000-000000000006"

        private val ALICE_KEY = E2eAuth.generateKey("alice-key", ownerId = ALICE)
        private val WANDA_KEY = E2eAuth.generateKey("wanda-key", ownerId = WANDA)
        private val PAM_KEY = E2eAuth.generateKey("pam-key", ownerId = PAM)

        /** An API (`endpoint`) key acting as its own identity, created by Wanda (#215 B4). */
        private val API_KEY = E2eAuth.generateKey("report-caller", ownerId = KEY_IDENTITY)

        private val EXECUTION_ID = Regex("\"execution_id\"\\s*:\\s*\"([0-9a-f-]{36})\"")

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private fun sessionJwt(
            userId: String,
            email: String,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val exp = now.plusSeconds(TOKEN_TTL_SECONDS).epochSecond
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Test User",""" +
                        """"iss":"datapipelines","iat":${now.epochSecond},"exp":$exp,"active_workspace":"acme"}""",
                )
            val signature =
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"))
                    b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
                }
            return "$header.$payload.$signature"
        }

        private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private var seeded = false

        fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement -> seedRows().forEach { statement.execute(it) } }
                insertKeys(connection)
            }
        }

        /** The fixture, in FK order: workspace, users, memberships, a released pipeline, and three finished runs. */
        private fun seedRows(): List<String> =
            listOf(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ACME', 'acme', 'Acme')",
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                    ('$ALICE', '$ALICE@acme.test', 'Alice', 'test', 'alice-sub', TRUE, FALSE),
                    ('$VERA', '$VERA@acme.test', 'Vera', 'test', 'vera-sub', TRUE, FALSE),
                    ('$WANDA', '$WANDA@acme.test', 'Wanda', 'test', 'wanda-sub', TRUE, FALSE),
                    ('$PAM', '$PAM@acme.test', 'Pam', 'test', 'pam-sub', TRUE, FALSE)
                """.trimIndent(),
                // The API key's identity, built the way V34 and ApiKeyService build one (record §3.3).
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES
                    ('$KEY_IDENTITY', '${API_KEY.id}@keys.invalid', 'report-caller', 'key', '${API_KEY.id}', TRUE, FALSE, 'service')
                """.trimIndent(),
                """
                INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                    ('$WS_ACME', '$ALICE', 'author'),
                    ('$WS_ACME', '$VERA', 'viewer'),
                    ('$WS_ACME', '$WANDA', 'workspace_admin'),
                    ('$WS_ACME', '$PAM', 'promoter')
                """.trimIndent(),
                """
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                    ('$PIPE_ID', 'acme/report', 'Acme Report', '', '$ALICE', '$WS_ACME', 1)
                """.trimIndent(),
                """
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                VALUES ('$PIPE_ID', 1, '{"schema_version":1,"name":"report","nodes":[]}'::jsonb, 'seed-hash',
                        'RELEASED', '$ALICE', '$ALICE', NOW())
                """.trimIndent(),
                // Three finished runs: Alice's session run, Vera's session run, and a run a
                // PUBLISHED ENDPOINT started with Alice's key (executed_by = the key owner,
                // key kind = endpoint: nobody's own, an admin's to see).
                """
                INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, parameters_json,
                                                 executed_by, executed_by_key_kind, triggered_via, root_execution_id,
                                                 started_at, completed_at, duration_ms) VALUES
                    ('$RUN_ALICE', '$PIPE_ID', 1, 'SUCCESS', '{}'::jsonb, '$ALICE', NULL, 'UI', '$RUN_ALICE', NOW(), NOW(), 12),
                    ('$RUN_VERA', '$PIPE_ID', 1, 'SUCCESS', '{}'::jsonb, '$VERA', NULL, 'UI', '$RUN_VERA', NOW(), NOW(), 12),
                    ('$RUN_ENDPOINT', '$PIPE_ID', 1, 'SUCCESS', '{}'::jsonb, '$ALICE', 'endpoint', 'ENDPOINT',
                     '$RUN_ENDPOINT', NOW(), NOW(), 12),
                    ('$RUN_IDENTITY', '$PIPE_ID', 1, 'SUCCESS', '{}'::jsonb, '$KEY_IDENTITY', 'endpoint', 'ENDPOINT',
                     '$RUN_IDENTITY', NOW(), NOW(), 12)
                """.trimIndent(),
            )

        private fun insertKeys(connection: Connection) {
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    // The three MCP keys act as their members (created_by = user_id, no role);
                    // the API key acts as its identity and was created by Wanda.
                    listOf(ALICE_KEY, WANDA_KEY, PAM_KEY, API_KEY).forEach { key ->
                        val apiKey = key === API_KEY
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(key.ownerId))
                        ps.setObject(3, UUID.fromString(if (apiKey) WANDA else key.ownerId))
                        ps.setString(4, key.name)
                        ps.setString(5, key.hash)
                        ps.setObject(6, UUID.fromString(WS_ACME))
                        ps.setString(7, if (apiKey) "endpoint" else "user")
                        ps.setString(8, if (apiKey) "api_caller" else null)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

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
            registry.add("datapipelines.jwt.secret") { jwtSecret }
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

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
