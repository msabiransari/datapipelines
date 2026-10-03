package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.assertions.withClue
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
import java.util.UUID

/**
 * #376 — the History tab pinned at the RENDER (ParameterSetsRenderTest's mould): the section links name one canonical
 * URL each and mark the current one, the History arm renders the house table and NO live form, graph or body block (so
 * it can issue no evaluate), the pager pages the same partial, a row opens its record, and every recorded name — a
 * parameter, a datasource, a template, a principal — is escaped. The model decides what renders; the lens is applied
 * there (ParameterSetEvaluationsBrowseModelTest), so a hidden record is exactly one that is not in the model.
 */
class ParameterSetHistoryRenderTest {
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

    private val setId = UUID.randomUUID()
    private val evaluationId = UUID.randomUUID()

    private fun row(principal: String = "Ada") =
        ParameterSetEvaluationsBrowseModel.HistoryRow(
            id = evaluationId.toString(),
            started = "2026-10-02 23:30:00",
            startedIso = "2026-10-02T23:30:00.123Z",
            caller = "MCP",
            principal = principal,
            version = "v2",
            status = "TIMEOUT",
            statusTone = "bad",
            valid = "—",
            outcomes = 3,
            queries = 2,
            took = "30012 ms",
        )

    private fun workspace(
        tab: String,
        rows: List<ParameterSetEvaluationsBrowseModel.HistoryRow> = listOf(row()),
        viewedVersion: Int? = 2,
    ): String =
        engine.process(
            "parameter-sets/workspace",
            webContext("/parameter-sets/$setId").apply {
                setVariable("parameterSetId", setId)
                setVariable("parameterSetName", "acme/geo_filters")
                setVariable("parameterSetDisplayName", "Geo filters")
                setVariable("navCurrentPath", "acme/geo_filters")
                setVariable("activeTab", tab)
                setVariable("hasSelectedBody", true)
                setVariable("viewedVersion", viewedVersion)
                setVariable("viewedIsDraft", false)
                setVariable("viewedLabel", "v2 · released · current")
                setVariable("versions", emptyList<Any>())
                setVariable("canEvaluate", true)
                setVariable("parameterSetJson", "{\"name\":\"acme/geo_filters\"}")
                setVariable("workspaceJson", "{\"viewedVersion\":2}")
                history(this, rows, offset = 0, hasMore = false)
            },
        )

    private fun history(
        context: WebContext,
        rows: List<ParameterSetEvaluationsBrowseModel.HistoryRow>,
        offset: Int,
        hasMore: Boolean,
    ) {
        context.setVariable("historySetId", setId)
        context.setVariable("evaluations", rows)
        context.setVariable("historyOffset", offset)
        context.setVariable("historyPageSize", ParameterSetEvaluationsBrowseModel.PAGE_SIZE)
        context.setVariable("historyHasMore", hasMore)
        context.setVariable("historyNextOffset", offset + ParameterSetEvaluationsBrowseModel.PAGE_SIZE)
        context.setVariable("historyPreviousOffset", (offset - ParameterSetEvaluationsBrowseModel.PAGE_SIZE).coerceAtLeast(0))
    }

    private fun fragment(
        rows: List<ParameterSetEvaluationsBrowseModel.HistoryRow>,
        offset: Int = 0,
        hasMore: Boolean = false,
    ): String =
        engine.process(
            "partials/parameter-set-evaluations",
            setOf("history"),
            webContext("/partials/parameter-sets/$setId/evaluations").apply { history(this, rows, offset, hasMore) },
        )

