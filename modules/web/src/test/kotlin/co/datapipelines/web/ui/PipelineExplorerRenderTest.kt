package co.datapipelines.web.ui

import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.StagingEngine
import co.datapipelines.pipeline.TempdbSettings
import co.datapipelines.typesystem.LogicalType
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
 * Render-level guard for the pipelines screen's EXPLORER layout (067): tree LEFT, the selected
 * pipeline RIGHT — the shape 058 gave templates, applied to pipelines now that their names are
 * paths too.
 *
 * Three properties this class exists to pin:
 *
 *  - **A selection populates the detail pane WITHOUT re-rendering the tree.** Pinned at the
 *    fragment-contract level, where it is true by construction: the leaf's swap targets
 *    `#pipeline-detail` with `innerHTML`, and the detail fragment contains NO tree markup, NO
 *    tree swap target and NO out-of-band swap — there is nothing in what a selection returns
 *    that could touch the tree's DOM.
 *  - **The tree ships no folder CRUD**, because a folder is a name prefix with no identity
 *    (§3.1). No New folder / rename / move / delete control, and no empty-folder state.
 *  - **T108: the dead "Create Pipeline" button is gone**, replaced by an honest sentence and a
 *    link. It promised an affordance the server does not have and its tooltip named an
 *    internal worktree.
 *
 * Comments are stripped before every assertion: the markup documents its own absences at
 * length, and a promise in a comment is not an affordance (the discipline
 * [TemplateExplorerRenderTest] established, for exactly this reason).
 */
class PipelineExplorerRenderTest {
    // ------------------------------------------------------------- the two panes

    @Test
    fun `the screen renders a tree pane and a detail pane, and the tree pane wraps the swap root`() {
        val html = render("pipelines/list") { fillPage() }

        html shouldContain "class=\"tplx-body\""
        html shouldContain "tplx-tree\" id=\"pipeline-tree-pane\""
        html shouldContain "class=\"tplx-detail\""
        // The left pane is a STABLE container AROUND the long-standing swap root: the search
        // and the levels replace #pipeline-list-wrapper's contents, never the pane itself.
        html shouldContain "id=\"pipeline-list-wrapper\""
        // Nothing selected: a quiet empty state, not a blank panel.
        html shouldContain "id=\"pipeline-detail\""
        html shouldContain "Select a pipeline"
        // The shared keyboard layer is on the page, and finds this pane by its marker.
        html shouldContain "src=\"/js/template-explorer.js\""
        html shouldContain "data-explorer-pane"
    }

    @Test
    fun `T108 - the dead Create Pipeline button and its worktree tooltip are gone`() {
        val html = render("pipelines/list") { fillPage() }

        html shouldNotContain "Create Pipeline"
        html shouldNotContain "Phase 2 other worktree"
        html shouldNotContain "disabled"
        // …replaced by what is actually true, and by where authoring happens.
        html shouldContain "authored through agents"
        html shouldContain "/docs/mcp-server"
        html shouldContain "on the roadmap"
    }

    @Test
    fun `the root level is a tree and a nested level is a group under its folder`() {
        val root = render("partials/pipeline-tree-level") { fillLevel() }
        val nested =
            render("partials/pipeline-tree-level") {
                fillLevel()
                setVariable("prefix", "nyc/mobility")
                setVariable("levelId", PipelineBrowseModel.levelId("nyc/mobility"))
                // A nested level's leaves are BY CONSTRUCTION under its prefix (the query
                // splits on the remainder), which is what lets the label be the last segment.
                setVariable("pipelines", listOf(record(DEEP_PATH)))
            }

        root shouldContain "role=\"tree\""
        root shouldContain "aria-label=\"Pipelines\""
        root shouldNotContain "role=\"group\""
        nested shouldContain "role=\"group\""
        nested shouldNotContain "aria-label=\"Pipelines\""
        root shouldContain "role=\"treeitem\""
        root shouldContain "role=\"none\""
    }

    @Test
    fun `a folder expands ONE level, labelled by its segment with the FULL prefix on title`() {
        val html = render("partials/pipeline-tree-level") { fillLevel() }

        // The selector shape the templates explorer's browser tests use, so they transfer.
        html shouldContain "<details class=\"tpl-folder\">"
        html shouldContain "class=\"tpl-summary\""
        html shouldContain "hx-get=\"/partials/pipelines?prefix=nyc\""
        html shouldContain "hx-trigger=\"click once\""
        html shouldContain "hx-target=\"next .tpl-level\""
        // Label = the segment; the FULL prefix rides on title (§9.4).
        html shouldContain ">nyc</span>"
        html shouldContain "title=\"nyc\""
        // The placeholder the child level replaces carries the SAME derived id.
        html shouldContain "id=\"" + PipelineBrowseModel.levelId("nyc") + "\""
    }

    @Test
    fun `the tree ships no folder CRUD and no empty-folder state`() {
        val html = render("partials/pipeline-tree-level") { fillLevel() }

        html shouldNotContain "New folder"
        html shouldNotContain "Rename"
        html shouldNotContain "Move"
        html shouldNotContain "Delete"
        // R10: this screen is read-only. The only write affordance anywhere is the editor link.
        html shouldNotContain "hx-post"
        html shouldNotContain "hx-put"
        html shouldNotContain "hx-delete"
    }

    @Test
    fun `a leaf SELECTS - its detail swaps into the detail pane and nothing in the tree moves`() {
        val html = render("partials/pipeline-tree-level") { fillLevel() }

        html shouldContain "hx-target=\"#pipeline-detail\""
        html shouldContain "hx-swap=\"innerHTML\""
        html shouldNotContain "hx-swap-oob"
        html shouldContain "aria-selected=\"false\""
        html shouldContain "data-editor-url=\"/pipelines/$LEAF_ID/editor\""
        // A rapid keyboard sweep must not race stale detail loads into the pane.
        html shouldContain "hx-sync=\"#pipeline-detail:replace\""
    }

