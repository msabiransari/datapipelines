package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.SitePageRenderer
import co.datapipelines.web.ui.site.SitePages
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 163, #157 — "one mark everywhere" (D1), pinned at the RENDER rather than at the source.
 *
 * `partials/brand-mark.html` exists so the outlined-tile mark is authored ONCE; this asserts
 * that fact rather than assuming it, by rendering the app rail (authenticated), the app's
 * signed-out top bar, the public site's header AND footer (through `SitePageRenderer`,
 * which calls the real `SiteController`), the login page (its card brand AND the auth
 * layout's brand link) and the forced-password gate (the auth layout alone), and comparing
 * every resulting `<svg>` body — not the opening tag, which legitimately carries a different
 * `class` per call site, but everything between it and `</svg>`. A future edit that inlines a
 * diverging copy at any of the sites turns this red, NAMING the surface; a future edit to the
 * shared fragment turns it green everywhere at once, which is the point of having one.
 *
 * The geometry itself is pinned too (D1: an outlined tile, `stroke="currentColor"`, its three
 * bars FILLED — not the retired filled tile with `var(--surface-default)` cutouts that read as
 * a solid light slab in dark mode), so a partial revert of the fragment's own content is also
 * caught here, not only a call site regressing to an inline copy.
 *
 * 161, #161 — the login card and the auth layout were not in the list above, so 163's sweep
 * left the retired mark on the sign-in dialog. They are surfaces now, and a second test greps
 * the whole `templates/` source tree for the retired tile's rect so the next inline copy
 * cannot come back on a surface nobody thought to render here.
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
    fun `the app rail, the signed-out top bar, the site header and footer, the login page and the auth layout render the same mark`() {
        val surfaces =
            linkedMapOf(
                "app rail" to engine.process("error/404", webContext().apply { fillChrome(authenticated = true) }),
                "signed-out top bar" to engine.process("error/404", webContext().apply { fillChrome(authenticated = false) }),
                "site header and footer" to SitePageRenderer.render(SitePages.HOME),
                "login page (auth layout brand link + sign-in card)" to engine.process("login", webContext().apply { fillLogin() }),
                "forced-password gate (auth layout brand link)" to
                    engine.process("settings/password-forced", webContext().apply { fillPassword() }),
            )
        val marks = surfaces.mapValues { (_, html) -> MARK.findAll(html).map { it.groupValues[1] }.toList() }

        // Non-vacuity: each surface carries exactly the copies its markup places — a selector
        // or fragment regression that stopped matching would otherwise compare empty lists and
        // pass. The login page carries two (the auth layout's brand link and the card's own
        // brand); the forced-password gate carries the layout's alone.
        withClue("app rail") { marks.getValue("app rail") shouldHaveSize 1 }
        withClue("signed-out top bar") { marks.getValue("signed-out top bar") shouldHaveSize 1 }
        withClue("site header and footer") { marks.getValue("site header and footer") shouldHaveSize 2 }
        withClue("login page") { marks.getValue("login page (auth layout brand link + sign-in card)") shouldHaveSize 2 }
        withClue("forced-password gate") { marks.getValue("forced-password gate (auth layout brand link)") shouldHaveSize 1 }

        // The rail is the reference; every other surface's every copy must be byte-identical
        // to it, and the failure names EVERY surface that diverged, not only the first.
        val reference = marks.getValue("app rail").single()
        assertSoftly {
            marks.forEach { (surface, copies) ->
                copies.forEachIndexed { index, copy ->
                    withClue("surface '$surface', copy ${index + 1} of ${copies.size}, differs from the app rail's mark") {
                        copy shouldBe reference
                    }
                }
            }
        }

        // D1's geometry, exactly (GitHub #157): the tile is a stroked outline, the three bars
        // are filled — never the other way around.
        reference shouldContain """<rect x="38" y="20" width="22" height="24" rx="4" fill="none" stroke="currentColor" stroke-width="4"/>"""
        reference shouldContain """<rect x="42" y="33" width="4" height="6" rx="1" fill="currentColor"/>"""
        reference shouldContain """<rect x="47" y="28" width="4" height="11" rx="1" fill="currentColor"/>"""
        reference shouldContain """<rect x="52" y="24" width="4" height="15" rx="1" fill="currentColor"/>"""
        // The retired filled-tile geometry never reappears.
        reference shouldNotContain "var(--surface-default)"
        reference shouldNotContain RETIRED_TILE_RECT
    }

    /**
     * The render test above sees only the surfaces it renders; this sees every template. The
     * retired filled tile's opening rect is the one string that identifies it, and it may live
     * nowhere in the source tree — not even in `partials/brand-mark.html`, which authors D1's
     * outlined rect instead. A copy pasted into a template this class does not render is
     * caught here before anyone renders it.
     */
    @Test
    fun `no template in the source tree carries the retired filled-tile rect`() {
        val carriers =
            templates()
                .filter { Files.readString(it).contains(RETIRED_TILE_RECT) }
                .map { it.toString() }
        withClue("templates still carrying the retired mark `$RETIRED_TILE_RECT`") { carriers.shouldBeEmpty() }
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

    /** The ceremony screens' models, as `AuthLayoutRenderTest` fills them: the layout's theme and CSRF token, plus each screen's own. */
    private fun WebContext.fillLogin() {
        fillChrome(authenticated = false)
        setVariable("providers", emptyList<Any>())
        setVariable("localEnabled", true)
    }

    private fun WebContext.fillPassword() {
        fillChrome(authenticated = true)
        setVariable("mustChange", true)
        setVariable("hasLocalPassword", true)
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()

    private fun templates(): List<Path> =
        Files.walk(Paths.get("src/main/resources/templates")).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".html") }.toList()
        }

    private companion object {
        /** Every `<svg viewBox="0 0 64 64" ...>…</svg>` the brand-mark fragment can emit. */
        val MARK = Regex("""<svg[^>]*viewBox="0 0 64 64"[^>]*>(.*?)</svg>""", RegexOption.DOT_MATCHES_ALL)

        /** The opening rect of the retired filled tile (pre-#157), which no template may carry. */
        const val RETIRED_TILE_RECT = """<rect x="36" y="18" width="26" height="28" rx="5" fill="currentColor"/>"""
    }
}
