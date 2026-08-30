package co.datapipelines.web.ui

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.Context
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The dashboard htmx partials render STANDALONE (025 B2 — T34's jar-round finding): the
 * two templates were authored as `fragment(name, children)` signatures wrapping
 * `th:replace="${children}"`, so the htmx endpoints that return the template AS A VIEW
 * died with `Error resolving fragment: "${children}"` — there is no `children` outside an
 * include. The templates are now plain content; this test renders each exactly as the
 * controller hands it to the engine (view name + model) and asserts the content arrives.
 *
 * `dashboard` itself is rendered too: its initial-load include of the partials was a
 * no-op round trip (the fragment emitted `children` unchanged); the skeleton markup now
 * lives in the page directly and must still render.
 */
class DashboardPartialsRenderTest {
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
    fun `dashboard-stats renders its content as a standalone view`() {
        val html =
            engine.process(
                "partials/dashboard-stats",
                Context().apply {
                    setVariable("totalPipelines", 3)
                    setVariable("executionsToday", 2)
                    setVariable("successRate", 50)
                },
            )

        html shouldContain "Total Pipelines"
        html shouldContain "Executions Today"
        html shouldContain "50%"
        html shouldNotContain "Error resolving fragment"
    }

    @Test
    fun `recent-executions renders its content as a standalone view`() {
        val html = engine.process("partials/recent-executions", Context().apply { setVariable("executions", emptyList<Any>()) })

        html shouldContain "No executions yet"
        html shouldNotContain "Error resolving fragment"
    }

    /**
     * 025 C1: the theme-swap fragment's OOB link must come out RENDERED — a resolved href
     * (context-pathed by @{...}), no surviving th: attributes. The old hand-built string
     * shipped th:href verbatim and htmx swapped it over the layout's stylesheet link.
     */
    @Test
    fun `theme-swap renders a resolved oob stylesheet link with no raw th attributes`() {
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val exchange = application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val html =
            engine.process(
                "partials/theme-swap",
                WebContext(exchange).apply { setVariable("theme", "ocean") },
            )

        html shouldContain """id="theme-link" """.trim()
        html shouldContain """hx-swap-oob="outerHTML" """.trim()
        // The RESOLVED href — @{...} ran; a hand-built or unprocessed value would still
        // carry the {theme} placeholder or a th: attribute.
        html shouldContain """href="/vendor/design-system/themes/ocean.css""".trim()
        html shouldContain "Theme updated to"
        html shouldNotContain "th:"
    }

    @Test
    fun `the dashboard page still renders its skeleton loaders`() {
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        val exchange = application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val context =
            WebContext(exchange).apply {
                setVariable("_csrf", mapOf("token" to "t"))
                setVariable("workspaceHeaderFragment", "")
                setVariable("workspaceOptions", emptyList<Any>())
                setVariable("activeWorkspace", null)
                setVariable("activeTheme", "saas")
            }

        val html = engine.process("dashboard", context)

        html shouldContain "hx-get=\"/partials/dashboard-stats\""
        html shouldContain "hx-get=\"/partials/recent-executions\""
        html shouldContain "ds-skeleton"
    }
}
