package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64

/**
 * The zero-setup deployment (auth.md §5A) end to end, against the FULL application
 * starting with **no OIDC configuration at all** — no providers, no base-url — only
 * local accounts and the config-seeded first admin:
 *
 *  1. startup succeeds with zero OIDC providers (the stock `google` entry binds
 *     empty and is ignored; `ConfigValidator` accepts local as the one auth method);
 *  2. the login page renders the form alone — no divider, no provider buttons;
 *  3. the seeded one-time credential logs in — and the §5A.4 gate redirects every
 *     route to the change screen until it is replaced;
 *  4. the self-service change (the §7.6 `CHANGE_OWN_PASSWORD` partial, CSRF
 *     double-submit) releases the gate;
 *  5. the admin creates a local user, whose one-time password walks the same
 *     forced-change path — the whole feature in one narrative, no email anywhere.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class LocalAdminSeedE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the zero-setup deployment end to end - seed, forced change, admin-created user`() {
        assertLoginPageIsLocalOnly()
        val adminLogin = postLogin(ADMIN_EMAIL, SEED_PASSWORD)
        assertGateEngaged(adminLogin)
        assertChangeReleasesGate(adminLogin)
        adminCreatesUserWhoWalksTheSamePath(adminLogin)
        assertRowStates()
    }

    /** (1)+(2) — startup with zero OIDC, and the form-only login page. */
    private fun assertLoginPageIsLocalOnly() {
        val html =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()
                .asString()
        html shouldContain "name=\"password\""
        html shouldNotContain ">or<"
        html shouldNotContain "/oauth2/authorization/"
    }

    /** (3) — the seeded credential logs in, and the §5A.4 gate engages immediately. */
    private fun assertGateEngaged(adminLogin: LoginResponse) {
        adminLogin.statusCode shouldBe 302
        adminLogin.location shouldBe "http://localhost:$port/dashboard"
        val adminSession = adminLogin.sessionCookie()

        val gated = getNoFollow("/", adminSession)
        gated.statusCode shouldBe 302
        gated.headers.getValue("Location") shouldBe "http://localhost:$port/settings/password"
        // The allowlisted screen itself answers.
        given()
            .port(port)
            .cookie("dp_session", adminSession)
            .`when`()
            .get("/settings/password")
            .then()
            .statusCode(200)
        // A JSON client gets the envelope, not a redirect.
        val apiResponse =
            given()
                .port(port)
                .cookie("dp_session", adminSession)
                .`when`()
                .get("/api/v1/auth/me")
        apiResponse.statusCode shouldBe 403
        apiResponse.body().asString() shouldContain "auth.password.change_required"
    }

    /** (4) — wrong current password: refused, still gated. Right one: the gate releases. */
    private fun assertChangeReleasesGate(adminLogin: LoginResponse) {
        val adminSession = adminLogin.sessionCookie()
        postPasswordChange(adminSession, adminLogin.csrfToken, "not-the-seed", NEW_ADMIN_PASSWORD, NEW_ADMIN_PASSWORD)
            .statusCode shouldBe 400
        postPasswordChange(adminSession, adminLogin.csrfToken, SEED_PASSWORD, NEW_ADMIN_PASSWORD, NEW_ADMIN_PASSWORD)
            .statusCode shouldBe 200
        given()
            .port(port)
            .cookie("dp_session", adminSession)
            .`when`()
            .get("/dashboard")
            .then()
            .statusCode(200)
    }

    /** (5) — the admin creates a local user, whose one-time password walks the same forced-change path. */
    private fun adminCreatesUserWhoWalksTheSamePath(adminLogin: LoginResponse) {
        val createResponse =
            given()
                .port(port)
                .cookie("dp_session", adminLogin.sessionCookie())
                .cookie("dp_csrf", adminLogin.csrfToken)
                .header("DP-CSRF-Token", adminLogin.csrfToken)
                .contentType(ContentType.URLENC)
                .formParam("email", CREATED_EMAIL)
                .formParam("displayName", "Created User")
                .`when`()
                .post("/partials/admin/users")
        createResponse.statusCode shouldBe 200
        val oneTime =
            checkNotNull(ONE_TIME_PASSWORD.find(createResponse.body().asString())) {
                "no one-time password in the create response"
            }.groupValues[1]

        val userLogin = postLogin(CREATED_EMAIL, oneTime)
        userLogin.statusCode shouldBe 302
        val userSession = userLogin.sessionCookie()
        getNoFollow("/", userSession).statusCode shouldBe 302
        postPasswordChange(userSession, userLogin.csrfToken, oneTime, NEW_USER_PASSWORD, NEW_USER_PASSWORD)
            .statusCode shouldBe 200
        given()
            .port(port)
            .cookie("dp_session", userSession)
            .`when`()
            .get("/dashboard")
            .then()
            .statusCode(200)
    }

    /** The row state matches the journey. */
    private fun assertRowStates() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("SELECT must_change_password, is_admin, provider FROM users WHERE email = ?")
                .use { ps ->
                    ps.setString(1, ADMIN_EMAIL)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean("must_change_password") shouldBe false
                        rs.getBoolean("is_admin") shouldBe true
                        rs.getString("provider") shouldBe "bootstrap"
                    }
                }
            connection
                .prepareStatement("SELECT must_change_password, provider FROM users WHERE email = ?")
                .use { ps ->
                    ps.setString(1, CREATED_EMAIL)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getBoolean("must_change_password") shouldBe false
                        rs.getString("provider") shouldBe "local"
                    }
                }
        }
    }

    // ------------------------------------------------------------------ helpers

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

    private fun getNoFollow(
        path: String,
        session: String,
    ): Response =
        given()
            .port(port)
            .cookie("dp_session", session)
            .redirects()
            .follow(false)
            .`when`()
            .get(path)

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val ADMIN_EMAIL = "seed-admin@datapipelines.test"
        private const val CREATED_EMAIL = "created-user@datapipelines.test"
        private const val NEW_ADMIN_PASSWORD = "a-brand-new-admin-password"
        private const val NEW_USER_PASSWORD = "a-brand-new-user-password"
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")
        private val ONE_TIME_PASSWORD = Regex("""([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})""")

        private val random = SecureRandom()

        /** Generated per run — no literal secret in any test fixture (HIGH-2). */
        private val SEED_PASSWORD = "e2e-seed-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

            // Generated per run — no literal secret in any test fixture (HIGH-2).
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // NO OIDC AT ALL: zero providers, no base-url. The stock google entry binds
            // empty (GOOGLE_CLIENT_ID unset) and is ignored with a WARN — the whole
            // point of this context is that nothing OIDC is required to start.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.bootstrap-admin-email") { ADMIN_EMAIL }
            registry.add("datapipelines.auth.local.bootstrap-password") { SEED_PASSWORD }
        }
    }
}
