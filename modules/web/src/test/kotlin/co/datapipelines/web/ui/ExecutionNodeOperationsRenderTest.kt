package co.datapipelines.web.ui

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
 * 149: the execution detail page's operation table renders what the durable
 * `node_progress` record said — the destination, the last state, the counts, the commit
 * badge — and marks an operation that was never observed to end, instead of upgrading it.
 */
class ExecutionNodeOperationsRenderTest {
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
    fun `a committed stage, a rolled-back write-back and an unfinished operation render honestly`() {
        val rows =
            listOf(
                NodeOperationRow(
                    "stage_trips",
                    "stage",
                    "tempdb.trips",
                    "completed",
                    true,
                    12453,
                    12453,
                    6120,
                    true,
                    false,
                    "query 410 ms · fetch 3.4 s · write 1.5 s",
                    null,
                ),
                NodeOperationRow("wb", "writeback", "pg.out", "failed", true, 3000, 3000, 900, false, true, "write 800 ms", null),
                NodeOperationRow("late", "materialize", "caller", "writing", false, 100, null, 50, null, false, null, null),
                // A commit the driver never confirmed before the node's deadline (R149-1).
                NodeOperationRow("unknown", "writeback", "pg.out", "failed", true, 3000, 3000, 1200, null, false, "write 1.1 s", null),
                // A node that failed AFTER its commit: the rows are durable and the row says so.
                NodeOperationRow("kept", "writeback", "pg.out", "failed", true, 3000, 3000, 1300, true, false, "write 1.2 s", null),
                NodeOperationRow("ddl", "statement", "no output", "completed", true, null, null, 5, null, false, "query 5 ms", null),
            )
        val html = engine.process("partials/execution-node-operations", webContext().apply { setVariable("nodeOperations", rows) })

        html shouldContain "data-node-operations"
        html shouldContain "<td class=\"u-mono\">stage_trips</td>"
        html shouldContain "<td class=\"u-mono\">tempdb.trips</td>"
        html shouldContain "12,453"
        html shouldContain "ds-badge-success\">committed</span>"
        html shouldContain "query 410 ms · fetch 3.4 s · write 1.5 s"
        html shouldContain "ds-badge-danger\">rolled back</span>"
        html shouldContain "data-node-id=\"late\" data-state=\"writing\""
        html shouldContain "(not observed to end)"
        // The unfinished row carries NO commit badge: nothing was observed. The failed node whose
        // commit WAS observed carries the success badge — two in total.
        (html.split("ds-badge-success").size - 1) shouldBe 2
        // The terminal row without commit evidence says "not observed" — never "not committed".
        html shouldContain "data-node-id=\"unknown\" data-state=\"failed\""
        (html.split("not observed</span>").size - 1) shouldBe 1
        (html.split("ds-badge-danger").size - 1) shouldBe 1
        html shouldNotContain "%"
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()
}
