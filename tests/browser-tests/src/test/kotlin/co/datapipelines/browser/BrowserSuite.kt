package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import de.mkammerer.argon2.Argon2Factory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Paths
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The browser suite's boot harness (module-structure §5.12, TEST-GAP-2026-09.md).
 *
 * One full application per spec class: SpringBootTest on a random port, real Postgres
 * and Redis via Testcontainers, LOCAL accounts enabled, no OIDC providers — one command,
 * zero manual setup, no dependency on an operator's running stack. The seeded admin's
 * stored hash is a real Argon2id of the known password with auth's exact parameters
 * (2 / 19 456 / 1), same declared-exception rationale as the integration suite's seeding.
 *
 * Playwright runs chromium-only, headless, with tracing captured to build/reports on
 * failure. No timing sleeps anywhere in the suite: Playwright's auto-wait is the only
 * synchronization primitive.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
abstract class BrowserSuite {
    @LocalServerPort
    protected var port: Int = 0

    protected val baseUrl: String get() = "http://localhost:$port"

    protected val adminEmail = "browser-admin@datapipelines.test"

    /** Generated per run — no literal secret in any fixture (the HIGH-2 rule). */
    protected val adminOneTimePassword =
        "browser-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

    /** The password the admin SETS at the forced-change screen during the flow. */
    protected val adminChosenPassword =
        "browser-chosen-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

    protected lateinit var page: Page
    private lateinit var context: BrowserContext

    @BeforeEach
    fun freshPage() {
        context = browser.newContext()
        page = context.newPage()
    }

    @AfterEach
    fun closePage() {
        // The trace covers exactly one test; on failure it is stopped and saved by
        // [onTestFailure] before the context closes.
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

    /**
     * Seeds the local admin ONCE per JVM — Flyway has migrated by the time the first
     * test runs; a static @BeforeAll would race it (the TracerBullet lesson the
     * integration suite recorded). `must_change_password = TRUE` is the point: the
     * golden path walks the §5A.4 forced-change gate, not around it.
     */
    protected fun seedAdminOnce() {
        if (!seeded.compareAndSet(false, true)) return
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val hash = argon2.hash(2, 19_456, 1, adminOneTimePassword.toCharArray())
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (email, display_name, provider, provider_subject,
                                       is_active, is_admin, password_hash, must_change_password)
                    VALUES ('$adminEmail', 'Browser Admin', 'local', '$adminEmail',
                            TRUE, TRUE, '$hash', TRUE)
                    """.trimIndent(),
                )
            }
        }
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val random = SecureRandom()

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
            Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        /**
         * Seeds the local admin ONCE, from inside a test-side hook — Flyway has migrated
         * by the time the first test runs; a static @BeforeAll would race it (the
         * TracerBullet lesson the integration suite recorded).
         *
         * `must_change_password = TRUE` is the point: the golden path walks the §5A.4
         * forced-change gate, not around it.
         */
        private val seeded = AtomicBoolean(false)



        @JvmStatic
        private var playwright: Playwright? = null

        @JvmStatic
        private var browserHolder: Browser? = null

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
        protected fun newBrowserContext(): BrowserContext = browser.newContext()

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
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            // LOCAL accounts only — no OIDC providers registered, so the login page is
            // exactly the local form and the suite never touches an identity provider.
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100 }
        }
    }
}
