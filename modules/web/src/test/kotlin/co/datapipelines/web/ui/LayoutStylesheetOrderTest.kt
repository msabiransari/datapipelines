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
 * T40 cause 2's regression guard: the layout loads Bootstrap AFTER the design system
 * but BEFORE `app.css`, and `app.css` restores the token-painted body canvas.
 *
 * Bootstrap's reboot declares `body { background-color: var(--bs-body-bg); ... }` —
 * concrete white for every theme. Loaded last (the pre-027 order), it painted `<body>`
 * white under EVERY theme: dark chrome over a white page (024 T40), and the light
 * themes' `--surface-page` canvas silently masked by Bootstrap's. The design intent is
 * `base.css`'s own `html` rule (background-color: var(--surface-page)); the fix is the
 * documented §3.4 order — design system first, Bootstrap, then app CSS — plus an app.css
 * body rule that re-asserts the tokens over Bootstrap's reboot.
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
    fun `app css loads after bootstrap so its body rule can win`() {
        val html = renderedHead()
        val bootstrap = html.indexOf("bootstrap.min.css")
        val appCss = html.indexOf("/css/app.css")
        bootstrap shouldBeGreaterThan -1
        appCss shouldBeGreaterThan -1
        bootstrap shouldBeLessThan appCss
    }

    @Test
    fun `app css restores the token body canvas over the bootstrap reboot`() {
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
