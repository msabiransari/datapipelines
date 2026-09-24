package co.datapipelines.web.ui.site

import co.datapipelines.web.bootstrap.BootstrapProperties
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The demo-data page's "call it as an API" section (#224), both directions: WITH the shipped
 * default configuration the section renders the configured key and one curl per family, and the
 * static export (which renders through this same handler) carries the working value; with the
 * key blanked (`BootstrapProperties(demoApiKey = "")`, the kill switch) the section does not
 * render at all — a page promising a key that answers nothing would be worse than no section.
 */
class SiteDemoApiSectionTest {
    @Test
    fun `the section renders the configured key and a curl per family`() {
        val html = SitePageRenderer.render(SitePages.DEMO_DATA, SITE_ORIGIN_HOST)

        html shouldContain "id=\"dd-api\""
        html shouldContain BootstrapProperties.DEFAULT_DEMO_API_KEY
        // One curl per family, each naming the path the seeder derives from the same mapping.
        html shouldContain "/api/demo/nyc/mobility/revenue-by-borough"
        html shouldContain "/api/demo/trade/balance-by-partner"
        html shouldContain "/api/demo/nyc/mobility/taxi-vs-rideshare"
        // The "public by design" sentence, and the budget's existence without a hand-typed number.
        html shouldContain "public by design"
        html shouldContain "429"
    }

    @Test
    fun `a blank key hides the section entirely`() {
        val controller = SitePagesController(SiteDemoData(javaClass.classLoader), BootstrapProperties(demoApiKey = ""))
        val model = ExtendedModelMap()
        val response: HttpServletResponse = MockHttpServletResponse()
        val view = controller.demoData(model, response)
        val html = process(view, model)

        html shouldNotContain "id=\"dd-api\""
        html shouldNotContain "dpk_"
    }

    /** The offline render, arm for arm with [SitePageRenderer.process], over a custom model. */
    private fun process(
        view: String,
        model: ExtendedModelMap,
    ): String {
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        model.asMap().forEach { (k, v) -> context.setVariable(k, v) }
        return engine.process(view, context)
    }
}
