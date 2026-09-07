package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutorJson
import io.kotest.matchers.shouldBe
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
 * 076 §D / §12.10's visibility rule: a CALCULATOR node reports `rows_out: 0` on every
 * surface, so its Context write (`context_key`/`context_value` on the node-stats row,
 * NodeStats §4.10) is the ONLY place an author debugging a wrong quarter can see what the
 * node computed. The executions detail table renders that pair in a Context column; every
 * other node type has the keys ABSENT (NON_NULL inclusion) and gets the em-dash.
 *
 * The fixture pins both shapes in one render: one CALCULATOR entry, one DQL entry.
 */
class ExecutionNodeStatsRenderTest {
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
    fun `a calculator node renders its context write and a dql node the em-dash`() {
        val nodeStats =
            ExecutorJson.mapper.readTree(
                """
                [
                  {"node_id": "quarter", "rows_out": 0, "duration_ms": 3,
                   "context_key": "current_quarter", "context_value": "2026-Q3"},
                  {"node_id": "fetch_rows", "rows_in": 10, "rows_out": 42, "duration_ms": 120}
                ]
                """.trimIndent(),
            )

        val html =
            engine.process(
                "partials/execution-node-stats",
                webContext().apply { setVariable("nodeStats", nodeStats) },
            )

        // 082 §D: Context is a MONO IDENTIFIER column, not a `.num` numeric one — and the
        // node id beside it is mono for the same reason (§3.3's decision table).
        html shouldContain "<th>Context</th>"
        html shouldContain "<td class=\"u-mono\">quarter</td>"
        html shouldContain "current_quarter → 2026-Q3"
        // Exactly one em-dash: the DQL row's Context cell, and nothing else.
        html.split("—").size - 1 shouldBe 1
        html shouldNotContain "context_key"
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
