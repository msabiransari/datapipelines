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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * **The login-minted MCP key, end to end** (179, roles design D16/§3.3 — gate 1 of the lane
 * brief), over the wire against the FULL application: real logins through `POST /login`, the
 * real `WorkspaceService.workspaceForLogin` hook, the real V31 table, and the real sealed
 * store opened by the top bar's copy endpoint.
 *
 * The properties pinned, one per test:
 *
 *  1. A user who owes a forced password change gets NO key — the mint waits for the first
 *     login AFTER the change (the gate, not a race).
 *  2. The first clean login mints exactly one `user` key — `mcp/<workspace>`, the role's
 *     scope set, `minted_at_login`, the sealed secret — and the second login mints NONE.
 *  3. Switching workspaces mints there too (one per user per workspace, V31's index).
 *  4. Delete-to-rotate: the top bar's DELETE revokes; the next switch mints a NEW id.
 *  5. The copy endpoint serves the OPENED secret, and that secret authenticates — the
 *     seal→open round trip is the live key, not a lookalike.
 *  6. No request surface mints a `user` key: REST and htmx answer `auth.key_kind_not_mintable`
 *     (400), on the one surface a workspace admin reaches (the role walk covers the rest).
 *  7. Concurrent logins leave ONE key — the unique index, not the check-then-insert, is the
 *     arbiter.
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
    fun `a login that owes a password change mints nothing - the first clean login mints`() {
        ensureCleanSlate()
        val first = postLogin(ADMIN_EMAIL, SEED_PASSWORD)
        first.statusCode shouldBe 302

        // The bootstrap admin owes the forced change (§5A.4): NO key yet — minting into a
        // session the gate holds would start a credential's life unreachable.
        keyRows(ADMIN_EMAIL) shouldHaveSize 0

        postPasswordChange(first.sessionCookie(), first.csrfToken, SEED_PASSWORD, ADMIN_PASSWORD, ADMIN_PASSWORD)
            .statusCode shouldBe 200

        // The first CLEAN login. The admin holds no membership yet, so D-R11's demo join
        // fires — and the mint lands there, carrying the SUPER ADMIN's ladder (D7), not the
        // demo membership's viewer reach: the key must not be capped below its issuer.
        val clean = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        clean.statusCode shouldBe 302

        val rows = keyRows(ADMIN_EMAIL)
        rows shouldHaveSize 1
        rows.single()["workspace"] shouldBe "demo"
        rows.single()["minted_at_login"] shouldBe true
        rows.single()["name"] shouldBe "mcp/demo"
        rows.single()["secret_sealed"] shouldBe true
        // #215 PK4: the MCP key carries no role and no scopes of its own — it acts as its
        // member (created_by = user_id), whose role is read per request and capped at author.
        rows.single()["role_is_null"] shouldBe true
        rows.single()["self_created"] shouldBe true
    }

    @Test
    @Order(2)
    fun `a second login mints nothing - mine keeps returning the same key`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val firstId = mine(admin.sessionCookie())["id"] as String

        postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)

        keyRows(ADMIN_EMAIL) shouldHaveSize 1
        (mine(admin.sessionCookie())["id"] as String) shouldBe firstId
    }

    @Test
    @Order(3)
    fun `switching workspaces mints there - one live key per user per workspace`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        createWorkspace(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        val switched = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)

        // The switch's workspace has its own key, minted by the switch, carrying the role
        // the admin holds THERE (workspace admin → the author's full ladder).
        val rows = keyRows(ADMIN_EMAIL)
        rows shouldHaveSize 2
        val acme = rows.single { it["workspace"] == WS_ACME }
        acme["name"] shouldBe "mcp/$WS_ACME"
        acme["minted_at_login"] shouldBe true
        acme["role_is_null"] shouldBe true
        acme["self_created"] shouldBe true
        // …and the re-stamped session's active workspace is the switched one.
        mine(switched)["name"] shouldBe "mcp/$WS_ACME"
    }

    @Test
    @Order(4)
    fun `delete-to-rotate - the top bar's DELETE revokes, the next switch mints a new id`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val session = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)
        val before = mine(session)["id"] as String

        // The top bar's verb: DELETE /partials/mcp-key, no id — the ONE live key in the
        // active workspace is unambiguous (V31).
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .`when`()
            .delete("/partials/mcp-key")
            .then()
            .statusCode(200)

        // The old key is revoked, and NOTHING is minted until the next entry (a login or a
        // switch — the DP-Workspace header path mints nothing, by design).
        mine(session)["id"] shouldBe null
        keyRows(ADMIN_EMAIL).count { it["workspace"] == WS_ACME } shouldBe 0

        val reentered = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val back = switch(reentered.sessionCookie(), reentered.csrfToken, WS_ACME)

        val after = mine(back)["id"] as String
        after shouldNotBe before
        after shouldMatch Regex("dpk_[A-Z2-7]{12}")
        keyRows(ADMIN_EMAIL).count { it["workspace"] == WS_ACME } shouldBe 1
    }

    @Test
    @Order(5)
    fun `the copy endpoint serves the opened secret, and that secret authenticates`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val session = switch(admin.sessionCookie(), admin.csrfToken, WS_ACME)
        val keyId = mine(session)["id"] as String

        val secret =
            given()
                .port(port)
                .cookie("dp_session", session)
                .`when`()
                .get("/partials/mcp-key/secret")
                .then()
                .statusCode(200)
                .header("Cache-Control", Matchers.containsString("no-store"))
                .extract()
                .asString()

        // The opened secret IS the live key: the id the chip shows, and it serves /mcp …
        secret shouldMatch Regex("^${Regex.escape(keyId)}\\.[A-Z2-7]{48}$")
        mcpList(secret).then().statusCode(200).body("result.isError", Matchers.not(Matchers.equalTo(true)))
        // … and only /mcp (#215 B2): REST refuses it as a key kind, not as a bad credential.
        given()
            .port(port)
            .header("DP-API-Key", secret)
            .`when`()
            .get("/api/v1/auth/me")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("endpoint.key_kind_refused"))
            .body("error.details.reason", Matchers.equalTo("user_key_off_surface"))

        // #213 show-once: that one GET destroyed the copyable copy in the same act that
        // served it — a second GET is 404 (the chip's Copy is gone with it) …
        given()
            .port(port)
            .cookie("dp_session", session)
            .`when`()
            .get("/partials/mcp-key/secret")
            .then()
            .statusCode(404)

        // … and the key itself is untouched: the Argon2id hash still serves a real read.
        mcpList(secret).then().statusCode(200).body("result.isError", Matchers.not(Matchers.equalTo(true)))
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

    @Test
    @Order(6)
    fun `no request surface mints a user key - REST and htmx refuse with the catalogued 400`() {
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val before = keyRows(ADMIN_EMAIL).size

        // REST, default (absent) kind and explicit — both mean `user`, both refused.
        given()
            .port(port)
            .cookie("dp_session", admin.sessionCookie())
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"on-demand"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(400)
            .body("error.code", Matchers.equalTo("auth.key_kind_not_mintable"))
        given()
            .port(port)
            .cookie("dp_session", admin.sessionCookie())
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"on-demand","kind":"user"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(400)
            .body("error.code", Matchers.equalTo("auth.key_kind_not_mintable"))

        // The htmx surface (the /api-keys page's form) — the same refusal, from the funnel.
        // HX-Request is what makes the answer a Shape C toast rather than the 400 page.
        given()
            .port(port)
            .cookie("dp_session", admin.sessionCookie())
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .header("HX-Request", "true")
            .contentType(ContentType.URLENC)
            .formParam("kind", "user")
            .formParam("name", "on-demand")
            .`when`()
            .post("/partials/api-keys")
            .then()
            .statusCode(400)
            .body(Matchers.containsString("auth.key_kind_not_mintable"))

        // Nothing was minted anywhere along the way.
        keyRows(ADMIN_EMAIL) shouldHaveSize before

        // …and the workspace admin's own verb still works: an API (endpoint) key is a 201.
        given()
            .port(port)
            .cookie("dp_session", admin.sessionCookie())
            .cookie("dp_csrf", admin.csrfToken)
            .header("DP-CSRF-Token", admin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"name":"ci","kind":"endpoint"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(201)
            .body("data.kind", Matchers.equalTo("endpoint"))
            .body("data.key", Matchers.startsWith("dpk_"))
    }

    @Test
    @Order(7)
    fun `concurrent logins leave exactly one key - the unique index is the arbiter`() {
        // A fresh member with a settled password who has NEVER logged in clean: both
        // threads mint. (The one-time-password login itself mints nothing — the gate.)
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val oneTime = createLocalUser(admin.sessionCookie(), admin.csrfToken, RACER_EMAIL, workspace = WS_ACME, role = "viewer")
        val racerLogin = postLogin(RACER_EMAIL, oneTime)
        keyRows(RACER_EMAIL) shouldHaveSize 0
        postPasswordChange(racerLogin.sessionCookie(), racerLogin.csrfToken, oneTime, RACER_PASSWORD, RACER_PASSWORD)
            .statusCode shouldBe 200

        val ready = CountDownLatch(1)
        val results = mutableListOf<Int>()
        val threads =
            (1..2).map {
                thread {
                    ready.await()
                    val response = postLogin(RACER_EMAIL, RACER_PASSWORD)
                    synchronized(results) { results += response.statusCode }
                }
            }
        ready.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        results shouldHaveSize 2
        results.forEach { it shouldBe 302 }
        // Two mints raced; the V31 partial unique index decided, and the loser re-read.
        keyRows(RACER_EMAIL) shouldHaveSize 1
    }

    // ------------------------------------------------------------------ helpers

    /**
     * This suite creates its world through the APP (the creation IS the subject), so it
     * cannot `E2eClean.beforeSeeding` (that truncates the demo content the mint-into-demo
     * assertion needs). What it can and must do is delete its OWN namespaced rows, so a
     * re-run on the shared container does not collide with the last run's: workspace
     * `mint-acme`, users `mint-*@datapipelines.test`, and the keys and bindings between
     * them. Dependency order, children first.
     */
    private fun ensureCleanSlate() {
        if (cleaned) return
        cleaned = true
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "DELETE FROM endpoint_key_bindings WHERE api_key_id IN " +
                        "(SELECT id FROM api_keys WHERE name LIKE 'on-demand%' OR workspace_id IN " +
                        "(SELECT id FROM workspaces WHERE name LIKE 'mint-%'))",
                )
                statement.execute(
                    "DELETE FROM api_keys WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'mint-%') " +
                        "OR user_id IN (SELECT id FROM users WHERE email LIKE 'mint-%@datapipelines.test')",
                )
                statement.execute(
                    "DELETE FROM workspace_invitations WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'mint-%')",
                )
                statement.execute(
                    "DELETE FROM workspace_members WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'mint-%') " +
                        "OR user_id IN (SELECT id FROM users WHERE email LIKE 'mint-%@datapipelines.test')",
                )
                statement.execute("DELETE FROM workspaces WHERE name LIKE 'mint-%'")
                statement.execute("DELETE FROM users WHERE email LIKE 'mint-%@datapipelines.test' AND email <> '$ADMIN_EMAIL'")
                // The bootstrap admin is the LocalAdminSeeder's row: keep the row, but a
                // re-run needs the seed credential BACK and the forced change owed again —
                // otherwise the first login's gate assertion runs against a settled account.
                // A real Argon2id hash of THIS run's seed password, from the same helper
                // every suite's key fixture uses (E2eAuth) — the hasher is stateless, so a
                // test-side one matches what the app's verifies.
                val hash = E2eAuth.argon2Hash(SEED_PASSWORD)
                statement.execute(
                    "UPDATE users SET password_hash = '$hash', password_changed_at = NOW(), must_change_password = TRUE, " +
                        "failed_login_count = 0, locked_until = NULL WHERE email = '$ADMIN_EMAIL'",
                )
            }
        }
    }

    private var cleaned = false

    /** The top bar's read: the caller's live MCP key in the session's active workspace. */
    private fun mine(session: String): Map<String, Any?> {
        val body =
            given()
                .port(port)
                .cookie("dp_session", session)
                .`when`()
                .get("/api/v1/auth/api-keys/mine")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap<String, Any?>("data")
        return body ?: emptyMap()
    }

    /** This suite's LIVE `user` key rows: workspace name, name, the V34 role/creator facts, the two V31 columns. */
    private fun keyRows(email: String): List<Map<String, Any>> =
        query(
            """
            SELECT w.name AS workspace, k.name, (k.role IS NULL) AS role_is_null,
                   (k.created_by = k.user_id) AS self_created,
                   k.minted_at_login, (k.secret_sealed IS NOT NULL) AS secret_sealed
              FROM api_keys k
              JOIN users u ON u.id = k.user_id
              JOIN workspaces w ON w.id = k.workspace_id
             WHERE u.email = '$email' AND k.kind = 'user' AND k.is_revoked = FALSE
            """.trimIndent(),
        ) { rs ->
            mapOf(
                "workspace" to rs.getString("workspace"),
                "name" to rs.getString("name"),
                "role_is_null" to rs.getBoolean("role_is_null"),
                "self_created" to rs.getBoolean("self_created"),
                "minted_at_login" to rs.getBoolean("minted_at_login"),
                "secret_sealed" to rs.getBoolean("secret_sealed"),
            )
        }

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

    /** Creates a local user through the REAL admin partial; returns the one-time password. */
    private fun createLocalUser(
        session: String,
        csrf: String,
        email: String,
        workspace: String,
        role: String,
    ): String {
        val response =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", csrf)
                .header("DP-CSRF-Token", csrf)
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("displayName", email.substringBefore('@'))
                .formParam("workspace", workspace)
                .formParam("role", role)
                .`when`()
                .post("/partials/admin/users")
        response.statusCode shouldBe 200
        return checkNotNull(ONE_TIME_PASSWORD.find(response.body().asString())) {
            "no one-time password in the create response"
        }.groupValues[1]
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
        private const val RACER_EMAIL = "mint-racer@datapipelines.test"
        private const val WS_ACME = "mint-acme"
        private const val ADMIN_PASSWORD = "a-brand-new-admin-password"
        private const val RACER_PASSWORD = "a-brand-new-racer-password"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")
        private val ONE_TIME_PASSWORD = Regex("""([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})""")

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
