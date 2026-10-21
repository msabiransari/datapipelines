package co.datapipelines.auth

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The `protocol: oauth2` login end to end (#655): the real Spring Security chain, the real
 * success and failure handlers, Postgres — and a GitHub-shaped [OAuth2ProviderStub] as the
 * provider, because GitHub is OAuth 2.0 without OpenID Connect and no live app exists for a test.
 * The twin of [OidcLoginIntegrationTest]'s full code flow, with the same driver: a cookie-carrying
 * client that follows redirects by hand, as a browser does.
 *
 * The provider entry carries `trust-email-without-verified-claim: true` ON PURPOSE (F5): every
 * refusal below must hold although the knob is set, because an entry with an `emails-uri` states
 * verification itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class OAuth2LoginIntegrationTest {
    @LocalServerPort private var appPort: Int = 0

    @Autowired private lateinit var jwtService: JwtService

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired private lateinit var authorizedClientService: OAuth2AuthorizedClientService

    @Autowired private lateinit var authorizedClientRepository: OAuth2AuthorizedClientRepository

    private val base: String get() = "http://localhost:${origin.originPort}"

    private val jar = mutableMapOf<String, String>()

    /** Every cookie NAME any response of the current flow set (or cleared). */
    private val setCookieNames = mutableListOf<String>()

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    @BeforeEach
    fun freshBrowser() {
        origin.forwardTo(appPort)
        jar.clear()
        setCookieNames.clear()
        stub.recorded.clear()
        stub.user = OAuth2ProviderStub.GITHUB_USER
        stub.emails = OAuth2ProviderStub.VERIFIED_PRIMARY
        stub.emailsStatus = HTTP_OK
    }

    @AfterAll
    fun closeOrigin() {
        origin.close()
        stub.close()
    }

    @Test
    fun `a full GitHub-shaped code flow issues dp_session and provisions the user from the verified primary email`() {
        val auditFloor = lastAuditId()

        // 1. Kick off → 302 to the CONFIGURED authorization endpoint (no discovery happened).
        val start = send("GET", "$base/oauth2/authorization/github")
        start.statusCode() shouldBe HTTP_FOUND
        val authorize = location(start)
        authorize shouldStartWith "${stub.base}${OAuth2ProviderStub.AUTHORIZE}?"
        val params = OAuth2ProviderStub.query(URI.create(authorize))
        params["response_type"] shouldBe "code"
        params["client_id"] shouldBe "gh-client"
        params["scope"] shouldBe "read:user user:email"
        params["redirect_uri"] shouldBe "$base/login/oauth2/code/github"
        params["state"].shouldNotBeNull()
        params["code_challenge"].shouldNotBeNull()
        params["code_challenge_method"] shouldBe "S256"

        // 2. The stub answers the authorize hop with the callback; 3. follow it to dp_session.
        followUntilSession(location(send("GET", authorize)))

        val session = jar["dp_session"].shouldNotBeNull()
        jwtService.validate(session)["email"] shouldBe "octo-alice@datapipelines.co"

        val row =
            jdbc.jdbcTemplate.queryForMap(
                "SELECT provider, provider_subject, display_name, profile_picture_url FROM users WHERE email = 'octo-alice@datapipelines.co'",
            )
        row["provider"] shouldBe "github"
        row["provider_subject"] shouldBe "4242"
        row["display_name"] shouldBe "Alice Octo"
        row["profile_picture_url"] shouldBe "https://avatars.githubusercontent.com/u/4242?v=4"
        // The /user `email` is the unverified PUBLIC address; it never became an account.
        jdbc.jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE email = 'public-alias@elsewhere.example'",
            Int::class.java,
        ) shouldBe 0

        val success = auditSince(auditFloor).filter { it.first == "auth.login.success" }
        success shouldHaveSize 1
        success.single().second shouldContain "\"provider\": \"github\""

        // The token exchange asked for JSON, authenticated with client_secret_basic and sent the
        // PKCE verifier; both user reads carried the access token as a bearer.
        val token = stub.requestsTo(OAuth2ProviderStub.TOKEN).single()
        token.method shouldBe "POST"
        token.headers.getValue("Accept") shouldContain "application/json"
        token.headers.getValue("Authorization") shouldBe "Basic " + Base64.getEncoder().encodeToString("gh-client:gh-secret".toByteArray())
        token.body shouldContain "grant_type=authorization_code"
        token.body shouldContain "code_verifier="
        stub.requestsTo(OAuth2ProviderStub.USER).single().headers["Authorization"] shouldBe "Bearer ${OAuth2ProviderStub.ACCESS_TOKEN}"
        stub.requestsTo(OAuth2ProviderStub.EMAILS).single().headers["Authorization"] shouldBe "Bearer ${OAuth2ProviderStub.ACCESS_TOKEN}"
        stub.wellKnownHits shouldBe 0

        // STATELESS holds over the whole flow: no servlet session, and the only cookies are the
        // signed authorization-request carrier and the session itself.
        setCookieNames.toSet() shouldBe setOf(AUTHORIZATION_REQUEST_COOKIE, "dp_session")
        jar.keys shouldBe setOf("dp_session")

        // The provider's access token is not retained anywhere: Boot's in-memory client service
        // never received it (the effect), because the wired repository stores nothing (the cause).
        authorizedClientService.loadAuthorizedClient<OAuth2AuthorizedClient>("github", "4242").shouldBeNull()
        authorizedClientRepository shouldBeSameInstanceAs NoStoreAuthorizedClientRepository

        println("EVIDENCE-655 stub requests:\n" + stub.recorded.joinToString("\n") { "  $it" })
    }

    @Test
    fun `an unverified primary address is refused as email_not_verified although the trust knob is set (F3, F5)`() {
        stub.user = user(id = 5001, login = "mallory")
        stub.emails = """[{"email":"victim@datapipelines.co","primary":true,"verified":false,"visibility":"private"}]"""

        val refusal = refusedLogin()

        refusal.reason shouldBe "email_not_verified"
        refusal.email shouldBe "victim@datapipelines.co"
        userCount("victim@datapipelines.co") shouldBe 0
    }

    @Test
    fun `no primary row is refused as missing_email - the public user email is never a fallback (F3)`() {
        stub.user = user(id = 5002, login = "noprimary", email = "noprimary@datapipelines.co")
        stub.emails = """[{"email":"noprimary@datapipelines.co","primary":false,"verified":true,"visibility":null}]"""

        refusedLogin().reason shouldBe "missing_email"
        userCount("noprimary@datapipelines.co") shouldBe 0
    }

    @Test
    fun `a failing emails endpoint takes the failure path with the named error code (F3)`() {
        stub.user = user(id = 5003, login = "brokenemails")
        stub.emailsStatus = HTTP_SERVER_ERROR
        stub.emails = """{"message":"Server Error"}"""

        val refusal = refusedLogin()

        refusal.reason shouldBe OAuth2IdentityUserService.INVALID_EMAILS_RESPONSE
        refusal.provider shouldBe "github"
    }

    @Test
    fun `a null GitHub name falls back to the login for the display name (F3)`() {
        stub.user = user(id = 5004, login = "nameless-octo", name = null)
        stub.emails = """[{"email":"nameless@datapipelines.co","primary":true,"verified":true,"visibility":"private"}]"""

        followUntilSession(location(send("GET", location(send("GET", "$base/oauth2/authorization/github")))))

        jar["dp_session"].shouldNotBeNull()
        jdbc.jdbcTemplate.queryForObject(
            "SELECT display_name FROM users WHERE email = 'nameless@datapipelines.co'",
            String::class.java,
        ) shouldBe "nameless-octo"
    }

    /** One refused provider login: the redirect, no session, and the single oidc_error audit row's fields. */
    private data class Refusal(
        val reason: String?,
        val provider: String?,
        val email: String?,
    )

    private fun refusedLogin(): Refusal {
        val floor = lastAuditId()
        val callback = location(send("GET", location(send("GET", "$base/oauth2/authorization/github"))))
        val landing = send("GET", callback)
        landing.statusCode() shouldBe HTTP_FOUND
        location(landing) shouldContain "/login?error=oidc_error"
        jar["dp_session"].shouldBeNull()
        auditSince(floor).filter { it.first == "auth.login.success" }.shouldBeEmpty()
        val rows =
            jdbc.jdbcTemplate.queryForList(
                "SELECT details_json->>'reason' AS reason, details_json->>'provider' AS provider, " +
                    "details_json->>'email' AS email FROM audit_log WHERE id > ? AND event = 'auth.login.oidc_error'",
                floor,
            )
        rows shouldHaveSize 1
        return Refusal(rows.single()["reason"] as String?, rows.single()["provider"] as String?, rows.single()["email"] as String?)
    }

    private fun user(
        id: Long,
        login: String,
        name: String? = "Some Octo",
        email: String? = null,
    ): String {
        val nameJson = name?.let { "\"$it\"" } ?: "null"
        val emailJson = email?.let { "\"$it\"" } ?: "null"
        val avatar = "https://avatars.githubusercontent.com/u/$id"
        return """{"id":$id,"login":"$login","name":$nameJson,"email":$emailJson,"avatar_url":"$avatar"}"""
    }

    private fun userCount(email: String): Int =
        jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ?", Int::class.java, email) ?: 0

    private fun lastAuditId(): Long = jdbc.jdbcTemplate.queryForObject("SELECT coalesce(max(id), 0) FROM audit_log", Long::class.java) ?: 0

    private fun auditSince(floor: Long): List<Pair<String, String>> =
        jdbc.jdbcTemplate.query("SELECT event, details_json::text AS d FROM audit_log WHERE id > ? ORDER BY id", { rs, _ ->
            rs.getString("event") to rs.getString("d")
        }, floor)

    /** Follows the callback chain until `dp_session` is set; fails with the trail otherwise. */
    private fun followUntilSession(firstLocation: String) {
        val trail = mutableListOf<String>()
        var next: String? = firstLocation
        var hops = 0
        while (next != null && hops < MAX_HOPS) {
            val resp = send("GET", next)
            trail += "${resp.statusCode()} $next -> ${resp.headers().firstValue("Location").orElse("(no Location)")}"
            if (jar.containsKey("dp_session")) return
            next = if (resp.statusCode() in HTTP_REDIRECTS) location(resp) else null
            hops++
        }
        throw AssertionError("The OAuth 2.0 callback chain never set dp_session. Trail:\n" + trail.joinToString("\n") { "  $it" })
    }

    private fun send(
        method: String,
        url: String,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
        // The jar is the APP's cookies; the stub provider is another origin and gets none.
        if (jar.isNotEmpty() && url.startsWith(base)) builder.header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        builder.method(method, HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").forEach(::storeCookie)
        return response
    }

    private fun storeCookie(setCookie: String) {
        val pair = setCookie.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        if (name.isEmpty()) return
        setCookieNames += name
        val expired = Regex("(?i)max-age=0|expires=Thu, 01 Jan 1970").containsMatchIn(setCookie)
        if (value.isEmpty() || expired) jar.remove(name) else jar[name] = value
    }

    private fun location(response: HttpResponse<String>): String =
        response.headers().firstValue("Location").orElseThrow { AssertionError("no Location on ${response.statusCode()}") }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(1)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(20)
        const val MAX_HOPS = 10

        /** The signed authorization-request carrier (`CookieOAuth2AuthorizationRequestRepository`, auth.md §5.4). */
        const val AUTHORIZATION_REQUEST_COOKIE = "dp_oauth2_authz"
        const val SECRET_BYTES = 32
        const val HTTP_OK = 200
        const val HTTP_FOUND = 302
        const val HTTP_SERVER_ERROR = 500
        val HTTP_REDIRECTS = 300..399

        /** The stable origin (= base-url), as in [OidcLoginIntegrationTest] (#334). */
        private val origin = StableOriginForwarder.open()

        private val stub = OAuth2ProviderStub()

        val postgres get() = SharedPostgres.postgres

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("server.port") { 0 }
            registry.add("server.address") { "127.0.0.1" }
            registry.add("datapipelines.jwt.secret") { Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES) { 7 }) }
            registry.add("datapipelines.auth.base-url") { "http://localhost:${origin.originPort}" }
            val p = "datapipelines.auth.oidc.providers[0]"
            registry.add("$p.name") { "github" }
            registry.add("$p.protocol") { "oauth2" }
            registry.add("$p.client-id") { "gh-client" }
            registry.add("$p.client-secret") { "gh-secret" }
            registry.add("$p.authorization-uri") { "${stub.base}${OAuth2ProviderStub.AUTHORIZE}" }
            registry.add("$p.token-uri") { "${stub.base}${OAuth2ProviderStub.TOKEN}" }
            registry.add("$p.user-info-uri") { "${stub.base}${OAuth2ProviderStub.USER}" }
            registry.add("$p.user-name-attribute") { "id" }
            registry.add("$p.scopes") { "read:user,user:email" }
            registry.add("$p.emails-uri") { "${stub.base}${OAuth2ProviderStub.EMAILS}" }
            registry.add("$p.display-name") { "GitHub" }
            // F5: set deliberately — it must change nothing for an entry with an emails-uri.
            registry.add("$p.trust-email-without-verified-claim") { "true" }
        }
    }
}