    /** Markup only — the templates' explanatory comments are not the page. */
    private fun String.markup(): String = replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun `the section links name one canonical URL each - the version rides along and the current one is marked`() {
        val html = workspace("history")

        html shouldContain "href=\"/parameter-sets/$setId?version=2\""
        html shouldContain "href=\"/parameter-sets/$setId?version=2&amp;tab=history\""
        Regex("""data-ps-tab="history"[^>]*aria-current="page"""").containsMatchIn(html) shouldBe true
        Regex("""data-ps-tab="workspace"[^>]*aria-current""").containsMatchIn(html) shouldBe false
        withClue("with no viewed version the links carry none — never a null parameter") {
            workspace("workspace", viewedVersion = null) shouldContain "href=\"/parameter-sets/$setId?tab=history\""
        }
    }

    @Test
    fun `the History arm renders the house table and no live form, graph or body block`() {
        val html = workspace("history").markup()

        html shouldContain "data-ps-history"
        html shouldContain "class=\"dt-frame dt-scroll dt-nowrap\""
        html shouldContain "class=\"ds-table\""
        html shouldContain "id=\"ps-history-detail\""
        html shouldNotContain "id=\"ps-form-host\""
        html shouldNotContain "id=\"ps-graph\""
        html shouldNotContain "id=\"ps-data\""
        // The state block still rides — the client reads hasBody:false from it and mounts nothing.
        html shouldContain "id=\"ps-workspace\""
    }

    @Test
    fun `the Workspace arm renders the form and the graph and no history`() {
        val html = workspace("workspace").markup()

        html shouldContain "id=\"ps-form-host\""
        html shouldContain "id=\"ps-graph\""
        html shouldNotContain "data-ps-history"
        html shouldNotContain "id=\"ps-history\""
    }

    @Test
    fun `a row names its caller and status as data and opens its record through the detail partial`() {
        val html = fragment(listOf(row()))

        html shouldContain "data-evaluation-id=\"$evaluationId\""
        html shouldContain "data-caller=\"MCP\""
        html shouldContain "data-status=\"TIMEOUT\""
        html shouldContain "app-chip-bad"
        html shouldContain "hx-get=\"/partials/parameter-sets/$setId/evaluations/$evaluationId\""
        html shouldContain "hx-target=\"#ps-history-detail\""
    }

    @Test
    fun `the pager pages the same partial - previous and next by offset, none on a single page`() {
        val middle = fragment(listOf(row()), offset = 25, hasMore = true)

        middle shouldContain "hx-get=\"/partials/parameter-sets/$setId/evaluations?offset=0\""
        middle shouldContain "hx-get=\"/partials/parameter-sets/$setId/evaluations?offset=50\""
        middle shouldContain "hx-target=\"#ps-history\""
        fragment(listOf(row())).markup().let {
            it shouldNotContain "data-history-next"
            it shouldNotContain "data-history-previous"
        }
    }

    @Test
    fun `an empty first page says what the history holds - no pager`() {
        val html = fragment(emptyList()).markup()

        html shouldContain "No evaluations recorded"
        html shouldNotContain "data-history-next"
    }

    @Test
    fun `a principal's name is escaped in the table`() {
        val html = fragment(listOf(row(principal = "<img src=x onerror=alert(1)>")))

        html shouldNotContain "<img src=x"
        html shouldContain "&lt;img src=x"
    }

    @Test
    fun `the record detail escapes every recorded name and shows the attempts' stamps as stored`() {
        val hostile = "<script>alert(1)</script>"
        val html =
            engine.process(
                "partials/parameter-set-evaluation",
                setOf("detail"),
                webContext("/partials/parameter-sets/$setId/evaluations/$evaluationId").apply {
                    setVariable(
                        "evaluation",
                        ParameterSetEvaluationsBrowseModel.DetailHeader(row(), "refresh-$hostile", "parameter.evaluate.timeout", "—"),
                    )
                    setVariable(
                        "evaluationOutcomes",
                        listOf(ParameterSetEvaluationsBrowseModel.OutcomeRow(hostile, "error", "x.y", hostile)),
                    )
                    setVariable(
                        "evaluationQueries",
                        listOf(
                            ParameterSetEvaluationsBrowseModel.QueryRow(
                                hostile,
                                hostile,
                                "$hostile v1",
                                "23:30:00.120",
                                "23:30:00.125",
                                "—",
                                "TIMEOUT",
                                "—",
                                "—",
                            ),
                        ),
                    )
                },
            )

        html shouldNotContain "<script>alert"
        html shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
        html shouldContain "data-outcome-code"
        html shouldContain "parameter.evaluate.timeout"
        html shouldContain ">23:30:00.125<"
        html shouldContain "data-outcome=\"TIMEOUT\""
    }

    private fun webContext(path: String): WebContext {
        val request = MockHttpServletRequest()
        request.requestURI = path
        return WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(request, MockHttpServletResponse()),
        ).withRoles().apply {
            setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
            setVariable("workspaceHeaderFragment", "")
            setVariable("workspaceOptions", emptyList<Any>())
            setVariable("activeWorkspace", "acme")
            setVariable("activeTheme", "saas")
            setVariable("authenticated", true)
            setVariable("currentPath", path)
            setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        }
    }
}
