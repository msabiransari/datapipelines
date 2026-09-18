package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.SitePageRenderer
import co.datapipelines.web.ui.site.SitePages
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 163, #157 — "one mark everywhere" (D1), pinned at the RENDER rather than at the source.
 *
 * `partials/brand-mark.html` exists so the outlined-tile mark is authored ONCE; this asserts
 * that fact rather than assuming it, by rendering the app rail (authenticated), the app's
 * signed-out top bar and the public site's header AND footer (through `SitePageRenderer`,
 * which calls the real `SiteController`) and comparing the FOUR resulting `<svg>` bodies —
 * not the opening tag, which legitimately carries a different `class` per call site, but
 * everything between it and `</svg>`. A future edit that inlines a diverging copy at any of
 * the four sites turns this red; a future edit to the shared fragment turns it green
 * everywhere at once, which is the point of having one.
 *
 * The geometry itself is pinned too (D1: an outlined tile, `stroke="currentColor"`, its three
 * bars FILLED — not the retired filled tile with `var(--surface-default)` cutouts that read as
 * a solid light slab in dark mode), so a partial revert of the fragment's own content is also
 * caught here, not only a call site regressing to an inline copy.
 */
class BrandMarkParityRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    @Test
    fun `the app rail, the signed-out top bar and the site header and footer render the same mark`() {
        val rail = engine.process("error/404", webContext().apply { fillChrome(authenticated = true) })
        val anonTopbar = engine.process("error/404", webContext().apply { fillChrome(authenticated = false) })
        val site = SitePageRenderer.render(SitePages.HOME)

        val railMarks = MARK.findAll(rail).map { it.groupValues[1] }.toList()
        val anonMarks = MARK.findAll(anonTopbar).map { it.groupValues[1] }.toList()
        val siteMarks = MARK.findAll(site).map { it.groupValues[1] }.toList()

        // Non-vacuity: the rail and the anon top bar each carry exactly their one copy, and
        // the site page carries exactly its two (header, footer) — a selector or fragment
        // regression that stopped matching would otherwise compare empty lists and pass.
        railMarks shouldHaveSize 1
        anonMarks shouldHaveSize 1
        siteMarks shouldHaveSize 2

        val allMarks = railMarks + anonMarks + siteMarks
        allMarks.toSet() shouldHaveSize 1

        val mark = allMarks.first()
        // D1's geometry, exactly (GitHub #157): the tile is a stroked outline, the three bars
        // are filled — never the other way around.
        mark shouldContain """<rect x="38" y="20" width="22" height="24" rx="4" fill="none" stroke="currentColor" stroke-width="4"/>"""
        mark shouldContain """<rect x="42" y="33" width="4" height="6" rx="1" fill="currentColor"/>"""
        mark shouldContain """<rect x="47" y="28" width="4" height="11" rx="1" fill="currentColor"/>"""
        mark shouldContain """<rect x="52" y="24" width="4" height="15" rx="1" fill="currentColor"/>"""
        // The retired filled-tile geometry never reappears.
        mark shouldNotContain "var(--surface-default)"
        mark shouldNotContain """x="36" y="18" width="26" height="28""""
    }

    private fun WebContext.fillChrome(authenticated: Boolean) {
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", authenticated)
        setVariable("currentPath", "/dashboard")
        setVariable("reportProblemUrl", co.datapipelines.web.ui.site.REPORT_PROBLEM_URL)
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()

    private companion object {
        /** Every `<svg viewBox="0 0 64 64" ...>…</svg>` the brand-mark fragment can emit. */
        val MARK = Regex("""<svg[^>]*viewBox="0 0 64 64"[^>]*>(.*?)</svg>""", RegexOption.DOT_MATCHES_ALL)
    }
}
