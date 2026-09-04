package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap

/**
 * [SiteController] — the public homepage's two guarantees (033): the one live number is
 * the COMPILE-TIME tool count (an injected bean list would render "0 tools" wherever the
 * conditional tool bean is absent — C4), and the page's defence is a short public
 * cache-control window, never a rate limiter (D1).
 */
class SiteControllerTest {
    private val controller = SiteController()

    @Test
    fun `the tool count is the catalog's compile-time size`() {
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        controller.home(model, response) shouldBe "site/index"

        model["toolCount"] shouldBe co.datapipelines.mcp.McpToolCatalog.NAMES.size
        model["toolCount"] as Int shouldBeGreaterThan 0
    }

    @Test
    fun `the response carries a short public cache window`() {
        val response = MockHttpServletResponse()

        controller.home(ExtendedModelMap(), response)

        response.getHeader("Cache-Control") shouldContain "max-age=300"
        response.getHeader("Cache-Control") shouldContain "public"
    }

    private infix fun Int.shouldBeGreaterThan(floor: Int) {
        this shouldBe this.also { if (this <= floor) throw AssertionError("$this <= $floor") }
    }
}
