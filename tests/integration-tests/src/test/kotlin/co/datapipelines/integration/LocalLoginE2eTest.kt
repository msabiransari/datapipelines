package co.datapipelines.integration

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The local password login (auth.md §5A) over HTTP against the FULL application, with
 * OIDC also configured — the both-methods deployment. Proves at the wire:
 *
 *  - the login page renders the form, then the divider, then the provider buttons;
 *  - a correct password mints the SAME `dp_session` the OIDC path mints and the
 *    session authenticates; the audit row is the same `auth.login.success` event;
 *  - unknown email and wrong password get IDENTICAL responses (status + redirect);
 *  - the login POST is CSRF-protected like every cookie-context state change;
 *  - the per-account lockout engages and refuses even the correct password;
 *  - the password never reaches a log line (the 021 captured-log pattern: expected
 *    event lines asserted PRESENT so the grep cannot pass vacuously).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class LocalLoginE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the login page renders the form, then the divider, then the provider buttons`() {
        val html = loginPageHtml()

        html shouldContain "name=\"email\""
        html shouldContain "name=\"password\""
        html shouldContain "/oauth2/authorization/google"
        // Form before divider before buttons — the owner-ratified layout, not tabs.
        val formAt = html.indexOf("name=\"password\"")
        val dividerAt = html.indexOf(">or<")
        val buttonAt = html.indexOf("/oauth2/authorization/google")
        (dividerAt > formAt) shouldBe true
        (buttonAt > dividerAt) shouldBe true
    }

    @Test
    fun `a correct password mints the same dp_session the OIDC path mints`() {
        seedLocalUsers()
        val successesBefore = auditRows("auth.login.success", LOCAL_EMAIL)

        val response = postLogin(LOCAL_EMAIL, LOCAL_PASSWORD)

        response.statusCode shouldBe 302
        response.location shouldBe "http://localhost:$port/dashboard"
        val session = response.sessionCookie()
        // The session a local login mints authenticates exactly like an OIDC one.
        given()
            .port(port)
            .cookie("dp_session", session)
            .`when`()
            .get("/dashboard")
            .then()
            .statusCode(200)

        auditRows("auth.login.success", LOCAL_EMAIL) shouldBe successesBefore + 1
        columnOf("failed_login_count", LOCAL_EMAIL) shouldBe "0"
    }

    @Test
    fun `unknown email and wrong password get identical responses at the wire`() {
        seedLocalUsers()
        val wrongPassword = postLogin(LOCAL_EMAIL, "definitely-wrong")
        val unknownEmail = postLogin("ghost@datapipelines.test", "definitely-wrong")
        val oidcOnly = postLogin(OIDC_ONLY_EMAIL, LOCAL_PASSWORD)

        wrongPassword.statusCode shouldBe 302
        unknownEmail.statusCode shouldBe wrongPassword.statusCode
        unknownEmail.location shouldBe wrongPassword.location
        oidcOnly.statusCode shouldBe wrongPassword.statusCode
        oidcOnly.location shouldBe wrongPassword.location
        wrongPassword.location shouldBe "http://localhost:$port/login?error=credentials"
        // Neither minted a session.
        wrongPassword.sessionCookieOrNull() shouldBe null
        unknownEmail.sessionCookieOrNull() shouldBe null
    }

    @Test
    fun `the login POST without the CSRF double-submit token is 403`() {
        val response =
            given()
                .port(port)
                .contentType(ContentType.URLENC)
                .formParam("email", LOCAL_EMAIL)
                .formParam("password", LOCAL_PASSWORD)
                .redirects()
                .follow(false)
                .`when`()
                .post("/login")

        response.statusCode shouldBe 403
    }

    @Test
    fun `the per-account lockout engages after N failures and refuses even the correct password`() {
        seedLocalUsers()
        repeat(LOCKOUT_MAX_FAILURES) {
            postLogin(LOCKOUT_EMAIL, "wrong-$it").statusCode shouldBe 302
        }
        auditRows("auth.login.locked", LOCKOUT_EMAIL) shouldBe 1

        val locked = postLogin(LOCKOUT_EMAIL, LOCKOUT_PASSWORD)

        locked.statusCode shouldBe 302
        locked.location shouldBe "http://localhost:$port/login?error=locked"
        locked.sessionCookieOrNull() shouldBe null
    }

    @Test
    fun `a deactivated local account with the correct password sees the inactive banner, never a session`() {
        seedLocalUsers()
        val response = postLogin(INACTIVE_EMAIL, LOCAL_PASSWORD)

        response.statusCode shouldBe 302
        response.location shouldBe "http://localhost:$port/login?error=inactive"
        response.sessionCookieOrNull() shouldBe null
    }

    @Test
    fun `no line the login path logs contains the password`() {
        seedLocalUsers()
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            postLogin(LOCAL_EMAIL, "a-wrong-password-attempt")
            postLogin(LOCAL_EMAIL, LOCAL_PASSWORD)
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }

        // Positive control first (the 021 pattern): the capture must contain the
        // expected event lines, or the negative below proves nothing.
        lines.any { it.contains("event=auth.login.local_outcome") } shouldBe true
        lines.forEach { it shouldNotContain LOCAL_PASSWORD }
    }

    // ------------------------------------------------------------------ helpers

    private fun loginPageHtml(): String =
        given()
            .port(port)
            .`when`()
            .get("/login")
            .then()
            .statusCode(200)
            .extract()
            .asString()

    private data class LoginResponse(
        val statusCode: Int,
        val location: String?,
        private val cookies: Map<String, String>,
    ) {
        fun sessionCookie(): String = checkNotNull(cookies["dp_session"]) { "no dp_session cookie in $cookies" }

        fun sessionCookieOrNull(): String? = cookies["dp_session"]
    }

    /**
     * The real browser flow: GET /login for the `dp_csrf` cookie and the form's hidden
     * token, then POST the form with both (the double-submit, auth.md §8.4).
     */
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
        return LoginResponse(
            statusCode = response.statusCode,
            location = response.headers.getValue("Location"),
            cookies = response.detailedCookies().asList().associate { it.name to it.value },
        )
    }

    private fun auditRows(
        event: String,
        email: String,
    ): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("SELECT COUNT(*) FROM audit_log WHERE event = ? AND details_json ->> 'email' = ?")
                .use { ps ->
                    ps.setString(1, event)
                    ps.setString(2, email)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun columnOf(
        column: String,
        email: String,
    ): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("SELECT $column::TEXT FROM users WHERE email = ?")
                .use { ps ->
                    ps.setString(1, email)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
        }

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val LOCKOUT_MAX_FAILURES = 3
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")

        private val random = SecureRandom()

        /** Generated per run — no literal secret in any test fixture (HIGH-2). */
        private val LOCAL_PASSWORD = "e2e-local-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
        private const val LOCAL_EMAIL = "local-user@datapipelines.test"
        private const val INACTIVE_EMAIL = "inactive-local@datapipelines.test"
        private const val OIDC_ONLY_EMAIL = "oidc-only@datapipelines.test"
        private const val LOCKOUT_EMAIL = "lockout-user@datapipelines.test"
        private val LOCKOUT_PASSWORD = "e2e-lockout-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

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

        private val oidc = OidcDiscoveryStub()

        private val seeded = AtomicBoolean(false)

        /**
         * Seeds the local/OIDC-only accounts exactly once, from inside a test — Flyway
         * has migrated by then. A static @BeforeAll runs BEFORE the context (and with
         * it Flyway) starts, and the table does not exist yet (the failure that shaped
         * TracerBullet's seed-in-first-test pattern).
         */
        private fun seedLocalUsers() {
            if (!seeded.compareAndSet(false, true)) return
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (email, display_name, provider, provider_subject, is_active, password_hash)
                        VALUES
                          ('$LOCAL_EMAIL', 'Local User', 'local', '$LOCAL_EMAIL', TRUE, '${E2eAuth.argon2Hash(LOCAL_PASSWORD)}'),
                          ('$INACTIVE_EMAIL', 'Inactive Local', 'local', '$INACTIVE_EMAIL', FALSE, '${E2eAuth.argon2Hash(LOCAL_PASSWORD)}'),
                          ('$LOCKOUT_EMAIL', 'Lockout User', 'local', '$LOCKOUT_EMAIL', TRUE, '${E2eAuth.argon2Hash(LOCKOUT_PASSWORD)}'),
                          ('$OIDC_ONLY_EMAIL', 'Oidc Only', 'google', 'sub-oidc-only', TRUE, NULL)
                        """.trimIndent(),
                    )
                }
            }
        }

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

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }

            // The feature under test. The rate limit is raised so the FLOW tests are not
            // fighting the per-IP limiter; POST /login metering is pinned by
            // AuthHttpBoundaryTest and LoginRateLimitFilterTest instead.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.local.lockout.max-failures") { LOCKOUT_MAX_FAILURES }
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100 }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
