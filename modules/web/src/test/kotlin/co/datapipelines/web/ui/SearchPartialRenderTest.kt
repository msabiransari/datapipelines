package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.Dialect
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
import java.time.Instant
import java.util.UUID

/**
 * 161 — `partials/search.html`, the palette's answer, pinned at the render: three groups in
 * order, every hit a real LINK to the page it names (the destination's own screen governs the
 * role there), executions carrying the status chip's `data-status` and the started-at stamp,
 * the "more…" rows carrying the query to the FILTERED list page, and the two honest edges —
 * the one-sentence empty state, and a blank query rendering nothing at all.
 *
 * The model's side (scoping, caps, forks) is [SearchControllerTest]'s subject; this file
 * fills the same attribute names by hand and asks what the markup does with them.
 */
class SearchPartialRenderTest {
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

    private fun render(vararg pairs: Pair<String, Any?>): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        pairs.forEach { context.setVariable(it.first, it.second) }
        // Thymeleaf 3 keeps HTML comments in the output; the fragment's own documentation
        // mentions role="option"/role="status" and would defeat the negative assertions.
        // The house strip (RoleVisibilityRenderTest's COMMENT).
        return COMMENT.replace(engine.process("partials/search", context), "")
    }

    private fun pipeline(name: String) =
        PipelineRecord(
            UUID.randomUUID(),
            name,
            name.substringAfterLast('/'),
            "",
            UUID.randomUUID(),
            1,
            Instant.EPOCH,
            Instant.EPOCH,
        )

    private fun template(id: String) =
        Template(
            id = id,
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = id,
            description = "",
            body = "SELECT 1",
            createdAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
        )

    private fun execution() =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 3,
            status = ExecutionStatus.FAILED,
            parametersJson = "{}",
            executedBy = UUID.randomUUID(),
            triggeredVia = ExecutionTrigger.UI,
            startedAt = Instant.parse("2026-09-18T10:24:00Z"),
        )

    private fun fullModel(): Array<Pair<String, Any?>> =
        arrayOf(
            "q" to "rev",
            "asked" to true,
            "empty" to false,
            "pipelineHits" to listOf(pipeline("nyc/mobility/revenue")),
            "pipelinesMore" to true,
            "templateHits" to listOf(template("acme/finance/revenue")),
            "templatesMore" to false,
            "executionHits" to listOf(execution()),
            "executionsMore" to false,
            "executionNames" to emptyMap<Any, Any>(),
            "statusMatch" to null,
            "pipelineMatch" to null,
        )

    private companion object {
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }

    @Test
    fun `three groups in order, rows rendered as options`() {
        val html = render(*fullModel())

        // Group order: Pipelines, then Templates, then Executions.
        val p = html.indexOf("Pipelines</p>")
        val t = html.indexOf("Templates</p>")
        val e = html.indexOf("Executions</p>")
        (0 <= p && p < t && t < e) shouldBe true

        // The rows are the listbox's options — the input's aria-activedescendant walk
        // addresses them.
        Regex("role=\"option\"").findAll(html).count() shouldBe 4 // 3 hits + the pipelines more row
    }

    @Test
    fun `each hit's href names its destination`() {
        val html = render(*fullModel())

        html shouldContain Regex("href=\"/pipelines/[0-9a-f-]{36}/editor\"")
        html shouldContain "href=\"/templates/editor?name=acme/finance/revenue\""
        html shouldContain Regex("href=\"/executions/[0-9a-f-]{36}\"")
    }

    @Test
    fun `the more rows link the filtered list page with the query`() {
        val html = render(*fullModel())

        html shouldContain "href=\"/pipelines?q=rev\""
        html shouldNotContain "href=\"/templates?q=rev\"" // templatesMore is false — no row
        // No single status or pipeline named, and executionsMore false: no executions more row.
        html shouldNotContain "href=\"/executions?"
    }

    @Test
    fun `executions carry the status enum and the started-at stamp`() {
        val html = render(*fullModel())

        html shouldContain "data-status=\"FAILED\""
        html shouldContain ">Failed</span>"
        // The stamp is the history table's format (#temporals on the server clock — its
        // ZONE is the deployment's, so the pattern is the assertion, not a literal time).
        (Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(html)) shouldBe true
    }

    @Test
    fun `no match anywhere is one sentence`() {
        val html =
            render(
                "q" to "zzz",
                "asked" to true,
                "empty" to true,
                "pipelineHits" to emptyList<Any>(),
                "pipelinesMore" to false,
                "templateHits" to emptyList<Any>(),
                "templatesMore" to false,
                "executionHits" to emptyList<Any>(),
                "executionsMore" to false,
                "executionNames" to emptyMap<Any, Any>(),
                "statusMatch" to null,
                "pipelineMatch" to null,
            )

        html shouldContain "No pipelines, templates or executions match"
        html shouldContain ">zzz<"
        html shouldNotContain "role=\"option\""
    }

    @Test
    fun `a blank query renders nothing - the palette's hint, not the server's, is the empty state`() {
        val html =
            render(
                "q" to "",
                "asked" to false,
                "empty" to false,
                "pipelineHits" to emptyList<Any>(),
                "pipelinesMore" to false,
                "templateHits" to emptyList<Any>(),
                "templatesMore" to false,
                "executionHits" to emptyList<Any>(),
                "executionsMore" to false,
                "executionNames" to emptyMap<Any, Any>(),
                "statusMatch" to null,
                "pipelineMatch" to null,
            )

        html shouldNotContain "role=\"option\""
        html shouldNotContain "app-search-empty"
    }
}
