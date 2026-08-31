package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * Two definitions of the same toast markup exist — the server fragment
 * `partials/toast.html` and the one client-side builder `DpToast.show` in
 * `static/js/toast.js` (Shape D, ui-screens.md §5.1) — and nothing but this
 * contract stops them drifting. Both sides assert ONE structure, written down
 * once in §5.1: root classes `ds-toast ds-toast-{variant}`, `role="status"`,
 * and exactly three children in the order close / title / body. The JS half
 * lives in `toast.test.mjs` ("show builds the server fragment's shape…"); if
 * either definition changes without the other, one of the two tests goes red.
 */
class ToastMarkupParityTest {
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
    fun `the server fragment emits the contracted toast structure`() {
        val html = render(variant = "success", title = "T", message = "M")

        html shouldContain "class=\"ds-toast ds-toast-success\""
        html shouldContain "role=\"status\""
        val order =
            Regex("class=\"ds-toast-(close|title|body)\"")
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()
        order shouldBe listOf("close", "title", "body")
    }

    private fun render(
        variant: String,
        title: String,
        message: String,
    ): String =
        engine.process(
            "partials/toast",
            webContext().apply {
                setVariable("variant", variant)
                setVariable("title", title)
                setVariable("message", message)
            },
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
