package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The public marketing page (033): renders ANONYMOUSLY (no principal, no workspace — the
 * model is exactly what SiteController hands an anonymous request), with the tool-count
 * fact baked from the catalog, and with chrome free of unresolved expressions (033/B2 —
 * the page has no doc body, so the sweep is whole-page here).
 */
class SiteRenderTest {
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
    fun `the marketing home renders anonymously with the catalog tool count and no unresolved expressions`() {
        val count = McpToolCatalog.NAMES.size
        val html =
            engine.process(
                "site/index",
                webContext().apply { setVariable("toolCount", count) },
            )

        html shouldContain "Agent-native Data Pipelines"
        // The three former hardcoded "18"s now render from the model (033/C4).
        html shouldContain "<span>$count</span> tools cover the full lifecycle"
        html shouldContain "/mcp — $count MCP tools"
        html shouldContain "($count tools)"
        // Assets resolve through the app's own static surface, never the retired website/ copy.
        html shouldContain "href=\"/vendor/design-system/tokens.css\""
        html shouldContain "href=\"/site/css/site.css\""
        html shouldContain "src=\"/site/img/execution-result.png\""
        html shouldContain "src=\"/site/js/site.js\""
        // The app serves this page now — sign-in is a route away.
        html shouldContain "href=\"/login\""

        html shouldNotContain "assets/"
        // " th:" with the leading space — an unprocessed th:* attribute. The root
        // element's xmlns:th namespace declaration survives every render legitimately.
        html shouldNotContain " th:"
        html shouldNotContain ("\${")
    }

    @Test
    fun `SiteController serves the site view with a public cache header and the catalog count`() {
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view = SiteController().home(model, response)

        view shouldBe "site/index"
        model["toolCount"] shouldBe McpToolCatalog.NAMES.size
        response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "max-age=300, public"
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
