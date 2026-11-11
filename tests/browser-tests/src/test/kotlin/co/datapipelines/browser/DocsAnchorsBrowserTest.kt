package co.datapipelines.browser

import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #713 — the docs pages render their long bodies inside `content-visibility: auto`
 * sections (`section.doc-part`, the ladder's second rung). Everything a reader does with
 * an anchor has to survive that skip-rendering, in a real browser against the served
 * page:
 *
 *  - a deep fragment URL lands on its heading (the browser renders the target section
 *    on demand and scrolls to it);
 *  - an in-page cross-reference walks to its section;
 *  - the sections far below the fold stay reachable: their headings are in the
 *    accessibility tree (what screen readers use) and their text is in the DOM (what
 *    Chrome's find-in-page indexes) — the two paths a reader has into skip-rendered
 *    content;
 *  - the page chrome's navigation is unchanged (header links, the way back to /docs).
 *
 * Anonymous throughout: docs routes are public (SitePublicRoutesBrowserTest).
 */
class DocsAnchorsBrowserTest : BrowserSuite() {
    @Test
    fun `a deep fragment lands on its heading inside the wrapped sections`() {
        startTrace()
        page.setViewportSize(1440, 900)
        navigate("/docs/auth#83-public-endpoints-no-auth-required")
        val deadline = System.currentTimeMillis() + 30_000
        var last: Map<String, Any?> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            last =
                page.evaluate(
                    """async () => {
                        const h = document.getElementById('83-public-endpoints-no-auth-required');
                        if (!h) return { found: false };
                        const r = h.getBoundingClientRect();
                        const part = h.closest('section.doc-part');
                        const cv = part ? getComputedStyle(part).contentVisibility : null;
                        return {
                            found: true, top: Math.round(r.top), scrollY: Math.round(scrollY),
                            docHeight: Math.round(document.documentElement.scrollHeight),
                            innerHeight: innerHeight, inPart: !!part, partCv: cv,
                            offsetParent: h.offsetParent !== null,
                            rulePresent: [...document.styleSheets].some(s => {
                                try { return [...s.cssRules].some(r => r.selectorText && r.selectorText.includes(':has(:target)')); }
                                catch (e) { return false; }
                            }),
                            selectors: [...document.styleSheets].flatMap(s => {
                                try {
                                    return [...s.cssRules]
                                        .filter(r => r.selectorText && r.selectorText.includes('doc-part'))
                                        .map(r => r.selectorText);
                                }
                                catch (e) { return []; }
                            }).slice(0, 12),
                            servedCssHasTarget: await fetch('/css/docs.css').then(r => r.text())
                                .then(t => t.includes(':target')).catch(() => 'fetch-failed'),
                        };
                    }""",
                ) as Map<String, Any?>
            val top = (last["top"] as? Number)?.toDouble() ?: Double.NaN
            val ih = (last["innerHeight"] as? Number)?.toDouble() ?: 0.0
            if (last["offsetParent"] == true && top >= -2.0 && top <= ih * 0.5) return
            Thread.sleep(250)
        }
        throw AssertionError(
            "the deep fragment never landed on its heading within 30s; final state: $last",
        )
    }

    @Test
    fun `an in-page cross-reference walks to its wrapped section`() {
        startTrace()
        page.setViewportSize(1440, 900)
        navigate("/docs/auth")
        page.locator("article.doc-body a[href='#75-key-roles']").click()
        page.waitForCondition { page.url().endsWith("#75-key-roles") }
        page.waitForCondition {
            page.evaluate(
                """() => {
                    const h = document.getElementById('75-key-roles');
                    if (!h) return false;
                    const r = h.getBoundingClientRect();
                    return r.top >= -2 && r.top <= innerHeight * 0.5;
                }""",
            ) as Boolean
        }
    }

    @Test
    fun `sections below the fold stay in the accessibility tree and the DOM text index`() {
        startTrace()
        page.setViewportSize(1440, 900)
        navigate("/docs/auth")
        // This heading sits ~10 viewports down and is skip-rendered at load; find-in-page
        // and screen readers must still reach it.
        withClue("wrapped section heading reachable in the accessibility tree") {
            page.locator("article.doc-body").ariaSnapshot().shouldContain("Public endpoints (no auth required)")
        }
        withClue("wrapped section text present in the DOM (find-in-page's index)") {
            page.evaluate(
                "() => document.querySelector('article.doc-body').textContent.includes('Public endpoints (no auth required)')",
            ) shouldBe true
        }
    }

    @Test
    fun `the page chrome's navigation is unchanged on a wrapped docs page`() {
        startTrace()
        page.setViewportSize(1440, 900)
        navigate("/docs/auth")
        withClue("the header's primary navigation is present") {
            page.locator("nav.nav-wide a").count() shouldBeGreaterThan 2
        }
        page.locator("a[href='/docs']").first().click()
        page.waitForLoadState(LoadState.NETWORKIDLE)
        withClue("the way back to the docs index still lands there") {
            page.url().shouldContain("/docs")
        }
    }

    private fun navigate(path: String) {
        page.navigate("$baseUrl$path")
        page.waitForLoadState(LoadState.NETWORKIDLE)
    }
}
