package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * T41's regression guard, swept as a CLASS (027): every state-changing `fetch` in the
 * app's static JS must carry the `DP-CSRF-Token` double-submit header.
 *
 * Cookie-authenticated mutations are 403 `auth.csrf.invalid` without it (auth.md §8.4,
 * exemption follows the credential — never the path). The htmx surface is covered by the
 * layout's inherited `hx-headers` (LayoutHxHeadersTest); the editor's raw `fetch` calls
 * bypass htmx and are exactly the calls that shipped headerless (024 T41: the in-editor
 * Execute and Cancel were both rejected 403).
 *
 * Method: a bounded window after each `fetch(` — NOT a JS parser. Sufficient for this
 * codebase's call shape (init object inline at the call site) and honest about the limit:
 * a state-changing fetch whose `method:` sits more than [WINDOW] chars past the call
 * would escape. GET fetches need no token; a fetch with NO explicit method is a GET.
 */
class StaticJsCsrfAuditTest {
    @Test
    fun `every state-changing fetch in static js carries the csrf token header`() {
        val scripts = staticJsSources()
        scripts.shouldNotBeEmpty()

        val violations =
            scripts.flatMap { (name, source) ->
                STATE_CHANGING_METHOD_REGEX
                    .findAll(source)
                    .map { match ->
                        // The method literal proves a mutating call; the token must appear
                        // in the fetch-init window around it (headers block sits beside it).
                        val windowStart = (match.range.first - WINDOW).coerceAtLeast(0)
                        val windowEnd = (match.range.last + WINDOW).coerceAtMost(source.length - 1)
                        Triple(name, match.value, windowStart..windowEnd)
                    }.filter { (_, _, window) -> "DP-CSRF-Token" !in source.substring(window.first, window.last + 1) }
                    .map { (name, methodLiteral, _) -> "$name: $methodLiteral without DP-CSRF-Token in its fetch init" }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `the audit is grounded - the editor's execute and cancel fetches are in scope`() {
        val sources = staticJsSources().toMap()
        val sse =
            sources["static/js/pipeline-editor/sse.js"]
                ?: error("sse.js not found on the test classpath — the audit ran vacuously")
        // Both known call sites must be discoverable by the method regex, else a
        // refactor to an indirect method value would silently defang this audit.
        STATE_CHANGING_METHOD_REGEX.findAll(sse).count() shouldBe 2
    }

    /**
     * 097 §D — the template editor's lifecycle calls joined the sweep. They used to live in an
     * inline `<script>` in `templates/templates/editor.html`, which this audit's static-js
     * glob could not see: a guard hole, and the file it could not see was the one carrying a
     * THIRD way of finding the CSRF token.
     *
     * Amended 110, repairing a grounding 102 left stale: 102 moved Release / Purge draft
     * out of `lifecycle.js` into the §4.3d dialogs (`partials/template-lifecycle-*.html` —
     * `hx-post` forms whose CSRF header is the layout's inherited `hx-headers`,
     * `LayoutHxHeadersTest`), leaving the file's only server call the render, through
     * `htmx.ajax`. The grounding flips accordingly: the file must carry ZERO mutating
     * fetches — the moment one appears it joins the sweep above, and if the regex can no
     * longer see such calls this test goes red too.
     * (Kotlin nests block comments, so the glob is spelled in words in the KDoc above —
     * the same trap InlineWidthAuditTest records.)
     */
    @Test
    fun `the audit is grounded - the template editor's js performs no state-changing fetch of its own`() {
        val sources = staticJsSources().toMap()
        val lifecycle =
            sources["static/js/template-editor/lifecycle.js"]
                ?: error("lifecycle.js not found on the test classpath — the audit ran vacuously")

        STATE_CHANGING_METHOD_REGEX.findAll(lifecycle).count() shouldBe 0
        (
            "/api/v1/templates/release" in lifecycle ||
                "/api/v1/templates/draft/discard" in lifecycle
        ) shouldBe false
        // The render (and any future partial read) is htmx's, and the CSRF header
        // rides on the layout for it.
        ("htmx" in lifecycle) shouldBe true
    }

    private fun staticJsSources(): List<Pair<String, String>> =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResources("classpath*:static/js/**/*.js")
            .filter { it.filename != null }
            .map {
                val path = TestRepoFiles.requireInModuleResources("static/js/" + it.url.path.substringAfter("static/js/"))
                path to it.inputStream.readBytes().decodeToString()
            }

    private companion object {
        val STATE_CHANGING_METHOD_REGEX =
            Regex("""method:\s*["'](POST|PUT|PATCH|DELETE)["']""")
        const val WINDOW = 400
    }
}
