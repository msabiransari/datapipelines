package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
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
import java.time.Instant
import java.util.UUID

/**
 * Falsification harness for D7 (029): the executions pager wrote
 * `hx-vals='{"offset": "[[${nextOffset}]]"}'` as a PLAIN attribute, and Thymeleaf 3.1
 * processes `[[...]]` inlining in TEXT nodes, not in attribute values — so the literal
 * was expected to reach the browser and the `offset: Int` binding to 400. This test
 * renders the partial and asserts the offsets come out RESOLVED; the fix is the form
 * ui-screens.md §5 prescribes (`th:attr="hx-vals=|{...}|"`).
 *
 * The executions list keeps its own contract (`#execution-table`, `innerHTML`,
 * `hx-include="#execution-filters"`) — it does NOT adopt the Task 4 shared pager.
 */
class ExecutionsPartialRenderTest {
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
    fun `the executions pager renders a resolved hx-vals offset`() {
        val html =
            engine.process(
                "partials/executions",
                webContext().apply {
                    setVariable("executions", listOf(executionRecord()))
                    setVariable("offset", 25)
                    setVariable("pageSize", 25)
                    setVariable("nextOffset", 50)
                    setVariable("hasMore", true)
                },
            )

        // Thymeleaf processes [[...]] inlining in TEXT nodes, not in plain attributes.
        html shouldNotContain "[[$" + "{"
        // The spec form renders the number unquoted, the quotes HTML-escaped in the attribute.
        html shouldContain "&quot;offset&quot;: 0"
        html shouldContain "&quot;offset&quot;: 50"
    }

    private fun executionRecord() =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = ExecutionStatus.SUCCESS,
            parametersJson = "{}",
            triggeredBy = UUID.randomUUID(),
            triggeredVia = ExecutionTrigger.REST,
            startedAt = Instant.parse("2026-08-30T14:30:00Z"),
            durationMs = 1200,
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
