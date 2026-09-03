package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import de.mkammerer.argon2.Argon2Factory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Paths
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64

/**
 * The browser suite's boot harness (module-structure §5.12, TEST-GAP-2026-09.md).
 *
 * One full application per spec class: SpringBootTest on a random port against the
 * module's [SharedBrowserE2e] containers (round 060's one-container-per-JVM convention —
 * never a per-class `@Container`), LOCAL accounts enabled, no OIDC providers — one
 * command, zero manual setup. The seeded users' stored hashes are real Argon2id of the
 * known passwords with auth's exact parameters (2 / 19 456 / 1), same declared-exception
 * rationale as the integration suite's seeding.
 *
 * **Order independence is a gate, not a hope** (060's shuffle seeds run the suite in any
 * order): every test seeds its OWN user with a unique email, and no test's assertions
 * depend on another test having run. [seedLocalUser] is idempotent per email.
 *
 * Playwright runs chromium-only, headless, with tracing captured to build/reports.
 * No timing sleeps anywhere: Playwright's auto-wait and explicit response waits are the
 * only synchronization primitives.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
abstract class BrowserSuite {
    @LocalServerPort
    protected var port: Int = 0

    protected val baseUrl: String get() = "http://localhost:$port"

    protected lateinit var page: Page
    private lateinit var context: BrowserContext

    @BeforeEach
    fun freshPage() {
        context = newBrowserContext()
        page = context.newPage()
    }

    @AfterEach
    fun closePage() {
        // The trace covers exactly one test; stopped here (and saved) if one was started.
        if (tracingThisTest) {
            context.tracing().stop(Tracing.StopOptions().setPath(tracePath()))
            tracingThisTest = false
        }
        context.close()
    }

    /** Subclass tests call this FIRST so a failing run leaves a diagnoseable trace. */
    protected fun startTrace() {
        context.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
        tracingThisTest = true
    }

    private fun tracePath() =
        Paths.get("build", "reports", "browser-traces", "trace-${System.currentTimeMillis()}.zip").also {
            it.parent.toFile().mkdirs()
        }

    private var tracingThisTest = false

    /**
     * Drives the local login form (auth.md §5A). Asserts nothing — the caller decides
     * what the outcome should be; this helper only types and clicks.
     */
    protected fun login(
        email: String,
        password: String,
    ) {
        page.navigate("$baseUrl/login")
        page.fill("#login-email", email)
        page.fill("#login-password", password)
        page.click("form button[type=submit]")
    }

    /** A logged-out session — a fresh context+page pair the test must [Session.close]. */
    protected class Session(
        val page: Page,
        private val ctx: BrowserContext,
    ) {
        fun close() = ctx.close()
    }

    protected fun newSession(): Session {
        val ctx = newBrowserContext()
        return Session(ctx.newPage(), ctx)
    }

    /** A per-test local user: its one-time password, its chosen password, its unique email. */
    protected class LocalUser(
        val email: String,
        val oneTimePassword: String,
        val chosenPassword: String,
    )

    /**
     * Seeds ONE local user with a unique email — the suite's isolation unit. No two tests
     * share a user, so execution order cannot matter. Called from inside a test (Flyway
     * has migrated by then — the TracerBullet lesson; a static @BeforeAll races the
     * context boot).
     */
    protected fun seedLocalUser(
        email: String,
        password: String,
        mustChange: Boolean,
    ): LocalUser {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val hash = argon2.hash(2, 19_456, 1, password.toCharArray())
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (email, display_name, provider, provider_subject,
                                           is_active, is_admin, password_hash, must_change_password)
                        VALUES ('$email', 'Browser User', 'local', '$email',
                                TRUE, TRUE, '$hash', $mustChange)
                        ON CONFLICT (email) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        return LocalUser(email, password, generatedPassword("chosen"))
    }

    /** Generates per-run secrets — no literal secret in any fixture (the HIGH-2 rule). */
    protected fun generatedPassword(prefix: String): String =
        "$prefix-" + (1..24).map { BASE32[SECURE_RANDOM.nextInt(BASE32.length)] }.joinToString("")

    protected fun uniqueEmail(slug: String): String = "$slug@browser.datapipelines.test"

    companion object {
        private const val SECRET_BYTES = 32
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val SECURE_RANDOM = SecureRandom()

        private fun randomSecret(): String =
            Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { SECURE_RANDOM.nextBytes(it) })

        @JvmStatic
        private var playwright: Playwright? = null

        @JvmStatic
        private var browserHolder: Browser? = null

        @JvmStatic
        protected fun newBrowserContext(): BrowserContext = browser.newContext()

        private val browser: Browser
            get() {
                if (browserHolder == null) {
                    playwright = Playwright.create()
                    browserHolder =
                        playwright!!.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
                }
                return browserHolder!!
            }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            browserHolder?.close()
            browserHolder = null
            playwright?.close()
            playwright = null
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedBrowserE2e.jdbcUrl }
            registry.add("spring.datasource.username") { SharedBrowserE2e.username }
            registry.add("spring.datasource.password") { SharedBrowserE2e.password }
            registry.add("spring.data.redis.host") { SharedBrowserE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedBrowserE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedBrowserE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedBrowserE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            // LOCAL accounts only — no OIDC providers registered, so the login page is
            // exactly the local form and the suite never touches an identity provider.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100 }
        }
    }
}
