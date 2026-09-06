package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * Stylesheet load-order contract for the app layout (succeeds T40 cause 2's guard).
 *
 * 076 §C removed Bootstrap 5.3.8 from the app entirely: no app template ever used a
 * Bootstrap class, and the defaults its reboot silently provided are now owned by
 * base.css plus the "076 §C" element-defaults section at the end of app.css. The order
 * contract that remains: the design-system vendor sheets load BEFORE `app.css`, and no
 * `webjars/bootstrap` reference exists anywhere in the rendered layout.
 *
 * The body-canvas assertion survives the removal on purpose: app.css's `body` rule
 * re-asserting `background-color: var(--surface-page); color: var(--text-primary)` was
 * born as the guard against Bootstrap's reboot painting `<body>` white under every
 * theme (024 T40), and it stays meaningful as the standing guard over any future reset
 * layered ahead of app.css.
 *
 * Load ORDER is load-bearing and invisible to per-file reasoning: equal-specificity
 * rules resolve by document order, so this test pins the rendered order, not the files.
 */
class LayoutStylesheetOrderTest {
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

    private fun renderedHead(): String {
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val exchange = application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val context = WebContext(exchange)
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", null)
        context.setVariable("activeTheme", "light")
        return engine.process("test-stub", context)
    }

    @Test
    fun `no webjars bootstrap reference remains in the rendered layout`() {
        renderedHead().contains("webjars/bootstrap") shouldBe false
    }

    @Test
    fun `the design system vendor sheets load before app css`() {
        val html = renderedHead()
        val tokens = html.indexOf("/vendor/design-system/tokens.css")
        val base = html.indexOf("/vendor/design-system/base.css")
        val motion = html.indexOf("/vendor/design-system/motion.css")
        val primitives = html.indexOf("/vendor/design-system/primitives.css")
        // 079: icons.css was vendored from the start and never linked, so `.ds-icon` sized
        // nothing. Pinned here so it cannot fall out again silently — the failure mode is an
        // unsized inline SVG, which looks like a layout bug rather than a missing stylesheet.
        val icons = html.indexOf("/vendor/design-system/icons.css")
        val appCss = html.indexOf("/css/app.css")
        tokens shouldBeGreaterThan -1
        base shouldBeGreaterThan -1
        motion shouldBeGreaterThan -1
        primitives shouldBeGreaterThan -1
        icons shouldBeGreaterThan -1
        appCss shouldBeGreaterThan -1
        tokens shouldBeLessThan base
        base shouldBeLessThan motion
        motion shouldBeLessThan primitives
        // app.css is LAST: its body-canvas and element-default rules win by order.
        primitives shouldBeLessThan icons
        icons shouldBeLessThan appCss
    }

    @Test
    fun `app css asserts the token body canvas`() {
        val appCss =
            ClassPathResource("static/css/app.css").inputStream.readBytes().decodeToString()
        appCss shouldMatch
            Regex(
                """(?s).*body\s*\{[^}]*background-color:\s*var\(--surface-page\)[^}]*color:\s*var\(--text-primary\)[^}]*\}.*""",
            )
        // The rule must exist as a real declaration, not in a comment.
        appCss.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "") shouldContain
            "background-color: var(--surface-page)"
    }
}
