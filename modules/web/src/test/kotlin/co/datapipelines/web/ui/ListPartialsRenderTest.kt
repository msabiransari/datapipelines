package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.Dialect
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
 * The pipelines/templates list pages 500'd the moment their first row existed
 * (2026-08-31): the pagination buttons' `th:attr` left its literal values
 * UNQUOTED (`hx-target=#pipeline-list-wrapper`), and Thymeleaf's assignation
 * parser rejected the whole sequence — `Could not parse as assignation
 * sequence` — but only once a row made the `th:unless`-guarded pagination
 * block render, which is why the empty-state pages looked fine and the
 * controller-level tests (model only, no render) never saw it.
 *
 * The house pattern for htmx attributes stays `th:attr` (TemplateHtmxRenderAuditTest
 * forbids `th:hx-*`); the fix quotes the literals: `hx-target='#...',
 * hx-swap='outerHTML'`. These tests render both partials standalone with a
 * NON-EMPTY list — exactly what the controllers hand the engine — and assert
 * the pager buttons come out resolved.
 *
 * 029 adds the design-system pins: the tables render on `.ds-table` (inline
 * padding/zebra markup GONE), chips are `.ds-badge`, and the empty states use
 * the real `.ds-empty` primitive — `.ds-empty-state` is a class no CSS defines.
 */
class ListPartialsRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
            // #temporals is part of Thymeleaf 3.1's standard dialect — no extras dialect needed.
        }

    @Test
    fun `pipelines partial renders resolved pagination controls when rows exist`() {
        val html =
            engine.process(
                "partials/pipelines",
                webContext().apply { fillPipelinesModel(listOf(pipelineRecord())) },
            )

        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "hx-get=\"/partials/pipelines?q=&amp;offset=-25\""
        html shouldContain "hx-get=\"/partials/pipelines?q=&amp;offset=25\""
    }

    @Test
    fun `templates partial renders resolved pagination controls when rows exist`() {
        val html =
            engine.process(
                "partials/templates",
                webContext().apply { fillTemplatesModel(listOf(templateRecord())) },
            )

        html shouldContain "hx-target=\"#template-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "hx-get=\"/partials/templates"
    }

    @Test
    fun `the pending-release badge renders when a draft exists`() {
        // versioning §7: unreleased (often agent) work must be VISIBLE on the list. The
        // badge was silently absent from first paint once (035): the full-page controller
        // rendered the partial with its own model and no `drafts` attribute, which the
        // template's null-guard swallowed — this render test is the guard against exactly
        // that silent absence, in both the with-draft and without-draft shapes.
        val record = pipelineRecord()
        val draft =
            co.datapipelines.pipeline.PipelineVersionDetail(
                pipelineId = record.id,
                version = 2,
                status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = java.time.Instant.EPOCH,
                createdBy = record.ownerId,
            )
        val withDraft =
            engine.process(
                "partials/pipelines",
                webContext().apply {
                    fillPipelinesModel(listOf(record))
                    setVariable("drafts", mapOf(record.id to draft))
                },
            )
        withDraft shouldContain "draft v2 pending release"

        val withoutDraft =
            engine.process(
                "partials/pipelines",
                webContext().apply { fillPipelinesModel(listOf(record)) },
            )
        withoutDraft shouldNotContain "pending release"
    }

    @Test
    fun `pipelines partial renders the design-system table`() {
        val html =
            engine.process(
                "partials/pipelines",
                webContext().apply { fillPipelinesModel(listOf(pipelineRecord())) },
            )

        html shouldContain "<table class=\"ds-table\">"
        // The pipelines table renders no chips — there is nothing to convert to ds-badge.
        // The migration is only done when the inline table styles are GONE.
        html shouldNotContain "border-collapse: collapse"
        html shouldNotContain "padding: var(--gap-sm) var(--gap-md); text-align: left"
    }

    @Test
    fun `pipelines empty state uses the ds-empty primitive`() {
        val html =
            engine.process(
                "partials/pipelines",
                webContext().apply { fillPipelinesModel(emptyList()) },
            )

        html shouldContain "class=\"ds-empty\""
        html shouldContain "class=\"ds-empty-title\""
        html shouldNotContain "ds-empty-state" // a class with no CSS anywhere (D4)
    }

    @Test
    fun `templates partial renders the design-system table and dialect badge`() {
        val html =
            engine.process(
                "partials/templates",
                webContext().apply { fillTemplatesModel(listOf(templateRecord())) },
            )

        html shouldContain "<table class=\"ds-table\">"
        html shouldContain "ds-badge"
        // The migration is only done when the inline table styles are GONE.
        html shouldNotContain "border-collapse: collapse"
        html shouldNotContain "padding: var(--gap-sm) var(--gap-md); text-align: left"
    }

    @Test
    fun `templates empty state uses the ds-empty primitive`() {
        val html =
            engine.process(
                "partials/templates",
                webContext().apply { fillTemplatesModel(emptyList()) },
            )

        html shouldContain "class=\"ds-empty\""
        html shouldContain "class=\"ds-empty-title\""
        html shouldNotContain "ds-empty-state" // a class with no CSS anywhere (D4)
    }

    @Test
    fun `pipelines page wires a live search control into the stable swap root`() {
        val html = engine.process("pipelines/list", webContext().apply { fillPipelineList() })

        html shouldContain "id=\"pipeline-filter-q\""
        html shouldContain "hx-get=\"/partials/pipelines\""
        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "hx-trigger=\"input changed delay:300ms, search\""
        html shouldContain "id=\"pipeline-filter-spinner\""
        html shouldContain "hx-indicator=\"#pipeline-filter-spinner\""
        // D3: the inert, unimplementable datasource select is gone.
        html shouldNotContain "All Datasources"
    }

    @Test
    fun `templates page wires search and dialect controls that include each other by id`() {
        val html = engine.process("templates/list", webContext().apply { fillTemplateList() })

        html shouldContain "id=\"template-filter-q\""
        html shouldContain "id=\"template-filter-dialect\""
        // By ID, never by name — other name="dialect" inputs exist on these screens.
        html shouldContain "hx-include=\"#template-filter-dialect\""
        html shouldContain "hx-include=\"#template-filter-q\""
        html shouldContain "hx-target=\"#template-list-wrapper\""
        html shouldContain "id=\"template-filter-spinner\""
    }

    /** Layout chrome plus PipelineUiController's model, one row so the pager renders. */
    private fun WebContext.fillPipelineList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        fillPipelinesModel(listOf(pipelineRecord()))
    }

    /** Layout chrome plus TemplateUiController's model, one row so the pager renders. */
    private fun WebContext.fillTemplateList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", listOf("POSTGRES", "SQLITE"))
        setVariable("selectedDialect", "")
        fillTemplatesModel(listOf(templateRecord()))
    }

    private fun WebContext.fillPipelinesModel(rows: List<PipelineRecord>) {
        setVariable("pipelines", rows)
        setVariable("drafts", emptyMap<UUID, co.datapipelines.pipeline.PipelineVersionDetail>())
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 30)
    }

    private fun WebContext.fillTemplatesModel(rows: List<Template>) {
        setVariable("templates", rows)
        setVariable("drafts", emptyMap<String, co.datapipelines.templates.TemplateVersionDetail>())
        setVariable("q", "")
        setVariable("selectedDialect", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 30)
    }

    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/")
    }

    private fun pipelineRecord() =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = "my-pipeline",
            displayName = "My Pipeline",
            description = "A test pipeline",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            isDeleted = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-10T00:00:00Z"),
        )

    private fun templateRecord() =
        Template(
            id = "orders.sql",
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = "Orders",
            description = "A test template",
            body = "SELECT 1",
            createdAt = Instant.parse("2026-08-10T00:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
