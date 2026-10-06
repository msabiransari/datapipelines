package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateVersionDetail
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
 * Render-level guard for the templates CATALOG page and its fragments (#398 — this class was
 * the explorer page's; the page it guarded is the catalog now, and its detail-pane guards
 * moved with the pane's capabilities onto the workspace's tabs).
 *
 * What is pinned here, at the byte level the browser receives:
 *  - the two-pane explorer is GONE — the page carries no pane, no divider and no drawer, and
 *    its other door is the sidebar tree's reveal;
 *  - the tree's ARIA shape (a root level is `role=tree`, a nested level a `role=group`);
 *  - the used-by fragment (#320's three arms, the "nothing pins it" sentence) — the
 *    workspace's Used by tab is its only page now;
 *  - the create modal (Q1(b), the four types, the transform blocks);
 *  - the `needs_review` marker on the rows that carry it;
 *  - the type filter's exact binding (the falsification anchor at the binding the controller
 *    uses).
 *
 * Engine infra mirrors [TemplateTreeRenderTest] (same WebContext shape, comments stripped —
 * the absences this class guards are documented in the markup's own comments, and a comment
 * is not an affordance).
 */
class TemplateExplorerRenderTest {
    // ---------------------------------------------------------------- the catalog page

    @Test
    fun `the catalog page carries no pane, no divider and no drawer - the workspace is the one place`() {
        val html = render("templates/list") { fillPage() }

        // The #398 retirement, pinned at the page level: a second tree or a detail pane would
        // make two places where a template is read.
        html shouldNotContain "tplx-body"
        html shouldNotContain "tplx-splitter"
        html shouldNotContain "tplx-detail"
        html shouldNotContain "data-explorer-pane"
        html shouldNotContain "data-explorer-drawer-open"
        html shouldNotContain "#template-detail"
        // The list root is the page's stable swap target, and the reveal opens the SIDEBAR.
        html shouldContain "id=\"template-list-wrapper\""
        html shouldContain "data-nav-tree-reveal=\"templates\""
        html shouldContain "aria-controls=\"nav-tree-templates\""
    }

    @Test
    fun `the catalog page loads the create modal's script and the search keeps its stable target`() {
        val html = render("templates/list") { fillPage() }

        html shouldContain "id=\"template-filter-q\""
        html shouldContain "hx-target=\"#template-list-wrapper\""
        html shouldContain "/js/template-create-modal.js"
        html shouldContain "data-action=\"template-create-open\""
    }

    // ---------------------------------------------------------------- the tree's ARIA shape

    @Test
    fun `the root level is a tree and a nested level is a group under its folder`() {
        val root = render("partials/template-tree-level") { fillLevel() }
        val nested = render("partials/template-tree-level") { fillNestedLevel() }

        root shouldContain "<ul class=\"tpl-tree\""
        root shouldContain "role=\"tree\""
        root shouldContain "aria-label=\"Templates\""
        nested shouldNotContain "role=\"tree\""
        nested shouldContain "role=\"group\""
    }

    @Test
    fun `a leaf is a navigating link carrying the leaf id and the path on title`() {
        val html = render("partials/template-tree-level") { fillNestedLevel() }

        html shouldContain "<a class=\"tpl-leaf\" role=\"treeitem\" aria-selected=\"false\" hx-boost=\"true\""
        html shouldContain "href=\"/templates/acme/finance/monthly_revenue.sql\""
        html shouldContain "data-leaf-id=\"acme/finance/monthly_revenue.sql\""
        html shouldContain "title=\"acme/finance/monthly_revenue.sql\""
        // The selection machinery is gone with the pane.
        html shouldNotContain "hx-get=\"/partials/templates/versions"
        html shouldNotContain "data-editor-url"
    }

    @Test
    fun `077 - the ROOT level renders folders only, never a leaf`() {
        // The fixture carries a leaf anyway: the model never queries the root's leaves, and
        // the ROOT id is what proves this is the root level.
        val root = render("partials/template-tree-level") { fillLevel() }

        root shouldNotContain "tpl-leaf"
        root shouldContain "tpl-folder"
    }

    @Test
    fun `a search row is the same navigating link, in a listbox on the nav instance`() {
        val nav = render("partials/template-search") { fillSearch(TemplateListScope.NAV) }
        val catalog = render("partials/template-search") { fillSearch(TemplateListScope.CATALOG) }

        nav shouldContain "role=\"option\""
        nav shouldContain "aria-selected=\"false\""
        catalog shouldNotContain "role=\"option\""
        listOf(nav, catalog).forEach {
            it shouldContain "href=\"/templates/$DEEP_PATH\""
            it shouldContain "data-leaf-id=\"$DEEP_PATH\""
            it shouldContain "title=\"$DEEP_PATH\""
        }
    }

