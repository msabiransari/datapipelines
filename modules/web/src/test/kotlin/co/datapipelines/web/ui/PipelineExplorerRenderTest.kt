package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.StagingEngine
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.ints.shouldBeGreaterThan
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
        // A nested level's leaves are BY CONSTRUCTION under its prefix (the query splits on the
        // remainder), which is what lets the label be the last segment.
        val nested = render("partials/pipeline-tree-level") { fillNestedLevel() }

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
    fun `077 - the ROOT level renders folders only, never a leaf`() {
        // §4.1 requires a folder, so nothing sits directly at the root and the fragment has no
        // "leaf at the root" branch left to exercise. Asserted on the RENDERED level rather
        // than on the model, because the branch that is gone lived in the markup: the label
        // used to be `prefix.isEmpty() ? p.name : p.name.substring(...)`.
        val root = render("partials/pipeline-tree-level") { fillLevel() }

        root shouldNotContain "tpl-leaf"
        root shouldNotContain "data-editor-url"
        root shouldContain "tpl-folder"
        // …and a NESTED level still renders its leaves, labelled by their last segment, so the
        // assertion above is about the root and not about leaves having disappeared.
        val nested = render("partials/pipeline-tree-level") { fillNestedLevel() }
        nested shouldContain "tpl-leaf"
        nested shouldContain ">revenue_by_borough</span>"
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
    fun `085 - the pipeline tree carries the same icon chrome as the templates tree`() {
        // One stylesheet dresses both explorers (067's convention), so the DOM shape is
        // pinned on both partials: folders at the root level, a leaf in a nested one.
        val root = render("partials/pipeline-tree-level") { fillLevel() }
        root shouldContain "class=\"ds-icon ds-icon-xs tpl-chevron\""
        root shouldContain "lucide-sprite.svg#chevron-right"
        root shouldContain "lucide-sprite.svg#folder\""
        root shouldContain "lucide-sprite.svg#folder-open"
        root shouldContain "tpl-level tpl-level-pending"

        val nested = render("partials/pipeline-tree-level") { fillNestedLevel() }
        nested shouldContain "lucide-sprite.svg#file-code"

        // Search stays a flat list — no guides, no tree icons.
        val search = render("partials/pipeline-search") { fillSearch() }
        search shouldNotContain "lucide-sprite"
        search shouldNotContain "tpl-chevron"
    }

    @Test
    fun `a leaf SELECTS - its detail swaps into the detail pane and nothing in the tree moves`() {
        // A NESTED level: since 077 a leaf can only sit under a folder (§4.1).
        val html = render("partials/pipeline-tree-level") { fillNestedLevel() }

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
    fun `every link into the pipeline editor is a full document load, never a boosted swap`() {
        // The editor's Alpine root is initialised by alpine.min.js as soon as swapped markup
        // lands, before the editor scripts that define pipelineEditor() have run: boosted
        // entry renders a graph with no Execute, no dock and no inspector, and every binding
        // throws. Found live on 2026-09-05, the first walk after 076 turned boost on.
        val html = render("partials/pipeline-detail") { fillDetail() }

        val editorLinks = Regex("""<a [^>]*href="/pipelines/[^"]+/editor"[^>]*>""").findAll(html).map { it.value }.toList()
        editorLinks.size shouldBeGreaterThan 0
        editorLinks.forEach { it shouldContain "hx-boost=\"false\"" }
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
        // 106: the path is the eyebrow and the LEAF is the title.
        html shouldContain "nyc/mobility/</p>"
        html shouldContain "revenue_by_borough</h2>"
        // The staging engine is a CHIP; the one-row "Settings" table is gone.
        html shouldContain "tempdb · H2"
        html shouldNotContain "tempdb engine"
        html shouldContain "start_date"
        html shouldContain "DATE"
        html shouldContain "RELEASED"
        html shouldContain "DRAFT"
        html shouldContain "3 nodes"
    }

    @Test
    fun `106 - the detail is three regions - header, reading column, acting column`() {
        val html = render("partials/pipeline-detail") { fillDetail() }

        html shouldContain "class=\"tplx-detail-header\""
        html shouldContain "class=\"tplx-read\""
        html shouldContain "class=\"tplx-act\""
        // The reading column's two cards, and the acting column's ONE tabbed card.
        html shouldContain ">Overview<"
        html shouldContain ">Parameters<"
        html shouldContain "data-explorer-tabs"
        html shouldContain "data-tab-panel=\"pipeline-tab-versions\""
        html shouldContain "data-tab-panel=\"pipeline-tab-runs\""
        html shouldContain "data-tab-panel=\"pipeline-tab-usage\""
        // Runs and Usage are LAZY — one fragment each, fetched on the tab's first click.
        html shouldContain "/partials/pipelines/$LEAF_ID/runs"
        html shouldContain "/partials/pipelines/$LEAF_ID/usage"
        html shouldContain "hx-trigger=\"click once\""
        // The overview's key/value strip, with the facts the mock's legend names.
        html shouldContain ">Datasources<"
        html shouldContain ">Templates<"
        html shouldContain ">Created<"
        html shouldContain ">Last run<"
        html shouldContain "sample-lake"
        html shouldContain "demo/top_carrier.sql@1"
        // No "via" chip: nothing records the surface a CREATE arrived on, so none is invented.
        html shouldNotContain "via MCP"
        html shouldNotContain "via UI"
    }

    @Test
    fun `106 - the acting column renders version ROWS, never a table`() {
        val html = render("partials/pipeline-versions") { fillDetail() }

        html shouldContain "class=\"tplx-vrow\""
        html shouldNotContain "<table"
        html shouldContain "7 runs"
        html shouldContain ">current<"
    }

    @Test
    fun `106 - the header renders exactly the verbs the lifecycle allows`() {
        // A draft over a release: Release yes; Delete no (101 purges the ENTITY only while the
        // only version is a draft); Discard yes (the pointer names a release).
        val overRelease = render("partials/pipeline-detail") { fillDetail() }
        overRelease shouldContain "Release v2…"
        overRelease shouldContain ">Discard<"
        overRelease shouldNotContain ">Delete<"

        // A never-released pipeline: Delete, and no Discard.
        val draftOnly =
            render("partials/pipeline-detail") {
                fillDetail()
                setVariable("canDelete", true)
                setVariable("canDiscardCurrent", false)
                setVariable("versions", listOf(draftRow()))
                setVariable("versionCount", 1)
            }
        draftOnly shouldContain ">Delete<"
        draftOnly shouldNotContain ">Discard<"
        draftOnly shouldContain "Release v2…"

        // Nothing to release: no Release button at all.
        val released =
            render("partials/pipeline-detail") {
                fillDetail()
                setVariable("releasableVersion", null)
                setVariable("draftVersion", null)
                setVariable("versions", listOf(releasedRow()))
            }
        released shouldNotContain "Release v"
    }

    @Test
    fun `106 - every lifecycle button points at a REST verb 101 shipped, and swaps nothing`() {
        val html = render("partials/pipeline-detail") { fillDetail() } + render("partials/pipeline-versions") { fillDetail() }

        html shouldContain "data-verb-url=\"/api/v1/pipelines/$LEAF_ID/release\""
        html shouldContain "data-verb-url=\"/api/v1/pipelines/$LEAF_ID/versions/1/discard\""
        html shouldContain "data-verb-url=\"/api/v1/pipelines/$LEAF_ID/versions/2\""
        // The hash precondition rides the button (versioning §4.2) — release what you tested.
        html shouldContain "data-if-match=\"h2\""
        // Every one carries a confirm, and none of them is a UI-only route.
        html shouldContain "data-confirm="
        html shouldNotContain "/partials/pipelines/$LEAF_ID/release"
    }

    @Test
    fun `106 - the detail carries no inline style anywhere`() {
        val html =
            render("partials/pipeline-detail") { fillDetail() } +
                render("partials/pipeline-versions") { fillDetail() } +
                render("partials/pipeline-usage") { fillUsage() }

        html shouldNotContain "style=\""
    }

    @Test
    fun `106 - the usage tab names what a discard would be refused over, and claims no schedules`() {
        val html = render("partials/pipeline-usage") { fillUsage() }

        html shouldContain "Published endpoints"
        html shouldContain "/api/x/rideshare"
        html shouldContain "Pipelines invoking it"
        html shouldContain "nyc/rollup"
        html shouldContain "pins v1"
        // 092 has not landed; a third empty heading would claim schedules were checked.
        html shouldNotContain "Schedule"
    }

    @Test
    fun `106 - the runs tab lists executions, newest first, each linking to its detail`() {
        val html = render("partials/pipeline-runs") { fillRuns() }

        html shouldContain "class=\"tplx-runrow\""
        html shouldContain "/executions/$RUN_ID"
        html shouldContain "data-status=\"SUCCESS\""
        html shouldContain "MCP"
        html shouldContain "4 minutes ago"
    }

    private fun WebContext.fillUsage() {
        setVariable(
            "usage",
            UsageView(
                endpoints = listOf(UsageView.EndpointUse("/rideshare", enabled = true, description = "Serves it.")),
                parents = listOf(UsageView.ParentUse(UUID.randomUUID(), "nyc/rollup", 2, "child", 1)),
            ),
        )
    }

    private fun WebContext.fillRuns() {
        val started = Instant.parse("2026-09-03T09:56:00Z")
        setVariable(
            "runs",
            listOf(
                ExecutionRecord(
                    executionId = RUN_ID,
                    pipelineId = LEAF_ID,
                    pipelineVersion = 2,
                    status = ExecutionStatus.SUCCESS,
                    parametersJson = "{}",
                    triggeredBy = ACTOR,
                    triggeredVia = ExecutionTrigger.MCP,
                    startedAt = started,
                    durationMs = 3200,
                    resultRowCount = 6,
                ),
            ),
        )
        setVariable("runActors", mapOf(ACTOR to "Muhammad"))
        setVariable("runAgo", mapOf(RUN_ID to RelativeTime.since(started, Instant.parse("2026-09-03T10:00:00Z"))))
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

    /**
     * The ROOT level: folders and nothing else (077, §4.1).
     *
     * It used to carry `record("legacy_flat")` — a leaf sitting at the root, which the grammar
     * now forbids and `PipelineBrowseModel` no longer puts in the model. Every assertion about
     * a LEAF therefore moved onto [fillNestedLevel].
     */
    private fun WebContext.fillLevel() {
        setVariable("searching", false)
        setVariable("prefix", "")
        setVariable("levelId", PipelineBrowseModel.ROOT_LEVEL_ID)
        setVariable("folders", listOf(PipelineFolderView("nyc", "nyc", 6, PipelineBrowseModel.levelId("nyc"))))
        setVariable("foldersTruncated", false)
        setVariable("pipelines", emptyList<PipelineRecord>())
        setVariable("drafts", emptyMap<UUID, Any>())
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 0)
        setVariable("q", "")
        setVariable("scopes", setOf("ADMIN"))
    }

    /** A NESTED level — the only kind that has leaves now. */
    private fun WebContext.fillNestedLevel() {
        fillLevel()
        setVariable("prefix", "nyc/mobility")
        setVariable("levelId", PipelineBrowseModel.levelId("nyc/mobility"))
        setVariable("folders", emptyList<PipelineFolderView>())
        setVariable("pipelines", listOf(record(DEEP_PATH)))
        setVariable("total", 1)
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

    /**
     * The 106 detail model: the three regions in one fill, exactly as
     * [PipelineBrowseModel.fillDetail] leaves it — a draft v2 over a released v1, which is the
     * state where every header verb has something to decide (Release yes, Delete no because a
     * release exists, Discard yes because the pointer names one).
     */
    private fun WebContext.fillDetail() {
        setVariable("pipelineId", LEAF_ID)
        setVariable("pipeline", record(DEEP_PATH))
        setVariable("folderPath", "nyc/mobility/")
        setVariable("leafName", "revenue_by_borough")
        setVariable("workingVersion", 2)
        setVariable("draftVersion", 2)
        setVariable("draftHash", "h2")
        setVariable("stagingEngine", StagingEngine.H2)
        val startDate =
            Parameter(
                LogicalType.DATE,
                required = false,
                default = PipelineJson.objectMapper().readTree("\"2024-01-01\""),
            )
        setVariable("parameters", mapOf("start_date" to startDate))
        setVariable("nodeCount", 3)
        setVariable("datasourceRows", listOf(DatasourceRowView("sample-lake", Dialect.POSTGRES)))
        setVariable("templatePins", listOf(TemplatePinView("demo/top_carrier.sql", 1)))
        setVariable("createdBy", "Muhammad")
        setVariable("lastRun", null)
        setVariable("lastRunAgo", null)
        setVariable("lastRunBy", null)
        setVariable("versions", listOf(draftRow(), releasedRow()))
        setVariable("versionCount", 2)
        setVariable("runCount", 7)
        setVariable("usageCount", 0)
        setVariable("releasableVersion", 2)
        setVariable("canDelete", false)
        setVariable("canDiscardCurrent", true)
    }

    private fun draftRow() =
        VersionRowView.of(
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            createdAt = Instant.parse("2026-09-02T10:00:00Z"),
            actor = "Muhammad",
            now = Instant.parse("2026-09-03T10:00:00Z"),
            usage = 7,
            usageUnit = "run",
            isCurrent = false,
        )

    private fun releasedRow() =
        VersionRowView.of(
            version = 1,
            status = PipelineVersionStatus.RELEASED,
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
            actor = "Muhammad",
            now = Instant.parse("2026-09-03T10:00:00Z"),
            usage = 1,
            usageUnit = "run",
            isCurrent = true,
        )

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
        val RUN_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
