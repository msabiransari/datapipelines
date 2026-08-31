package co.datapipelines.web.ui

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
 * The details panel's SQL section (pipeline-editor.md §8): the swap target the
 * htmx.ajax loader in init.js fills, its indicator, the no-selection empty
 * state, and the highlighter script the section depends on.
 */
class PipelineEditorRenderTest {
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
    fun `the details panel carries the SQL swap target, its spinner and a select-a-node empty state`() {
        val html = render()

        html shouldContain "id=\"pe-node-sql\""
        html shouldContain "id=\"pe-node-sql-spinner\""
        html shouldContain "ds-spinner"
        html shouldContain "Select a node"
    }

    @Test
    fun `the page loads the SQL highlighter`() {
        render() shouldContain "/js/pipeline-editor/sql-highlight.js"
    }

    private fun render(): String =
        engine.process(
            "pipelines/editor",
            webContext().apply {
                setVariable("_csrf", mapOf("token" to "t"))
                setVariable("workspaceHeaderFragment", "")
                setVariable("workspaceOptions", emptyList<Any>())
                setVariable("activeWorkspace", "acme")
                setVariable("activeTheme", "saas")
                setVariable("authenticated", true)
                setVariable("currentPath", "/pipelines")
                setVariable(
                    "pipelineJson",
                    """{"id":"00000000-0000-0000-0000-000000000001","name":"p","display_name":"P","version":1,"parameters":{},"nodes":[]}""",
                )
            },
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
