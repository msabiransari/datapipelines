package co.datapipelines.web.ui

import co.datapipelines.datasources.Datasource
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.StoredResultView
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
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
                    webContext().apply { fillDetailModel(executionId) },
                )

        html shouldContain "hx-delete=\"/partials/executions/$executionId/cancel\""
        html shouldContain "hx-get=\"/partials/executions/$executionId/result\""
        html shouldNotContain "th:hx-"
        Regex("""hx-[a-z-]+="[^"]*\$\{""").containsMatchIn(html) shouldBe false
    }

    /**
     * An unquoted literal inside a th:attr assignation sequence (`hx-target=#id`)
     * makes Thymeleaf reject the WHOLE sequence at render time — "Could not parse
     * as assignation sequence" — but only once the block containing it renders,
     * which is why the empty-state pages looked fine while both list screens 500'd
     * on their first row (2026-08-31 hotfix). Source-level, because the render that
     * would catch it needs a non-empty model on every screen.
     */
    @Test
    fun `no th-attr sequence carries an unquoted literal value`() {
        val violations =
            templateSources().flatMap { (name, source) ->
                TH_ATTR_VALUE
                    .findAll(source)
                    .flatMap { m -> splitAssignations(m.groupValues[1]) }
                    .filter { it.isNotBlank() && !isProcessableValue(it.substringAfter('=', "")) }
                    .map { "$name: `$it` — the literal must be quoted: `${it.substringBefore('=')}='…'`" }
            }
        violations shouldBe emptyList()
    }

    /** Non-vacuity: the guard must be ABLE to see the shape it exists to catch. */
    @Test
    fun `the unquoted-assignation guard can go red`() {
        val bad = """th:attr="hx-get=@{/partials/pipelines}, hx-target=#pipeline-list-wrapper""""
        val good = """th:attr="hx-get=@{/partials/pipelines}, hx-target='#pipeline-list-wrapper'""""
        offenders(bad).shouldNotBeEmpty()
        offenders(good) shouldBe emptyList()
    }

    private fun offenders(source: String): List<String> =
        TH_ATTR_VALUE
            .findAll(source)
            .flatMap { m -> splitAssignations(m.groupValues[1]) }
            .filter { it.isNotBlank() && !isProcessableValue(it.substringAfter('=', "")) }
            .toList()

    /**
     * D1: `th:replace` REMOVES the host element, so an id written on the host div
     * never reaches the DOM. Both list pages targeted an id that no rendered page
     * produced, and their pagers matched nothing from first paint. Source-level
     * checks cannot see this — only the rendered output can.
     */
    @Test
    fun `every hx-target id a rendered list page references exists in that page`() {
        val missing =
            listOf(
                "pipelines/list" to { c: WebContext -> c.fillPipelineList() },
                "templates/list" to { c: WebContext -> c.fillTemplateList() },
                "datasources/list" to { c: WebContext -> c.fillDatasourceList() },
            ).flatMap { (view, fill) ->
                // Comments are stripped first: the templates document their own contract in
                // prose ("The ROOT carries id=..."), and an id scraped out of a COMMENT makes
                // the guard green while the markup produces nothing (found by falsification).
                val html =
                    templateEngine()
                        .process(view, webContext().apply { fill(this) })
                        .replace(Regex("<!--[\\s\\S]*?-->"), "")
                val referenced =
                    Regex("""hx-target="#([A-Za-z0-9_-]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
                referenced.shouldNotBeEmpty()
                val produced =
                    Regex("""id="([A-Za-z0-9_-]+)"""").findAll(html).map { it.groupValues[1] }.toSet()
                (referenced - produced - LAYOUT_PROVIDED_IDS).map { "$view: #$it" }
            }
        missing shouldBe emptyList()
    }

    /** Layout chrome (the UiWorkspaceAdvice set) every page render needs. */
    private fun WebContext.fillLayoutChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/")
    }

    /** PipelineUiController's model — ONE row, or the pager never renders and the guard is vacuous. */
    private fun WebContext.fillPipelineList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        setVariable(
            "pipelines",
            listOf(
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
                ),
            ),
        )
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 30)
    }

    /** TemplateUiController's model — ONE row, or the pager never renders and the guard is vacuous. */
    private fun WebContext.fillTemplateList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        setVariable("selectedDialect", "")
        setVariable(
            "templates",
            listOf(
                Template(
                    id = "orders.sql",
                    version = 1,
                    dialect = Dialect.POSTGRES,
                    displayName = "Orders",
                    description = "A test template",
                    body = "SELECT 1",
                    createdAt = Instant.parse("2026-08-10T00:00:00Z"),
                    createdBy = UUID.randomUUID(),
                ),
            ),
        )
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 30)
    }

    /** DatasourceUiController's model — ONE row, or the pager never renders and the guard is vacuous. */
    private fun WebContext.fillDatasourceList() {
        fillLayoutChrome()
        setVariable("scopes", setOf("READ"))
        setVariable("dialects", emptyList<String>())
        setVariable("selectedDialect", "")
        setVariable("isAdmin", false)
        setVariable("memberDatasourcesEnabled", false)
        setVariable("canRegister", false)
        setVariable("bindingHint", "")
        setVariable(
            "datasources",
            listOf(
                Datasource(
                    name = "pg-prod",
                    displayName = "Production Postgres",
                    description = null,
                    dialect = Dialect.POSTGRES,
                    jdbcUrl = "jdbc:postgresql://db:5432/app",
                    username = "readonly",
                ),
            ),
        )
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 30)
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

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

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

        /** The raw value of every th:attr, captured for assignation-level checking. */
        val TH_ATTR_VALUE = Regex("""th:attr="([^"]*)"""")

        /** Ids the layout provides when a fragment renders standalone (the toast stack). */
        val LAYOUT_PROVIDED_IDS = setOf("toast")

        /** Split on commas that are not inside @{...}, ${...} or '...'. */
        fun splitAssignations(value: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var quoted = false
            for (c in value) {
                when {
                    c == '\'' -> {
                        quoted = !quoted
                        current.append(c)
                    }

                    quoted -> {
                        current.append(c)
                    }

                    c == '{' -> {
                        depth++
                        current.append(c)
                    }

                    c == '}' -> {
                        depth--
                        current.append(c)
                    }

                    c == ',' && depth == 0 -> {
                        parts.add(current.toString().trim())
                        current.clear()
                    }

                    else -> {
                        current.append(c)
                    }
                }
            }
            parts.add(current.toString().trim())
            return parts
        }

        /** A value Thymeleaf can evaluate: an expression, a quoted literal, or a boolean. */
        fun isProcessableValue(raw: String): Boolean {
            val v = raw.trim()
            return v.startsWith("'") ||
                v.startsWith("@{") || v.startsWith("$" + "{") || v.startsWith("#{") ||
                v.startsWith("|") || v == "true" || v == "false"
        }
    }
}
