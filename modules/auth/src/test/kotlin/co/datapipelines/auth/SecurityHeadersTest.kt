package co.datapipelines.auth

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.SpringSecurityCoreVersion
import org.springframework.stereotype.Controller
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * [SecurityHeaders] — the CSP CONTRACT (#188, one policy since #195), in the shape
 * `WebCorsConfigurationTest` holds for CORS: the policy strings themselves, and the
 * writer run against mock requests so the header is proven on the object the filter
 * chain uses, not on prose.
 *
 * The property pinned hardest: no policy anywhere carries `'unsafe-inline'`, a nonce,
 * or `'unsafe-eval'` — the editor's route-scoped eval exemption 188 shipped is retired
 * (195: the page runs Alpine's CSP build), and the test below pins its ABSENCE on the
 * editor route explicitly. `AuthHttpBoundaryTest` and the app module's smoke test read
 * the same headers off the wire.
 *
 * #1023 adds the chain's cache headers, read off the wire through the REAL chain (this class boots
 * [AuthTestApplication] on a random port with the probe controller below): the framework's
 * `Cache-Control`, `Pragma` and `Expires` on every answer except a font file's, and every other stated
 * header on every answer, the font file's included. Nothing pinned `Pragma` or `Expires` before #1023.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHeadersTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var jwtService: JwtService

    private lateinit var session: String

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** A signed-in caller (a super admin, so every probe's permission admits it), seeded as `AuthHttpBoundaryTest` does. */
    @BeforeAll
    fun seed() {
        DefaultWorkspaceFixture.ensure(jdbc)
        val tag = UUID.randomUUID().toString().take(TAG_LENGTH)
        val user = UserRepository(jdbc).insert("headers-$tag@company.com", "Headers", null, "keycloak", "sub-headers-$tag", isAdmin = true)
        session = jwtService.issue(user)
    }

    private fun headersFor(uri: String): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        SecurityHeaders.cspWriters().forEach { it.writeHeaders(MockHttpServletRequest("GET", uri), response) }
        return response
    }

    @Test
    fun `the policy has no unsafe-inline, no unsafe-eval and no nonce - a script is a file`() {
        SecurityHeaders.CSP_POLICY shouldNotContain "unsafe-inline"
        SecurityHeaders.CSP_POLICY shouldNotContain "unsafe-eval"
        SecurityHeaders.CSP_POLICY shouldNotContain "nonce"
        SecurityHeaders.CSP_POLICY shouldStartWith "default-src 'self'; script-src 'self'; style-src 'self' 'sha256-"
        SecurityHeaders.CSP_POLICY shouldContain "frame-ancestors 'self'"
        SecurityHeaders.CSP_POLICY shouldContain "base-uri 'self'"
        SecurityHeaders.CSP_POLICY shouldContain "form-action 'self'"
        SecurityHeaders.CSP_POLICY shouldContain "object-src 'none'"
    }

    /**
     * #197 — the exact `img-src` value, pinned character for character: `'self' data:`, and
     * nothing else — no scheme wildcard and no provider host. The OIDC picture is served by
     * the app's own `/avatar` proxy, so the policy is a CONSTANT: it does not vary with the
     * configured providers (which feed the proxy's fetch allowlist instead), and it must be
     * identical whether a deployment names two providers or none. The old standing `https:`
     * grant is asserted absent by name, so a revert of the narrowing fails here.
     */
    @Test
    fun `img-src admits exactly self and data - no scheme wildcard, no provider host`() {
        SecurityHeaders.CSP_POLICY shouldContain "img-src 'self' data:"
        SecurityHeaders.CSP_POLICY shouldNotContain "img-src 'self' data: https:"
        SecurityHeaders.CSP_POLICY shouldNotContain "https:"
        headersFor("/dashboard").getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
    }

    /**
     * 195 — the exemption's retirement record, in the negative: the eval directive 188
     * scoped onto `GET /pipelines/{id}/editor` is gone from the object AND from the
     * header that route actually receives. Falsified by re-appending ` 'unsafe-eval'`
     * to the `script-src 'self'` constant in the DIRECTIVES assembly — the first
     * assertion goes red naming the directive.
     */
    @Test
    fun `no response carries unsafe-eval - the editor route included`() {
        SecurityHeaders.CSP_POLICY shouldNotContain "unsafe-eval"
        headersFor("/pipelines/abc-123/editor").getHeader(SecurityHeaders.CSP_HEADER) shouldNotContain "unsafe-eval"
        headersFor("/pipelines/abc-123/editor").getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
    }

    @Test
    fun `style-src admits Cytoscape's one sheet by hash and nothing else inline`() {
        SecurityHeaders.CSP_POLICY shouldContain "style-src 'self' 'sha256-"
        SecurityHeaders.CSP_POLICY shouldNotContain "unsafe-inline"
        SecurityHeaders.CSP_POLICY shouldNotContain "unsafe-hashes"
        // The style-src directive is exactly self + the one hash, nothing more.
        val styleSrc = SecurityHeaders.CSP_POLICY.split("; ").first { it.startsWith("style-src") }
        styleSrc shouldBe "style-src 'self' " + SecurityHeaders.CYTOSCAPE_STYLESHEET_HASH
    }

    /**
     * The hashed sheet IS the one the vendored Cytoscape injects: the literal is read out of
     * `cytoscape.min.js` itself, so a bump that changes the text (or stops injecting it)
     * fails here — a stale hash would otherwise silently become a blocked sheet on the
     * live editor. The vendored file lives in the web module; located from the repo root.
     */
    @Test
    fun `the hashed stylesheet is exactly what the vendored Cytoscape injects`() {
        val vendored = repoFile("modules/web/src/main/resources/static/vendor/cytoscape/cytoscape.min.js").readText()
        // u.textContent="."+s+" { position: relative; }" with s = "__________cytoscape_container"
        val container = Regex("\"(_{5,}cytoscape_container)\"").find(vendored)?.groupValues?.get(1) ?: error("container class not found")
        val suffix = Regex("""textContent="\."\+s\+"([^"]*)"""").find(vendored)?.groupValues?.get(1) ?: error("injected sheet not found")
        ".$container$suffix" shouldBe SecurityHeaders.CYTOSCAPE_STYLESHEET
        SecurityHeaders.CYTOSCAPE_STYLESHEET_HASH shouldBe "'sha256-pgvDUBa4IjFA2yuSJ2cqcyxmNYJMborsd0ORcRv9vw8='"
    }

    @Test
    fun `the sitemap carries no policy - the browser's own XML viewer is the only style it ever has`() {
        headersFor("/sitemap.xml").getHeader(SecurityHeaders.CSP_HEADER) shouldBe null
        // and nothing near it is exempt
        headersFor("/sitemap.xml/x").getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
        headersFor("/robots.txt").getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
    }

    private fun repoFile(relative: String): java.io.File {
        var dir = java.io.File(".").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${java.io.File(".").absolutePath}")
        }
        return java.io.File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    @Test
    fun `every policed route gets the one policy - the editor's included, its neighbours unchanged`() {
        // The editor's full-document GET — the route the exemption used to sit on —
        // takes the same policy as everything else.
        headersFor("/pipelines/abc-123/editor").getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
        listOf(
            "/pipelines",
            "/pipelines/abc-123",
            "/pipelines/abc-123/editor/x",
            "/partials/pipelines/abc-123/editor",
            "/templates/editor",
            "/login",
            "/api/v1/pipelines",
            "/mcp",
        ).forEach { uri ->
            headersFor(uri).getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
        }
    }

    @Test
    fun `every response gets exactly one policy - the single writer`() {
        listOf("/pipelines/abc-123/editor", "/pipelines", "/").forEach { uri ->
            headersFor(uri).getHeaders(SecurityHeaders.CSP_HEADER).size shouldBe 1
        }
    }

    @Test
    fun `the context path is stripped before the route is judged`() {
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("GET", "/dp/pipelines/abc-123/editor").apply { contextPath = "/dp" }
        SecurityHeaders.cspWriters().forEach { it.writeHeaders(request, response) }
        response.getHeader(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
    }

    // ------------------------------------------------- the chain's cache headers (#1023)

    /**
     * One answer of the real chain per row, with no session or with one. [Cache.CHAIN]: the framework's three cache
     * headers exactly; [Cache.NONE]: none of the three (the probe sets none of its own, so an absent header is one the
     * chain did not write). Every row also carries every other stated header unchanged.
     */
    @Test
    fun `the chain writes its cache headers on every answer but a font file's, and every other header on all of them`() {
        println("1023|spring-security|${SpringSecurityCoreVersion.getVersion()}")
        val rows =
            listOf(
                Row("a font file", "$FONT_PREFIX_PROBE/probe-1023.woff2", signedIn = false, 200, Cache.NONE),
                Row("a font file, signed in", "$FONT_PREFIX_PROBE/probe-1023.woff2", signedIn = true, 200, Cache.NONE),
                Row("a font file at the prefix's top", "/vendor/fonts/probe-1023.woff2", signedIn = false, 200, Cache.NONE),
                Row("near miss: a longer segment", "/vendor/fontsX/probe-1023.woff2", signedIn = false, 200, Cache.CHAIN),
                Row("near miss: the prefix under the api", "/api/vendor/fonts/probe-1023.woff2", signedIn = false, 401, Cache.CHAIN),
                Row("near miss: the prefix without a file", "/vendor/fonts", signedIn = false, 404, Cache.CHAIN),
                Row("near miss: the prefix's directory", "/vendor/fonts/", signedIn = false, 404, Cache.CHAIN),
                Row("near miss: a directory under it", "$FONT_PREFIX_PROBE/", signedIn = false, 404, Cache.CHAIN),
                Row("near miss: a POST to a font file", "$FONT_PREFIX_PROBE/probe-1023.woff2", signedIn = false, 403, Cache.CHAIN, method = "POST"),
                Row("a stylesheet", "/css/probe-1023.css", signedIn = false, 200, Cache.CHAIN),
                Row("a script", "/js/probe-1023.js", signedIn = false, 200, Cache.CHAIN),
                Row("a vendored file outside fonts", "/vendor/probe/probe-1023.js", signedIn = false, 200, Cache.CHAIN),
                Row("a site file", "/site/css/probe-1023.css", signedIn = false, 200, Cache.CHAIN),
                Row("the login page", "/login", signedIn = false, 200, Cache.CHAIN),
                Row("a signed-in page", "/pipelines", signedIn = true, 200, Cache.CHAIN),
                Row("a signed-out page", "/pipelines", signedIn = false, LOGIN_REDIRECT, Cache.CHAIN, accept = "text/html"),
                Row("a signed-in api answer", "/api/v1/probe-1023", signedIn = true, 200, Cache.CHAIN),
                Row("a 401", "/api/v1/probe-1023", signedIn = false, 401, Cache.CHAIN),
            )
        assertSoftly {
            for (row in rows) {
                val response = get(row)
                val shown = CACHE_HEADERS.joinToString(" | ") { "$it: ${response.header(it) ?: "(absent)"}" }
                println("1023|chain|${row.label}|${row.method} ${row.path}|status=${response.statusCode()}|$shown")
                withClue("${row.label}: GET ${row.path}") {
                    response.statusCode() shouldBe row.status
                    when (row.cache) {
                        Cache.CHAIN -> CHAIN_CACHE_HEADERS.forEach { (name, value) -> response.header(name) shouldBe value }
                        Cache.NONE -> CHAIN_CACHE_HEADERS.keys.forEach { name -> response.header(name) shouldBe null }
                    }
                    response.header("X-Content-Type-Options") shouldBe "nosniff"
                    response.header("X-Frame-Options") shouldBe SecurityHeaders.FRAME_OPTIONS
                    response.header("X-XSS-Protection") shouldBe "0"
                    response.header("Referrer-Policy") shouldBe SecurityHeaders.REFERRER_POLICY
                    response.header("Permissions-Policy") shouldBe SecurityHeaders.PERMISSIONS_POLICY
                    response.header(SecurityHeaders.CSP_HEADER) shouldBe SecurityHeaders.CSP_POLICY
                    response.header("Strict-Transport-Security") shouldBe null
                }
            }
        }
    }

    /**
     * The matcher itself, on every path it was tried against (the handback lists them): the writer [SecurityHeaders]
     * hands the chain writes the framework's three headers unless the request is a font file. Mock requests, so the
     * context path can be set: the path is judged inside the application.
     */
    @Test
    fun `the cache writer skips exactly the font files - every near miss keeps all three headers`() {
        val fontFiles =
            listOf(
                "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2",
                "/vendor/fonts/ibm-plex-mono/OFL.txt",
                "/vendor/fonts/x",
                "/vendor/fonts/no-such-font-1023.woff2",
                "/vendor/fonts/noto-sans",
            )
        val nearMisses =
            listOf(
                "/vendor/fonts",
                "/vendor/fonts/",
                "/vendor/fonts/noto-sans/",
                "/vendor/fontsX/noto-sans/noto-sans-latin-wght-normal.woff2",
                "/vendor/font/x.woff2",
                "/vendor/Fonts/x.woff2",
                "/VENDOR/fonts/x.woff2",
                "/vendorfonts/x.woff2",
                "/api/vendor/fonts/x.woff2",
                "/site/vendor/fonts/x.woff2",
                "/vendor/x.woff2",
                "/vendor/htmx/htmx.min.js",
                "/css/app.css",
                "/js/app.js",
                "/js/js-rendered/engine.js",
                "/site/css/site.css",
                "/",
                "/login",
                "/dashboard",
                "/api/v1/executions",
                "/partials/pipelines/abc-123/editor",
                "/favicon.ico",
            )
        assertSoftly {
            fontFiles.forEach { uri -> withClue(uri) { cacheHeadersFor(MockHttpServletRequest("GET", uri)) shouldBe NO_CACHE_HEADERS } }
            nearMisses.forEach { uri -> withClue(uri) { cacheHeadersFor(MockHttpServletRequest("GET", uri)) shouldBe CHAIN_CACHE_HEADERS } }
            // The context path is stripped before the path is judged, and only the context path.
            val underContext = MockHttpServletRequest("GET", "/dp/vendor/fonts/x.woff2").apply { contextPath = "/dp" }
            withClue("a font file under a context path") { cacheHeadersFor(underContext) shouldBe NO_CACHE_HEADERS }
            val contextLooksLikeFonts = MockHttpServletRequest("GET", "/vendor/fonts/css/app.css").apply { contextPath = "/vendor/fonts" }
            withClue("a context path that looks like the prefix") { cacheHeadersFor(contextLooksLikeFonts) shouldBe CHAIN_CACHE_HEADERS }
            val noContext = MockHttpServletRequest("GET", "/dp/vendor/fonts/x.woff2")
            withClue("the prefix below an unknown first segment") { cacheHeadersFor(noContext) shouldBe CHAIN_CACHE_HEADERS }
            // The handler answers GET and HEAD only: any other method on a font path is refused and keeps the headers.
            val fontPath = "/vendor/fonts/noto-sans/noto-sans-latin-wght-normal.woff2"
            withClue("HEAD of a font file") { cacheHeadersFor(MockHttpServletRequest("HEAD", fontPath)) shouldBe NO_CACHE_HEADERS }
            listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE").forEach { method ->
                withClue("$method of a font file") { cacheHeadersFor(MockHttpServletRequest(method, fontPath)) shouldBe CHAIN_CACHE_HEADERS }
            }
        }
        println("1023|matcher|font-files=$fontFiles")
        println("1023|matcher|near-misses=$nearMisses")
    }

    private fun cacheHeadersFor(request: MockHttpServletRequest): Map<String, String> {
        val response = MockHttpServletResponse()
        SecurityHeaders.cacheControlWriter().writeHeaders(request, response)
        return CACHE_HEADERS.mapNotNull { name -> response.getHeader(name)?.let { name to it } }.toMap()
    }

    /**
     * The firewall refuses an encoded or dot-segment path before any writer or handler runs, so such a request never
     * reaches the font rule (nor the font handler): it is refused, not served.
     */
    @Test
    fun `an encoded or dot-segment path under the font prefix is refused by the firewall, not served`() {
        listOf("/vendor/fonts/%2e%2e/css/probe-1023.css", "/vendor/fonts/../css/probe-1023.css", "/vendor/fonts/%2fprobe-1023.woff2")
            .forEach { path ->
                val response = get(Row("refused", path, signedIn = false, 0, Cache.CHAIN))
                println("1023|firewall|$path|status=${response.statusCode()}|body=${response.body().take(BODY_SHOWN)}")
                withClue("GET $path") {
                    response.statusCode() shouldBe 400
                    // The firewall's error answer, never the probe's (the error body echoes the refused path).
                    response.body() shouldNotBe PROBE_BODY
                }
            }
    }

    private fun get(row: Row): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port${row.path}"))
                .method(row.method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
        if (row.signedIn) builder.header("Cookie", "${OidcSuccessHandler.SESSION_COOKIE}=$session")
        row.accept?.let { builder.header("Accept", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<*>.header(name: String): String? = headers().allValues(name).takeIf { it.isNotEmpty() }?.joinToString(", ")

    private enum class Cache { CHAIN, NONE }

    private data class Row(
        val label: String,
        val path: String,
        val signedIn: Boolean,
        val status: Int,
        val cache: Cache,
        val accept: String? = null,
        val method: String = "GET",
    )

    /**
     * Probe endpoints standing in for the web module's handlers (this module owns the chain, not the handlers): each
     * answers a fixed body and sets no cache header of its own, so every cache header on its answer is the chain's.
     * Registered as `AuthHttpBoundaryTest`'s probes are (a `@Controller` nested in a `@TestConfiguration`).
     */
    @TestConfiguration
    class ProbeConfiguration {
        @Controller
        class ProbeController {
            @GetMapping(
                "$FONT_PREFIX_PROBE/probe-1023.woff2",
                "/vendor/fonts/probe-1023.woff2",
                "/vendor/fontsX/probe-1023.woff2",
                "/css/probe-1023.css",
                "/js/probe-1023.js",
                "/vendor/probe/probe-1023.js",
                "/site/css/probe-1023.css",
                "/login",
            )
            @ResponseBody
            fun publicProbe(): String = PROBE_BODY

            @GetMapping("/pipelines", "/api/v1/probe-1023")
            @ResponseBody
            @RequiredScope(Permission.PIPELINE_READ)
            fun signedInProbe(): String = PROBE_BODY
        }
    }

    private companion object {
        const val PROBE_BODY = "probe-1023"
        const val FONT_PREFIX_PROBE = "/vendor/fonts/probe"
        const val TAG_LENGTH = 8
        const val BODY_SHOWN = 120

        /** What a signed-out page request is answered with (`AuthEntryPoint`: a redirect to the login page). */
        const val LOGIN_REDIRECT = 302

        val CACHE_HEADERS = listOf("Cache-Control", "Pragma", "Expires")
        val NO_CACHE_HEADERS = emptyMap<String, String>()

        /** The framework's cache writer (`CacheControlHeadersWriter` of the resolved Spring Security), read off the base. */
        val CHAIN_CACHE_HEADERS =
            mapOf(
                "Cache-Control" to "no-cache, no-store, max-age=0, must-revalidate",
                "Pragma" to "no-cache",
                "Expires" to "0",
            )

        val JWT_SECRET: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 23).toByte() })

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { SharedPostgres.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedPostgres.postgres.username }
            registry.add("spring.datasource.password") { SharedPostgres.postgres.password }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.auth.base-url") { "https://dp.example.com" }
            // No OIDC provider: the chain is the same either way (`SecurityConfig`'s KDoc), and local accounts are the
            // simplest configuration that boots.
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }
    }
}
