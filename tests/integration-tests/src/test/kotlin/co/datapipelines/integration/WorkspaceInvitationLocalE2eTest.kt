package co.datapipelines.integration

import io.kotest.matchers.shouldBe
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
import org.testcontainers.containers.GenericContainer
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64

/**
 * The 113 owner scenario for LOCAL accounts, over the wire against the FULL application
 * (auth.md §4.6, §5A): the admin creates local users, invites one into a workspace, and
 * the first one-time-password login lands them there — with NO `demo` membership (D-R11).
 *
 * Both creation orders are pinned, because they take different code paths to the same end:
 *
 *  - **bob**: row created FIRST (without a workspace), then the invite — an invitation for
 *    a user who ALREADY exists is never created (auth.md §4.6 rule 1): the REST verb answers
 *    `200` with a real membership row, the `invitations[]` array stays empty, and the
 *    `workspace.invitation_materialised` event never fires;
 *  - **carol**: row created and the membership written IN THE SAME ACT through the
 *    create-user form's optional workspace + flags (113 §B.3) — no invitation needed
 *    because the row exists.
 *
 * The SSO twin of this suite (invite → FIRST login materialises a pending invitation)
 * lives in `:modules:auth` against the Keycloak harness, where the OIDC flow is real.
 *
 * Everything this suite owns is NAMESPACED (`invite-admin@…`, workspace `invites-acme`)
 * because the module's containers are shared between suites in one JVM run: another
 * suite's `acme`, its members and its audit rows must not be able to turn these
 * assertions green, and these rows must not be able to turn another suite red.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkspaceInvitationLocalE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @Order(1)
    fun `the admin invites an existing local user and the first login lands them in the workspace`() {
        // The seed admin walks the forced-change path, then runs the admin half.
        val adminLogin = postLogin(ADMIN_EMAIL, SEED_PASSWORD)
        postPasswordChange(adminLogin.sessionCookie(), adminLogin.csrfToken, SEED_PASSWORD, NEW_ADMIN_PASSWORD, NEW_ADMIN_PASSWORD)
            .statusCode shouldBe 200
        val admin = adminLogin.sessionCookie()

        createWorkspace(admin, adminLogin.csrfToken, WORKSPACE)

        // The admin creates bob WITHOUT the optional workspace: a local account with a
        // one-time password (auth.md §5A.1), exactly as before this round.
        val bobOneTime = createLocalUser(admin, adminLogin.csrfToken, BOB_EMAIL, workspace = null)

        // NOW the invite. The users row exists, so this is a MEMBERSHIP at once — `200`
        // with the member row, never the `202` invitation echo, and no invitation row.
        given()
            .port(port)
            .cookie("dp_session", admin)
            .cookie("dp_csrf", adminLogin.csrfToken)
            .header("DP-CSRF-Token", adminLogin.csrfToken)
            .contentType(ContentType.JSON)
            .body("""{"email":"$BOB_EMAIL","author":true}""")
            .`when`()
            .post("/api/v1/workspaces/$WORKSPACE/members")
            .then()
            .statusCode(200)
            .body("data.email", Matchers.equalTo(BOB_EMAIL))
            .body("data.author", Matchers.equalTo(true))
            .body("data.invited", Matchers.nullValue())

        invitationRowCount() shouldBe 0

        // Bob's FIRST login: the one-time password walks the forced-change path, and every
        // membership assertion runs AFTER the gate is released (the API refuses until then).
        val bobLogin = postLogin(BOB_EMAIL, bobOneTime)
        bobLogin.statusCode shouldBe 302
        val bobSession = bobLogin.sessionCookie()
        postPasswordChange(bobSession, bobLogin.csrfToken, bobOneTime, NEW_USER_PASSWORD, NEW_USER_PASSWORD)
            .statusCode shouldBe 200

        // Bob's own memberships: EXACTLY the invited workspace, with the invited author
        // flag. No `demo` — D-R11's viewer default fires only for a user with NO
        // membership at all, and bob has had one since before he ever logged in.
        given()
            .port(port)
            .cookie("dp_session", bobSession)
            .`when`()
            .get("/api/v1/workspaces")
            .then()
            .statusCode(200)
            .body("data.name", Matchers.hasItem(WORKSPACE))
            .body("data.name", Matchers.not(Matchers.hasItem("demo")))
            .body("data.author", Matchers.hasItem(true))

        // The members listing separates the arrays: bob is a MEMBER of this workspace.
        given()
            .port(port)
            .cookie("dp_session", admin)
            .cookie("dp_csrf", adminLogin.csrfToken)
            .header("DP-CSRF-Token", adminLogin.csrfToken)
            .`when`()
            .get("/api/v1/workspaces/$WORKSPACE/members")
            .then()
            .statusCode(200)
            .body("data.members.email", Matchers.hasItem(BOB_EMAIL))
            .body("data.invitations", Matchers.empty<Any>())

        // No materialisation event ever fired for this workspace: bob's invite became a
        // membership at once (rule 1), and carol's membership needed none.
        auditEventCount("workspace.invitation_materialised", WORKSPACE) shouldBe 0
        auditEventCount("workspace.member_added", WORKSPACE) shouldBe 1
    }

    @Test
    @Order(2)
    fun `the create-user form's optional workspace writes the membership in the same act`() {
        val adminLogin = postLogin(ADMIN_EMAIL, NEW_ADMIN_PASSWORD)
        val admin = adminLogin.sessionCookie()

        // Carol is created WITH the optional workspace + flags (113 §B.3): the response
        // carries the one-time password AND the membership note.
        val carolOneTime = createLocalUser(admin, adminLogin.csrfToken, CAROL_EMAIL, workspace = WORKSPACE, author = true)

        check(carolOneTime.isNotBlank()) { "expected a one-time password, got blank" }
        val carolLogin = postLogin(CAROL_EMAIL, carolOneTime)
        val carolSession = carolLogin.sessionCookie()
        postPasswordChange(carolSession, carolLogin.csrfToken, carolOneTime, NEW_USER_PASSWORD, NEW_USER_PASSWORD)
            .statusCode shouldBe 200

        given()
            .port(port)
            .cookie("dp_session", carolSession)
            .`when`()
            .get("/api/v1/workspaces")
            .then()
            .statusCode(200)
            .body("data.name", Matchers.hasItem(WORKSPACE))
            .body("data.name", Matchers.not(Matchers.hasItem("demo")))

        // Still no invitation anywhere: both local paths write memberships, because the
        // row exists by the time the membership is written.
        invitationRowCount() shouldBe 0
    }

    // ------------------------------------------------------------------ helpers

    private fun createWorkspace(
        adminSession: String,
        csrf: String,
        name: String,
    ) {
        given()
            .port(port)
            .cookie("dp_session", adminSession)
            .cookie("dp_csrf", csrf)
            .header("DP-CSRF-Token", csrf)
            .contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            // §17.3's create has always answered 201 CREATED — the one workspace verb that
            // does; the members verbs answer 200/202.
            .statusCode(201)
    }

    /** Creates a local user through the REAL admin partial; returns the one-time password. */
    private fun createLocalUser(
        adminSession: String,
        csrf: String,
        email: String,
        workspace: String? = null,
        author: Boolean = false,
    ): String {
        val spec =
            given()
                .port(port)
                .cookie("dp_session", adminSession)
                .cookie("dp_csrf", csrf)
                .header("DP-CSRF-Token", csrf)
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("displayName", email.substringBefore('@'))
        if (workspace != null) {
            spec.formParam("workspace", workspace).formParam("author", author.toString())
        }
        val response =
            spec
                .`when`()
                .post("/partials/admin/users")
        response.statusCode shouldBe 200
        return checkNotNull(ONE_TIME_PASSWORD.find(response.body().asString())) {
            "no one-time password in the create response"
        }.groupValues[1]
    }

    private fun invitationRowCount(): Int {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM workspace_invitations").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    /** Audit rows for [event] naming THIS suite's workspace in `details_json` — other suites' rows cannot leak in. */
    private fun auditEventCount(
        event: String,
        workspace: String,
    ): Int {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("SELECT COUNT(*) FROM audit_log WHERE event = ? AND details_json->>'workspace' = ?")
                .use { ps ->
                    ps.setString(1, event)
                    ps.setString(2, workspace)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        return rs.getInt(1)
                    }
                }
        }
    }

    private data class LoginResponse(
        val statusCode: Int,
        val location: String?,
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
            location = response.headers.getValue("Location"),
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

    companion object {
        /** Namespaced to THIS suite — the module's containers are shared between suites in one run. */
        private const val ADMIN_EMAIL = "invite-admin@datapipelines.test"
        private const val BOB_EMAIL = "bob@datapipelines.test"
        private const val CAROL_EMAIL = "carol@datapipelines.test"
        private const val WORKSPACE = "invites-acme"
        private const val NEW_ADMIN_PASSWORD = "a-brand-new-admin-password"
        private const val NEW_USER_PASSWORD = "a-brand-new-user-password"
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
        }
    }
}
