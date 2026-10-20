package co.datapipelines.browser

import com.microsoft.playwright.Locator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #554 A.4 — the record's acceptance for the three sibling columns (menu · resource browser ·
 * workspace), in real Chromium: (a) a folder expansion at any depth moves no menu item and
 * changes no menu width; (b) Hide/Show is presentation only — no tree request, no navigation,
 * independent of the menu's `dp-rail`; (d) a non-family page hides the browser and gives main
 * its width; (e) on a phone the two drawers open one at a time, close on Escape, the scrim and
 * a navigation, hand focus back, and leave the rest of the page `inert`; (g) the 1512 reference.
 * (c) is `TemplateSidebarTreeBrowserTest`'s family switch; (f) `ReusableRestTreeBrowserTest`'s
 * retired-key case.
 */
class ShellColumnsBrowserTest : RestTreeBrowserFixture() {
    /** Every menu link's box and the menu's width, unrounded: the (a) witness. */
    private fun menuGeometry(): Map<*, *> =
        page.evaluate(
            """() => ({
              rail: document.getElementById('app-rail').getBoundingClientRect().width,
              links: Array.from(document.querySelectorAll('nav.app-nav .app-nav-link')).map(link => {
                const box = link.getBoundingClientRect();
                return [link.getAttribute('data-nav-section'), box.x, box.y, box.width, box.height].join(' ');
              }),
            })""",
        ) as Map<*, *>

    /** SVG has no offsetParent in Chromium; the rendered SVG box identifies the visible glyph. */
    private fun visibleOpenerGlyph(): Any? =
        page.evaluate(
            """() => Array.from(document.querySelectorAll('#browser-open svg'))
              .filter(svg => getComputedStyle(svg).display !== 'none' && svg.getClientRects().length > 0)
              .map(svg => svg.querySelector('use').getAttribute('href').split('#')[1])""",
        )

    @Test
    fun `a deep folder expansion moves no menu item and leaves the menu width identical`() {
        readyTree()
        seedPipeline("acme/alpha/beta/gamma/leaf")
        openTree()
        val before = menuGeometry()
        (before["links"] as List<*>).size shouldBe page.locator("nav.app-nav .app-nav-link").count()
        expand("acme")
        expand("acme/alpha")
        expand("acme/alpha/beta")
        expand("acme/alpha/beta/gamma")
        page.waitForSelector(leaf("acme/alpha/beta/gamma/leaf"))
        settle()
        val after = menuGeometry()
        val datasources = { geometry: Map<*, *> -> (geometry["links"] as List<*>).first { "$it".startsWith("/datasources ") } }
        println("554-A4a datasources before=${datasources(before)} after=${datasources(after)}")
        after shouldBe before
    }

    @Test
    fun `Hide and Show the browser fetch nothing, keep the page, and are independent of the menu collapse`() {
        readyTree()
        seedPipeline("acme/one")
        openTree()
        expand("acme")
        page.evaluate("() => { window.__hideWitness = window.DatapipelinesSidebarTrees.get('pipelines'); }")
        val requests = mutableListOf<String>()
        page.onRequest { if (it.url().contains("/api/v1/")) requests += it.url() }
        val workspace = page.evaluate("() => document.documentElement.dataset.dpWorkspace") as String
        val rail = {
            page.evaluate("() => [localStorage.getItem('dp-rail'), document.documentElement.classList.contains('rail-collapsed')]")
        }
        val railBefore = rail()
        println("554-A4b dp-rail before=$railBefore")
        val url = page.url()
        page.click("#browser-hide")
        page.locator("#app-browser").isVisible shouldBe false
        mainWidth() shouldBe 1440 - 202
        menuWidth() shouldBe 202
        rail() shouldBe railBefore
        page.evaluate("ws => localStorage.getItem('dp-browser-hidden:' + ws)", workspace) shouldBe "1"
        page.locator("#browser-open").getAttribute("aria-label") shouldBe "Browse Pipelines"
        page.locator("#browser-open").getAttribute("aria-expanded") shouldBe "false"
        page.locator("#browser-open").isVisible shouldBe true
        visibleOpenerGlyph() shouldBe listOf("chevron-right")
        page.evaluate(
            """() => Math.abs(document.getElementById('browser-open').getBoundingClientRect().left -
              document.getElementById('app-main').getBoundingClientRect().left) <= 16""",
        ) shouldBe true
        // The menu's collapse while the browser is hidden does not bring it back, and vice versa.
        page.click("#rail-collapse")
        page.locator("#app-browser").isVisible shouldBe false
        menuWidth() shouldBe 64
        page.click("#rail-collapse")
        menuWidth() shouldBe 202
        rail() shouldBe listOf("0", false)
        page.focus("#browser-open")
        page.keyboard().press("Enter")
        page.locator("#app-browser").isVisible shouldBe true
        width() shouldBe 300
        page.evaluate("() => document.activeElement.matches('#nav-tree-pipelines input[type=search]')") shouldBe true
        page.evaluate("() => window.__hideWitness === window.DatapipelinesSidebarTrees.get('pipelines')") shouldBe true
        page.locator("${folder("acme")}[aria-expanded=true]").count() shouldBe 1
        page.evaluate("ws => localStorage.getItem('dp-browser-hidden:' + ws)", workspace) shouldBe null
        page.url() shouldBe url
        requests shouldBe emptyList()
    }

