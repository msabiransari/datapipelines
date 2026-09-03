package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import jakarta.servlet.ServletContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * AU-TEST-5: the auth contract asserted **at the wire**, over the real filter chain
 * assembled by [AuthTestApplication] — the class that proves A1 (`/mcp` refuses
 * cookies; CSRF exemption follows the credential), A5 (the full error envelope),
 * B4 (default deny), B9 (session codes at the boundary) and B12 (no double filter
 * registration) together rather than one mock at a time.
 *
 * A probe controller stands in for the endpoints P6a will ship: this module owns the
 * gate, not the endpoints, so the gate is what gets exercised.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpBoundaryTest {
    @Autowired private lateinit var restTemplate: TestRestTemplate

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired private lateinit var jwtService: JwtService

    @Autowired private lateinit var apiKeyService: ApiKeyService

    @Autowired private lateinit var servletContext: ServletContext

    @Autowired private lateinit var authFilters: AuthFilters

    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private lateinit var user: User
    private lateinit var readKey: String
    private lateinit var expiredKey: String
    private lateinit var session: String
    private lateinit var mustChangeSession: String

    /**
     * Probe endpoints standing in for the controllers P6a will ship — this module owns
     * the gate, not the endpoints, so the gate is what gets exercised.
     *
     * Two wiring facts this arrangement depends on, both verified the hard way:
     * `@Controller` is required (Spring 6.2's `RequestMappingHandlerMapping.isHandler`
     * detects only `@Controller`, no longer a bare `@RequestMapping`), and a
     * `@Component`-meta-annotated class nested inside a `@Configuration` is registered
     * by `ConfigurationClassParser`'s member-class processing — so it needs no `@Bean`
     * method, and adding one registers it TWICE (ambiguous mapping). Nesting it inside
     * this test's `@TestConfiguration` also keeps it out of every other context, since
     * Boot's `TestTypeExcludeFilter` bars test-nested classes from component scanning.
     */
    @TestConfiguration
    class ProbeConfiguration {
        @Controller
        class ProbeController {
            /** Public probe — §8.3 permitAll. */
            @GetMapping("/health")
            @ResponseBody
            fun health() = mapOf("status" to "UP")

            @GetMapping("/api/v1/probe")
            @ResponseBody
            @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
            fun read() = principalPayload()

            @PostMapping("/api/v1/probe")
            @ResponseBody
            @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
            fun write() = principalPayload()

            @GetMapping("/api/v1/admin-probe")
            @ResponseBody
            @RequiredScope(ScopeMatrix.RestOperation.MUTATE_DATASOURCES)
            fun adminOnly() = principalPayload()

            /** Deliberately unannotated — the default-deny case (AUTH-SEC-9). */
            @GetMapping("/api/v1/unannotated")
            @ResponseBody
            fun unannotated() = principalPayload()

            /**
             * A route no allowlist or matrix row has ever heard of — the §5A.4 pin:
             * the forced password change gate must catch it WITHOUT being told about
             * it, because that is the whole point of running ahead of every handler.
             */
            @GetMapping("/brand-new-route")
            @ResponseBody
            @Suppress("FunctionOnlyReturningConstant") // the body is irrelevant — the GATE before it is the assertion
            fun brandNew() = "ok"

            @PostMapping("/mcp")
            @ResponseBody
            @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
            fun mcp() = principalPayload()

            private fun principalPayload(): Map<String, String?> {
                val principal =
                    SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
                return mapOf(
                    "email" to principal?.email,
                    "auth_method" to principal?.authMethod?.name,
                    "key_id" to principal?.keyId,
                )
            }
        }
    }

    @BeforeAll
    fun seed() {
        // The shared container arrives migrated; apiKeyService.issue writes
        // api_keys.workspace_id (the V4 pin).
        user = UserRepository(jdbc).insert("agent@company.com", "Agent", null, "keycloak", "sub-1", isAdmin = false)
        // Keys pin the seeded `default` workspace; the ADMIN creator scope bypasses the
        // membership check (D4), so no workspace_members row is needed for this user.
        readKey =
            apiKeyService
                .issue(user.id, "read-key", setOf(Scope.READ), setOf(Scope.ADMIN), DEFAULT_WORKSPACE_ID)
                .plaintext
        expiredKey =
            apiKeyService
                .issue(
                    user.id,
                    "expired-key",
                    setOf(Scope.READ),
                    setOf(Scope.ADMIN),
                    DEFAULT_WORKSPACE_ID,
                    Instant.now().minusSeconds(3600),
                ).plaintext
        session = jwtService.issue(user)

        // A user mid forced-change (§5A.4): the gate reads must_change_password
        // from the row on every request, so seeding it here is enough.
        val mustChangeUser =
            UserRepository(jdbc).insert("mustchange@company.com", "Must Change", null, "local", "mustchange@company.com", isAdmin = false)
        UserRepository(jdbc).setPassword(mustChangeUser.id, "not-a-real-hash", mustChange = true)
        mustChangeSession = jwtService.issue(mustChangeUser)
    }

    // ---------------------------------------------------------------- helpers

    private fun call(
        method: HttpMethod,
        path: String,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<String> = restTemplate.exchange(path, method, HttpEntity<Any>(headers), String::class.java)

    private fun headers(
        apiKey: String? = null,
        bearer: String? = null,
        cookies: List<String> = emptyList(),
        csrfToken: String? = null,
        correlationId: String? = null,
    ): HttpHeaders =
        HttpHeaders().apply {
            apiKey?.let { set(ApiKeyCredential.HEADER, it) }
            bearer?.let { set("Authorization", "Bearer $it") }
            if (cookies.isNotEmpty()) set("Cookie", cookies.joinToString("; "))
            csrfToken?.let { set(SecurityConfig.CSRF_HEADER, it) }
            correlationId?.let { set(AuthErrorWriter.CORRELATION_HEADER, it) }
        }

    private fun error(response: ResponseEntity<String>): Map<*, *> {
        val body = mapper.readValue(response.body, Map::class.java)
        body["schema_version"] shouldBe 1
        (body["correlation_id"] as String).shouldNotBeBlank()
        return body["error"] as Map<*, *>
    }

    private fun code(response: ResponseEntity<String>): Any? = error(response)["code"]

    // ------------------------------------------------------------- public path

    @Test
    fun `the health probe is anonymous and 200`() {
        call(HttpMethod.GET, "/health").statusCode.value() shouldBe 200
    }

    // ------------------------------------------------- API-key rejection codes

    @Test
    fun `an anonymous api call is 401 auth-api_key-missing with the full envelope`() {
        // UUID-shaped so the §3.4 adoption gate echoes it (a non-UUID inbound value is
        // attacker-controlled text and is replaced — covered in AuthErrorWriterTest).
        val correlationId = UUID.randomUUID().toString()
        val response = call(HttpMethod.GET, "/api/v1/probe", headers(correlationId = correlationId))

        response.statusCode.value() shouldBe 401
        val error = error(response)
        error["code"] shouldBe "auth.api_key.missing"
        error["user_message"] shouldBe "You are not signed in. Sign in and try again."
        error["doc_url"] shouldBe "https://docs.datapipelines.co/errors/auth-api-key-missing"
        mapper.readValue(response.body, Map::class.java)["correlation_id"] shouldBe correlationId
        response.headers.getFirst(AuthErrorWriter.CORRELATION_HEADER) shouldBe correlationId
    }

    @Test
    fun `an unrecognized api key is 401 auth-api_key-invalid`() {
        val response = call(HttpMethod.GET, "/api/v1/probe", headers(apiKey = "dpk_ZZZZZZZZZZZZ.${"A".repeat(48)}"))

        response.statusCode.value() shouldBe 401
        code(response) shouldBe "auth.api_key.invalid"
    }

    @Test
    fun `an expired api key is 401 auth-api_key-expired`() {
        val response = call(HttpMethod.GET, "/api/v1/probe", headers(apiKey = expiredKey))

        response.statusCode.value() shouldBe 401
        code(response) shouldBe "auth.api_key.expired"
    }

    @Test
    fun `a valid api key authenticates and reaches the handler`() {
        val response = call(HttpMethod.GET, "/api/v1/probe", headers(apiKey = readKey))

        response.statusCode.value() shouldBe 200
        mapper.readValue(response.body, Map::class.java)["auth_method"] shouldBe "API_KEY"
    }

    @Test
    fun `an insufficient scope is 403 auth-scope-insufficient`() {
        val response = call(HttpMethod.GET, "/api/v1/admin-probe", headers(apiKey = readKey))

        response.statusCode.value() shouldBe 403
        code(response) shouldBe "auth.scope.insufficient"
    }

    @Test
    fun `an unannotated api handler is denied by default (AUTH-SEC-9)`() {
        val response = call(HttpMethod.GET, "/api/v1/unannotated", headers(apiKey = readKey))

        response.statusCode.value() shouldBe 403
        (error(response)["details"] as Map<*, *>)["reason"] shouldBe "handler_not_annotated"
    }

    // -------------------------------------------------- session codes (AU-TEST-3)

    @Test
    fun `a valid session cookie authenticates on the api surface`() {
        val response = call(HttpMethod.GET, "/api/v1/probe", headers(cookies = listOf("dp_session=$session")))

        response.statusCode.value() shouldBe 200
        mapper.readValue(response.body, Map::class.java)["auth_method"] shouldBe "OIDC"
    }

    @Test
    fun `an expired session cookie is 401 auth-session-expired, not api_key-missing`() {
        val response = call(HttpMethod.GET, "/api/v1/probe", headers(cookies = listOf("dp_session=${expiredSession()}")))

        response.statusCode.value() shouldBe 401
        code(response) shouldBe "auth.session.expired"
    }

    /**
     * The tamper lands on the signature's **first** character, deliberately.
     *
     * An HS256 signature is 32 bytes → 43 unpadded base64url characters → 258 encoded
     * bits, so the **last** character carries only 4 significant bits and its low 2 bits
     * are padding: `…A`, `…B`, `…C` and `…D` all decode to the SAME 32 signature bytes.
     * Flipping the last character therefore produces a cookie that is not tampered at
     * all roughly 1 run in 16 (whenever the signature happens to end in that group), and
     * the token validates — a 6% flake that reads as an auth bypass. The first character
     * carries all 6 of its bits, so changing it always changes the MAC.
     */
    @Test
    fun `a tampered session cookie is 401 auth-session-invalid`() {
        val parts = session.split(".")
        val signature = parts[2]
        val tampered = "${parts[0]}.${parts[1]}.${if (signature.first() == 'A') 'B' else 'A'}${signature.drop(1)}"

        val response = call(HttpMethod.GET, "/api/v1/probe", headers(cookies = listOf("dp_session=$tampered")))

        response.statusCode.value() shouldBe 401
        code(response) shouldBe "auth.session.invalid"
    }

    @Test
    fun `an api key wins over a session cookie when both are present (AU-TEST-11)`() {
        val response =
            call(
                HttpMethod.GET,
                "/api/v1/probe",
                headers(apiKey = readKey, cookies = listOf("dp_session=$session")),
            )

        response.statusCode.value() shouldBe 200
        val body = mapper.readValue(response.body, Map::class.java)
        body["auth_method"] shouldBe "API_KEY"
        (body["key_id"] as String) shouldBe readKey.substringBefore('.')
    }

    // ---------------------------------------------------------- /mcp (AUTH-SEC-1)

    @Test
    fun `a session cookie authenticates nobody on the mcp endpoint`() {
        val response = call(HttpMethod.POST, "/mcp", headers(cookies = listOf("dp_session=$session")))

        response.statusCode.value() shouldBe 401
        code(response) shouldBe "auth.api_key.missing"
    }

    @Test
    fun `the mcp endpoint accepts DP-API-Key and the Bearer dpk_ carrier`() {
        call(HttpMethod.POST, "/mcp", headers(apiKey = readKey)).statusCode.value() shouldBe 200
        call(HttpMethod.POST, "/mcp", headers(bearer = readKey)).statusCode.value() shouldBe 200
    }

    // ---------------------------------------------- CSRF by credential (AUTH-SEC-1)

    @Test
    fun `a cookie-authenticated state change with no csrf token is 403 reason=missing`() {
        val response = call(HttpMethod.POST, "/api/v1/probe", headers(cookies = listOf("dp_session=$session")))

        response.statusCode.value() shouldBe 403
        val error = error(response)
        error["code"] shouldBe "auth.csrf.invalid"
        (error["details"] as Map<*, *>)["reason"] shouldBe "missing"
    }

    @Test
    fun `a cookie-authenticated state change with the wrong csrf token is 403 reason=mismatch`() {
        val response =
            call(
                HttpMethod.POST,
                "/api/v1/probe",
                headers(cookies = listOf("dp_session=$session", "dp_csrf=the-real-token"), csrfToken = "a-different-token"),
            )

        response.statusCode.value() shouldBe 403
        val error = error(response)
        error["code"] shouldBe "auth.csrf.invalid"
        (error["details"] as Map<*, *>)["reason"] shouldBe "mismatch"
    }

    @Test
    fun `a cookie-authenticated state change with the matching double-submit token succeeds`() {
        val response =
            call(
                HttpMethod.POST,
                "/api/v1/probe",
                headers(cookies = listOf("dp_session=$session", "dp_csrf=the-real-token"), csrfToken = "the-real-token"),
            )

        response.statusCode.value() shouldBe 200
    }

    /**
     * 027 (024 T41's browser family): the dp_csrf cookie must SURVIVE authenticated
     * responses untouched. Spring's default composite runs CsrfAuthenticationStrategy
     * whenever the security context changes during a request — with per-request JWT
     * authentication that is every authenticated request — and that strategy ROTATES
     * the token or DELETES the cookie outright (observed live: htmx partial responses
     * wiped dp_csrf, so every subsequent browser mutation 403'd against the token its
     * page had rendered). A double-submit cookie is only usable if it is stable, so
     * this asserts NO dp_csrf Set-Cookie at all leaves an authenticated response.
     */
    @Test
    fun `an authenticated response leaves the dp_csrf cookie untouched - no rotate, no delete`() {
        val response =
            call(
                HttpMethod.GET,
                "/api/v1/probe",
                headers(cookies = listOf("dp_session=$session", "dp_csrf=the-real-token")),
            )

        response.statusCode.value() shouldBe 200
        val csrfCookies = response.headers[HttpHeaders.SET_COOKIE].orEmpty().filter { it.startsWith("dp_csrf") }
        csrfCookies.shouldBeEmpty()
    }

    @Test
    fun `an api-key state change needs no csrf token at all`() {
        call(HttpMethod.POST, "/api/v1/probe", headers(apiKey = readKey)).statusCode.value() shouldBe 200
    }

    // ----------------------------------- filter execution count (B12 behavioral, 015)
    //
    // AU-API-10 proven BEHAVIORALLY, not by the presence of a workaround: each
    // auth filter must execute EXACTLY ONCE per request. Spring Boot auto-registers
    // every `Filter` bean with the servlet container on top of the security chain —
    // the hazard the deleted `AuthFilterRegistrationConfig` used to suppress and the
    // zero-bean wiring (015) removes structurally. Each test below fails the moment
    // its filter executes twice for one request, whatever the wiring mechanism that
    // caused it.

    /**
     * The container's own registration table must name none of the three auth
     * filter classes. Since 015 the filters are not beans at all, so there is
     * nothing for Boot to auto-register; a registration here means a second,
     * container-level execution path exists.
     */
    @Test
    fun `the servlet container holds no registration for any auth filter class`() {
        val authFilterClasses =
            setOf(
                ApiKeyFilter::class.java.name,
                JwtAuthenticationFilter::class.java.name,
                LoginRateLimitFilter::class.java.name,
            )
        servletContext.filterRegistrations.values
            .filter { it.className in authFilterClasses }
            .shouldBeEmpty()
    }

    /**
     * The login rate limit is pinned to 4 ([props]). Five `/login` calls must yield
     * exactly one 429 — the FIFTH. A double-executed [LoginRateLimitFilter] counts
     * two per request and 429s on the third call instead.
     *
     * The window is reset first (034 F4): the budget is per-IP, in-memory and SHARED
     * by the whole context, so a second consumer of `/login` or `/oauth2/` in this
     * class would pre-consume it and make the resulting early-429 mimic the very
     * double-execution this test detects.
     */
    @Test
    fun `the login rate limiter meters each request exactly once`() {
        authFilters.loginRateLimit.resetWindowsForTest()
        repeat(LOGIN_LIMIT) {
            call(HttpMethod.GET, "/login").statusCode.value() shouldNotBe 429
        }
        call(HttpMethod.GET, "/login").statusCode.value() shouldBe 429
    }

    /**
     * One well-shaped-but-unknown key is validated once and refused once: exactly
     * one `auth.api_key.rejected` audit row (§10.1). A double-executed
     * [ApiKeyFilter] — the AU-API-10 "two Argon2 verifications" hazard — writes two.
     */
    @Test
    fun `one rejected api key produces exactly one audit row`() {
        val before = rejectionCount()
        val response =
            call(HttpMethod.GET, "/api/v1/probe", headers(apiKey = "dpk_ZZZZZZZZZZZZ.${"A".repeat(48)}"))
        response.statusCode.value() shouldBe 401
        rejectionCount() shouldBe before + 1
    }

    /**
     * A rejected session cookie is cleared by exactly one `Set-Cookie` header. A
     * double-executed [JwtAuthenticationFilter] clears it twice.
     */
    @Test
    fun `a rejected session cookie is cleared exactly once`() {
        val response =
            call(HttpMethod.GET, "/api/v1/probe", headers(cookies = listOf("dp_session=${expiredSession()}")))

        response.statusCode.value() shouldBe 401
        response.headers[HttpHeaders.SET_COOKIE]
            .orEmpty()
            .count { it.startsWith("${OidcSuccessHandler.SESSION_COOKIE}=") } shouldBe 1
    }

    // ------------------------------------------ forced password change gate (§5A.4)

    @Test
    fun `a brand-new route is still gated for a must-change user - the gate cannot be forgotten by a future controller`() {
        // java.net.http with redirects NEVER followed: TestRestTemplate's default
        // factory follows GET redirects (HttpURLConnection), so the 302 would
        // surface as the target's 404 in this context — the redirect IS the
        // assertion, so it must be observed raw.
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/brand-new-route"))
                .header("Cookie", "dp_session=$mustChangeSession")
                .GET()
                .build()
        val response =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())

        response.statusCode() shouldBe 302
        response
            .headers()
            .firstValue("Location")
            .orElse("")
            .endsWith("/settings/password") shouldBe true
    }

    @Test
    fun `the must-change gate answers the change-required envelope on the api surface`() {
        val response =
            call(HttpMethod.GET, "/api/v1/probe", headers(cookies = listOf("dp_session=$mustChangeSession")))

        response.statusCode.value() shouldBe 403
        code(response) shouldBe "auth.password.change_required"
    }

    @Test
    fun `a compliant session is not gated`() {
        call(HttpMethod.GET, "/brand-new-route", headers(cookies = listOf("dp_session=$session")))
            .statusCode
            .value() shouldBe 200
    }

    private fun rejectionCount(): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event = 'auth.api_key.rejected'",
                emptyMap<String, Any>(),
                Int::class.java,
            ),
        ) { "COUNT(*) returned no row" }

    private fun expiredSession(): String {
        val past = Instant.now().minusSeconds(3600)
        return Jwts
            .builder()
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("name", user.displayName)
            .claim("scopes", listOf("read"))
            .issuer("datapipelines")
            .issuedAt(Date.from(past.minusSeconds(60)))
            .expiration(Date.from(past))
            .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(JWT_SECRET)), Jwts.SIG.HS256)
            .compact()
    }

    private companion object {
        val JWT_SECRET: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 11).toByte() })

        /** Pinned login rate limit for the filter-once test — see [props]. */
        const val LOGIN_LIMIT = 4

        // The module's shared container: already started and migrated by the time any
        // @DynamicPropertySource supplier resolves (first touch starts it).
        val postgres get() = SharedPostgres.postgres

        val discovery = OidcDiscoveryStub()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            // Pinned low so the filter-once test (B12) needs only five /login calls.
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { LOGIN_LIMIT }
            // Required once a provider is configured (§5.2); no OIDC login happens here.
            registry.add("datapipelines.auth.base-url") { "https://dp.example.com" }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "stub" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "dp-client" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "dp-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { discovery.issuer }
        }
    }
}

/** The V4-seeded `default` workspace (metadata-db §4.11) — a legitimate test pin: these suites seed the default world. */
private val DEFAULT_WORKSPACE_ID: java.util.UUID = java.util.UUID.fromString("defa0000-0000-0000-0000-000000000001")