    // ---------------------------------------------------------------- the used-by fragment

    @Test
    fun `320 - the used-by fragment names the parameter sets and the visualizations that pin the template`() {
        val html =
            render("partials/template-used-by") {
                fillUsedBy(
                    pipelines =
                        listOf(
                            pin("acme/rollup", 1),
                            pin("acme/rollup", 2),
                        ),
                    sets = listOf(setPin("acme/sales/region_filters", 1)),
                    visualizations = listOf(visualizationPin("acme/charts/revenue", 1)),
                )
            }

        // One row per PIN: two nodes of one pipeline are two facts (the collapse would hide
        // which node to go and change); the sets and the visualizations are rows of the same
        // card (#320), and the links are the CANONICAL workspace, not the editor redirect.
        html shouldContain "data-used-by-set"
        html shouldContain "data-used-by-visualization"
        // The canonical workspace is UUID-addressed; the /editor redirect hop is gone.
        html shouldContain "href=\"/pipelines/"
        html shouldContain "pins v1"
        html shouldContain "pins v2"
        html shouldNotContain "/editor"
    }

    @Test
    fun `320 - with no pin from any kind the used-by fragment says nothing pins the template`() {
        val html = render("partials/template-used-by") { fillUsedBy() }

        html shouldContain "Nothing pins this template"
    }

    // ---------------------------------------------------------------- the create modal

    @Test
    fun `the create modal offers the four types and carries the transform blocks, hidden and disabled`() {
        val html =
            render("templates/list") {
                fillPage()
                setVariable("transformTypes", "jsonata,javascript")
            }

        TemplateType.WIRE_VALUES.forEach { ty ->
            html shouldContain "value=\"$ty\""
        }
        html shouldContain "id=\"create-template-blocks-field\" hidden"
        // Disabled, not merely hidden: a hidden-but-enabled control still posts.
        html shouldContain "id=\"create-template-contract\" name=\"contract\" class=\"ds-input app-template-body-input\" disabled"
    }

    @Test
    fun `a transform leaf and search row show their language as the type badge, with no dialect`() {
        val tree =
            render("partials/template-tree-level") {
                fillNestedLevel()
                setVariable("templates", listOf(template("acme/finance/row_pick", TemplateType.JSONATA)))
            }
        val search =
            render("partials/template-search") {
                fillSearch()
                setVariable("templates", listOf(template(DEEP_PATH, TemplateType.JSONATA)))
            }

        listOf(tree, search).forEach {
            it shouldContain ">jsonata</span>"
            it shouldNotContain ">POSTGRES</span>"
        }
    }

    @Test
    fun `the needs-review marker renders only when its flag is set - tree and search rows`() {
        render("partials/template-tree-level") { fillNestedLevel() } shouldNotContain "data-needs-review"
        render("partials/template-tree-level") {
            fillNestedLevel()
            setVariable("needsReviewIds", setOf("acme/finance/monthly_revenue.sql"))
        } shouldContain "data-needs-review"
        render("partials/template-search") { fillSearch() } shouldNotContain "data-needs-review"
        render("partials/template-search") {
            fillSearch()
            setVariable("needsReviewIds", setOf(DEEP_PATH))
        } shouldContain "data-needs-review"
    }

    /**
     * The filter FALSIFICATION at the binding the partial uses: `jsonata` binds to exactly the
     * JSONATA type (the repository's exact-match filter), never to `html` — and an unknown
     * value binds to nothing, which shows everything rather than nothing (the dialect filter's
     * long-standing rule).
     */
    @Test
    fun `the type filter binds jsonata to JSONATA exactly, and an unknown value to no filter`() {
        TemplateFilters.type("jsonata") shouldBe TemplateType.JSONATA
        TemplateFilters.type("javascript") shouldBe TemplateType.JAVASCRIPT
        TemplateFilters.type("JSONata") shouldBe TemplateType.JSONATA
        TemplateFilters.type("jsonnet") shouldBe null
        (TemplateFilters.type("jsonata") == TemplateType.HTML) shouldBe false
    }

    // ------------------------------------------------------------------ fixtures

