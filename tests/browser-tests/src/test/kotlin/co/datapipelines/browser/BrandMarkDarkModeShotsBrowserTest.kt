package co.datapipelines.browser

import com.microsoft.playwright.Locator
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
 * because a signed-in visitor is redirected off `/login` (090 §C). Its card carries the card's
 * own brand AND the auth layout's brand link; both are asserted, and the card is captured
 * first so a regression leaves its picture behind.
 *
 * Files land in `build/reports/163-screenshots/` as `163-<state>.png` (`161-login-card.png`
 * for the login leg).
 */
class BrandMarkDarkModeShotsBrowserTest : BrowserSuite() {
    @Test
    fun `the login card, the app rail in dark mode and the site header all show the outlined mark`() {
        startTrace()
        page.navigate("$baseUrl/login")
        val card = page.locator(".app-auth-card").first()
        card.waitFor()
        page.waitForFunction("() => document.fonts.ready.then(() => document.fonts.status === 'loaded')")
        card.screenshot(Locator.ScreenshotOptions().setPath(shotDir().resolve("161-login-card.png")))
        assertD1Geometry(page.locator(".app-auth-card-brand .app-brand-tile").first().innerHTML())
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
}