    @Test
    fun `an empty workspace says how a pipeline is created, and offers no button that does not work`() {
        val html =
            render("partials/pipeline-tree-level") {
                fillLevel()
                setVariable("folders", emptyList<PipelineFolderView>())
                setVariable("pipelines", emptyList<PipelineRecord>())
                setVariable("total", 0)
            }

        html shouldContain "class=\"ds-empty\""
        html shouldContain "No pipelines yet"
        html shouldContain "MCP server"
        html shouldNotContain "Create Pipeline"
    }

    @Test
    fun `the detail fragment cannot touch the tree - no tree markup, no tree target, no OOB`() {
        val html = render("partials/pipeline-detail") { fillDetail() }

        html shouldNotContain "tpl-tree"
        html shouldNotContain "pipeline-list-wrapper"
        html shouldNotContain "pipeline-tree-pane"
        html shouldNotContain "prefix="
        html shouldNotContain "hx-swap-oob"
        html shouldNotContain "tpl-folder"
        // …and it IS the selected pipeline: the full path, the settings, the declared
        // parameters (the pipeline's calling convention) and every version with its status.
        html shouldContain "class=\"tplx-detail-path\""
        html shouldContain DEEP_PATH
        html shouldContain "title=\"$DEEP_PATH\""
        html shouldContain "Open in editor"
        html shouldContain "/pipelines/$LEAF_ID/editor"
        html shouldContain "tempdb engine"
        html shouldContain "H2"
        html shouldContain "start_date"
        html shouldContain "DATE"
        html shouldContain "RELEASED"
        html shouldContain "DRAFT"
        html shouldContain "3 nodes"
    }

    @Test
    fun `a pipeline that declares no parameters says so rather than rendering an empty table`() {
        val html =
            render("partials/pipeline-detail") {
                fillDetail()
                setVariable("parameters", emptyMap<String, Parameter>())
            }

        html shouldContain "declares no parameters"
    }

    @Test
    fun `a search result SELECTS exactly like a tree leaf`() {
        val html = render("partials/pipeline-search") { fillSearch() }

        html shouldContain "role=\"listbox\""
        html shouldContain "aria-label=\"Search results\""
        html shouldContain "role=\"option\""
        html shouldContain "hx-target=\"#pipeline-detail\""
        html shouldContain "hx-swap=\"innerHTML\""
        html shouldContain "data-editor-url=\"/pipelines/$LEAF_ID/editor\""
        // The flat list shows the FULL path — that is what someone searching wants to read.
        html shouldContain ">$DEEP_PATH</span>"
    }

    @Test
    fun `an id that no longer names a live pipeline renders a quiet not-found detail`() {
        val html =
            render("partials/pipeline-detail") {
                fillDetail()
                setVariable("pipeline", null)
            }

        html shouldContain "Pipeline not found"
        html shouldContain "it may have been deleted"
        html shouldNotContain "tplx-detail-path"
        html shouldNotContain "Open in editor"
        html shouldNotContain "tpl-tree"
    }

    // ------------------------------------------------------------------ fixtures

    private fun WebContext.fillLevel() {
        setVariable("searching", false)
        setVariable("prefix", "")
        setVariable("levelId", PipelineBrowseModel.ROOT_LEVEL_ID)
        setVariable("folders", listOf(PipelineFolderView("nyc", "nyc", 6, PipelineBrowseModel.levelId("nyc"))))
        setVariable("foldersTruncated", false)
        setVariable("pipelines", listOf(record("legacy_flat")))
        setVariable("drafts", emptyMap<UUID, Any>())
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("q", "")
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillSearch() {
        setVariable("searching", true)
        setVariable("pipelines", listOf(record(DEEP_PATH)))
        setVariable("drafts", emptyMap<UUID, Any>())
        setVariable("q", "revenue")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 4)
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillDetail() {
        setVariable("pipelineId", LEAF_ID)
        setVariable("pipeline", record(DEEP_PATH))
        setVariable("workingVersion", 2)
        setVariable("draftVersion", 2)
        setVariable("settings", PipelineSettings(TempdbSettings(StagingEngine.H2)))
        setVariable(
            "parameters",
            mapOf("start_date" to Parameter(LogicalType.DATE, required = false, default = PipelineJson.objectMapper().readTree("\"2024-01-01\""))),
        )
        setVariable("nodeCount", 3)
        setVariable(
            "versions",
            listOf(
                PipelineVersionRecord(LEAF_ID, 2, PipelineVersionStatus.DRAFT, "h2", Instant.parse("2026-09-02T10:00:00Z"), ACTOR),
                PipelineVersionRecord(LEAF_ID, 1, PipelineVersionStatus.RELEASED, "h1", Instant.parse("2026-09-01T10:00:00Z"), ACTOR),
            ),
        )
    }

    private fun WebContext.fillPage() {
        fillChrome()
        fillLevel()
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
    }

    private fun WebContext.fillChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/pipelines")
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun record(name: String) =
        PipelineRecord(
            id = LEAF_ID,
            name = name,
            displayName = "Revenue by borough",
            description = "Fixture.",
            ownerId = ACTOR,
            currentVersion = 2,
            isDeleted = false,
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            updatedAt = Instant.parse("2026-09-02T10:00:00Z"),
        )

    /** Renders a view with HTML COMMENTS STRIPPED — a promise in a comment is not an affordance. */
    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = COMMENT.replace(engine().process(view, context().apply(fill)), "")

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

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
        const val DEEP_PATH = "nyc/mobility/revenue_by_borough"
        val LEAF_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
