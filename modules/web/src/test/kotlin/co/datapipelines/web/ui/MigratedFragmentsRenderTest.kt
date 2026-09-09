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
 * The fragments 097 §C created out of Kotlin string builders, RENDERED.
 *
 * A controller test that asserts a view name and a model attribute proves the controller;
 * it does not prove the template compiles, and a Thymeleaf expression error is a 500 at the
 * first real request. This suite is the other half — and it is here because the first draft
 * of `partials/template-render` shipped `${renderError} == null and ${renderOutput}.isEmpty()`,
 * which is not a valid expression, and every Kotlin test stayed green: only the browser walk
 * found it. Each fragment gets its states rendered, plus the escaping the hand-written
 * `escaped()` used to do.
 */
class MigratedFragmentsRenderTest {
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

    private fun render(
        view: String,
        vararg model: Pair<String, Any?>,
    ): String =
        engine.process(
            view,
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).apply { model.forEach { (k, v) -> setVariable(k, v) } },
        )

    @Test
    fun `the preview renders the SQL, escaped, in the output box`() {
        val html = render("partials/template-render", "renderOutput" to "SELECT '<b>' AS x", "renderError" to null)

        html shouldContain "te-render-out"
        html shouldContain "SELECT &#39;&lt;b&gt;&#39; AS x"
        // The rendered SQL is DATA. It reached the page as markup exactly once — through a
        // hand-rolled `escaped()` — and that is the half of this migration that was a
        // correctness risk rather than a tidiness one.
        html shouldNotContain "<b>"
        html shouldNotContain "te-render-card-error"
    }

    @Test
    fun `a blank render is a result, not a failure`() {
        val html = render("partials/template-render", "renderOutput" to "", "renderError" to null)

        html shouldContain "(empty output)"
        html shouldContain "te-render-empty"
        html shouldNotContain "te-render-card-error"
    }

    @Test
    fun `a failed render is the danger card, with the message escaped`() {
        val html = render("partials/template-render", "renderOutput" to "", "renderError" to "Render failed: <x>")

        html shouldContain "te-render-card-error"
        html shouldContain "te-render-error"
        html shouldContain "Render failed: &lt;x&gt;"
        html shouldNotContain "te-render-out"
    }

    @Test
    fun `the inline refusal is the shared box, and escapes the reason`() {
        val html = render("partials/inline-refusal", "message" to "A datasource named '<x>' already exists.")

        html shouldContain "app-inline-refusal"
        html shouldContain "&lt;x&gt;"
        html shouldNotContain "style="
    }

    @Test
    fun `the field error is the inline span the password form swaps in`() {
        val html = render("partials/field-error", "message" to "The current password is incorrect")

        html shouldContain "u-danger"
        html shouldContain "The current password is incorrect"
        html shouldNotContain "style="
    }
}