    @Test
    fun `family menu navigation reveals Hide with one boosted GET and the active family click reveals it again`() {
        readyTree()
        openTree()
        val workspace = page.evaluate("() => document.documentElement.dataset.dpWorkspace") as String
        val boosted = mutableListOf<String>()
        val documents = mutableListOf<String>()
        page.onRequest { request ->
            if (request.resourceType() == "document") documents += request.url()
            if (request.method() == "GET" && request.headers()["hx-request"] == "true") {
                boosted += request.url()
            }
        }
        page.click("#browser-hide")
        page.evaluate("ws => localStorage.getItem('dp-browser-hidden:' + ws)", workspace) shouldBe "1"
        page.click("#app-rail .app-nav-link[data-nav-section='/templates']")
        page.waitForURL("**/templates")
        page.waitForFunction("() => document.querySelector('#app-browser-title [data-browser-family=templates]:not([hidden])') !== null")
        settle()
        page.locator("#app-browser").isVisible shouldBe true
        page.locator("#app-browser-title [data-browser-family=templates]").innerText() shouldBe "Templates"
        width() shouldBe 300
        mainWidth() shouldBe 1440 - 202 - 300
        page.evaluate("ws => localStorage.getItem('dp-browser-hidden:' + ws)", workspace) shouldBe null
        boosted.size shouldBe 1
        boosted.single().substringBefore('?').endsWith("/templates") shouldBe true
        documents shouldBe emptyList()

        page.click("#browser-hide")
        page.click("#app-rail .app-nav-link[data-nav-section='/templates']")
        page.waitForFunction("() => !document.documentElement.classList.contains('browser-hidden')")
        page.locator("#app-browser").isVisible shouldBe true
        page.evaluate("ws => localStorage.getItem('dp-browser-hidden:' + ws)", workspace) shouldBe null

        // A future overview href must not change how the click identifies a family (#556).
        page.click("#browser-hide")
        page.evaluate(
            """() => {
              const link = document.querySelector('#app-rail .app-nav-link[data-nav-section="/pipelines"]');
              link.setAttribute('href', '/overview/pipelines');
              link.addEventListener('click', event => event.preventDefault(), {once: true});
            }""",
        )
        page.click("#app-rail .app-nav-link[data-nav-section='/pipelines']")
        page.waitForFunction("() => !document.documentElement.classList.contains('browser-hidden')")
        page.evaluate("ws => localStorage.getItem('dp-browser-hidden:' + ws)", workspace) shouldBe null
    }

    @Test
    fun `a non-family page hides the browser and main grows by exactly its width`() {
        readyTree()
        openTree()
        val familyMain = mainWidth()
        val browser = width()
        println("554-A4d on /pipelines main=$familyMain browser=$browser")
        page.click(".app-nav-link[data-nav-section='/executions']")
        page.waitForURL("**/executions")
        // #586: the URL commits before htmx:afterSettle runs sidebar.mjs's sync(), which is
        // what hides the column; measure only once the state the case asserts is on the page.
        page.waitForFunction("() => document.getElementById('app-browser').hidden === true")
        settle()
        mainWidth() shouldBe familyMain + browser
        page.locator("#app-browser").isVisible shouldBe false
        page.locator("#browser-open").isVisible shouldBe false
        page.locator("#browser-boundary").isVisible shouldBe false
        // Back to the family: the column returns with the same instance.
        page.goBack()
        page.waitForURL("**/pipelines")
        page.waitForFunction("() => document.getElementById('app-browser').hidden === false")
        settle()
        page.locator("#app-browser").isVisible shouldBe true
        mainWidth() shouldBe familyMain
    }

