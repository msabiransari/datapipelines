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
 * 076 §A — one width policy, pinned at the RENDER, not at the model.
 *
 * Before 076, `layouts/default.html` capped `.app-container` at
 * `--app-content-max: 1600px` and the pipeline editor alone opted out via a
 * `fullBleed` model attribute (065 §A). Measured on the owner's ~3000px window
 * (2026-09-05): four content widths across eleven screens. The opt-in became
 * the rule — EVERY screen's `<main>` is the same full-bleed shell now, reading
 * content sits in `.app-reading` inside it, and `fullBleed`/`.app-main-bleed`/
 * `--app-content-max` are gone.
 *
 * So the pair below inverts 065's: the editor page and the pipelines list must
 * render the SAME `<main>` — the invariant is that no page can opt into a
 * different container any more. A layout edit that re-introduces a conditional
 * class, or a controller that resurrects a per-page width flag, fails here.
 *
 * Harness: the standalone `SpringTemplateEngine` that `ListPartialsRenderTest`
 * uses.
 */
class EditorLayoutRenderTest {
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
    fun `the editor page renders the same full-bleed main as every other page`() {
        val html = engine.process("pipelines/editor", webContext().apply { fillEditor() })

        html shouldContain "class=\"app-container app-main\""
        html shouldNotContain "app-main-bleed"
    }

    @Test
    fun `the pipelines list page renders the same full-bleed main`() {
        val html = engine.process("pipelines/list", webContext().apply { fillList() })

        html shouldContain "class=\"app-container app-main\""
        html shouldNotContain "app-main-bleed"
    }

    /** The editor controller's model, plus the layout chrome the shell needs. */
    private fun WebContext.fillEditor() {
        fillLayoutChrome()
        setVariable("pipelineJson", "{\"id\":\"p1\",\"name\":\"demo\",\"nodes\":[]}")
        setVariable("lifecycleJson", "{\"hasDraft\":false}")
        setVariable("pipelineId", "11111111-1111-1111-1111-111111111111")
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("draftHash", null)
        setVariable("releasedVersion", 1)
    }

    /** PipelineUiController's model. */
    private fun WebContext.fillList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        setVariable("pipelines", emptyList<Any>())
        setVariable("drafts", emptyMap<Any, Any>())
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 0)
    }

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/pipelines")
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
