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

    @Test
    fun `the pipeline cell shows the display name and carries the machine name on title`() {
        // T114: the join happens web-side (one batch query per page); the partial renders
        // the DISPLAY name as prose and the machine folder-path name on `title`.
        val record = executionRecord()
        val html =
            engine.process(
                "partials/executions",
                webContext().apply {
                    setVariable("executions", listOf(record))
                    setVariable(
                        "pipelineNames",
                        mapOf(
                            record.pipelineId to
                                PipelineName(name = "nyc/mobility/revenue_by_borough", displayName = "Revenue by borough"),
                        ),
                    )
                    setVariable("offset", 0)
                    setVariable("pageSize", 25)
                    setVariable("nextOffset", null)
                    setVariable("hasMore", false)
                },
            )

        html shouldContain "Revenue by borough"
        html shouldContain "title=\"nyc/mobility/revenue_by_borough\""
        html shouldNotContain record.pipelineId.toString().substring(0, 8)
    }

    @Test
    fun `a missing pipeline falls back to the truncated id`() {
        // A deleted pipeline leaves its executions behind; the lookup returns no row and
        // the cell degrades to the old truncated-id rendering instead of crashing.
        val record = executionRecord()
        val html =
            engine.process(
                "partials/executions",
                webContext().apply {
                    setVariable("executions", listOf(record))
                    setVariable("pipelineNames", emptyMap<UUID, PipelineName>())
                    setVariable("offset", 0)
                    setVariable("pageSize", 25)
                    setVariable("nextOffset", null)
                    setVariable("hasMore", false)
                },
            )

        html shouldContain record.pipelineId.toString().substring(0, 8) + "..."
    }

    /**
     * §5 (097 §B): `/executions` renders the shell AND the initial fragment. It used to
     * render a spinner behind `hx-trigger="load"`, so the screen's first paint was an empty
     * frame waiting on a round trip the browser had not made yet — the one list screen still
     * on that idiom. Rendering the page's own `content` fragment (no layout) is enough to see
     * it: the table is THERE, and the loading placeholder is gone.
     */
    @Test
    fun `the page's first paint carries the table itself, not a loading placeholder`() {
        val record = executionRecord()
        val html =
            engine.process(
                "executions/list",
                setOf("content"),
                webContext().apply {
                    setVariable("pipelines", emptyList<Any>())
                    setVariable("statuses", ExecutionStatus.entries)
                    setVariable("selectedPipelineId", "")
                    setVariable("selectedStatus", "FAILED")
                    setVariable("selectedStartedAfter", "2026-09-01")
                    setVariable("selectedStartedBefore", "")
                    setVariable("executions", listOf(record))
                    setVariable("pipelineNames", emptyMap<UUID, PipelineName>())
                    setVariable("offset", 0)
                    setVariable("pageSize", 20)
                    setVariable("nextOffset", null)
                    setVariable("hasMore", false)
                },
            )

        html shouldContain "<table class=\"ds-table\""
        html shouldNotContain "Loading executions"
        html shouldNotContain "hx-trigger=\"load\""
        // The bar re-renders in the state the rows were fetched with (a shared filtered link).
        html shouldContain "value=\"2026-09-01\""
        html shouldContain "selected=\"selected\""
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
