package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.StoredResultView
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
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
 * The template-side extension of the 027 CSRF/htmx audit (StaticJsCsrfAuditTest
 * swept static JS `fetch` calls; 027b E2 extends it to RENDERED htmx attributes
 * in templates). Two failure shapes, one class — an htmx attribute that reaches
 * the browser as unprocessed text, so the mutation either DELETEs a nonsense
 * URL (404, silently) or never fires at all:
 *
 *  1. `hx-delete="${...}"` as a PLAIN attribute — only the standard Thymeleaf
 *     starter is on the classpath (modules/web/build.gradle.kts — no htmx
 *     dialect, no `addDialect` anywhere), so Thymeleaf emits the literal
 *     `${...}` text, htmx resolves it against the current page, and the real
 *     endpoint is never reached (027b E: the execution-detail Cancel button
 *     404'd and executions kept running).
 *  2. `th:hx-*` — NOT a processor any registered dialect defines. Thymeleaf
 *     3.1 leaves unknown `th:` attributes verbatim in the output, so no `hx-*`
 *     attribute exists for htmx to act on and the control is inert (027b:
 *     executions/detail.html's result loader and execution-result.html's
 *     Prev/Next pager buttons spun forever).
 *
 * The house pattern for resolved htmx attributes is `th:attr="hx-...=@{...}"`
 * (datasources.html, api-keys.html) — `@{...}` also prepends the context path,
 * which hand-built strings miss.
 *
 * Method, honest limits: a source-level regex sweep — NOT an HTML parser,
 * sufficient for this codebase's attribute-per-line authoring shape; and one
 * full RENDER of the page that carried the E defect, asserting the cancel
 * button comes out resolved. A mutating attribute whose value is a constant
 * needs no processing and passes by design.
 */
class TemplateHtmxRenderAuditTest {
    /** Source sweep: no un-processable htmx mutation attribute ships. */
    @Test
    fun `no template ships an htmx attribute thymeleaf cannot process`() {
        val templates = templateSources()
        templates.shouldNotBeEmpty()

        val violations =
            templates.flatMap { (name, source) ->
                buildList {
                    PLAIN_MUTATING_ATTR_WITH_EXPRESSION
                        .findAll(source)
                        .forEach {
                            add(
                                "$name: plain attribute `${it.value}` — Thymeleaf emits the literal \${...}; " +
                                    "use th:attr=\"${it.groupValues[1]}=@{...}\"",
                            )
                        }
                    UNKNOWN_TH_HX_ATTRIBUTE
                        .findAll(source)
                        .forEach {
                            add(
                                "$name: `${it.value.split("=")[0]}` is no processor — no htmx dialect is " +
                                    "registered; it renders verbatim and the control is inert",
                            )
                        }
                }
            }
        violations shouldBe emptyList()
    }

    /** Non-vacuity: the file that carried the E defect must be in the sweep. */
    @Test
    fun `the audit is grounded - executions detail is in scope`() {
        val names = templateSources().map { it.first }
        names shouldContain "templates/executions/detail.html"
        // The regexes must be ABLE to match the defect shapes they exist to catch.
        PLAIN_MUTATING_ATTR_WITH_EXPRESSION.containsMatchIn(
            """<button hx-delete="${'$'}{'/x/' + id + '/cancel'}">""",
        ) shouldBe true
        UNKNOWN_TH_HX_ATTRIBUTE.containsMatchIn("""<div th:hx-get="${'$'}{'/x'}">""") shouldBe true
    }

    /**
     * The E fix, asserted at the RENDER level: the cancel button's `hx-delete`
     * comes out as a resolved `@{...}` URL (context-pathed), and no `th:hx-*`
     * processor attribute or raw `${...}` survives anywhere in the page — the
     * result loader (`#result-content`) included.
     */
    @Test
    fun `execution detail renders a resolved cancel button and no unprocessed htmx attributes`() {
        val engine = templateEngine()
        val executionId = UUID.randomUUID()
        val html =
            engine
                .process(
                    "executions/detail",
                    WebContext(
                        JakartaServletWebApplication
                            .buildApplication(MockServletContext())
                            .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
                    ).apply { fillDetailModel(executionId) },
                )

        html shouldContain "hx-delete=\"/partials/executions/$executionId/cancel\""
        html shouldContain "hx-get=\"/partials/executions/$executionId/result\""
        html shouldNotContain "th:hx-"
        Regex("""hx-[a-z-]+="[^"]*\$\{""").containsMatchIn(html) shouldBe false
    }

    private fun WebContext.fillDetailModel(executionId: UUID) {
        // Layout variables (the same set DashboardPartialsRenderTest provides).
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", null)
        setVariable("activeTheme", "saas")
        // The detail model ExecutionDetailController builds — RUNNING + canCancel
        // renders the E-defect cancel button; resultState available renders the
        // result loader that shipped a dead th:hx-get.
        setVariable(
            "record",
            ExecutionRecord(
                executionId = executionId,
                pipelineId = UUID.randomUUID(),
                pipelineVersion = 1,
                status = ExecutionStatus.RUNNING,
                parametersJson = "{}",
                triggeredBy = UUID.randomUUID(),
                triggeredVia = ExecutionTrigger.REST,
                startedAt = Instant.parse("2026-08-30T14:30:00Z"),
                durationMs = null,
            ),
        )
        setVariable("family", emptyList<Any>())
        setVariable("correlationId", "corr-1")
        setVariable("canCancel", true)
        setVariable("nodeStats", emptyMap<String, Any>())
        setVariable("resultState", "available")
        setVariable("resultUrl", "/api/v1/executions/$executionId/result")
        setVariable(
            "resultView",
            StoredResultView(
                key = "result:$executionId",
                executionId = executionId,
                schema = listOf(ColumnSchema("n", LogicalType.INTEGER)),
                firstPage = emptyList(),
                totalRows = 2500,
                bytes = 26968,
                expiresAt = Instant.parse("2026-08-30T15:00:00Z"),
            ),
        )
    }

    private fun templateEngine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private fun templateSources(): List<Pair<String, String>> =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResources("classpath*:templates/**/*.html")
            .filter { it.filename != null }
            .map { "templates/" + it.url.path.substringAfter("templates/") to it.inputStream.readBytes().decodeToString() }

    private companion object {
        /** A PLAIN mutating htmx attribute whose value holds an expression — never processed. */
        val PLAIN_MUTATING_ATTR_WITH_EXPRESSION =
            Regex("""\b(hx-(?:post|put|patch|delete))="[^"]*\$\{""")

        /** th:hx-anything — no dialect in this build defines such a processor. */
        val UNKNOWN_TH_HX_ATTRIBUTE = Regex("""\bth:hx-[a-z-]+=""")
    }
}
