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
 * Render-level guard for the pipelines screen's fragments — the catalog's flat list (since #466
 * the sidebar's tree is the REST one, rendered client-side, so no tree level or nav search is
 * left in the server's fragments), and the Versions surface the workspace composes (the detail pane these suites
 * once guarded beside them is REMOVED by #401; its render suite left with it).
 *
 * The properties this class exists to pin:
 *
 *  - **The catalog is the page's own** — it renders its stable swap root and no sidebar markup
 *    (no listbox, no nav scope), so it cannot touch the sidebar tree's DOM.
 *  - **The catalog ships no folder CRUD**, because a folder is a name prefix with no identity
 *    (§3.1). No New folder / rename / move / delete control, and no empty-folder state.
 *  - **T108: the dead "Create Pipeline" button is gone**, replaced by an honest sentence and a
 *    link. It promised an affordance the server does not have and its tooltip named an
 *    internal worktree.
 *
 * Comments are stripped before every assertion: the markup documents its own absences at
 * length, and a promise in a comment is not an affordance (the discipline
 * [TemplateExplorerRenderTest] established, for exactly this reason).
 *
 * `LargeClass` is suppressed knowingly: the catalog's list fragment and the workspace's
 * versions fragment share one fixture set.
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
        // The resource browser's other door, and the browser's tree itself in the same document
        // (#554: outside #app-main, in the layout's #app-browser column).
        main shouldContain "data-nav-tree-reveal=\"pipelines\""
        html shouldContain "data-nav-tree=\"pipelines\""
        html shouldContain "id=\"nav-tree-pipelines\""
        main shouldNotContain "id=\"nav-tree-pipelines\""
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
    fun `the catalog ships no folder CRUD and no empty-folder state`() {
        val html = render("partials/pipeline-search") { fillCatalog() }

        html shouldNotContain "New folder"
        html shouldNotContain "Rename"
        html shouldNotContain "Move"
        html shouldNotContain "Delete"
        // R10: this screen is read-only.
        html shouldNotContain "hx-post"
        html shouldNotContain "hx-put"
        html shouldNotContain "hx-delete"
        html shouldNotContain "empty folder"
    }

    @Test
    fun `085 - the catalog stays a flat list - no guides, no tree icons`() {
        val search = render("partials/pipeline-search") { fillSearch() }

        search shouldNotContain "lucide-sprite"
        search shouldNotContain "tpl-chevron"
        search shouldNotContain "tpl-folder"
    }

    @Test
    fun `#350 - a row NAVIGATES - a full-document link to the canonical workspace, never a detail swap`() {
        val html = render("partials/pipeline-search") { fillSearch() }

        // The canonical read workspace with NO version (its current-first rule resolves it,
        // spec §3.1), as a full document load (the graph entry spec §2 keeps).
        val row = Regex("""<a class="tpl-result"[^>]*>""").find(html)?.value ?: error("no row link in $html")
        row shouldContain "href=\"/pipelines/$LEAF_ID\""
        row shouldContain "hx-boost=\"true\""
        row shouldContain "data-leaf-id=\"$LEAF_ID\""
        row shouldNotContain "version="
        // The old selection contract is gone with the pane.
        html shouldNotContain "pipeline-detail"
        html shouldNotContain "data-editor-url"
        html shouldNotContain "/editor"
        html shouldNotContain "hx-swap-oob"
        // The flat list shows the FULL path — that is what someone searching wants to read.
        html shouldContain ">$DEEP_PATH</span>"
    }

    @Test
    fun `an empty workspace says how a pipeline is created, and offers no button that does not work`() {
        val html =
            render("partials/pipeline-search") {
                fillCatalog()
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
    fun `#350 - a CATALOG row is a plain link in a plain list, and its pager stays on the page`() {
        val html = render("partials/pipeline-search") { fillSearch() }

        html shouldContain "id=\"pipeline-list-wrapper\""
        html shouldContain "aria-label=\"Pipelines\""
        // #466: no sidebar presentation is left in the fragment.
        html shouldNotContain "role=\"listbox\""
        html shouldNotContain "role=\"option\""
        html shouldNotContain "aria-selected"
        html shouldContain "href=\"/pipelines/$LEAF_ID\""
        html shouldContain "hx-target=\"#pipeline-list-wrapper\""
        html shouldContain "q=revenue"
        html shouldNotContain "scope="
        html shouldNotContain "pipeline-nav-root"
    }

    @Test
    fun `#350 - the catalog's empty answer says how pipelines arrive, a no-match offers its Clear search`() {
        val empty =
            render("partials/pipeline-search") {
                fillCatalog()
                setVariable("pipelines", emptyList<PipelineRecord>())
                setVariable("q", "")
                setVariable("total", 0)
            }
        empty shouldContain "No pipelines yet"
        empty shouldNotContain "match your search"

        // #432: the catalog's no-match clear names the box it empties (shell.js clears it) and
        // replaces any search still in flight from that box, so a stale answer cannot land after it.
        val catalogMiss =
            render("partials/pipeline-search") {
                fillCatalog()
                setVariable("pipelines", emptyList<PipelineRecord>())
                setVariable("q", "zzz")
                setVariable("total", 0)
            }
        catalogMiss shouldContain "No pipelines match your search"
        catalogMiss shouldNotContain "data-nav-tree-clear"
        catalogMiss shouldContain "Clear search"
        catalogMiss shouldContain "data-dp-clear-filters=\"#pipeline-filter-q\""
        catalogMiss shouldContain "hx-sync=\"#pipeline-filter-q:replace\""
    }

    // ------------------------------------------------------------------ fixtures

    /** The /pipelines CATALOG's search (#350): the flat list under the page's own root. */
    private fun WebContext.fillSearch() {
        setVariable("searching", true)
        setVariable("rootId", PipelineBrowseModel.CATALOG_ROOT_ID)
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

    /** The /pipelines CATALOG (#350) with no query: every pipeline. */
    private fun WebContext.fillCatalog() {
        fillSearch()
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
