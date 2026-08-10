package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The spec-named OIDC login integration test (auth.md §5): a **real** OIDC provider
 * (Keycloak Testcontainer) with **real** issuer discovery and the **real** authorization
 * -code flow through the browser redirect, ending in the internal `dp_session` JWT.
 * Also proves bootstrap-admin (§4.4): the configured email logs in and lands as admin.
 *
 * The flow is driven with a cookie-carrying HTTP client that follows redirects
 * manually — exactly what a browser does: kick off `/oauth2/authorization/keycloak`,
 * authenticate at Keycloak, come back on the callback, receive `dp_session`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class OidcLoginIntegrationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jwtService: JwtService

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate

    private val jar = mutableMapOf<String, String>()
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

    @BeforeAll
    fun createSchema() {
        jdbc.jdbcTemplate.execute(RepoFiles.read(RepoFiles.MIGRATION_PATH))
    }

    @Test
    fun `a full OIDC code flow issues dp_session and provisions the bootstrap admin`() {
        val base = "http://localhost:$port"

        // 1. Kick off login → 302 to Keycloak's authorization endpoint (discovery-derived).
        val start = send("GET", "$base/oauth2/authorization/keycloak")
        start.statusCode() shouldBe 302
        val kcAuthorize = location(start)
        // PKCE (AUTH-SEC-8) is on the wire, and the redirect_uri is the configured
        // absolute base-url (§5.2) — not one derived from this request's Host header.
        kcAuthorize shouldContain "code_challenge_method=S256"
        kcAuthorize shouldContain "code_challenge="
        kcAuthorize shouldContain "redirect_uri=http://localhost:$port/login/oauth2/code/keycloak"

        // 2. Load Keycloak's login page and extract the login form action.
        val loginPage = send("GET", kcAuthorize)
        loginPage.statusCode() shouldBe 200
        val formAction = extractFormAction(loginPage.body())

        // 3. Submit credentials → 302 back to our callback with ?code=&state=.
        val afterLogin =
            send("POST", formAction, body = "username=alice&password=alice-password&credentialId=")
        afterLogin.statusCode() shouldBe 302

        // 4. Follow redirects until our callback runs and sets dp_session.
        followUntilSession(location(afterLogin))

        // dp_session was issued and is a valid internal JWT for the provisioned user.
        val session = jar["dp_session"]
        session.shouldNotBeNull()
        val claims = jwtService.validate(session)
        claims["email"] shouldBe "alice@datapipelines.co"

        @Suppress("UNCHECKED_CAST")
        val scopes = claims["scopes"] as List<String>
        // Bootstrap admin (§4.4 / D14): alice is the configured bootstrap email → admin.
        scopes shouldContain "admin"

        // The user row was created with the OIDC identity and is_admin true.
        val row =
            jdbc.jdbcTemplate.queryForList(
                "SELECT provider, is_admin FROM users WHERE email = 'alice@datapipelines.co'",
            )
        row.size shouldBe 1
        row.first()["provider"] shouldBe "keycloak"
        row.first()["is_admin"] shouldBe true

        // Both the login and the admin grant were audited (§10.1).
        events() shouldContain "auth.login.success"
        events() shouldContain "auth.user.admin_granted"
    }

    private fun events(): List<String> = jdbc.jdbcTemplate.queryForList("SELECT event FROM audit_log", String::class.java)

    /**
     * Follows the redirect chain back from the IdP until `dp_session` is set.
     *
     * Giving up **fails loudly with the trail** rather than returning quietly: a silent
     * return turns every login-flow regression into the same opaque "dp_session was
     * null" assertion, with no way to tell a rejected callback (`/login?error=…`) from a
     * chain that simply ran long. The trail names the last status, location and error
     * parameter, which is what actually identifies the fault.
     */
    private fun followUntilSession(firstLocation: String) {
        val trail = mutableListOf<String>()
        var next: String? = firstLocation
        var hops = 0
        while (next != null && hops < MAX_HOPS) {
            val resp = send("GET", next)
            trail += "${resp.statusCode()} $next -> ${resp.headers().firstValue("Location").orElse("(no Location)")}"
            if (jar.containsKey("dp_session")) return
            next = if (resp.statusCode() in 300..399) location(resp) else null
            hops++
        }
        throw AssertionError(
            "The OIDC callback chain never set dp_session after $hops hop(s). Trail:\n" +
                trail.joinToString("\n") { "  $it" },
        )
    }

    /** Sends a request carrying the accumulated cookie jar; records Set-Cookie back. */
    private fun send(
        method: String,
        url: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
        if (jar.isNotEmpty()) builder.header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        when (method) {
            "POST" -> {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            }

            else -> {
                builder.GET()
            }
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").forEach(::storeCookie)
        return response
    }

    private fun storeCookie(setCookie: String) {
        val pair = setCookie.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        val expired = Regex("(?i)max-age=0|expires=Thu, 01 Jan 1970").containsMatchIn(setCookie)
        if (name.isEmpty()) return
        if (value.isEmpty() || expired) jar.remove(name) else jar[name] = value
    }

    private fun location(response: HttpResponse<String>): String =
        response.headers().firstValue("Location").orElseThrow { AssertionError("no Location on ${response.statusCode()}") }

    private fun extractFormAction(html: String): String {
        val match =
            Regex("""<form[^>]*\baction="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: error("no login form action in Keycloak page")
        return match.groupValues[1].replace("&amp;", "&")
    }

    private companion object {
        const val MAX_HOPS = 10
        const val KEYCLOAK_PORT = 8080
        const val SECRET_BYTES = 32

        /**
         * A pre-reserved local port, so `datapipelines.auth.base-url` (auth.md §5.2)
         * can name this server's exact origin BEFORE the context starts. The v2.4
         * redirect URI is absolute and configured, not derived from the request, so
         * `RANDOM_PORT` — whose value only exists after startup — is no longer usable
         * here. The realm registers `*` as its redirect URI, so Keycloak accepts
         * whatever port we reserve; the server itself must actually be reachable there,
         * which is what makes this the real end-to-end callback.
         */
        @JvmStatic
        val serverPort: Int = java.net.ServerSocket(0).use { it.localPort }

        // Started explicitly in the init block below — NOT via @Testcontainers/@Container.
        // With @SpringBootTest, the context (and its @DynamicPropertySource) can load before
        // the Testcontainers extension's beforeAll runs, so the mapped ports would not yet
        // exist; a static-init start guarantees both containers are up first. Ryuk reaps them.
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")

        @JvmStatic
        val keycloak: GenericContainer<*> =
            GenericContainer("quay.io/keycloak/keycloak:26.0")
                .withExposedPorts(KEYCLOAK_PORT)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/realm-datapipelines.json"),
                    "/opt/keycloak/data/import/realm-datapipelines.json",
                ).withCommand("start-dev", "--import-realm")
                .waitingFor(
                    Wait
                        .forHttp("/realms/datapipelines/.well-known/openid-configuration")
                        .forPort(KEYCLOAK_PORT)
                        .forStatusCode(200)
                        // Generous: Keycloak's Quarkus augmentation is CPU-heavy and this
                        // suite may run alongside other Testcontainers on a loaded machine.
                        .withStartupTimeout(Duration.ofMinutes(10)),
                )

        init {
            postgres.start()
            keycloak.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            registry.add("server.port") { serverPort }
            registry.add("datapipelines.jwt.secret") { Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES) { 9 }) }
            // §5.2 / configuration.md §3.4: the redirect URI is built absolutely from this.
            registry.add("datapipelines.auth.base-url") { "http://localhost:$serverPort" }
            registry.add("datapipelines.auth.bootstrap-admin-email") { "Alice@Datapipelines.CO" }
            registry.add("datapipelines.auth.allowlist.domains") { "datapipelines.co" }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "keycloak" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "dp-client" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "dp-secret" }
            // Lazy — the mapped port only exists after the container has started.
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") {
                "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_PORT)}/realms/datapipelines"
            }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Company SSO" }
        }
    }
}
