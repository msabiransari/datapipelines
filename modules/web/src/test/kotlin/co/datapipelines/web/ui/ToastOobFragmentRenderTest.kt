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
 * The `partials/toast-oob` wrapper is the ONLY way a toast reaches the #toast
 * stack by OOB swap (ui-screens.md §5.1). htmx 2.0.10's `oobSwap` swaps the oob
 * element's CHILDREN for any swap style other than `outerHTML` ("we use the
 * content of the node, not the node itself" — htmx.js), so `hx-swap-oob` must
 * live on a WRAPPER with the `.ds-toast` as its child. Put the attribute on the
 * toast itself and htmx appends the close button, title and body bare into the
 * stack: no `.ds-toast` node is created, `toast.js` arms nothing, and nothing
 * auto-dismisses — silently. These tests pin the nesting, not a substring.
 */
class ToastOobFragmentRenderTest {
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
    fun `the oob wrapper carries the swap attribute and WRAPS the toast`() {
        val html =
            engine.process(
                "partials/toast-oob",
                webContext().apply {
                    setVariable("variant", "success")
                    setVariable("title", "Datasource registered")
                    setVariable("message", "pg-prod is ready to use.")
                },
            )

        // htmx 2.0.10 oobSwap: a non-outerHTML style swaps the oob element's
        // CHILDREN, so the .ds-toast MUST be the first element child of the
        // element carrying hx-swap-oob (only whitespace/comments may precede it).
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        Regex("""hx-swap-oob="beforeend:#toast"[^>]*>(?:\s|<!--[\s\S]*?-->)*<div class="ds-toast""")
            .containsMatchIn(html) shouldBe true
        html shouldContain "ds-toast-success"
        html shouldContain "Datasource registered"
    }

    @Test
    fun `the attribute never lands on the toast itself`() {
        val html =
            engine.process(
                "partials/toast-oob",
                webContext().apply {
                    setVariable("variant", "danger")
                    setVariable("title", "t")
                    setVariable("message", "m")
                },
            )

        Regex("""<div class="ds-toast[^"]*"[^>]*hx-swap-oob""").containsMatchIn(html) shouldBe false
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