    private fun WebContext.fillLevel() {
        setVariable("searching", false)
        setVariable("scope", TemplateListScope.NAV.wire)
        setVariable("prefix", "")
        setVariable("levelId", TemplateBrowseModel.ROOT_LEVEL_ID)
        setVariable(
            "folders",
            listOf(
                TemplateFolderView("acme", "acme", 12, TemplateBrowseModel.levelId("acme")),
            ),
        )
        setVariable("foldersTruncated", false)
        setVariable("templates", emptyList<Template>())
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 0)
        setVariable("selectedDialect", "")
        setVariable("selectedType", "")
    }

    /** A NESTED level — the only kind that has leaves now. */
    private fun WebContext.fillNestedLevel() {
        fillLevel()
        setVariable("prefix", "acme/finance")
        setVariable("levelId", TemplateBrowseModel.levelId("acme/finance"))
        setVariable("folders", emptyList<TemplateFolderView>())
        setVariable("templates", listOf(template("acme/finance/monthly_revenue.sql")))
        setVariable("total", 1)
    }

    private fun WebContext.fillSearch(scope: TemplateListScope = TemplateListScope.NAV) {
        setVariable("searching", true)
        setVariable("scope", scope.wire)
        setVariable("rootId", scope.rootId)
        setVariable("templates", listOf(template(DEEP_PATH)))
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("q", "revenue")
        setVariable("selectedDialect", "")
        setVariable("selectedType", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 4)
    }

    private fun WebContext.fillUsedBy(
        pipelines: List<co.datapipelines.pipeline.TemplatePin> = emptyList(),
        sets: List<co.datapipelines.parameters.ParameterSetPin> = emptyList(),
        visualizations: List<co.datapipelines.visualization.ArtifactPin> = emptyList(),
    ) {
        setVariable("usedBy", pipelines)
        setVariable("usedByCount", pipelines.map { it.pipelineId }.distinct().size)
        setVariable("usedBySets", sets)
        setVariable("usedByVisualizations", visualizations)
        setVariable("usedBySummary", if (pipelines.isEmpty() && sets.isEmpty() && visualizations.isEmpty()) "nothing" else "1 pipeline")
    }

    private fun WebContext.fillPage() {
        fillChrome()
        fillSearch(TemplateListScope.CATALOG)
        setVariable("q", "")
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("types", TemplateType.WIRE_VALUES)
        setVariable("namePattern", co.datapipelines.templates.TemplateNameGrammar.pattern)
        setVariable("nameMaxLength", co.datapipelines.templates.TemplateNameGrammar.maxLength)
        setVariable("nameHint", co.datapipelines.templates.TemplateNameGrammar.DESCRIPTION)
        setVariable("skeleton", TransformSkeleton)
        setVariable("canAuthor", true)
    }

    private fun WebContext.fillChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun pin(
        pipelineName: String,
        version: Int,
    ): co.datapipelines.pipeline.TemplatePin =
        co.datapipelines.pipeline.TemplatePin(
            pipelineId = java.util.UUID.randomUUID(),
            pipelineName = pipelineName,
            nodeId = "n$version",
            pipelineVersion = version,
            versionStatus = PipelineVersionStatus.RELEASED,
            pinnedVersion = version,
        )

    private fun setPin(
        setName: String,
        version: Int,
    ): co.datapipelines.parameters.ParameterSetPin =
        co.datapipelines.parameters.ParameterSetPin(
            setId = java.util.UUID.randomUUID(),
            setName = setName,
            parameter = "region",
            setVersion = version,
            versionStatus = PipelineVersionStatus.RELEASED,
            pinnedVersion = version,
        )

    private fun visualizationPin(
        name: String,
        version: Int,
    ): co.datapipelines.visualization.ArtifactPin =
        co.datapipelines.visualization.ArtifactPin(
            artifactId = java.util.UUID.randomUUID(),
            name = name,
            version = version,
            status = PipelineVersionStatus.RELEASED,
            pinnedVersion = version,
        )

    private fun template(
        id: String,
        type: TemplateType = TemplateType.SQL,
    ) = Template(
        id = id,
        version = 1,
        type = type,
        dialect = if (type == TemplateType.SQL) Dialect.POSTGRES else null,
        displayName = id,
        description = "Fixture.",
        body = "SELECT 1",
        createdAt = Instant.parse("2026-09-01T10:00:00Z"),
        createdBy = ACTOR,
        status = PipelineVersionStatus.RELEASED,
    )

    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = COMMENT.replace(engine().process(view, context().apply(fill)), "")

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private companion object {
        const val DEEP_PATH = "acme/finance/monthly_revenue"
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
