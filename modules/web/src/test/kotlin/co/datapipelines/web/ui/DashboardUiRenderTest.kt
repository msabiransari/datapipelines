package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
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
 * #10 L3b / #400 — the dashboards pages pinned at the RENDER (ShellRenderTest's mould): the
 * tree level's contract (folders first, root holds no leaves, a leaf navigates un-boosted,
 * the lens decides what renders), the catalog's contract (a FLAT list — folders are NOT the
 * catalog's axis; a draft-only dashboard's row says so), the workspace page's ONE-bundle
 * contract (exactly one Plotly script tag, its declaration equal to the server's
 * `renderer.bundle`, for a 2d board and a 3d board), the viewed-version chip and the version
 * selector (every version switch a FULL navigation), the choose-a-version state (#409: the
 * NAME in the heading, the draft offered, never the old empty `h1`), and the refusal state
 * that stands in for a board that cannot run.
 *
 * The lens fixture renders what the MODEL carries — the model is where the lens is applied
 * (DashboardBrowseModel, through the service's lensed reads), so the browser-side guarantee
 * is exactly this: a hidden dashboard is not in the model, hence not in the HTML. The
 * falsification plants the unfiltered model and watches this test go red on its presence.
 */
class DashboardUiRenderTest {
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

    private val folderA = DashboardBrowseModel.DashboardFolderView("acme/dashboards", "acme", 2, "dash-level-nav-aaaa", "nav")
    private val board1 =
        DashboardBrowseModel.DashboardLeafView(UUID.randomUUID(), "acme/dashboards/revenue", "revenue", "Revenue", 3, true)
    private val board2 =
        DashboardBrowseModel.DashboardLeafView(UUID.randomUUID(), "acme/dashboards/costs", "costs", "Costs", 1, false)

    // ------------------------------------------------------------------ the sidebar's tree level

    @Test
    fun `the root level lists folders only - a dashboard name needs a folder`() {
        val html =
            engine.process(
                "partials/dashboard-tree-level",
                webContext("/dashboards").apply {
                    setVariable("prefix", "")
                    setVariable("scope", "nav")
                    setVariable("levelId", DashboardBrowseModel.NAV_ROOT_ID)
                    setVariable("folders", listOf(folderA))
                    setVariable("dashboards", emptyList<DashboardBrowseModel.DashboardLeafView>())
                    setVariable("offset", 0)
                    setVariable("hasMore", false)
                    setVariable("total", 0)
                },
            )

        // One folder, its lazy one-level request riding the summary, the NAV scope on every
        // URL (#400: the page instance is retired — the tree is the sidebar's); NO leaf at
        // the root.
        Regex("hx-target=\"next .tpl-level\"").findAll(html).toList().size shouldBe 1
        html shouldContain "/partials/dashboards/tree?prefix=acme/dashboards&amp;scope=nav"
        html shouldNotContain "scope=page"
        html shouldNotContain "tpl-leaf"
    }

    @Test
    fun `a level's children render as un-boosted links to the workspace`() {
        val html = levelWith(board1, board2)

        // A leaf NAVIGATES: a real href (the workspace page), full navigation —
        // hx-boost="false", the §6.4 one-bundle rule — and the full path on `title`.
        html shouldContain "hx-boost=\"true\""
        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldContain "title=\"acme/dashboards/revenue\""
        // versioning §7: the unreleased edits stay visible, marked.
        html shouldContain "tpl-leaf-draft"
        html shouldContain ">v3<"
    }

    @Test
    fun `a dashboard the lens hides from the model is absent from the HTML`() {
        val html = levelWith(board1)

        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldNotContain "href=\"/dashboards/${board2.id}\""
    }

    private fun levelWith(vararg leaves: DashboardBrowseModel.DashboardLeafView): String =
        engine.process(
            "partials/dashboard-tree-level",
            webContext("/dashboards").apply {
                setVariable("prefix", "acme/dashboards")
                setVariable("scope", "nav")
                setVariable("levelId", "dash-level-nav-x")
                setVariable("folders", emptyList<DashboardBrowseModel.DashboardFolderView>())
                setVariable("dashboards", leaves.toList())
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", leaves.size)
            },
        )

    // ------------------------------------------------------------------ the catalog (#400)

    @Test
    fun `the catalog's rows are a FLAT list into the workspace - folders are not its axis`() {
        val html = searchPartial(nav = false, rows = listOf(board1, board2))

        html shouldContain "id=\"dash-list-wrapper\""
        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldContain "href=\"/dashboards/${board2.id}\""
        // Full navigations only: the workspace loads a Plotly bundle.
        html shouldContain "hx-boost=\"true\""
        html shouldNotContain "tpl-summary"
        html shouldNotContain "partials/dashboards/tree"
    }

    @Test
    fun `a draft-only dashboard's row says so - the list page's promise as a row state`() {
        val html = searchPartial(nav = false, rows = listOf(board2))

        // v1 is the listing version AND the only one; the badge names the state a reader is
        // about to meet (the workspace's choose-a-version state), never a broken page.
        html shouldContain "draft v1 pending release"
    }

    @Test
    fun `the sidebar's search results keep the nav listbox roles and the tree route's pager`() {
        val html = searchPartial(nav = true, rows = listOf(board1))

        html shouldContain "id=\"dash-tree-nav\""
        html shouldContain "role=\"listbox\""
        html shouldContain "/partials/dashboards/tree?"
        html shouldContain "scope=nav"
    }

    @Test
    fun `the sidebar's EMPTY search offers no nav-scope clear button (the engine has its own)`() {
        val html = searchPartial(nav = true, rows = emptyList())

        html shouldContain "No dashboards match your search"
        html shouldNotContain "data-nav-tree-clear"
    }

    @Test
    fun `the catalog page renders the flat list under its stable root - no tree anywhere (#400)`() {
        val html =
            engine.process(
                "dashboards/list",
                webContext("/dashboards").apply {
                    setVariable("q", "")
                    setVariable("lensUnavailable", null)
                    setVariable("scope", "page")
                    setVariable("rootId", DashboardListScope.PAGE.rootId)
                    setVariable("dashboards", listOf(board1))
                    setVariable("offset", 0)
                    setVariable("hasMore", false)
                    setVariable("total", 1)
                    setVariable("searching", true)
                },
            )

        // The catalog's swap root and its search control; folders are NOT the page's axis.
        html shouldContain "id=\"dash-list-wrapper\""
        html shouldContain "Search dashboards"
        html shouldContain "data-nav-tree-reveal=\"dashboards\""
        html shouldNotContain "tpl-summary"
        html shouldNotContain "dash-tree-page"
    }

    private fun searchPartial(
        nav: Boolean,
        rows: List<DashboardBrowseModel.DashboardLeafView>,
    ): String =
        engine.process(
            "partials/dashboard-search",
            webContext("/dashboards").apply {
                setVariable("scope", if (nav) "nav" else "page")
                setVariable("rootId", if (nav) DashboardBrowseModel.NAV_ROOT_ID else DashboardListScope.PAGE.rootId)
                setVariable("q", "")
                setVariable("dashboards", rows)
                setVariable("lensUnavailable", null)
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", rows.size)
            },
        )

    // ------------------------------------------------------------------ the workspace page

    @Test
    fun `a 2d board renders exactly one bundle script tag and its declaration is the server's value`() {
        val html = workspacePage(bundle = "2d")

        Regex("<script[^>]*plotly-\\d+d?\\.min\\.js[^>]*>").findAll(html).toList().size shouldBe 1
        html shouldContain "src=\"/vendor/plotly/plotly-3d.min.js\""
        html shouldContain "data-chart-assets"
        html shouldNotContain "plotly-2d.min.js"
        // The runtime and its three renderers ride the page, then the glue — and the container
        // carries the dashboard id, the only server value the glue reads. The DEFAULT
        // resolution writes NO version attribute (the glue's init stays "released").
        html shouldContain "/js/datapipelines-dashboard.js"
        html shouldContain "/js/datapipelines-dashboard-plotly.js"
        html shouldContain "/js/datapipelines-dashboard-table.js"
        html shouldContain "/js/datapipelines-dashboard-kpi.js"
        html shouldContain "/js/dashboards-page.js"
        html shouldContain "data-dp-dashboard-id="
        html shouldNotContain "data-dp-dashboard-version="
        // A booting board has no refusal to show: the region stays hidden, server-side.
        html shouldContain "id=\"dp-board-refusal\" class=\"ds-empty\" hidden=\"hidden\""
        // The tab strip and the glue of the workspace.
        html shouldContain "data-dp-tab=\"board\""
        html shouldContain "/js/workspace/tabs.js"
        html shouldContain "/js/dashboards/workspace.js"
        html shouldContain "id=\"dp-dialog\""
    }

    @Test
    fun `a 3d board renders the 3d bundle and its declaration names it`() {
        val html = workspacePage(bundle = "3d")

        Regex("<script[^>]*plotly-\\d+d?\\.min\\.js[^>]*>").findAll(html).toList().size shouldBe 1
        html shouldContain "src=\"/vendor/plotly/plotly-3d.min.js\""
        html shouldContain "data-chart-assets"
        html shouldNotContain "plotly-2d.min.js"
    }

    @Test
    fun `a NAMED version rides the data attribute channel, and every version switch is a full navigation`() {
        val html = workspacePage(bundle = "2d", namedVersion = 2)

        html shouldContain "data-dp-dashboard-version=\"2\""
        // The chip names the viewed version — the #369 banner's successor.
        html shouldContain "data-dp-viewed-label"
        html shouldContain "v2 · draft"
        // The selector rows never boost: a draft's bundle can differ from the release's
        // (the one-bundle rule, #369 review F1).
        Regex("<a[^>]*hx-boost=\"true\"[^>]*href=\"/dashboards/${board1.id}\\?version=[12][^>]*>").findAll(html).toList().size shouldBe 2
        html shouldContain "href=\"/dashboards/${board1.id}?version=1"
    }

    @Test
    fun `#409 - the choose-a-version state shows the NAME, offers the draft, and loads no bundle`() {
        val html =
            engine.process(
                "dashboards/board",
                webContext("/dashboards/${board1.id}").apply {
                    setVariable("dashboardId", board1.id.toString())
                    setVariable("dashboardName", board1.name)
                    setVariable("hasSelected", false)
                    setVariable("viewedVersion", null)
                    setVariable("viewedLabel", "no release yet")
                    setVariable("viewedIsDraft", false)
                    setVariable("viewedIsCurrent", false)
                    setVariable("servedVersion", null)
                    setVariable("hasDraft", true)
                    setVariable("draftVersion", 2)
                    setVariable("boardVersion", null)
                    setVariable("activeTab", "board")
                    setVariable("canRelease", true)
                    setVariable("canManageVersions", true)
                    setVariable("canSwitch", true)
                    setVariable("canDelete", true)
                    setVariable("canBindKeys", false)
                    setVariable("navCurrentPath", board1.name)
                    setVariable(
                        "versions",
                        listOf(DashboardWorkspaceController.VersionRow(2, "DRAFT", isServed = false, isSelected = false)),
                    )
                },
            )

        // The old board page rendered an empty h1 and "not found" for a dashboard the tree
        // had just linked (#409): the workspace names it, offers the draft with its link,
        // and says what state it is in.
        html shouldContain ">" + board1.name + "<"
        html shouldContain "Choose a version"
        html shouldContain "has no release yet"
        html shouldContain "href=\"/dashboards/${board1.id}?version=2&amp;tab=board\""
        val body = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
        body shouldNotContain "plotly-2d.min.js"
        body shouldNotContain "dashboards-page.js"
        body shouldNotContain "dashboard.not_found"
    }

    @Test
    fun `a board that cannot run renders the refusal state and no bundle at all`() {
        val html =
            engine.process(
                "dashboards/board",
                webContext("/dashboards/${board1.id}").apply {
                    setVariable("dashboardId", board1.id.toString())
                    setVariable("dashboardName", board1.name)
                    setVariable("refusalCode", "dashboard.not_found")
                    setVariable("refusalMessage", "This dashboard has no release to view yet.")
                    setVariable("hasSelected", true)
                    setVariable("viewedVersion", 1)
                    setVariable("viewedLabel", "v1 · released · current")
                    setVariable("viewedIsDraft", false)
                    setVariable("viewedIsCurrent", false)
                    setVariable("servedVersion", null)
                    setVariable("hasDraft", false)
                    setVariable("draftVersion", null)
                    setVariable("boardVersion", null)
                    setVariable("activeTab", "board")
                    setVariable("canRelease", true)
                    setVariable("canManageVersions", true)
                    setVariable("canSwitch", true)
                    setVariable("canDelete", true)
                    setVariable("canBindKeys", false)
                    setVariable("navCurrentPath", board1.name)
                    setVariable("versions", emptyList<DashboardWorkspaceController.VersionRow>())
                },
            )

        // Never a blank page: the refusal region, server-filled — and not one byte of Plotly
        // loaded for a board that has nothing to render into. The NAME stays in the h1.
        html shouldContain "This dashboard has no release to view yet."
        html shouldContain "dashboard.not_found"
        html shouldContain ">" + board1.name + "<"
        // The refusal is SHOWN: the hidden attribute is gone from the region's open tag.
        html shouldNotContain Regex("id=\"dp-board-refusal\"[^>]*hidden")
        val body = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
        body shouldNotContain "plotly-2d.min.js"
        body shouldNotContain "plotly-3d.min.js"
        body shouldNotContain "dashboards-page.js"
    }

    @Test
    fun `the events pane renders the caller's refreshes newest first and links their executions`() {
        val execution = DashboardBrowseModel.ExecutionLinkView("revenue_source", UUID.randomUUID().toString(), true)
        val row =
            DashboardBrowseModel.RefreshRowView(
                refreshId = UUID.randomUUID().toString(),
                status = "COMPLETED",
                scope = "all",
                startedAt = "2026-10-01T09:00:00Z",
                ago = "5 minutes ago",
                finished = true,
                executions = listOf(execution),
            )
        val html =
            engine.process(
                "partials/dashboard-refreshes",
                webContext("/dashboards/${board1.id}").apply {
                    setVariable("dashboardId", board1.id.toString())
                    setVariable("refreshes", listOf(row))
                },
            )

        html shouldContain ">revenue_source<"
        html shouldContain "href=\"/executions/${execution.executionId}\""
        html shouldContain "COMPLETED"
        html shouldContain "5 minutes ago"
        // 476 (#476): the pane declares only the REQUEST — the page glue's single shared timer
        // pokes `dp:refresh` on whichever copies are visible, so two mounted copies never mean
        // two self-scheduled pollers. The swap re-processes the fresh copy, so the poke keeps
        // landing after every re-fetch.
        html shouldContain "hx-trigger=\"dp:refresh\""
        html shouldNotContain "every 15s"
        html shouldNotContain "closest("
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "shared"
    }

    /** The workspace page through the real layout: the chrome plus the board's own model. */
    private fun workspacePage(
        bundle: String,
        namedVersion: Int? = null,
    ): String =
        engine.process(
            "dashboards/board",
            webContext("/dashboards/${board1.id}").apply {
                setVariable("dashboardId", board1.id.toString())
                setVariable("dashboardName", board1.name)
                setVariable("bundle", bundle)
                setVariable("hasSelected", true)
                setVariable("viewedVersion", namedVersion ?: 1)
                setVariable("viewedLabel", if (namedVersion != null) "v2 · draft" else "v1 · released · current")
                setVariable("viewedIsDraft", namedVersion != null)
                setVariable("viewedIsCurrent", namedVersion == null)
                setVariable("servedVersion", if (namedVersion != null) 1 else null)
                setVariable("hasDraft", namedVersion != null)
                setVariable("draftVersion", namedVersion)
                setVariable("boardVersion", namedVersion)
                setVariable("activeTab", "board")
                setVariable("canRelease", true)
                setVariable("canManageVersions", true)
                setVariable("canSwitch", true)
                setVariable("canDelete", true)
                setVariable("canBindKeys", false)
                setVariable("navCurrentPath", board1.name)
                setVariable("refreshes", emptyList<DashboardBrowseModel.RefreshRowView>())
                setVariable(
                    "versions",
                    listOf(
                        DashboardWorkspaceController.VersionRow(
                            1,
                            "RELEASED",
                            isServed = namedVersion == null,
                            isSelected =
                                namedVersion == null,
                        ),
                        DashboardWorkspaceController.VersionRow(2, "DRAFT", isServed = false, isSelected = namedVersion != null),
                    ),
                )
            },
        )

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
