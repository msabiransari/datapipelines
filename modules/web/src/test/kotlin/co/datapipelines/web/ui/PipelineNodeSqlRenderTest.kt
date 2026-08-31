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
 * The partial's markup per state (pipeline-editor.md §8). The security-critical
 * assertion is the escaping one: the rendered SQL is emitted with `th:text`, so a
 * template body carrying markup comes out as TEXT (`&lt;script&gt;`), never as
 * live markup inside the details panel.
 */
class PipelineNodeSqlRenderTest {
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
    fun `the rendered state shows the dialect badge, the template link and the copy button`() {
        val html = render("rendered", sql = "SELECT 1")

        html shouldContain "class=\"ds-badge ds-badge-default\""
        html shouldContain "POSTGRES"
        html shouldContain "href=\"/templates/trips_by_day.sql/editor\""
        html shouldContain "trips_by_day.sql @ v1"
        html shouldContain "pe-sql-copy"
        html shouldContain "<pre class=\"pe-sql\">"
    }

    @Test
    fun `markup inside the rendered SQL is escaped, never live`() {
        val html = render("rendered", sql = "SELECT '<script>alert(1)</script>' AS x")

        html shouldNotContain "<script>"
        html shouldContain "&lt;script&gt;"
    }

    @Test
    fun `the sampled state names the sampled parameters`() {
        val html = render("rendered", sql = "SELECT 1", sampled = listOf("start_date"))

        html shouldContain "Preview uses sample values for: start_date"
    }

    @Test
    fun `the child-pipeline state points at the child, no SQL block`() {
        val html = render("child-pipeline")

        html shouldContain "has no SQL of its own"
        html shouldContain "child_pipe @ v3"
        html shouldNotContain "<pre class=\"pe-sql\">"
    }

    @Test
    fun `the template-missing state names the pinned ref`() {
        val html = render("template-missing")

        html shouldContain "trips_by_day.sql @ v1"
        html shouldContain "not in this workspace"
    }

    @Test
    fun `the parameter-rejected state names the parameter and shows no SQL`() {
        val html = render("parameter-rejected")

        html shouldContain "limit"
        html shouldContain "executor would refuse"
        html shouldNotContain "<pre class=\"pe-sql\">"
    }

    @Test
    fun `the render-failed state shows the engine's message`() {
        val html = render("render-failed")

        html shouldContain "undefined variable: nope"
    }

    @Test
    fun `the node-missing state names the node`() {
        val html = render("node-missing")

        html shouldContain "no_such_node"
    }

    private fun render(
        state: String,
        sql: String = "SELECT 1",
        sampled: List<String> = emptyList(),
    ): String =
        engine.process(
            "partials/pipeline-node-sql",
            webContext().apply {
                setVariable("state", state)
                setVariable("sql", sql)
                setVariable("dialect", "POSTGRES")
                setVariable("templateId", "trips_by_day.sql")
                setVariable("templateVersion", 1)
                setVariable("sampledParameters", sampled)
                setVariable("childName", "child_pipe")
                setVariable("childVersion", 3)
                setVariable("failures", listOf(mapOf("parameter" to "limit", "message" to "must be a number")))
                setVariable("message", "undefined variable: nope")
                setVariable("nodeId", "no_such_node")
            },
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
