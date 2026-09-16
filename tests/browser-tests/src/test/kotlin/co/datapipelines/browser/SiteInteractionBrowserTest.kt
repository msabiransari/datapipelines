package co.datapipelines.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.ReducedMotion
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The public site's interactions and postures, in a real browser (145 §5, §8.6):
 *
 *  - **(a) the worked example's tabs are ARIA tabs:** click, ArrowRight/ArrowLeft, Home and
 *    End move the selection; exactly one tab is selected and in the tab order; its panel is
 *    the one visible; focus follows the keyboard;
 *  - **(b) copy works:** the quickstart's Copy button (hidden until the script runs) writes
 *    the command to the clipboard and says so;
 *  - **(c) keyboard focus is visible:** every header link and the hero's controls show a
 *    focus ring when tabbed to — hover is never the only affordance;
 *  - **(d) reduced motion:** with the preference on, no entrance class is applied after a
 *    scroll and the hero copy carries no animation; and when the preference changes while the
 *    page is open (emulated), applied entrances are removed;
 *  - **(e) light lock:** under a dark colour scheme AND with the app's saved dark theme in
 *    storage, the public page still paints the approved light ground and declares
 *    `color-scheme: light`;
 *  - **(f) no-JS readability:** with JavaScript disabled the hero, all three example panels
 *    and the navigation are visible and the tablist stays hidden.
 *
 * Anonymous throughout: the site is public.
 */
class SiteInteractionBrowserTest : BrowserSuite() {
    @Test
    fun `the worked example's tabs move with the keyboard and show one panel at a time`() {
        startTrace()
        page.setViewportSize(1440, 900)
        navigate("/")
        page.locator("[role=tablist]:not([hidden])").waitFor()
        val tabs = page.locator("[role=tab]")
        tabs.count() shouldBe 3
        selected() shouldBe "tab-chart"
        visiblePanels() shouldBe listOf("chart")

        page.click("#tab-api")
        selected() shouldBe "tab-api"
        visiblePanels() shouldBe listOf("api")
        withClue("only the selected tab is in the tab order") { tabIndexes() shouldBe listOf(-1, 0, -1) }

        page.keyboard().press("ArrowRight")
        selected() shouldBe "tab-evidence"
        visiblePanels() shouldBe listOf("evidence")
        withClue("focus follows the arrow") { activeId() shouldBe "tab-evidence" }
        page.keyboard().press("ArrowRight")
        withClue("the arrow wraps") { selected() shouldBe "tab-chart" }
        page.keyboard().press("End")
        selected() shouldBe "tab-evidence"
        page.keyboard().press("Home")
        selected() shouldBe "tab-chart"
        visiblePanels() shouldBe listOf("chart")
        // The recorded run's rows are in the chart panel: five boroughs, five bars.
        page.locator("#chart .bar-row").count() shouldBe 5
    }

    @Test
    fun `the quickstart's copy button writes the command to the clipboard`() {
        startTrace()
        context().grantPermissions(listOf("clipboard-read", "clipboard-write"))
        page.setViewportSize(1440, 900)
        navigate("/how-it-works")
        val button = page.locator(".quick-code .copy-btn")
        withClue("the button is revealed by the script") { button.isVisible() shouldBe true }
        button.click()
        page.waitForCondition { button.textContent().trim() == "Copied" }
        val clipboard = page.evaluate("() => navigator.clipboard.readText()") as String
        clipboard shouldBe "./app.sh --start --demo nyc,trade,lake"
    }

    @Test
    fun `header links and hero controls show a visible focus ring when tabbed to`() {
        startTrace()
        page.setViewportSize(1440, 900)
        navigate("/")
        val seen = mutableListOf<String>()
        val faint = mutableListOf<String>()
        repeat(TAB_STOPS) {
            page.keyboard().press("Tab")
            @Suppress("UNCHECKED_CAST")
            val focus = page.evaluate(FOCUS_JS) as Map<String, Any>
            val label = focus["label"] as String
            if (label.isBlank()) return@repeat
            seen += label
            val outline = focus["outline"] as String
            if (outline == "none" || (focus["width"] as Number).toDouble() < 1.0) faint += "$label: outline $outline ${focus["width"]}px"
        }
        withClue("the tab walk reached the header and the hero") {
            seen.any { it.contains("How it works") } shouldBe true
            seen.any { it.contains("Explore a worked example") } shouldBe true
        }
        withClue("focused controls without a visible ring") { faint.shouldBeEmpty() }
    }

