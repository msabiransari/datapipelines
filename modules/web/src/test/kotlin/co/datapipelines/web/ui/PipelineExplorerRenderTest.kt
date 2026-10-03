package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineVersionStatus
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
 * Render-level guard for the pipelines screen's fragments — the sidebar tree, the search and
 * the catalog, and the Versions surface the workspace composes (the detail pane these suites
 * once guarded beside them is REMOVED by #401; its render suite left with it).
 *
 * The properties this class exists to pin:
 *
 *  - **A tree swap never leaks into the catalog and vice versa** — each presentation renders
 *    its own stable swap root, and the fragments contain no markup that could touch the
 *    other's DOM.
 *  - **The tree ships no folder CRUD**, because a folder is a name prefix with no identity
 *    (§3.1). No New folder / rename / move / delete control, and no empty-folder state.
 *  - **T108: the dead "Create Pipeline" button is gone**, replaced by an honest sentence and a
 *    link. It promised an affordance the server does not have and its tooltip named an
 *    internal worktree.
 *
 * Comments are stripped before every assertion: the markup documents its own absences at
 * length, and a promise in a comment is not an affordance (the discipline
 * [TemplateExplorerRenderTest] established, for exactly this reason).
 *
 * `LargeClass` is suppressed knowingly: the list fragments (sidebar tree, nav search,
 * catalog) and the workspace's versions fragment share one fixture set.
 */
@Suppress("LargeClass")
class PipelineExplorerRenderTest {
    // ------------------------------------------------------------- the two panes

    @Test
    fun `#350 - the page is the CATALOG - a flat list under its own root, no tree pane and no detail pane`() {
        val html = render("pipelines/list") { fillPage() }
        val main = html.substringAfter("id=\"app-main\"")

        // The catalog's one stable swap root, rendered as the FLAT list of full paths.
        main shouldContain "id=\"pipeline-list-wrapper\""
        main shouldContain "data-pipeline-list=\"page\""
        main shouldContain ">$DEEP_PATH</span>"
        // The tree and the selection pane are gone from the page (spec §3.2/§5): the tree lives
        // in the sidebar now and a row opens the workspace.
        main shouldNotContain "tplx-body"
        main shouldNotContain "pipeline-tree-pane"
        main shouldNotContain "id=\"pipeline-detail\""
        main shouldNotContain "data-explorer-pane"
        main shouldNotContain "tpl-folder"
        main shouldNotContain "/js/splitter.js"
        main shouldNotContain "/js/explorer-detail.js"
        // The sidebar tree's other door, and the sidebar tree itself in the same document.
        main shouldContain "data-nav-tree-reveal=\"pipelines\""
        html shouldContain "data-nav-tree=\"pipelines\""
        html shouldContain "id=\"pipeline-nav-root\""
    }

    @Test
    fun `T108 - the dead Create Pipeline button and its worktree tooltip are gone`() {
        val html = render("pipelines/list") { fillPage() }

        html shouldNotContain "Create Pipeline"
        html shouldNotContain "Phase 2 other worktree"
        // No disabled control in the page's head (the catalog's pager below may disable its
        // Previous on the first page — that is the pager's own honest state, not T108's).
        html.substringAfter("id=\"app-main\"").substringBefore("id=\"pipeline-list-wrapper\"") shouldNotContain "disabled"
        // …replaced by what is actually true, and by where authoring happens.
        html shouldContain "authored through agents"
        html shouldContain "/docs/mcp-server"
        // D1 (workspace spec, owner 2026-09-30): pipeline definitions are AI-native — the old
        // "browser authoring is on the roadmap" sentence became false and is gone.
        html shouldNotContain "on the roadmap"
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
    fun `#350 - a leaf NAVIGATES - a full-document link to the canonical workspace, never a detail swap`() {
        // A NESTED level: since 077 a leaf can only sit under a folder (§4.1).
        val html = render("partials/pipeline-tree-level") { fillNestedLevel() }

        // The canonical read workspace with NO version (its current-first rule resolves it,
        // spec §3.1), as a full document load (the graph entry spec §2 keeps).
        val leaf = Regex("""<a class="tpl-leaf"[^>]*>""").find(html)?.value ?: error("no leaf link in $html")
        leaf shouldContain "href=\"/pipelines/$LEAF_ID\""
        leaf shouldContain "hx-boost=\"false\""
        leaf shouldContain "role=\"treeitem\""
        leaf shouldContain "data-leaf-id=\"$LEAF_ID\""
        leaf shouldNotContain "version="
        // The old selection contract is gone with the pane.
        html shouldNotContain "pipeline-detail"
        html shouldNotContain "data-editor-url"
        html shouldNotContain "/editor"
        html shouldNotContain "hx-swap-oob"
        html shouldContain "aria-selected=\"false\""
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
    fun `the versions surface renders the house table (349 spec section 4_4) - not the old compact rows`() {
        // 106's "rows, never a table" was the narrow-pane decision of its day; the owner's
        // 2026-09-30 workspace ruling (spec §4.4, D4) supersedes it: every version surface
        // composes the ACTUAL house table — frame, viewport, ds-table — and no second
        // renderer. The row hooks (data-version-row, the ⋯ menus, "7 runs", the current
        // mark) are the same facts in the new markup.
        val html = render("partials/pipeline-versions") { fillDetail() }

        html shouldContain "dt-frame"
        html shouldContain "<table class=\"ds-table\""
        html shouldContain "data-version-row="
        html shouldNotContain "class=\"tplx-vrow\""
        html shouldContain "7 runs"
        html shouldContain ">current<"
    }

    /**
     * The version menu, same ladder. The `⋯` itself disappears when no verb survives the
     * role — a menu that opens on an empty list is exactly the tease "hide, don't disable"
     * forbids, and it is the failure a per-item guard alone would leave behind.
     */
    @Test
    fun `114 - the version menu hides itself entirely when the role holds none of its verbs`() {
        val admin = render("partials/pipeline-versions") { fillDetail() }
        admin shouldContain "tplx-vmenu"

        val viewer =
            render("partials/pipeline-versions") {
                fillDetail()
                withRoles(RoleModel.NONE.copy(canRead = true, canExecute = true))
            }
        viewer shouldNotContain "tplx-vmenu"
        viewer shouldNotContain "data-verb="
        // The row itself is still there, with its Open link — a viewer reads versions
        // (#349 composes the rows as the house table; the Open anchor is the row hook).
        viewer shouldContain "data-pe-version-link"

        // A promoter's menu is GONE with the release lever (D8): no verb survives the role.
        val promoter =
            render("partials/pipeline-versions") {
                fillDetail()
                withRoles(canExecute = false, canAuthor = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "promoter")
            }
        promoter shouldNotContain "tplx-vmenu"
        promoter shouldNotContain "data-verb=\"pipeline-release\""
        promoter shouldNotContain "data-verb=\"pipeline-purge\""
        promoter shouldNotContain "data-verb=\"pipeline-restore\""
        // …and an author's carries Release with the destructive verbs.
        val author =
            render("partials/pipeline-versions") {
                fillDetail()
                withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
            }
        author shouldContain "data-verb=\"pipeline-release\""
    }

    @Test
    fun `102 - every lifecycle verb opens its dialog partial, into the screen's container`() {
        // SUPERSEDES 106's "points at a REST verb and swaps nothing": the verbs are §4.3d
        // dialogs — hx-get into the workspace's #pe-dialog (the #401 default; the explorer's
        // #px-dialog shapes are gone) — and the DIALOG's POST is what calls the service (the
        // plain-confirm fetch path to the REST routes is gone, with its
        // data-verb/data-confirm/data-if-match attributes). #401: the rows are the one
        // producer of these links — the detail header that hx-get the bare release/switch
        // URLs into #px-dialog is gone.
        val html = render("partials/pipeline-versions") { fillDetail() }

        html shouldContain "hx-get=\"/partials/pipelines/$LEAF_ID/lifecycle/release?from=editor\""
        // th:attr escapes `&` in the attribute value — the assertions match the markup as written.
        html shouldContain "hx-get=\"/partials/pipelines/$LEAF_ID/lifecycle/discard?version=1&amp;from=editor\""
        html shouldContain "hx-get=\"/partials/pipelines/$LEAF_ID/lifecycle/purge?version=2&amp;from=editor\""
        html shouldContain "hx-get=\"/partials/pipelines/$LEAF_ID/lifecycle/restore?version=0&amp;from=editor\""
        // A row's Switch-to names its version — not pinned here (the fixture's only released
        // row IS current, so no row-level Switch renders); the dialog render suite owns it.
        html shouldContain "hx-target=\"#pe-dialog\""
        html shouldNotContain "px-dialog"
        // The fetch path is gone entirely: no verb attributes, no plain confirms.
        html shouldNotContain "data-verb-url="
        html shouldNotContain "data-confirm="
        html shouldNotContain "data-if-match="
    }

    @Test
    fun `the workspace's fragments carry no inline style anywhere`() {
        val html =
            render("partials/pipeline-versions") { fillDetail() } +
                render("partials/pipeline-usage") { fillUsage() }

        html shouldNotContain "style=\""
    }

    @Test
    fun `106 - the usage tab names what a discard would be refused over`() {
        val html = render("partials/pipeline-usage") { fillUsage() }

        html shouldContain "Published endpoints"
        html shouldContain "/api/rideshare/v1/daily"
        html shouldContain "Pipelines invoking it"
        html shouldContain "nyc/rollup"
        html shouldContain "pins v1"
    }

    @Test
    fun `106 - the usage tab lists the schedules that run the pipeline (#259)`() {
        val scheduleId = UUID.randomUUID()
        val html =
            render("partials/pipeline-usage") {
                setVariable(
                    "usage",
                    UsageView(
                        endpoints = emptyList(),
                        parents = emptyList(),
                        schedules = listOf(UsageView.ScheduleUse(scheduleId, "reports/nightly", "enabled")),
                    ),
                )
            }

        html shouldContain "Schedules running it"
        html shouldContain "reports/nightly"
        html shouldContain "/schedules?id=$scheduleId"
        html shouldContain ">enabled<"
        // Schedules are not refusal evidence: the empty state stays silent while they run it.
        html shouldNotContain "Nothing depends on this pipeline"
    }

    @Test
    fun `106 - a blocked schedule reads as blocked, and a truly empty tab states all three absences`() {
        val scheduleId = UUID.randomUUID()
        val blocked =
            render("partials/pipeline-usage") {
                setVariable(
                    "usage",
                    UsageView(
                        endpoints = emptyList(),
                        parents = emptyList(),
                        schedules = listOf(UsageView.ScheduleUse(scheduleId, "reports/nightly", "blocked")),
                    ),
                )
            }
        blocked shouldContain ">blocked<"

        val empty = render("partials/pipeline-usage") { fillUsage(empty = true) }
        empty shouldContain "no published endpoint serves it, no live pipeline or dashboard pins it and no schedule runs it"
    }

    @Test
    fun `320 - a dashboard whose source pins a version is refusal evidence on the tab - named, and the empty sentence is gone`() {
        val html =
            render("partials/pipeline-usage") {
                setVariable(
                    "usage",
                    UsageView(
                        endpoints = emptyList(),
                        parents = emptyList(),
                        dashboards = listOf(UsageView.DashboardUse("acme/boards/revenue", 4, "RELEASED", 2)),
                    ),
                )
            }

        html shouldContain "Dashboards pinning it"
        html shouldContain "acme/boards/revenue"
        html shouldContain "pins v2"
        html shouldNotContain "Nothing depends on this pipeline"
    }

    @Test
    fun `106 - the runs tab lists executions, newest first, each linking to its detail`() {
        val html = render("partials/pipeline-runs") { fillRuns() }

        // #349: the runs surface composes the house table (spec §4.4); the row keeps its
        // execution link (data-href, the table's keyboard contract) and every fact column.
        html shouldContain "dt-frame"
        html shouldContain "data-href=\"/executions/$RUN_ID\""
        html shouldContain "data-status=\"SUCCESS\""
        html shouldContain "MCP"
        html shouldContain "4 minutes ago"
    }

    private fun WebContext.fillUsage(empty: Boolean = false) {
        setVariable(
            "usage",
            if (empty) {
                UsageView(endpoints = emptyList(), parents = emptyList())
            } else {
                UsageView(
                    endpoints = listOf(UsageView.EndpointUse("/rideshare/v1/daily", enabled = true, description = "Serves it.")),
                    parents = listOf(UsageView.ParentUse(UUID.randomUUID(), "nyc/rollup", 2, "child", 1)),
                )
            },
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
                    executedBy = ACTOR,
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
    fun `#350 - a SIDEBAR search result is a listbox option linking to the workspace, under the sidebar's root`() {
        val html = render("partials/pipeline-search") { fillSearch() }

        html shouldContain "id=\"pipeline-nav-root\""
        html shouldContain "role=\"listbox\""
        html shouldContain "aria-label=\"Pipeline search results\""
        html shouldContain "role=\"option\""
        html shouldContain "href=\"/pipelines/$LEAF_ID\""
        html shouldContain "hx-boost=\"false\""
        html shouldNotContain "pipeline-detail"
        html shouldNotContain "data-editor-url"
        // The pager stays in the sidebar: its requests carry the nav scope and target its root.
        html shouldContain "scope=nav"
        html shouldContain "hx-target=\"#pipeline-nav-root\""
        // The flat list shows the FULL path — that is what someone searching wants to read.
        html shouldContain ">$DEEP_PATH</span>"
    }

    @Test
    fun `#350 - a CATALOG row is a plain link in a plain list, and its pager stays on the page`() {
        val html = render("partials/pipeline-search") { fillCatalog() }

        html shouldContain "id=\"pipeline-list-wrapper\""
        html shouldNotContain "role=\"listbox\""
        html shouldNotContain "role=\"option\""
        html shouldContain "href=\"/pipelines/$LEAF_ID\""
        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldNotContain "scope=nav"
    }

    @Test
    fun `#350 - the catalog's empty answer says how pipelines arrive, a sidebar no-match offers its own clear`() {
        val empty =
            render("partials/pipeline-search") {
                fillCatalog()
                setVariable("pipelines", emptyList<PipelineRecord>())
                setVariable("q", "")
                setVariable("total", 0)
            }
        empty shouldContain "No pipelines yet"
        empty shouldNotContain "match your search"

        val noMatch =
            render("partials/pipeline-search") {
                fillSearch()
                setVariable("pipelines", emptyList<PipelineRecord>())
                setVariable("total", 0)
            }
        noMatch shouldContain "No pipelines match your search"
        noMatch shouldContain "data-nav-tree-clear"
        // The sidebar's clear is nav-tree.js's (it empties the box too) — no bare hx-get.
        noMatch shouldNotContain "hx-get=\"/partials/pipelines\""
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

    /** The SIDEBAR's search (#350): `scope=nav`, rooted at the sidebar's swap root. */
    private fun WebContext.fillSearch() {
        setVariable("searching", true)
        setVariable("scope", PipelineListScope.NAV.wire)
        setVariable("rootId", PipelineListScope.NAV.rootId)
        setVariable("pipelines", listOf(record(DEEP_PATH)))
        setVariable("drafts", emptyMap<UUID, Any>())
        setVariable("q", "revenue")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 4)
        setVariable("scopes", setOf("ADMIN"))
    }

    /**
     * The Versions model: a draft v2 over a released v1 (plus one discarded row for
     * Restore), the state where every row verb has something to decide — the variables the
     * fragments read; the detail-only variables left with the detail suite (#401).
     */
    private fun WebContext.fillDetail() {
        setVariable("pipeline", record(DEEP_PATH))
        setVariable("versions", listOf(draftRow(), releasedRow(), discardedRow()))
        // What the workspace's Versions tab passes to the fragment (editor.html's th:with).
        setVariable("dialogTarget", "#pe-dialog")
        setVariable("dialogFrom", "editor")
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

    /** A DISCARDED row: the row menu's Restore target (§3.5's {R,X,D}-side of the fixture). */
    private fun discardedRow() =
        VersionRowView.of(
            version = 0,
            status = PipelineVersionStatus.DISCARDED,
            createdAt = Instant.parse("2026-08-31T10:00:00Z"),
            actor = "Muhammad",
            now = Instant.parse("2026-09-03T10:00:00Z"),
            usage = 0,
            usageUnit = "run",
            isCurrent = false,
        )

    /** The /pipelines CATALOG (#350): the flat list under the page's own root. */
    private fun WebContext.fillCatalog() {
        fillSearch()
        setVariable("scope", PipelineListScope.CATALOG.wire)
        setVariable("rootId", PipelineListScope.CATALOG.rootId)
        setVariable("q", "")
    }

    private fun WebContext.fillPage() {
        fillChrome()
        fillCatalog()
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
        const val DEEP_PATH = "nyc/mobility/revenue_by_borough"
        val LEAF_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val RUN_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
