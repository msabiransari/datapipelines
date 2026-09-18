package co.datapipelines.integration

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The 137 owner rulings end to end (auth.md §5A.8), against the FULL application with mail
 * CONFIGURED — a GreenMail container is the SMTP server and its IMAP side is the box the
 * assertions read — and a real Keycloak realm for the social-login half:
 *
 *  1. an admin creates a local user over the admin route → TWO messages: the welcome to the
 *     user (the login URL, the login, the one-time password, the first-login sentence, the
 *     reply-to) and the "New user" notice to every ops-to address (who, by whom, where — and
 *     no password); the screen says "Emailed to" and never the password; the claim rows are
 *     SENT; `mail.sent` is audited without the password;
 *  2. a double create attempt over the admin route → one welcome, never two (the second POST
 *     is the `409` the row already earns; the claim ROW itself — a second send attempt for one
 *     message identity finding the row — is `MailNotifierIntegrationTest`'s arm against the real
 *     table, falsified by deleting `tryClaim`, and `auth` is not on this module's classpath);
 *  3. a FIRST social login (the real code flow through Keycloak) → one "New user" notice
 *     naming the provider; the SAME user's second login → no message;
 *  4. the Postmark stream header rides every message when configured, and Reply-To is set
 *     (the header ABSENT when unconfigured is `MailNotifierIntegrationTest`'s arm — the
 *     property is fixed per context);
 *  5. the one-time password reaches the transport and nothing else — audit rows, the claim
 *     rows and the captured log are grepped for it (a positive control first).
 *
 * Mail OFF — no message, the INFO no-op line, the password on the screen — is the
 * `LocalAdminSeedE2eTest` arm (that suite boots with no mail configured, as every suite before
 * 137 did).
 *
 * DEFINED_PORT with a pre-reserved port: `datapipelines.auth.base-url` must name the exact
 * origin — it is the OIDC redirect's origin AND the login URL in the welcome mail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
)
class MailNoticesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    @Test
    fun `an admin creates a local user - the welcome carries the password to the user, the notice carries none to ops`() {
        seedAdmin()
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        val logLines = capturingLogs { createUser(admin, CREATED_EMAIL, "Created User", workspace = "demo") }
        val createHtml = logLines.first

        // The screen: where it went and whether — never what it was.
        createHtml shouldContain "Emailed to"
        createHtml shouldContain CREATED_EMAIL
        ONE_TIME_PASSWORD.containsMatchIn(createHtml) shouldBe false

        // The user's box: the welcome, with everything the ruling names.
        val welcome = awaitMessages(CREATED_EMAIL, expected = 1).single()
        welcome.subject shouldBe "Your datapipelines account"
        val welcomeText = textPart(welcome)
        welcomeText shouldContain "http://localhost:$port/login"
        welcomeText shouldContain CREATED_EMAIL
        val oneTime = checkNotNull(ONE_TIME_PASSWORD.find(welcomeText)) { "no one-time password in the welcome mail" }.groupValues[1]
        welcomeText shouldContain "the first login asks you to choose a new password"
        welcomeText shouldContain REPLY_TO
        (welcome.replyTo.single() as InternetAddress).address shouldBe REPLY_TO
        (welcome.from.single() as InternetAddress).address shouldBe "noreply@datapipelines.test"
        welcome.getHeader("X-PM-Message-Stream").toList() shouldBe listOf(STREAM)
        htmlPart(welcome) shouldContain oneTime

        // Every ops-to address: the notice, and no password anywhere in it.
        listOf(OPS_EMAIL, SEC_EMAIL).forEach { box ->
            val notice = awaitMessages(box, expected = 1) { it.subject == "New user: $CREATED_EMAIL (local)" }.single()
            val text = textPart(notice)
            text shouldContain "Created User"
            text shouldContain "Provider:   local"
            text shouldContain "Created by: $ADMIN_EMAIL"
            text shouldContain "Workspace:  demo"
            text shouldContain "http://localhost:$port/admin/users"
            text.lowercase() shouldNotContain "password"
            text shouldNotContain oneTime
            htmlPart(notice) shouldNotContain oneTime
            notice.getHeader("X-PM-Message-Stream").toList() shouldBe listOf(STREAM)
        }

        // The mailed password is the credential: it logs the user in (and the gate engages).
        val userLogin = postLogin(CREATED_EMAIL, oneTime)
        userLogin.statusCode shouldBe 302
        userLogin.location shouldBe "http://localhost:$port/dashboard"

        // The claim rows and the audit rows — and the password in none of them.
        awaitClaimStatuses(CREATED_EMAIL, mapOf("welcome" to "sent", "new_user" to "sent"))
        auditRows("mail.sent", CREATED_EMAIL) shouldBe 2
        auditRows("mail.failed", CREATED_EMAIL) shouldBe 0
        val userId = sql("SELECT id FROM users WHERE email = '$CREATED_EMAIL'")
        sql("SELECT string_agg(details_json::text, ' ') FROM audit_log WHERE user_id = '$userId'") shouldNotContain oneTime
        sql("SELECT string_agg(row_to_json(m)::text, ' ') FROM mail_sends m WHERE user_id = '$userId'") shouldNotContain oneTime
        logLines.second.forEach { it shouldNotContain oneTime }
        // Positive control for the log grep: the pool task DID log its two accepted sends.
        logLines.second.count { it.contains("event=mail.accepted") } shouldBe 2
    }

    @Test
    fun `a double create attempt - one welcome, never two`() {
        seedAdmin()
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        createUser(admin, CLAIMED_EMAIL, "Claimed Twice")
        awaitMessages(CLAIMED_EMAIL, expected = 1)

        // The double submit: the row exists, so the route refuses (409) before any hook fires.
        createUserExpecting(admin, CLAIMED_EMAIL, "Claimed Twice", status = 409)

        settle()
        messages(CLAIMED_EMAIL) shouldHaveSize 1
        sql(
            "SELECT COUNT(*) FROM mail_sends m JOIN users u ON u.id = m.user_id WHERE u.email = '$CLAIMED_EMAIL' AND m.kind = 'welcome'",
        ) shouldBe "1"
    }

    @Test
    fun `a FIRST social login notifies ops once - the same user's second login not at all`() {
        val jar = mutableMapOf<String, String>()
        runOidcFlow("carol", "carol-password", jar)
        jar["dp_session"].shouldNotBeNull()

        val notice =
            awaitMessages(OPS_EMAIL, expected = 1) { it.subject == "New user: $CAROL_EMAIL (mail-keycloak)" }.single()
        val text = textPart(notice)
        text shouldContain "Carol Newcomer"
        text shouldContain "Created by: self-service via mail-keycloak"
        text.lowercase() shouldNotContain "password"
        awaitClaimStatuses(CAROL_EMAIL, mapOf("new_user" to "sent"))

        // Log out (drop the jar) and log in again: the row exists, the branch is "found", nothing goes.
        val again = mutableMapOf<String, String>()
        runOidcFlow("carol", "carol-password", again)
        again["dp_session"].shouldNotBeNull()
        settle()
        messages(OPS_EMAIL).count { it.subject == "New user: $CAROL_EMAIL (mail-keycloak)" } shouldBe 1
        sql("SELECT COUNT(*) FROM mail_sends m JOIN users u ON u.id = m.user_id WHERE u.email = '$CAROL_EMAIL'") shouldBe "1"
    }

    // ------------------------------------------------------------------ the admin route

    private fun createUser(
        admin: LoginResponse,
        email: String,
        displayName: String,
        workspace: String = "",
    ): String = createUserExpecting(admin, email, displayName, status = 200, workspace = workspace)

    private fun createUserExpecting(
        admin: LoginResponse,
        email: String,
        displayName: String,
        status: Int,
        workspace: String = "",
    ): String {
        val response =
            given()
                .port(port)
                .cookie("dp_session", admin.sessionCookie())
                .cookie("dp_csrf", admin.csrfToken)
                .header("DP-CSRF-Token", admin.csrfToken)
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("displayName", displayName)
                .formParam("workspace", workspace)
                .`when`()
                .post("/partials/admin/users")
        response.statusCode shouldBe status
        return response.body().asString()
    }

    private data class LoginResponse(
        val statusCode: Int,
        val location: String?,
        private val cookies: Map<String, String>,
    ) {
        fun sessionCookie(): String = checkNotNull(cookies["dp_session"]) { "no dp_session cookie in $cookies" }

        val csrfToken: String get() = checkNotNull(cookies["dp_csrf"]) { "no dp_csrf cookie in $cookies" }
    }

    /** The real browser flow (the LocalAdminSeedE2eTest helper): GET /login for the cookies + token, then POST. */
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
        val cookies =
            page.detailedCookies().asList().associate { it.name to it.value } +
                response.detailedCookies().asList().associate { it.name to it.value }
        return LoginResponse(response.statusCode, response.headers.getValue("Location"), cookies)
    }

    // ------------------------------------------------------------------ the OIDC code flow (the auth-module harness)

    private fun runOidcFlow(
        username: String,
        password: String,
        jar: MutableMap<String, String>,
    ) {
        val base = "http://localhost:$port"
        val start = send("GET", "$base/oauth2/authorization/mail-keycloak", jar)
        start.statusCode() shouldBe 302
        val loginPage = send("GET", location(start), jar)
        loginPage.statusCode() shouldBe 200
        val formAction = extractFormAction(loginPage.body())
        val afterLogin = send("POST", formAction, jar, body = "username=$username&password=$password&credentialId=")
        afterLogin.statusCode() shouldBe 302
        var next: String? = location(afterLogin)
        var hops = 0
        while (next != null && hops < MAX_HOPS) {
            val resp = send("GET", next, jar)
            if (jar.containsKey("dp_session")) return
            next = if (resp.statusCode() in 300..399) location(resp) else null
            hops++
        }
        throw AssertionError("The OIDC callback chain never set dp_session after $hops hop(s)")
    }

    private fun send(
        method: String,
        url: String,
        jar: MutableMap<String, String>,
        body: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
        if (jar.isNotEmpty()) builder.header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (method == "POST") {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
            builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
        } else {
            builder.GET()
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").forEach { setCookie ->
            val pair = setCookie.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "")
            val expired = Regex("(?i)max-age=0|expires=Thu, 01 Jan 1970").containsMatchIn(setCookie)
            if (name.isNotEmpty()) {
                if (expired) jar.remove(name) else jar[name] = value
            }
        }
        return response
    }

    private fun location(response: HttpResponse<String>): String =
        response.headers().firstValue("Location").orElseThrow { AssertionError("no Location on ${response.statusCode()}") }

    private fun extractFormAction(html: String): String =
        checkNotNull(Regex("""<form[^>]*\baction="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)) { "no login form action" }
            .groupValues[1]
            .replace("&amp;", "&")

    // ------------------------------------------------------------------ the box (GreenMail over IMAP)

    /** Every message in [address]'s box, read over IMAP (auth is disabled on the container: any password). */
    private fun messages(address: String): List<MimeMessage> {
        val props =
            Properties().apply {
                setProperty("mail.store.protocol", "imap")
                setProperty("mail.imap.host", greenmail.host)
                setProperty("mail.imap.port", greenmail.getMappedPort(IMAP_PORT).toString())
            }
        val store = Session.getInstance(props).getStore("imap")
        store.connect(greenmail.host, greenmail.getMappedPort(IMAP_PORT), address, "any-password")
        return try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            // Copy the parsed message out before the folder closes — a lazy MimeMessage
            // reads its parts from the connection.
            inbox.messages.map { MimeMessage(it as MimeMessage) }
        } finally {
            store.close()
        }
    }

    /** The send is asynchronous: synchronise on the box, never on a clock (up to [AWAIT]). */
    private fun awaitMessages(
        address: String,
        expected: Int,
        filter: (MimeMessage) -> Boolean = { true },
    ): List<MimeMessage> {
        val deadline = System.nanoTime() + AWAIT.toNanos()
        var found: List<MimeMessage> = emptyList()
        while (System.nanoTime() < deadline) {
            found = messages(address).filter(filter)
            if (found.size >= expected) return found
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("expected $expected message(s) in $address's box within $AWAIT, found ${found.size}")
    }

    /** A negative needs a settled box: wait a full poll interval past the last claim, then read. */
    private fun settle() = Thread.sleep(SETTLE_MS)

    private fun textPart(message: Message): String = part(message, "text/plain")

    private fun htmlPart(message: Message): String = part(message, "text/html")

    private fun part(
        message: Message,
        mime: String,
    ): String {
        val multipart = message.content as Multipart

        fun walk(mp: Multipart): String? {
            for (i in 0 until mp.count) {
                val body = mp.getBodyPart(i)
                if (body.isMimeType(mime)) return body.content.toString()
                (body.content as? Multipart)?.let { nested -> walk(nested)?.let { return it } }
            }
            return null
        }
        return checkNotNull(walk(multipart)) { "no $mime part in '${message.subject}'" }
    }

    // ------------------------------------------------------------------ the database

    private fun awaitClaimStatuses(
        email: String,
        expected: Map<String, String>,
    ) {
        val deadline = System.nanoTime() + AWAIT.toNanos()
        var observed: Map<String, String> = emptyMap()
        while (System.nanoTime() < deadline) {
            observed =
                sqlRows(
                    // A failed row reports its recorded transport error, not just "failed" —
                    // #121: the next under-load sighting names the SMTP error instead of
                    // leaving it in the row for someone to query by hand.
                    "SELECT m.kind, CASE WHEN m.sent_at IS NOT NULL THEN 'sent' " +
                        "WHEN m.error IS NOT NULL THEN 'failed: ' || m.error ELSE 'pending' END " +
                        "FROM mail_sends m JOIN users u ON u.id = m.user_id WHERE u.email = '$email'",
                )
            if (observed == expected) return
            Thread.sleep(POLL_MS)
        }
        observed shouldBe expected
    }

    private fun sqlRows(query: String): Map<String, String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
                }
            }
        }

    private fun sql(query: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rs ->
                    rs.next()
                    rs.getString(1).orEmpty()
                }
            }
        }

    private fun auditRows(
        event: String,
        email: String,
    ): Int =
        sql(
            "SELECT COUNT(*) FROM audit_log a JOIN users u ON u.id = a.user_id WHERE a.event = '$event' AND u.email = '$email'",
        ).toInt()

    private fun <T> capturingLogs(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            val result = block()
            // The pool task logs after the response; give it the same window the box gets.
            awaitClaimStatuses(CREATED_EMAIL, mapOf("welcome" to "sent", "new_user" to "sent"))
            result to appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val SMTP_PORT = 3025
        private const val IMAP_PORT = 3143
        private const val KEYCLOAK_PORT = 8080
        private const val MAX_HOPS = 10
        private const val POLL_MS = 250L
        private const val SETTLE_MS = 2_000L
        private val AWAIT: Duration = Duration.ofSeconds(30)
        private val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private const val ADMIN_EMAIL = "mail-admin@datapipelines.test"
        private const val CREATED_EMAIL = "mail-created@datapipelines.test"
        private const val CLAIMED_EMAIL = "mail-claimed@datapipelines.test"
        private const val CAROL_EMAIL = "carol@datapipelines.test"
        private const val OPS_EMAIL = "ops@datapipelines.test"
        private const val SEC_EMAIL = "sec@datapipelines.test"
        private const val REPLY_TO = "help@datapipelines.test"
        private const val STREAM = "outbound"

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")
        private val ONE_TIME_PASSWORD = Regex("""([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})""")

        private val random = SecureRandom()

        /** Generated per run — no literal secret in any test fixture (HIGH-2). */
        private val ADMIN_PASSWORD = "e2e-mail-" + (1..24).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")

        /** A pre-reserved local port, so `datapipelines.auth.base-url` names the exact origin. */
        @JvmStatic
        val serverPort: Int = java.net.ServerSocket(0).use { it.localPort }

        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

        /**
         * GreenMail (2.1.13, verified on Docker Hub 2026-09-14): SMTP on 3025, IMAP on 3143,
         * `greenmail.auth.disabled` in the image's default GREENMAIL_OPTS — any credentials,
         * mailboxes created on delivery. STARTTLS is OFF in this context (the development
         * posture allows it; the hardened refusal is `ConfigValidatorMailTest`'s).
         */
        @JvmStatic
        val greenmail: GenericContainer<*> =
            GenericContainer("greenmail/standalone:2.1.13")
                .withExposedPorts(SMTP_PORT, IMAP_PORT)
                .withReuse(true)
                .waitingFor(Wait.forListeningPorts(SMTP_PORT, IMAP_PORT).withStartupTimeout(Duration.ofMinutes(2)))

        /** The auth module's Keycloak harness, with its own realm (`mail`, one user: carol). */
        @JvmStatic
        val keycloak: GenericContainer<*> =
            GenericContainer("quay.io/keycloak/keycloak:26.0")
                .withExposedPorts(KEYCLOAK_PORT)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/realm-mail.json"),
                    "/opt/keycloak/data/import/realm-mail.json",
                ).withCommand("start-dev", "--import-realm")
                .withReuse(true)
                .waitingFor(
                    Wait
                        .forHttp("/realms/mail/.well-known/openid-configuration")
                        .forPort(KEYCLOAK_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(20)),
                )

        init {
            greenmail.start()
            keycloak.start()
        }

        private val seeded = AtomicBoolean(false)

        /** The admin, seeded from inside a test (Flyway has migrated by then) with no forced change. */
        private fun seedAdmin() {
            if (!seeded.compareAndSet(false, true)) return
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO users (email, display_name, provider, provider_subject, is_active, is_admin, password_hash) " +
                            "VALUES ('$ADMIN_EMAIL', 'Mail Admin', 'local', '$ADMIN_EMAIL', TRUE, TRUE, " +
                            "'${E2eAuth.argon2Hash(ADMIN_PASSWORD)}')",
                    )
                }
            }
        }

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { serverPort }
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

            registry.add("datapipelines.auth.base-url") { "http://localhost:$serverPort" }
            registry.add("datapipelines.auth.local.enabled") { true }
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100 }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "mail-keycloak" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "dp-client" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "dp-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") {
                "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_PORT)}/realms/mail"
            }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Company SSO" }

            // The feature under test (configuration.md §3.27): mail is ON because host + from are set.
            registry.add("datapipelines.mail.host") { greenmail.host }
            registry.add("datapipelines.mail.port") { greenmail.getMappedPort(SMTP_PORT) }
            registry.add("datapipelines.mail.username") { "dp" }
            registry.add("datapipelines.mail.password") { "dp-any" }
            registry.add("datapipelines.mail.starttls") { false }
            registry.add("datapipelines.mail.from") { "Data Pipelines <noreply@datapipelines.test>" }
            registry.add("datapipelines.mail.reply-to") { REPLY_TO }
            registry.add("datapipelines.mail.ops-to") { "$OPS_EMAIL, $SEC_EMAIL" }
            registry.add("datapipelines.mail.message-stream") { STREAM }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = Unit
    }
}