    @Test
    fun `reduced motion applies no entrance, at load and when the preference changes`() {
        startTrace()
        val reduced = newBrowserContext(Browser.NewContextOptions().setReducedMotion(ReducedMotion.REDUCE))
        try {
            val p = reduced.newPage()
            p.setViewportSize(1440, 900)
            p.navigate("$baseUrl/")
            p.waitForLoadState(LoadState.NETWORKIDLE)
            p.mouse().wheel(0.0, 2400.0)
            p.waitForTimeout(400.0)
            withClue("no entrance class under reduced motion") { p.locator(".is-entering").count() shouldBe 0 }
            val heroAnimation = p.evaluate("() => getComputedStyle(document.querySelector('.hero-copy > h1')).animationName") as String
            withClue("the hero copy does not animate") { heroAnimation shouldBe "none" }
        } finally {
            reduced.close()
        }

        // The live change: entrances applied under no-preference are removed when the
        // preference flips while the page is open.
        page.setViewportSize(1440, 900)
        navigate("/")
        page.mouse().wheel(0.0, 1600.0)
        page.waitForCondition { page.locator(".is-entering").count() > 0 }
        page.emulateMedia(
            com.microsoft.playwright.Page
                .EmulateMediaOptions()
                .setReducedMotion(ReducedMotion.REDUCE),
        )
        page.waitForCondition { page.locator(".is-entering").count() == 0 }
    }

    @Test
    fun `the public site stays light under a dark scheme and a saved dark theme`() {
        startTrace()
        val dark = newBrowserContext(Browser.NewContextOptions().setColorScheme(ColorScheme.DARK))
        try {
            val p = dark.newPage()
            p.setViewportSize(1440, 900)
            p.navigate("$baseUrl/")
            p.evaluate("() => localStorage.setItem('dp-site-theme', 'dark')")
            p.navigate("$baseUrl/how-it-works")
            p.waitForLoadState(LoadState.NETWORKIDLE)
            val body = p.evaluate("() => getComputedStyle(document.body).backgroundColor") as String
            val scheme = p.evaluate("() => getComputedStyle(document.documentElement).colorScheme") as String
            val ink = p.evaluate("() => getComputedStyle(document.querySelector('main h1')).color") as String
            withClue("the ground is the approved light ground") { body shouldBe LIGHT_GROUND }
            withClue("the document declares a light scheme") { scheme shouldContain "light" }
            withClue("the headline is ink on light") { ink shouldBe INK }
            withClue("no theme attribute is set") { p.locator("html[data-theme]").count() shouldBe 0 }
        } finally {
            dark.close()
        }
    }

    @Test
    fun `without JavaScript the hero, every example panel and the navigation are readable`() {
        startTrace()
        val noJs = newBrowserContext(Browser.NewContextOptions().setJavaScriptEnabled(false))
        try {
            val p = noJs.newPage()
            p.setViewportSize(1440, 900)
            p.navigate("$baseUrl/")
            p.waitForLoadState(LoadState.LOAD)
            withClue("the hero is visible") { p.locator("main h1").isVisible() shouldBe true }
            withClue("the tablist stays hidden without the script") { p.locator("[role=tablist]").isVisible() shouldBe false }
            listOf("chart", "api", "evidence").forEach { id ->
                withClue("panel #$id is readable without the script") { p.locator("#$id").isVisible() shouldBe true }
            }
            withClue("the primary navigation is there") { p.locator(".nav-wide a[href='/how-it-works']").isVisible() shouldBe true }
            withClue("the copy button stays hidden without the script") {
                p.locator(".copy-btn").all().none { it.isVisible() } shouldBe true
            }
            // The phone menu is a <details>: it opens natively.
            p.setViewportSize(390, 844)
            p.click(".nav-mobile > summary")
            withClue("the phone menu opens without the script") {
                p.locator(".nav-mobile .nav-panel a[href='/use-cases']").isVisible() shouldBe true
            }
        } finally {
            noJs.close()
        }
    }

    private fun navigate(path: String) {
        page.navigate("$baseUrl$path")
        page.waitForLoadState(LoadState.NETWORKIDLE)
    }

    private fun context() = page.context()

    private fun selected(): String? =
        page.evaluate("() => { const t = document.querySelector('[role=tab][aria-selected=\"true\"]'); return t && t.id }") as String?

    @Suppress("UNCHECKED_CAST")
    private fun visiblePanels(): List<String> =
        page.evaluate(
            "() => Array.from(document.querySelectorAll('[role=tabpanel]')).filter(p => !p.hidden).map(p => p.id)",
        ) as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun tabIndexes(): List<Int> =
        (page.evaluate("() => Array.from(document.querySelectorAll('[role=tab]')).map(t => t.tabIndex)") as List<Number>).map { it.toInt() }

    private fun activeId(): String? = page.evaluate("() => document.activeElement && document.activeElement.id") as String?

    private companion object {
        const val TAB_STOPS = 12

        /** `--site-ground` (#F5F7FA) and `--site-ink` (#101820) as the browser reports them. */
        const val LIGHT_GROUND = "rgb(245, 247, 250)"
        const val INK = "rgb(16, 24, 32)"

        /** The focused element's label and its computed outline. */
        const val FOCUS_JS =
            """() => {
                 const e = document.activeElement;
                 if (!e || e === document.body) return { label: '', outline: 'none', width: 0 };
                 const cs = getComputedStyle(e);
                 return {
                   label: (e.getAttribute('aria-label') || e.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 40),
                   outline: cs.outlineStyle,
                   width: parseFloat(cs.outlineWidth)
                 };
               }"""
    }
}
