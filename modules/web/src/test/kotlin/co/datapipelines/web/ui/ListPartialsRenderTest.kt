package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.string.shouldContain
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
        val pipeline =
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

        val html =
            engine.process(
                "partials/pipelines",
                webContext().apply {
                    setVariable("pipelines", listOf(pipeline))
                    setVariable("q", "")
                    setVariable("offset", 0)
                    setVariable("hasMore", true)
                    setVariable("total", 30)
                },
            )

        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "hx-get=\"/partials/pipelines?q=&amp;offset=-25\""
        html shouldContain "hx-get=\"/partials/pipelines?q=&amp;offset=25\""
    }

    @Test
    fun `templates partial renders resolved pagination controls when rows exist`() {
        val template =
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

        val html =
            engine.process(
                "partials/templates",
                webContext().apply {
                    setVariable("templates", listOf(template))
                    setVariable("q", "")
                    setVariable("selectedDialect", "")
                    setVariable("offset", 0)
                    setVariable("hasMore", true)
                },
            )

        html shouldContain "hx-target=\"#template-list-wrapper\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "hx-get=\"/partials/templates"
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
