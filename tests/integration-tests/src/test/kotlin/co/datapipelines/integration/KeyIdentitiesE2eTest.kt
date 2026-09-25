package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
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
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.UUID

/**
 * **Key identities, end to end** (#215 slice (b), record §3.3 / PK5, C5, B1, B2 — brief A.8).
 *
 * An API key created over the product's own REST surface — by a signed-in workspace admin, the
 * only way a person reaches REST since B2 — gets its OWN `service` identity, and this suite
 * follows that identity through its whole life against the full application:
 *
 * 1. **Created with the key** — the `users` row exists (`kind = service`, provider `key`, the
 *    key id as subject, `<key id>@keys.invalid`, no password, not an admin), cannot sign in,
 *    is absent from the users list and 404 on every user-admin route (A3), and appears on the
 *    Keys page beside its key as "<key name> (API key)".
 * 2. **Acts** — a run through its bound endpoint is attributed to the identity, and counted
 *    against the identity's OWN concurrency slot: with one slot per user, the key's second
 *    concurrent call is refused while its CREATOR's session still runs (C5). Before #215 the run
 *    was the creator's, and the creator's run would have been the one refused.
 * 3. **Revoked** — the identity is deactivated with the key, the key's next call is refused, and
 *    the history keeps the name.
 * 4. **The MCP key is MCP-only (B2)** — the admin's own MCP key is refused on REST by kind and
 *    admitted on `/mcp`. The walk over EVERY route, for every role's key, is
 *    `RoleWalkE2eTest`'s (`every REST route refuses each key kind off its surface`), where the
 *    non-vacuity count is the route count.
 * 5. **No key is a super admin (B1, keys v2 form)** — `super_admin` is NOT OFFERABLE (the subset
 *    rule refuses it at the creation route — no workspace membership holds it, D7) and NOT
 *    ACCEPTABLE (V35's CHECK refuses the role on the table itself), while the same person's
 *    SESSION in a workspace they are not a member of is the implicit super admin and authors.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KeyIdentitiesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private lateinit var callerKey: String
    private lateinit var callerKeyId: String

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        registerDatasource()
        template("ki/one.sql", "SELECT 1 AS one")
        template("ki/sleep.sql", "SELECT pg_sleep($SLOW_SECONDS) AS slept, 1 AS marker")
        pipeline(PIPE_ONE, "ki/one.sql")
        pipeline(PIPE_SLOW, "ki/sleep.sql")
        publish("/ki/v1/one", PIPE_ONE, timeoutSeconds = 60)
        // timeout_seconds = 1 against a longer query: the 202 path, so the run is still holding
        // its slot when the next call arrives.
        publish("/ki/v1/slow", PIPE_SLOW, timeoutSeconds = 1)
        val created =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body("""{"name": "$CALLER_NAME", "kind": "endpoint", "bindings": ["/ki"]}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .body("data.role", equalTo("api_caller"))
                .extract()
                .jsonPath()
        callerKey = created.getString("data.key")
        callerKeyId = created.getString("data.id")
    }

    // ------------------------------------------------------------------ 1. created with the key

    @Test
    @Order(1)
    fun `creating an API key creates its identity - a service user that is not a person`() {
        val key = keyRow(callerKeyId)
        withClue("the key acts as its identity and records its creator (B4)") {
            key.getValue("created_by") shouldBe ADMIN_USER
            key.getValue("user_id") shouldNotBe ADMIN_USER
            key.getValue("role") shouldBe "api_caller"
        }
        val identity = userRow(key.getValue("user_id")!!)
        identity.getValue("kind") shouldBe "service"
        identity.getValue("provider") shouldBe "key"
        identity.getValue("provider_subject") shouldBe callerKeyId
        // The address is the key id LOWERCASED (UserService.identityEmail) — emails are stored normalized.
        identity.getValue("email") shouldBe "${callerKeyId.lowercase()}@keys.invalid"
        identity.getValue("display_name") shouldBe CALLER_NAME
        identity.getValue("is_admin") shouldBe "f"
        identity.getValue("is_active") shouldBe "t"
        identity.getValue("has_password") shouldBe "f"
    }

    @Test
    @Order(2)
    fun `the identity cannot sign in, is not in the users list, and every user-admin route is a 404`() {
        val identityId = keyRow(callerKeyId).getValue("user_id")!!
        val email = "${callerKeyId.lowercase()}@keys.invalid"

        // Local sign-in: the `kind = 'human'` predicate refuses before any password is compared.
        val login = postLogin(email, "any-password-at-all")
        withClue("a key identity must never receive a session: ${login.cookies}") {
            login.status shouldBe 302
            login.cookies.keys shouldNotContain "dp_session"
        }

        // A3: the users list is people only, and the admin routes answer an identity not-found.
        val listed =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/auth/users?limit=200")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList<String>("data.items.email")
        listed shouldNotContain email
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/auth/users/$identityId")
            .then()
            .statusCode(404)
        listOf("deactivate", "activate", "grant-admin", "revoke-admin").forEach { verb ->
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .post("/api/v1/auth/users/$identityId/$verb")
                .then()
                .statusCode(404)
        }
        // Nothing moved: still active, still not an admin.
        userRow(identityId).getValue("is_active") shouldBe "t"
        userRow(identityId).getValue("is_admin") shouldBe "f"
    }

    @Test
    @Order(3)
    fun `the identity appears on the Keys page beside its key`() {
        val page =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .accept("text/html")
                .`when`()
                .get("/api-keys")
                .then()
                .statusCode(200)
                .extract()
                .asString()
        page shouldContain "$CALLER_NAME (API key)"
    }

    // ------------------------------------------------------------------ 2. acts

    @Test
    @Order(10)
    fun `a run through its bound endpoint is attributed to the identity and counted against its own slot`() {
        val identityId = keyRow(callerKeyId).getValue("user_id")!!

        val served = serve("/api/ki/v1/one")
        served.statusCode shouldBe 200
        val executionId = served.jsonPath().getString("execution_id")
        val run = executionRow(executionId)
        withClue("C5: the run is the key's identity's, not its creator's") {
            run.getValue("executed_by") shouldBe identityId
            run.getValue("executed_by_key_kind") shouldBe "endpoint"
        }

        // The identity's ONE slot (max-concurrent-executions-per-user = 1 in this context):
        // the slow call answers 202 and keeps running…
        val slow = serve("/api/ki/v1/slow")
        slow.statusCode shouldBe 202
        val slowId = slow.jsonPath().getString("execution_id")
        try {
            // …so the key's next call is refused for ITS limit…
            val refused = serve("/api/ki/v1/one")
            refused.statusCode shouldBe 429
            refused.jsonPath().getString("error.code") shouldBe CONCURRENCY_LIMIT
            // …while the creator's own session still runs: the slot was never theirs.
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .accept("text/event-stream")
                .body("""{"parameters": {}}""")
                .`when`()
                .post("/api/v1/pipelines/${pipelineId(PIPE_ONE)}/execute")
                .then()
                .statusCode(200)
                .body(not(equalTo("")))
        } finally {
            awaitTerminal(slowId)
        }
    }

    // ------------------------------------------------------------------ 3. revoked

    @Test
    @Order(20)
    fun `revoking the key deactivates its identity, refuses its next call, and history keeps the name`() {
        val identityId = keyRow(callerKeyId).getValue("user_id")!!
        val attributed = countRuns(identityId)
        withClue("non-vacuity: the identity has history to keep") { (attributed > 0) shouldBe true }

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/auth/api-keys/$callerKeyId")
            .then()
            .statusCode(204)

        userRow(identityId).getValue("is_active") shouldBe "f"
        val refused = serve("/api/ki/v1/one")
        refused.statusCode shouldBe 401
        refused.jsonPath().getString("error.code") shouldBe "auth.api_key.invalid"

        // History: the rows still name the identity, the identity still carries the key's name,
        // and the Keys page keeps the (now verb-less) row with its "Acts as".
        countRuns(identityId) shouldBe attributed
        userRow(identityId).getValue("display_name") shouldBe CALLER_NAME
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .accept("text/html")
            .`when`()
            .get("/api-keys")
            .then()
            .statusCode(200)
            .extract()
            .asString() shouldContain "$CALLER_NAME (API key)"
    }

    // ------------------------------------------------------------------ 4. B2, 5. B1

    @Test
    @Order(30)
    fun `the admin's MCP key is refused on REST by kind and admitted on mcp`() {
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_MCP_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
            .body("error.code", equalTo("endpoint.key_kind_refused"))
            .body("error.details.reason", equalTo("mcp_key_off_surface"))
        mcp(ADMIN_MCP_KEY.plaintext, "pipelines_list", "{}").statusCode shouldBe 200
    }

    @Test
    @Order(31)
    fun `super admin is not offerable - and not acceptable as a key role`() {
        // NOT OFFERABLE (A14's subset rule + B1): `super_admin` is not even a value the wire
        // parses as a key role — the request is refused before any permission question, and no
        // creator could offer it anyway, because no workspace membership holds it (D7).
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body("""{"name": "ki-super-admin-probe", "kind": "mcp", "role": "super_admin"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(400)
            .body("error.details.role", equalTo("super_admin"))

        // NOT ACCEPTABLE (B1): even a row forged past the service is refused by the DATABASE —
        // `super_admin` is not a value `api_keys.role` accepts (V35's chk_api_keys_role).
        shouldThrow<SQLException> {
            DriverManager
                .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .use { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                                "VALUES (?, ?, ?, 'ki-forged-super', 'forged', ?::uuid, 'mcp', 'super_admin')",
                        ).use { ps ->
                            ps.setString(1, "dpk_FORGEDSUPER00")
                            ps.setObject(2, UUID.fromString(ADMIN_USER))
                            ps.setObject(3, UUID.fromString(ADMIN_USER))
                            ps.setString(4, DEFAULT_WORKSPACE)
                            ps.execute()
                        }
                }
        }

        // The same person's SESSION in the other workspace IS the implicit super admin (D7).
        given()
            .port(port)
            .asSession(E2eSession.jwt(JWT_SECRET, ADMIN_USER, ADMIN_EMAIL, OTHER_WORKSPACE_NAME))
            .contentType(ContentType.JSON)
            .body(
                """{"id": "ki/b1.sql", "dialect": "POSTGRES", "display_name": "B1", "description": "#215 B1.",
                   "imports": [], "body": "SELECT 1"}""",
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    // ------------------------------------------------------------------ drivers

    private fun serve(path: String): Response =
        given()
            .port(port)
            .header(API_KEY_HEADER, callerKey)
            .accept("application/json")
            .`when`()
            .get(path)

    private fun mcp(
        key: String,
        tool: String,
        arguments: String,
    ): Response =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")
            .`when`()
            .post("/mcp")

    /** Waits for [executionId] to leave RUNNING — the slow run must not hold the slot into the next test. */
    private fun awaitTerminal(executionId: String) {
        val deadline = System.currentTimeMillis() + TERMINAL_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (executionRow(executionId).getValue("status") != "RUNNING") return
            Thread.sleep(POLL_MS)
        }
        error("execution $executionId was still RUNNING after ${TERMINAL_BUDGET_MS}ms")
    }

    /** The local sign-in form, driven as a browser drives it; the cookies it left behind. */
    private fun postLogin(
        email: String,
        password: String,
    ): Login {
        val page =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()
        val csrf = checkNotNull(CSRF_FIELD.find(page.asString())) { "no _csrf hidden input on the login page" }.groupValues[1]
        val response =
            given()
                .port(port)
                .cookies(page.detailedCookies().asList().associate { it.name to it.value })
                .contentType(ContentType.URLENC)
                .formParam("_csrf", csrf)
                .formParam("email", email)
                .formParam("password", password)
                .redirects()
                .follow(false)
                .`when`()
                .post("/login")
        return Login(response.statusCode, response.detailedCookies().asList().associate { it.name to it.value })
    }

    private class Login(
        val status: Int,
        val cookies: Map<String, String>,
    )

    // ------------------------------------------------------------------ fixture

    private fun registerDatasource() {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Key identities source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** Creates a template and RELEASES it — a released pipeline may only pin released templates (versioning §6). */
    private fun template(
        id: String,
        body: String,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body(
                """{"id": "$id", "dialect": "POSTGRES", "display_name": "$id", "description": "#215 key identities.",
                   "imports": [], "body": "$body"}""",
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        val hash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .queryParam("name", id)
                .`when`()
                .get("/api/v1/templates")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .header("If-Match", hash)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    /** Creates a one-node pipeline over [templateId] and RELEASES it — an endpoint serves released versions only. */
    private fun pipeline(
        name: String,
        templateId: String,
    ) {
        val created =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(
                    """{"schema_version": 1, "name": "$name", "display_name": "$name", "description": "#215.",
                       "parameters": {}, "nodes": [{"id": "n1", "description": "One read", "type": "DQL",
                       "source": "$DATASOURCE", "template": {"id": "$templateId", "version": 1}, "depends_on": []}]}""",
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
    }

    private fun publish(
        path: String,
        pipeline: String,
        timeoutSeconds: Int,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body("""{"path": "$path", "pipeline": "$pipeline", "timeout_seconds": $timeoutSeconds}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)
    }

    /**
     * The admin (a super admin, and a workspace admin of `default` — so the subset rule offers
     * them every mcp role there, keys v2 A13/A14), a second workspace they are NOT a member of
     * (for the session half of B1), and their mcp key. The API key is created over REST in
     * [seed], never seeded.
     */
    private fun seedRows() {
        metadata { statement ->
            statement.execute(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)" +
                    " VALUES ('$ADMIN_USER', '$ADMIN_EMAIL', 'KI Admin', 'test', 'ki-admin', TRUE, TRUE)",
            )
            statement.execute(
                "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                    " VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER', 'workspace_admin')",
            )
            statement.execute(
                "INSERT INTO workspaces (id, name, display_name, created_by)" +
                    " VALUES ('$OTHER_WORKSPACE', '$OTHER_WORKSPACE_NAME', 'Key identities other', '$ADMIN_USER')",
            )
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            // Keys v2 (A13): the admin's mcp key acts as its own `service` identity and holds its
            // chosen role in its pinned workspace (the person is a workspace_admin there; A14).
            val adminIdentity = "5e5e0000-0000-0000-0000-000000000170"
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                        "('$adminIdentity', '${ADMIN_MCP_KEY.id.lowercase()}@keys.invalid', '${ADMIN_MCP_KEY.name}', 'key', " +
                        "'${ADMIN_MCP_KEY.id}', TRUE, FALSE, 'service')",
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                ).use { ps ->
                    ps.setString(1, ADMIN_MCP_KEY.id)
                    ps.setString(2, adminIdentity)
                    ps.setString(3, ADMIN_USER)
                    ps.setString(4, ADMIN_MCP_KEY.name)
                    ps.setString(5, ADMIN_MCP_KEY.hash)
                    ps.setString(6, DEFAULT_WORKSPACE)
                    ps.executeUpdate()
                }
        }
    }

    private fun metadata(block: (java.sql.Statement) -> Unit) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use(block)
        }
    }

    private fun single(sql: String): Map<String, String?> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    check(rs.next()) { "no row for: $sql" }
                    (1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getString(it) }
                }
            }
        }

    private fun keyRow(keyId: String): Map<String, String?> =
        single("SELECT user_id::text AS user_id, created_by::text AS created_by, role FROM api_keys WHERE id = '$keyId'")

    private fun userRow(userId: String): Map<String, String?> =
        single(
            "SELECT kind, provider, provider_subject, email, display_name, is_admin, is_active," +
                " (password_hash IS NOT NULL) AS has_password FROM users WHERE id = '$userId'",
        )

    private fun executionRow(executionId: String): Map<String, String?> =
        single(
            "SELECT executed_by::text AS executed_by, executed_by_key_kind, status FROM pipeline_executions" +
                " WHERE execution_id = '$executionId'",
        )

    private fun countRuns(userId: String): Int =
        single("SELECT COUNT(*)::text AS n FROM pipeline_executions WHERE executed_by = '$userId'").getValue("n")!!.toInt()

    private fun pipelineId(name: String): String =
        single("SELECT id::text AS id FROM pipelines WHERE name = '$name' AND workspace_id = '$DEFAULT_WORKSPACE'").getValue("id")!!

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val OTHER_WORKSPACE = "defa0000-0000-0000-0000-000000000215"
        private const val OTHER_WORKSPACE_NAME = "ki-other"
        private const val DATASOURCE = "ki-source"
        private const val PIPE_ONE = "ki/one"
        private const val PIPE_SLOW = "ki/slow"
        private const val CALLER_NAME = "ki-caller"
        private const val CONCURRENCY_LIMIT = "pipeline.execution.concurrency_limit"
        private const val ADMIN_EMAIL = "ki-admin@datapipelines.test"
        private const val SLOW_SECONDS = 4
        private const val TERMINAL_BUDGET_MS = 30_000L
        private const val POLL_MS = 250L
        private const val SECRET_BYTES = 32

        private val ADMIN_USER: String = UUID.randomUUID().toString()
        private val ADMIN_MCP_KEY = E2eAuth.generateKey("mcp/default")

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret`; REST is a session's surface (B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, ADMIN_EMAIL)

        private val postgres get() = SharedE2e.postgres
        private val source = SharedE2e.scratchDatabase("key_identities_source")
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { java.security.SecureRandom().nextBytes(it) })

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
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            // Local accounts: §7 needs an auth method, and the sign-in refusal is walked through it.
            registry.add("datapipelines.auth.local.enabled") { "true" }
            // ONE slot per user, so "counted against ITS limit" is observable in two requests.
            registry.add("datapipelines.executor.max-concurrent-executions-per-user") { "1" }
        }
    }
}
