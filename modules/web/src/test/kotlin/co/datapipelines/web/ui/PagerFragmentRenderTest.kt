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
 * The one shared pager every list screen renders through `partials/pager :: pager(...)`
 * (029, ui-screens.md §5). The fragment never builds URLs — Thymeleaf link expressions
 * take literal parameter names, so a per-screen filter set cannot be splatted into
 * `@{...}`; each caller passes finished `prevUrl`/`nextUrl` strings, and they must come
 * out VERBATIM (only HTML-escaped). `total` is nullable: TemplatePartialController
 * supplies none, so the count renders as `Showing N` without the `of M`.
 */
class PagerFragmentRenderTest {
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
    fun `first page disables previous and resolves the next url verbatim`() {
        val html = render(offset = 0, hasMore = true, shown = 25, total = 30)

        html shouldContain "disabled"
        html shouldContain "hx-get=\"/partials/pipelines?q=trip&amp;offset=25\""
        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "Showing 25 of 30"
    }

    @Test
    fun `a middle page enables both buttons`() {
        val html = render(offset = 25, hasMore = true, shown = 25, total = 100)
        Regex("""<button[^>]*\sdisabled""").containsMatchIn(html) shouldBe false
    }

    @Test
    fun `the last page disables next`() {
        render(offset = 25, hasMore = false, shown = 5, total = 30) shouldContain "disabled"
    }

    @Test
    fun `a null total renders the count alone`() {
        render(offset = 0, hasMore = true, shown = 25, total = null) shouldContain "Showing 25"
    }

    private fun render(
        offset: Int,
        hasMore: Boolean,
        shown: Int,
        total: Int?,
    ): String =
        engine.process(
            "partials/pager",
            webContext().apply {
                setVariable("targetId", "#pipeline-list-wrapper")
                setVariable("prevUrl", "/partials/pipelines?q=trip&offset=" + (offset - 25))
                setVariable("nextUrl", "/partials/pipelines?q=trip&offset=" + (offset + 25))
                setVariable("offset", offset)
                setVariable("hasMore", hasMore)
                setVariable("shown", shown)
                setVariable("total", total)
            },
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
