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
 * 065 §A — the full-bleed opt-out, pinned at the RENDER, not at the model.
 *
 * `layouts/default.html` wraps every page in `<main class="app-container app-main">`,
 * and `app.css` caps `.app-container` at `--app-content-max: 1600px`. That cap is
 * right for prose pages and wrong for the graph editor, which is the one surface
 * whose value scales with width: on the owner's ~2000px viewport the editor
 * occupied ~1450px between two dead margins.
 *
 * The opt-out is one model attribute (`fullBleed`) read by one `th:classappend`.
 * Both halves are easy to lose silently — a controller refactor drops the
 * attribute, or a layout edit drops the append — and neither shows up in a
 * controller test that only inspects the model. So this renders the real
 * templates through the real layout and reads the `<main>` tag that comes out.
 *
 * The negative case is the point of the pair: `th:classappend` on a null
 * condition must add NOTHING, so every other screen keeps the 1600px cap. A rule
 * that leaked onto the list pages would widen the whole app, which is precisely
 * what the owner did not ask for.
 *
 * Harness: the standalone `SpringTemplateEngine` that `ListPartialsRenderTest`
 * uses. (The brief called for MockMvc; the tree's render-test harness beside that
 * class is this one, and it exercises the same two template files with no context
 * to boot.)
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
    fun `the editor page's main element carries app-main-bleed`() {
        val html = engine.process("pipelines/editor", webContext().apply { fillEditor() })

        html shouldContain "app-container app-main app-main-bleed"
    }

    @Test
    fun `the pipelines list page's main element does not`() {
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
        // The one attribute under test — PipelineEditorController sets it; no other
        // controller does.
        setVariable("fullBleed", true)
    }

    /** PipelineUiController's model, with `fullBleed` deliberately ABSENT. */
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
