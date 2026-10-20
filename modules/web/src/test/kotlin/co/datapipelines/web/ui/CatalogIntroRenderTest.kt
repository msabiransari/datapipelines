package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
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
 * #588 — one wording on the five family catalogs' intro sentence. Since #554 the family trees
 * live in the resource browser column (`#app-browser`, opened by each catalog's Browse folders
 * button), so every catalog's visible intro names "the resource browser's <Family> tree" and
 * none of them still says "the sidebar's" — the Parameter Sets sentence is the model.
 *
 * The assertion reads the RENDERED page's text nodes (Jsoup `text()`): comments are dropped by
 * the parser and attribute values are not text, so the comment blocks documenting the move at
 * length cannot satisfy the pin, and a "sidebar" riding on an attribute cannot fail it —
 * the issue's rule is about what a reader SEES (code names such as `js/tree/sidebar.mjs` are
 * identifiers, out of scope). "Resource browser" in the text is the non-vacuity floor: an
 * absence-only assertion would stay green if the sentence were deleted outright.
 */
class CatalogIntroRenderTest {
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

    // ------------------------------------------------------------------- the five catalogs

    @Test
    fun `pipelines - the intro names the resource browser's tree and no visible text says sidebar`() {
        val text = visibleText(render("pipelines/list") { fillPipelines() })

        hasNoSidebar(text) shouldBe true
        text shouldContain "resource browser"
        text shouldContain "Browse the folders in the resource browser's Pipelines tree"
    }

    @Test
    fun `templates - the intro names the resource browser's tree and no visible text says sidebar`() {
        val text = visibleText(render("templates/list") { fillTemplates() })

        hasNoSidebar(text) shouldBe true
        text shouldContain "resource browser"
        text shouldContain "Browse the folders in the resource browser's Templates tree"
    }

    @Test
    fun `dashboards - the intro names the resource browser's tree and no visible text says sidebar`() {
        val text = visibleText(render("dashboards/list") { fillDashboards() })

        hasNoSidebar(text) shouldBe true
        text shouldContain "resource browser"
        text shouldContain "Browse the folders in the resource browser's Dashboards tree"
    }

    @Test
    fun `visualizations - the intro names the resource browser's tree and no visible text says sidebar`() {
        val text = visibleText(render("visualizations/list") { fillVisualizations() })

        hasNoSidebar(text) shouldBe true
        text shouldContain "resource browser"
        text shouldContain "Browse the folders in the resource browser's Visualizations tree"
    }

    @Test
    fun `parameter sets - the intro names the resource browser's tree and no visible text says sidebar`() {
        val text = visibleText(render("parameter-sets/list") { fillParameterSets() })

        hasNoSidebar(text) shouldBe true
        text shouldContain "resource browser"
        text shouldContain "Browse the folders in the resource browser's Parameter Sets tree"
    }

    // ------------------------------------------------------------------------- assertions

    /** The issue's rule over the page's whole visible text, the layout's rail included. */
    private fun hasNoSidebar(text: String): Boolean = !text.contains("sidebar", ignoreCase = true)

    /** Text nodes only: the parser drops comments, and attribute values are not text. */
    private fun visibleText(html: String): String = Jsoup.parse(html).text()

    // ------------------------------------------------------- the render and the page fills

    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = engine.process(view, context().apply(fill))

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles().apply {
            setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
            setVariable("workspaceHeaderFragment", "")
            setVariable("workspaceOptions", emptyList<Any>())
            setVariable("activeWorkspace", "acme")
            setVariable("activeTheme", "saas")
            setVariable("authenticated", true)
            setVariable("scopes", setOf("ADMIN"))
            setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        }

    // Each page's fill is the full-page model its render test pins (PipelineExplorerRenderTest,
    // TemplateExplorerRenderTest, DashboardUiRenderTest, VisualizationUiRenderTest,
    // ParameterSetsRenderTest), reduced to the one row the assertions need.

    private fun WebContext.fillPipelines() {
        setVariable("currentPath", "/pipelines")
        setVariable("searching", true)
        setVariable("rootId", PipelineBrowseModel.CATALOG_ROOT_ID)
        setVariable("pipelines", listOf(pipeline()))
        setVariable("drafts", emptyMap<UUID, Any>())
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
    }

    private fun WebContext.fillTemplates() {
        setVariable("currentPath", "/templates")
        setVariable("searching", true)
        setVariable("rootId", TemplateBrowseModel.CATALOG_ROOT_ID)
        setVariable("templates", listOf(template()))
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("q", "")
        setVariable("selectedDialect", "")
        setVariable("selectedType", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("types", TemplateType.WIRE_VALUES)
        setVariable("namePattern", TemplateNameGrammar.pattern)
        setVariable("nameMaxLength", TemplateNameGrammar.maxLength)
        setVariable("nameHint", TemplateNameGrammar.DESCRIPTION)
        setVariable("skeleton", TransformSkeleton)
        setVariable("canAuthor", true)
    }

    private fun WebContext.fillDashboards() {
        setVariable("currentPath", "/dashboards")
        setVariable("q", "")
        setVariable("lensUnavailable", null)
        setVariable("rootId", DashboardBrowseModel.PAGE_ROOT_ID)
        setVariable("dashboards", listOf(board()))
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("searching", true)
    }

    private fun WebContext.fillVisualizations() {
        setVariable("currentPath", "/visualizations")
        setVariable("q", "")
        setVariable("searching", true)
        setVariable("rootId", VisualizationBrowseModel.PAGE_ROOT_ID)
        setVariable("lensUnavailable", null)
        setVariable("offset", 0)
        setVariable("visualizations", listOf(vizLeaf()))
        setVariable("hasMore", false)
        setVariable("total", 1)
    }

    private fun WebContext.fillParameterSets() {
        setVariable("currentPath", "/parameter-sets")
        setVariable("lifecycle", "")
        setVariable("lifecycleLabel", null)
        setVariable("searching", true)
        setVariable("rootId", ParameterSetsBrowseModel.CATALOG_ROOT_ID)
        setVariable("q", "")
        setVariable("parameterSets", listOf(setLeaf()))
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("lensUnavailable", null)
    }

    // Fixtures (the exemplar suites' shapes).

    private val actor = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val vizId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val setId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    private fun pipeline() =
        PipelineRecord(
            id = boardId,
            name = "nyc/mobility/revenue_by_borough",
            displayName = "Revenue by borough",
            description = "Fixture.",
            ownerId = actor,
            currentVersion = 2,
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            updatedAt = Instant.parse("2026-09-02T10:00:00Z"),
        )

    private fun template() =
        Template(
            id = "nyc/mobility/revenue_by_borough",
            version = 1,
            type = TemplateType.SQL,
            dialect = Dialect.POSTGRES,
            displayName = "Revenue by borough",
            description = "Fixture.",
            body = "SELECT 1",
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            createdBy = actor,
            status = PipelineVersionStatus.RELEASED,
        )

    private fun board() = DashboardBrowseModel.DashboardLeafView(boardId, "acme/dashboards/revenue", "revenue", "Revenue", 3, true)

    private fun vizLeaf() = VisualizationBrowseModel.LeafView(vizId, "acme/charts/revenue", "revenue", "Revenue", 3, released = true)

    private fun setLeaf() = ParameterSetsBrowseModel.ParameterSetLeafView(setId, "acme/geo_filters", "geo_filters", "Geo filters", 3, true)
}
