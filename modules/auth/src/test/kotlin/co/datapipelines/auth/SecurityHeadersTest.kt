package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

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
 */
class SecurityHeadersTest {
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
}
