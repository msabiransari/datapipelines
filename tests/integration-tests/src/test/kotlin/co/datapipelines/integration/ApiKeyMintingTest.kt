package co.datapipelines.integration

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.Matchers
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64
import kotlin.concurrent.thread

/**
 * **Keys v2: creation, over the wire** (keys v2 A13–A19 — the lane's gates), against the FULL
 * application: real logins through `POST /login`, the real Keys-page and REST creation paths,
 * the real subset rule, and the real database CHECK.
 *
 * The properties pinned, one per test:
 *
 *  1. **A first login mints NOTHING** — no membership event creates a key (A15); the gate's
 *     falsification: the mint's old hook, restored in a test configuration, fails the first
 *     assertion. A login that owes a password change mints nothing either (it minted nothing
 *     before, for a different reason).
 *  2. Creating an `mcp` key over REST works: role `author` on the row, an identity as its
 *     user, the creator as `created_by`, and the plaintext exactly once.
 *  3. The subset rule over the wire: a PROMOTER may mint `promoter` but not `author`; a
 *     workspace admin may mint all three; a viewer reaches no create route at all.
 *  4. A duplicate live name is a catalogued 409; revoking frees the name (A18).
 *  5. Revoke-own: `DELETE /api/v1/auth/api-keys/{id}` revokes a key the caller created — and
 *     is silent on a key it did not (A14).
 *  6. The retired wires stay retired: kind `user` is an unknown kind; an absent kind is the
 *     catalogued 400; `GET /api/v1/auth/api-keys/mine` and `DELETE /partials/mcp-key` no
 *     longer exist.
 *
 * Namespaced (`mint-*@…`, `mint-acme`) because the module's containers are shared between
 * suites in one JVM run.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApiKeyMintingTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @Order(1)
    fun `a first login mints nothing - and neither does a login that owes a password change`() {
        ensureCleanSlate()
        val first = postLogin(ADMIN_EMAIL, SEED_PASSWORD)
        first.statusCode shouldBe 302

        // The bootstrap admin owes the forced change (§5A.4): no key.
        keyRows(ADMIN_EMAIL) shouldHaveSize 0

        postPasswordChange(first.sessionCookie(), first.csrfToken, SEED_PASSWORD, ADMIN_PASSWORD, ADMIN_PASSWORD)
            .statusCode shouldBe 200

        // The first CLEAN login — and still no key anywhere: the login mint is retired
        // (keys v2 A15). The falsification: restoring `McpKeyMint` in AuthConfiguration and
        // `ApiKeyService.mintLoginKey` makes this exact assertion red.
        postLogin(ADMIN_EMAIL, ADMIN_PASSWORD).statusCode shouldBe 302
        keyRows(ADMIN_EMAIL) shouldHaveSize 0

        // A workspace creation plus switch mints nothing either (the switch half of D16, gone).
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        createWorkspace(admin.sessionCookie(), admin.csrfToken, WS_ACME)
        switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)
        keyRows(ADMIN_EMAIL) shouldHaveSize 0
    }

    @Test
    @Order(2)
    fun `creating an mcp key on the Keys-page path works - role, identity, creator, plaintext once`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val session = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        val created =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", admin.csrfToken)
                .header("DP-CSRF-Token", admin.csrfToken)
                .contentType(ContentType.JSON)
                .body("""{"name":"my-agent","kind":"mcp","role":"author"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .body("data.kind", Matchers.equalTo("mcp"))
                .body("data.role", Matchers.equalTo("author"))
                .body("data.key", Matchers.startsWith("dpk_"))
                .extract()

        val plaintext = created.jsonPath().getString("data.key")
        plaintext shouldMatch Regex("^dpk_[A-Z2-7]{12}\\.[A-Z2-7]{48}$")

        val rows = keyRows(ADMIN_EMAIL)
        rows shouldHaveSize 1
        rows.single()["workspace"] shouldBe WS_ACME
        rows.single()["name"] shouldBe "my-agent"
        rows.single()["role"] shouldBe "author"
        // Identity-backed (A13): the key acts as a `service` row, and the creator is the person.
        rows.single()["identity_kind"] shouldBe "service"
        rows.single()["created_by_is_me"] shouldBe true

        // The created key authenticates over /mcp — its ONE surface — and is refused off it
        // with the keys-v2 reason.
        mcpList(plaintext).then().statusCode(200).body("result.isError", Matchers.not(Matchers.equalTo(true)))
        given()
            .port(port)
            .header("DP-API-Key", plaintext)
            .`when`()
            .get("/api/v1/auth/me")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("endpoint.key_kind_refused"))
            .body("error.details.reason", Matchers.equalTo("mcp_key_off_surface"))
    }

    @Test
    @Order(3)
    @Suppress("LongMethod") // every ordered creator-role x requested-role cell is one matrix row; splitting it orphans a cell
    fun `the subset rule over the wire - a promoter mints promoter only, a workspace admin all three, a viewer nothing`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val session = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        // A promoter and a viewer join the workspace — seeded as rows + memberships (the
        // members partial INVITES an unknown email; local accounts are not its subject).
        seedMember(PROMOTER_EMAIL, "promoter")
        seedMember(VIEWER_EMAIL, "viewer")

        val promoter = postLogin(PROMOTER_EMAIL, MEMBER_PASSWORD)
        promoter.statusCode shouldBe 302

        // A promoter may mint a promoter-role key (equal columns)…
        given()
            .port(port)
            .cookie("dp_session", promoter.sessionCookie())
            .cookie("dp_csrf", promoter.csrfToken)
            .header("DP-CSRF-Token", promoter.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"p-agent","kind":"mcp","role":"promoter"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)

        // …and NOT an author-role key: A14's refusal names the ROLE that was asked for and
        // the role the creator was judged as.
        given()
            .port(port)
            .cookie("dp_session", promoter.sessionCookie())
            .cookie("dp_csrf", promoter.csrfToken)
            .header("DP-CSRF-Token", promoter.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"p-agent-2","kind":"mcp","role":"author"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.role_required"))
            .body("error.details.required", Matchers.equalTo("author"))
            .body("error.details.held", Matchers.equalTo("promoter"))

        // A viewer holds no mcp_key.create at all: the route refuses before the body is read.
        val viewer = postLogin(VIEWER_EMAIL, MEMBER_PASSWORD)
        given()
            .port(port)
            .cookie("dp_session", viewer.sessionCookie())
            .cookie("dp_csrf", viewer.csrfToken)
            .header("DP-CSRF-Token", viewer.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"v-agent","kind":"mcp","role":"author"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.role_required"))
            .body("error.details.required", Matchers.equalTo("mcp_key.create"))

        // The workspace admin mints the remaining roles.
        val wsAdmin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val adminSession = switch(wsAdmin.sessionCookie(), wsAdmin.csrfToken, WS_ACME)
        listOf("promoter", "workspace_admin").forEach { role ->
            given()
                .port(port)
                .cookie("dp_session", adminSession)
                .cookie("dp_csrf", wsAdmin.csrfToken)
                .header("DP-CSRF-Token", wsAdmin.csrfToken)
                .contentType(ContentType.JSON)
                .body("""{"name":"admin-$role","kind":"mcp","role":"$role"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .body("data.role", Matchers.equalTo(role))
        }
    }

    @Test
    @Order(4)
    fun `a duplicate live name is a catalogued conflict, and revoking frees the name (A18)`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val session = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"dup-agent","kind":"mcp","role":"author"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)

        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"dup-agent","kind":"mcp","role":"author"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("auth.key_name_taken"))
            .body("error.details.reason", Matchers.equalTo("key_name_taken"))

        // Revoke-own (A14): the creator's delete — and the name is free again.
        val keyId = keyRows(ADMIN_EMAIL).single { it["name"] == "dup-agent" && it["live"] == true }["id"] as String
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .`when`()
            .delete("/api/v1/auth/api-keys/$keyId")
            .then()
            .statusCode(204)

        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"dup-agent","kind":"mcp","role":"promoter"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
    }

    @Test
    @Order(5)
    fun `revoke-own - the creator revokes theirs, the workspace admin revokes any (A14)`() {
        // The promoter creates a key of their own...
        val promoter = postLogin(PROMOTER_EMAIL, MEMBER_PASSWORD)
        val promoterSession = switch(promoter.sessionCookie(), promoter.csrfToken, WS_ACME)
        val latestAnswer =
            given()
                .port(port)
                .cookie("dp_session", promoterSession)
                .cookie("dp_csrf", promoter.csrfToken)
                .header("DP-CSRF-Token", promoter.csrfToken)
                .contentType(ContentType.JSON)
                .body("""{"name":"p-agent-del","kind":"mcp","role":"promoter"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
        println("DEBUG p-agent-del -> " + latestAnswer.statusCode + " " + latestAnswer.body().asString().take(300))
        latestAnswer
            .then()
            .statusCode(201)

        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val adminSession = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        val foreignId =
            keyRows(PROMOTER_EMAIL).single { it["name"] == "p-agent-del" && it["live"] == true }["id"] as String

        // The admin holds api_key.revoke — a foreign key is revoked through the same route.
        given()
            .port(port)
            .cookie("dp_session", adminSession)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .`when`()
            .delete("/api/v1/auth/api-keys/$foreignId")
            .then()
            .statusCode(204)
        keyRows(PROMOTER_EMAIL).count { it["name"] == "p-agent-del" && it["live"] == true } shouldBe 0
    }

    @Test
    @Order(6)
    fun `the retired wires stay retired - user kind, absent kind, mine, rotate`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val session = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        // kind `user` — the pre-v2 wire word — is simply unknown now (A19) …
        val debugAnswer =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", admin.csrfToken)
                .header("DP-CSRF-Token", admin.csrfToken)
                .contentType(ContentType.JSON)
                .body("""{"name":"claude","kind":"user"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
        debugAnswer
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("endpoint.key_kind_refused"))

        // … and an ABSENT kind is the catalogued 400 (there is no default any more).
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"claude"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(400)
            .body("error.code", Matchers.equalTo("auth.key_kind_not_mintable"))

        // The chip's endpoints are gone with the chip (A15). `mine` now falls inside the
        // {keyId} template, which answers GET with 405 — either way the route is gone.
        given()
            .port(port)
            .cookie("dp_session", session)
            .`when`()
            .get("/api/v1/auth/api-keys/mine")
            .then()
            .statusCode(405)
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .`when`()
            .delete("/partials/mcp-key")
            .then()
            // No mapping answers this any more; Spring's no-handler answer for the verb.
            .statusCode(404)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * This suite creates its world through the APP (the creation IS the subject), so it
     * cannot `E2eClean.beforeSeeding`. What it can and must do is delete its OWN namespaced
     * rows, so a re-run on the shared container does not collide with the last run's.
     * Dependency order, children first — identities included (keys v2: every key has one).
     */
    private fun ensureCleanSlate() {
        if (cleaned) return
        cleaned = true
        val memberEmails = listOf(PROMOTER_EMAIL, VIEWER_EMAIL)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val mintUsers =
                    (memberEmails + ADMIN_EMAIL)
                        .joinToString(", ") { "'$it'" }
                statement.execute(
                    "DELETE FROM api_keys WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'mint-%') " +
                        "OR created_by IN (SELECT id FROM users WHERE email IN ($mintUsers))",
                )
                statement.execute(
                    "DELETE FROM users WHERE kind = 'service' AND email LIKE '%@keys.invalid' AND " +
                        "provider_subject IN (SELECT id FROM api_keys WHERE workspace_id IN " +
                        "(SELECT id FROM workspaces WHERE name LIKE 'mint-%'))",
                )
                statement.execute(
                    "DELETE FROM workspace_invitations WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'mint-%')",
                )
                statement.execute(
                    "DELETE FROM workspace_members WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'mint-%') " +
                        "OR user_id IN (SELECT id FROM users WHERE email IN ($mintUsers))",
                )
                statement.execute("DELETE FROM workspaces WHERE name LIKE 'mint-%'")
                statement.execute("DELETE FROM users WHERE email IN ($mintUsers) AND email <> '$ADMIN_EMAIL'")
                // The bootstrap admin is the LocalAdminSeeder's row: keep the row, but a
                // re-run needs the seed credential BACK and the forced change owed again.
                val hash = E2eAuth.argon2Hash(SEED_PASSWORD)
                statement.execute(
                    "UPDATE users SET password_hash = '$hash', password_changed_at = NOW(), must_change_password = TRUE, " +
                        "failed_login_count = 0, locked_until = NULL WHERE email = '$ADMIN_EMAIL'",
                )
            }
        }
    }

    private var cleaned = false

    /** This suite's key rows CREATED BY [email]: the facts keys v2 states about each. */
    private fun keyRows(email: String): List<Map<String, Any?>> =
        query(
            """
            SELECT k.id, w.name AS workspace, k.name, COALESCE(k.role, 'NULL') AS role,
                   u.kind AS identity_kind, (k.created_by = k2.id) AS created_by_is_me,
                   NOT k.is_revoked AS live
              FROM api_keys k
              JOIN workspaces w ON w.id = k.workspace_id
              JOIN users k2 ON k2.id = k.created_by
              LEFT JOIN users u ON u.id = k.user_id
             WHERE k2.email = '$email'
             ORDER BY k.created_at
            """.trimIndent(),
        ) { rs ->
            mapOf(
                "id" to rs.getString("id"),
                "workspace" to rs.getString("workspace"),
                "name" to rs.getString("name"),
                "role" to rs.getString("role"),
                "identity_kind" to rs.getString("identity_kind"),
                "created_by_is_me" to rs.getBoolean("created_by_is_me"),
                "live" to rs.getBoolean("live"),
            )
        }

    /**
     * Seeds [email] as a SETTLED local member of `mint-acme` with [role] — straight to SQL,
     * because the members partial INVITES an unknown email rather than creating a local
     * account, and this suite's subject is the key, not the invitation flow. The Argon2id
     * hash comes from the same helper every suite's key fixture uses.
     */
    private fun seedMember(
        email: String,
        role: String,
    ) {
        val hash = E2eAuth.argon2Hash(MEMBER_PASSWORD)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, password_hash, " +
                        "password_changed_at, must_change_password) VALUES " +
                        "(gen_random_uuid(), '$email', '${email.substringBefore('@')}', 'local', '$email', TRUE, '$hash', NOW(), FALSE)" +
                        " ON CONFLICT (email) DO NOTHING",
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "SELECT (SELECT id FROM workspaces WHERE name = '$WS_ACME'), id, '$role' FROM users WHERE email = '$email'",
                )
            }
        }
    }

    /** `pipelines_list` over `/mcp` — the MCP key's one surface (#215 B2). */
    private fun mcpList(key: String): Response =
        given()
            .port(port)
            .header("DP-API-Key", key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"pipelines_list","arguments":{}}}""")
            .`when`()
            .post("/mcp")

    private fun createWorkspace(
        session: String,
        csrf: String,
        name: String,
    ) {
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", csrf)
            .header("DP-CSRF-Token", csrf)
            .contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(201)
    }

    /** The switcher POST; returns the RE-STAMPED session cookie (the old one is dead weight). */
    private fun switch(
        session: String,
        csrf: String,
        workspace: String,
    ): String {
        val response =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", csrf)
                .header("DP-CSRF-Token", csrf)
                .contentType(ContentType.URLENC)
                .formParam("name", workspace)
                .redirects()
                .follow(false)
                .`when`()
                .post("/workspace/switch")
                .then()
                .statusCode(302)
                .extract()
        return response.detailedCookies().firstOrNull { it.name == "dp_session" }?.value ?: session
    }

    private data class LoginResponse(
        val statusCode: Int,
        private val cookies: Map<String, String>,
    ) {
        fun sessionCookie(): String = checkNotNull(cookies["dp_session"]) { "no dp_session cookie in $cookies" }

        /** The `dp_csrf` cookie IS the token (plain double-submit, auth.md §8.4). */
        val csrfToken: String get() = checkNotNull(cookies["dp_csrf"]) { "no dp_csrf cookie in $cookies" }
    }

    /** The real browser flow: GET /login for the cookies + hidden token, then POST. */
    private fun postLogin(
        email: String,
        password: String,
    ): LoginResponse {
        val page =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()
        val csrf =
            checkNotNull(CSRF_FIELD.find(page.asString())) { "no _csrf hidden input on the login page" }
                .groupValues[1]
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
        // dp_csrf rides the PAGE response (the POST does not re-issue it); dp_session
        // rides the POST. Merge, POST winning.
        val cookies =
            page.detailedCookies().asList().associate { it.name to it.value } +
                response.detailedCookies().asList().associate { it.name to it.value }
        return LoginResponse(
            statusCode = response.statusCode,
            cookies = cookies,
        )
    }

    private fun postPasswordChange(
        session: String,
        csrf: String,
        current: String,
        new: String,
        confirm: String,
    ): Response =
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", csrf)
            .header("DP-CSRF-Token", csrf)
            .contentType(ContentType.URLENC)
            .formParam("currentPassword", current)
            .formParam("newPassword", new)
            .formParam("confirmPassword", confirm)
            .`when`()
            .post("/partials/account/password")

    private fun <T> query(
        sql: String,
        row: (ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) row(rs) else null }.toList()
                }
            }
        }

    companion object {
        /** Namespaced to THIS suite — the module's containers are shared between suites in one run. */
        private const val ADMIN_EMAIL = "mint-admin@datapipelines.test"
        private const val PROMOTER_EMAIL = "mint-promoter@datapipelines.test"
        private const val VIEWER_EMAIL = "mint-viewer@datapipelines.test"
        private const val WS_ACME = "mint-acme"
        private const val ADMIN_PASSWORD = "a-brand-new-admin-password"
        private const val MEMBER_PASSWORD = "a-brand-new-member-password"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")

        private val random = SecureRandom()

        /** Generated per run — no literal secret in any test fixture (HIGH-2). */
        private val SEED_PASSWORD = "e2e-seed-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(32).also { random.nextBytes(it) })

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

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // Local-only deployment (no OIDC at all), the zero-setup shape (auth.md §5A.2).
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.bootstrap-admin-email") { ADMIN_EMAIL }
            registry.add("datapipelines.auth.local.bootstrap-password") { SEED_PASSWORD }
            // This suite logs in CONSTANTLY (that is its subject); the per-IP login limit
            // would 429 the later tests for the earlier ones' traffic.
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100000 }
        }
    }
}
