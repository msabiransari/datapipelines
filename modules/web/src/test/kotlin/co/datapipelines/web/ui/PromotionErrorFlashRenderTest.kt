package co.datapipelines.web.ui

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

/**
 * The promotion page's error-flash bin (A2, L1c-c): the receiver's `body_invalid` refusal — the count
 * bounds' and strict readers' code, and therefore the aggregate batch ceiling's — must render its toast.
 * Before this round the key was UNMAPPED in the page's own bin, and an unmapped key renders nothing
 * (ui-screens §5.1's "safe direction"): a push that did not happen was silent on the very screen that
 * issued it. This is the proof it is not silent now, and that the closed set stays closed otherwise.
 *
 * The `missing_datasources` case is the witness of the L1c-c entity repair, precisely scoped (the
 * L1c-d correction): that branch was one of the two repaired for `&#39;` entities INSIDE the
 * fragment-expression string literal — attoparser decodes the entity before the expression parses,
 * the decoded apostrophe terminated the literal, and rendering the flash threw (a render-time 500 on
 * the promotion screen, worse than silence). The delivered round's handback claimed the render test
 * pinned BOTH repaired branches; in fact only `key_invalid` (and `missing_template`, the dynamic-code
 * branch) was exercised — `missing_datasources` never rendered through the test until this case. It
 * renders through the REAL template now, and the entity plant in this round's evidence shows the
 * test red on the exact literal the branch used to carry.
 *
 * Thymeleaf, no Spring context — the [PromotionSetsRenderTest] harness shape.
 */
class PromotionErrorFlashRenderTest {
    @Test
    fun `the body_invalid flash renders its toast - a refused batch is never silent`() {
        val rendered = render(error = "body_invalid")

        rendered shouldContain "Nothing was promoted"
        rendered shouldContain "rolled all of it back"
        rendered shouldContain "count bound on the batch"
    }

    @Test
    fun `the missing_datasources flash renders its toast through the real template`() {
        val rendered = render(error = "missing_datasources")

        rendered shouldContain "Nothing was promoted"
        rendered shouldContain "no datasource by one of the names these pipelines reference"
        rendered shouldContain "Register it there"
    }

    @Test
    fun `the other known refusals keep their toasts, and an unmapped key still renders nothing`() {
        render(error = "missing_template") shouldContain "Its own refusal code is missing_template."
        render(error = "key_invalid") shouldContain "promotion key"

        val unmapped = render(error = "some_future_code")
        unmapped shouldContain "data-toast-flash"
        unmapped shouldNotContain "some_future_code"
    }

    // ------------------------------------------------------------------ harness

    private fun render(error: String): String =
        COMMENT
            .replace(
                engine().process("promotion/index", context(error).apply { promotionModel() }),
                "",
            )

    private fun context(error: String): WebContext {
        val request = MockHttpServletRequest().apply { addParameter("error", error) }
        return WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(request, MockHttpServletResponse()),
        )
    }

    private fun WebContext.promotionModel() {
        setVariable("currentPath", "/promotion")
        setVariable("hasTarget", true)
        setVariable("targetBaseUrl", "https://prod.example")
        setVariable("planError", null)
    }

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
