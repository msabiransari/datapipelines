package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 163, #157 — D1 (the outlined tile) in a REAL, rendered, dark-mode browser.
 * `BrandMarkParityRenderTest` pins the source string; this is the reader's half of the same
 * claim (see `UiBoundaryShotsBrowserTest`'s KDoc for why both exist) and the one check that
 * would catch a browser genuinely painting the retired filled tile as a solid slab — a defect
 * no server-side render can see, since nothing about it depends on CSS cascade order or an
 * actual paint.
 *
 * The site header cannot be photographed IN dark mode: 145 made the public site light-only,
 * on purpose (no theme toggle, no `themes/auto.css`). Its screenshot documents shape parity
 * with the rail's mark, not a dark rendering — the geometry comes from the same fragment
 * either way, which is exactly what `BrandMarkParityRenderTest` already proved at the source.
 *
 * The login page (161, #161 — "the login dialog still has the old logo") is photographed the
 * same way: anonymously, so in the deployment's default theme (`ThemeResolver` falls back to
 * `ui.theme` when there is no signed-in preference to read), and BEFORE the sign-in below,
 * because a signed-in visitor is redirected off `/login` (090 §C). The companion design
 * (#619) carries one brand in the header; its geometry is asserted and captured first
 * so a regression leaves its picture behind.
 *
 * Files land in `build/reports/163-screenshots/` as `163-<state>.png` (`161-login-card.png`
 * for the login leg).
 */
class BrandMarkDarkModeShotsBrowserTest : DashboardBrowserSuite() {
    @Test
    fun `the approved dark palette paints accessible shell surfaces`() {
        startTrace()
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/login")
        page.locator(".app-auth-brand").first().waitFor()
        page.waitForFunction("() => getComputedStyle(document.querySelector('.app-auth-brand')).opacity === '1'")
        paletteShot("login")

        val root = ready("palette552")
        ensureTheme("dark")
        val board = seedBoardWithGrid(root)
        page.navigate("$baseUrl/pipelines")
        page.locator("#pipeline-filter-q").waitFor()
        // #554: the admitted count moved from the menu badge to the resource browser's header.
        page.locator("#app-browser .app-browser-count[data-browser-family='pipelines']").waitFor()
        paletteShot("pipelines")
        val measurements = measurePalette()

        page.setViewportSize(768, 900)
        paletteShot("compact")
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/dashboards/$board")
        awaitBoardReady()
        page.locator("#dp-board [data-dp-viz='slowchart'] .plotly .main-svg").first().waitFor()
        page.waitForFunction(
            """
            () => {
              const states = [...document.querySelectorAll('#dp-board [data-dp-state]')];
              return states.length === 4 && states.every(e => ['success', 'ready'].includes(e.dataset.dpState));
            }
            """.trimIndent(),
        )
        paletteShot("dashboard")

        // Capture all four baseline screens before the deliberate old-palette failure.
        println("552 dark palette: $measurements")
        measurements["page"] shouldBe "rgb(19, 22, 27)"
        measurements["rail"] shouldBe "rgb(23, 27, 33)"
        measurements["selected"] shouldBe "rgb(48, 57, 83)"
        measurements["bodyText"] shouldBe "rgb(233, 236, 242)"
        measurements["indicator"] shouldBe "rgb(126, 144, 238)"
        measurements["boundary"] shouldBe "rgb(104, 115, 134)"
        measurements["violations"] shouldBe emptyList<String>()
    }

    /**
     * #629: on CI this wait once ran out its whole bound with nothing to say why. A timeout now
     * names what the page held: whether the mount ran, its error and code, and each visual's
     * `data-dp-state` (CI attempt 1 of run 39348253765 timed out here; alone it takes seconds).
     */
    private fun awaitBoardReady() {
        try {
            page.waitForFunction("() => window.__dpPage && window.__dpPage.ready === true")
        } catch (e: TimeoutError) {
            throw AssertionError("the board never reported ready: ${page.evaluate(BOARD_STATE_SCRIPT)} url=${page.url()}", e)
        }
    }

    private fun paletteShot(name: String) {
        page.evaluate("() => document.fonts.ready")
        val directory = Paths.get("build", "reports", "552-dark-palette").also { it.toFile().mkdirs() }
        page.screenshot(Page.ScreenshotOptions().setPath(directory.resolve("$name.png")))
    }

    /** Read actual painted backgrounds, including transparent elements' ancestors. */
    @Suppress("UNCHECKED_CAST")
    private fun measurePalette(): Map<String, Any> = page.evaluate(PALETTE_MEASUREMENT_SCRIPT) as Map<String, Any>

    @Test
    fun `the login card, the app rail in dark mode and the site header all show the outlined mark`() {
        startTrace()
        page.navigate("$baseUrl/login")
        val card = page.locator(".app-auth-brand").first()
        card.waitFor()
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        card.screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("161-login-card.png")))
        assertD1Geometry(page.locator(".app-auth-brand .app-brand-tile").first().innerHTML())

        val user =
            seedLocalUser(uniqueEmail("brandmark-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("brandmarkws" + generatedPassword("w").take(8).lowercase())

        ensureTheme("dark")
        val railTile = page.locator(".app-rail .app-brand-tile").first()
        assertD1Geometry(railTile.innerHTML())
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        railTile.screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("163-app-rail-dark.png")))

        // The public site: light-only by design (145), reached anonymously — 033's "no
        // auto-redirect" means an authenticated session still gets the real anonymous render.
        page.navigate(baseUrl)
        val header = page.locator(".site-header .brand svg").first()
        assertD1Geometry(header.innerHTML())
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        page.locator(".site-header").screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("163-site-header.png")))
    }

    /**
     * D1's geometry (GitHub #157): the tile is a stroked outline, the three bars are
     * filled — never the retired filled tile with `var(--surface-default)` cutouts, which
     * is the "solid light slab" #157 exists to remove. Matched WITHOUT the self-closing
     * `/>` the server's raw HTML uses: a real browser's DOM re-serializes every void SVG
     * element as an explicit `<rect …></rect>` pair, so a check written against the source
     * form would pass on the wrong markup here — the whole point of asserting through a
     * real browser rather than only through `BrandMarkParityRenderTest`'s source strings.
     */
    private fun assertD1Geometry(markup: String) {
        markup shouldContain """<rect x="38" y="20" width="22" height="24" rx="4" fill="none" stroke="currentColor" stroke-width="4">"""
        markup shouldContain """<rect x="42" y="33" width="4" height="6" rx="1" fill="currentColor">"""
        markup shouldContain """<rect x="47" y="28" width="4" height="11" rx="1" fill="currentColor">"""
        markup shouldContain """<rect x="52" y="24" width="4" height="15" rx="1" fill="currentColor">"""
        markup shouldNotContain "var(--surface-default)"
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "163-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        /** Mount and per-visual state only: no cookie, token or result row. */
        val BOARD_STATE_SCRIPT =
            """
            () => {
              const p = window.__dpPage;
              const board = document.getElementById('dp-board');
              return JSON.stringify({
                readyState: document.readyState,
                mounted: !!(board && board.__dpPageMounted),
                runtime: !!window.DatapipelinesDashboard,
                page: p ? { ready: p.ready, error: p.error ? String(p.error) : null, code: p.code } : null,
                visuals: [...document.querySelectorAll('#dp-board [data-dp-state]')]
                  .map(e => (e.closest('[data-dp-viz]')?.dataset.dpViz ?? '?') + '=' + e.dataset.dpState),
              });
            }
            """.trimIndent()

        val PALETTE_MEASUREMENT_SCRIPT =
            """
            () => {
              const style = el => getComputedStyle(el);
              const background = el => {
                for (let node = el; node; node = node.parentElement) {
                  const color = style(node).backgroundColor;
                  if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') return color;
                }
                throw new Error('No painted background');
              };
              const luminance = color => {
                const channels = color.match(/[\d.]+/g).map(Number);
                if (channels.length === 4 && channels[3] !== 1) throw new Error('Translucent contrast pair: ' + color);
                const linear = channels.slice(0, 3).map(c => {
                  const v = c / 255;
                  return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
                });
                return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
              };
              const contrast = (a, b) => {
                const first = luminance(a), second = luminance(b);
                return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
              };
              const nav = document.querySelector('.app-nav-link[aria-current="page"]');
              const frame = document.querySelector('#pipeline-filter-q');
              const badge = document.querySelector('#app-browser .app-browser-count:not([hidden])');
              const measured = {
                page: background(document.querySelector('#app-main')),
                rail: background(document.querySelector('.app-rail')),
                browser: background(document.querySelector('#app-browser')),
                selected: background(nav), indicator: style(nav).borderLeftColor,
                indicatorWidth: style(nav).borderLeftWidth,
                boundary: style(frame).borderTopColor, boundaryWidth: style(frame).borderTopWidth,
                boundarySurface: background(frame), boundaryOutside: background(frame.parentElement),
                bodyText: style(document.body).color, navText: style(nav).color,
                badgeText: style(badge).color, badgeSurface: background(badge),
              };
              const probe = document.createElement('span');
              probe.style.color = 'var(--text-muted)';
              document.body.appendChild(probe);
              const mutedText = style(probe).color;
              probe.remove();
              const violations = [];
              const pairs = [
                ['body on page', measured.bodyText, measured.page, 4.5],
                ['body on rail', measured.bodyText, measured.rail, 4.5],
                ['body on browser', measured.bodyText, measured.browser, 4.5],
                ['nav on selected', measured.navText, measured.selected, 4.5],
                ['badge on its surface', measured.badgeText, measured.badgeSurface, 4.5],
                ['boundary inside', measured.boundary, measured.boundarySurface, 3],
                ['boundary outside', measured.boundary, measured.boundaryOutside, 3],
                ['selected on rail', measured.selected, measured.rail, 1.5],
                ['indicator on selected', measured.indicator, measured.selected, 3],
              ];
              measured.ratios = Object.fromEntries(pairs.map(([name, a, b, floor]) => {
                const ratio = contrast(a, b);
                if (ratio < floor) violations.push(name + ': ' + ratio + ' < ' + floor);
                return [name, ratio];
              }));
              if (parseFloat(measured.boundaryWidth) <= 0) violations.push('Boundary is not drawn');
              if (parseFloat(measured.indicatorWidth) <= 0) violations.push('Selection indicator is not drawn');
              for (const el of [nav, ...nav.querySelectorAll('*')]) {
                if (!el.getClientRects().length || ![...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim())) continue;
                if (parseFloat(style(el).fontSize) < 24 && background(el) === measured.selected &&
                    style(el).color === mutedText) violations.push('Muted small text on selected: ' + el.className);
              }
              measured.violations = violations;
              return measured;
            }
            """.trimIndent()
    }
}