    @Test
    fun `the 1512 reference leaves the content frame 1010 and icon mode returns 138`() {
        readyTree()
        page.setViewportSize(1512, 900)
        openTree()
        settle()
        menuWidth() shouldBe 202
        width() shouldBe 300
        mainWidth() shouldBe 1010
        page.click("#rail-collapse")
        settle()
        menuWidth() shouldBe 64
        mainWidth() shouldBe 1010 + 138
    }

    @Test
    @Suppress("LongMethod") // one phone session walks both drawers through every open and close path
    fun `on a phone the two drawers open one at a time, close on Escape scrim and navigation, and the page is inert`() {
        readyTree()
        val id = seedPipeline("acme/phone")
        page.setViewportSize(390, 844)
        page.navigate("$baseUrl/pipelines")
        page.waitForSelector("#app-main")
        val open = { page.evaluate("() => ['rail-open', 'browser-open'].filter(c => document.documentElement.classList.contains(c))") }
        open() shouldBe emptyList<String>()
        page.locator("[inert]").count() shouldBe 0
        visibleOpenerGlyph() shouldBe listOf("folder")

        page.click("#rail-open")
        open() shouldBe listOf("rail-open")
        page.evaluate("() => document.getElementById('app-rail').contains(document.activeElement)") shouldBe true
        // The compact desktop preference never strips the drawer's labels.
        page.locator(".app-nav-link[data-nav-section='/pipelines']").innerText() shouldContain "Pipelines"
        // The close control sits in the brand row and never covers the wordmark (a 202px drawer put
        // it over the label). Both are inside the drawer, so a mid-slide read compares the same way.
        page.evaluate(
            """() => { const close = document.getElementById('rail-close').getBoundingClientRect();
              const label = document.querySelector('#app-rail .app-brand .app-rail-label').getBoundingClientRect();
              return label.right <= close.left; }""",
        ) shouldBe true
        page.keyboard().press("Escape")
        open() shouldBe emptyList<String>()
        page.evaluate("() => document.activeElement?.id") shouldBe "rail-open"

        page.click("#browser-open")
        open() shouldBe listOf("browser-open")
        page.waitForFunction("() => document.activeElement?.matches('#nav-tree-pipelines input[type=search]')")
        width() shouldBe 360
        // The rest of the page is inert: Tab stays in the drawer, and nothing outside it can take focus at all.
        repeat(12) {
            page.keyboard().press("Tab")
            page.evaluate("() => document.getElementById('app-browser').contains(document.activeElement)") shouldBe true
        }
        val outside =
            page.evaluate(
                """() => {
                  const drawer = document.getElementById('app-browser');
                  const back = document.activeElement;
                  const candidates = Array.from(document.querySelectorAll('.app-shell a[href], .app-shell button, .app-shell input'))
                    .filter(el => !drawer.contains(el) && !el.disabled && el.getClientRects().length > 0);
                  const focused = candidates.filter(el => { el.focus(); return document.activeElement === el; });
                  back.focus();
                  return [candidates.length, focused.map(el => el.id || el.getAttribute('href') || el.tagName)];
                }""",
            ) as List<*>
        println("554-A4e outside candidates=${outside[0]} focusable=${outside[1]}")
        ((outside[0] as Number).toInt() > 0) shouldBe true
        outside[1] shouldBe emptyList<String>()
        listOf("app-rail", "app-main").forEach { page.locator("#$it").getAttribute("inert") shouldBe "" }
        page.locator("#app-browser").getAttribute("inert") shouldBe null

        // One at a time: opening the menu closes the browser without bouncing focus to Browse.
        page.evaluate("() => window.DpShell.setRailOpen(document, true)")
        open() shouldBe listOf("rail-open")
        page.locator("#app-browser").getAttribute("inert") shouldBe ""
        page.locator(".app-rail-backdrop").click(Locator.ClickOptions().setPosition(385.0, 400.0))
        open() shouldBe emptyList<String>()
        page.evaluate("() => document.activeElement?.id") shouldBe "rail-open"
        page.locator("[inert]").count() shouldBe 0

        // A navigation from the drawer closes it.
        page.click("#browser-open")
        expand("acme")
        page.click(leaf("acme/phone"))
        page.waitForURL("**/pipelines/$id")
        page.waitForFunction("() => !document.documentElement.classList.contains('browser-open')")
        page.locator("[inert]").count() shouldBe 0
    }
}
