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
 * catalog's contract (a FLAT list — folders are NOT the catalog's axis; a row navigates to
 * the workspace with its full path; a draft-only dashboard's row says so; the lens decides
 * what renders; since #466 no sidebar presentation is left in the fragment), the workspace page's chart
 * scripts (the engine's files in load order from the server's list, then the runtime and its renderers,
 * #987), the viewed-version chip and the version selector (every version link a prepared main-content
 * navigation), the choose-a-version state (#409: the
 * NAME in the heading, no admitted draft, never the old empty `h1`), and the refusal state
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

    private val board1 =
        DashboardBrowseModel.DashboardLeafView(UUID.randomUUID(), "acme/dashboards/revenue", "revenue", "Revenue", 3, true)
    private val board2 =
        DashboardBrowseModel.DashboardLeafView(UUID.randomUUID(), "acme/dashboards/costs", "costs", "Costs", 1, false)

    // ------------------------------------------------------------------ the catalog (#400)

    @Test
    fun `the catalog's rows are a FLAT list into the workspace - folders are not its axis`() {
        val html = searchPartial(rows = listOf(board1, board2))

        html shouldContain "id=\"dash-list-wrapper\""
        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldContain "href=\"/dashboards/${board2.id}\""
        // Prepared main-content navigation, and the full path rides on `title` (§9.4).
        html shouldContain "hx-boost=\"true\""
        html shouldContain "title=\"acme/dashboards/revenue\""
        // D55: the working version.
        html shouldContain ">v3<"
        html shouldNotContain "tpl-summary"
        html shouldNotContain "partials/dashboards/tree"
    }

    @Test
    fun `a dashboard the lens hides from the model is absent from the HTML`() {
        val html = searchPartial(rows = listOf(board1))

        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldNotContain "href=\"/dashboards/${board2.id}\""
    }

    @Test
    fun `a draft-only dashboard's row says so - the list page's promise as a row state`() {
        val html = searchPartial(rows = listOf(board2))

        // v1 is the listing version AND the only one; the badge names the state a reader is
        // about to meet (the workspace opens that draft directly, #537).
        html shouldContain "draft v1 pending release"
    }

    @Test
    fun `the catalog's rows are a plain list - no sidebar listbox, no nav scope, no tree route (#466)`() {
        val html = searchPartial(rows = listOf(board1), q = "rev")

        html shouldContain "data-dashboard-list=\"page\""
        html shouldNotContain "role=\"listbox\""
        html shouldNotContain "role=\"option\""
        html shouldNotContain "scope="
        html shouldNotContain "data-nav-tree-clear"
    }

    @Test
    fun `the catalog's EMPTY search offers the Clear search control naming its box (#432)`() {
        // #432: the no-match clear names the box it empties (shell.js) and replaces a search
        // still in flight from that box.
        val catalogMiss = searchPartial(rows = emptyList(), q = "zzz")

        catalogMiss shouldContain "No dashboards match your search"
        catalogMiss shouldContain "Clear search"
        catalogMiss shouldContain "data-dp-clear-filters=\"#dashboard-filter-q\""
        catalogMiss shouldContain "hx-sync=\"#dashboard-filter-q:replace\""
    }

    @Test
    fun `a search with 200 or more matches states no cap - the pager's total is the count (#524)`() {
        // Since #512 the catalog pages the SQL search, so `total` is the true match count and
        // every match is reachable through the pager; the old "reads at most 200" note described
        // an in-memory first-200 read that no longer exists.
        val html = searchPartial(rows = listOf(board1), q = "board", total = 201)

        html shouldNotContain "at most 200"
        html shouldNotContain "tpl-overflow"
        html shouldContain "Showing 1 of 201"
    }

    @Test
    fun `the catalog page renders the flat list under its stable root - no tree anywhere (#400)`() {
        val html =
            engine.process(
                "dashboards/list",
                webContext("/dashboards").apply {
                    setVariable("q", "")
                    setVariable("lensUnavailable", null)
                    setVariable("rootId", DashboardBrowseModel.PAGE_ROOT_ID)
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
        rows: List<DashboardBrowseModel.DashboardLeafView>,
        q: String = "",
        total: Int = rows.size,
    ): String =
        engine.process(
            "partials/dashboard-search",
            webContext("/dashboards").apply {
                setVariable("rootId", DashboardBrowseModel.PAGE_ROOT_ID)
                setVariable("q", q)
                setVariable("dashboards", rows)
                setVariable("lensUnavailable", null)
                setVariable("offset", 0)
                setVariable("hasMore", total > rows.size)
                setVariable("total", total)
            },
        )

    // ------------------------------------------------------------------ the workspace page

    @Test
    fun `a board with something to draw loads the engine's files in load order, then the runtime, its renderers and the glue`() {
        val html = workspacePage()

        html shouldContain "data-page-assets"
        // The catalog opens with the chart scripts, whole and in order: the engine's files from the server's list
        // (#987 D2: the same files the server draws with), then the runtime, its three renderers and the glue — and
        // the container carries the dashboard id, the only server value the glue reads. The DEFAULT resolution
        // writes NO version attribute (the glue's init stays "released").
        val chartScripts =
            JsRenderedAssetConfig.scripts +
                listOf(
                    "/js/datapipelines-dashboard.js",
                    "/js/datapipelines-chart-interactions.js",
                    "/js/datapipelines-dashboard-chart.js",
                    "/js/datapipelines-dashboard-table.js",
                    "/js/datapipelines-dashboard-kpi.js",
                    "/js/dashboards/events-dock.js",
                    "/js/dashboards-page.js",
                )
        val catalogScripts =
            Regex("src=\"(/[^\"]+)\"")
                .findAll(html.substringAfter("<template data-page-assets>").substringBefore("</template>"))
                .map { it.groupValues[1] }
                .toList()
        catalogScripts.take(chartScripts.size) shouldBe chartScripts
        JsRenderedAssetConfig.scripts.first() shouldBe "/js/js-rendered/00-namespace.js"
        html shouldContain "data-dp-dashboard-id="
        html shouldNotContain "data-dp-dashboard-version="
        // A booting board has no refusal to show: the region stays hidden, server-side.
        html shouldContain "id=\"dp-board-refusal\" class=\"ds-empty\" hidden=\"hidden\""
        // The tab strip and the glue of the workspace.
        html shouldContain "data-dp-tab=\"board\""
        html shouldContain "/js/workspace/tabs.js"
        html shouldContain "/js/dashboards/workspace.js"
        html shouldContain "id=\"dp-dialog\""
        // #876 G6: the Dependencies tab, a mount over the selected version (name + version, never an id), its
        // eight catalog entries last in the page's catalog.
        html shouldContain "data-dp-tab=\"dependencies\""
        Regex("<div class=\"dp-deps\" data-dp-deps data-family=\"dashboard\"[^>]*data-name=\"[^\"]+\"[^>]*data-version=\"\\d+\"")
            .containsMatchIn(html) shouldBe true
        Regex("id=\"dp-pane-dependencies\"[^>]*data-lazy-url").containsMatchIn(html) shouldBe false
        val catalog = html.substringAfter("<template data-page-assets>").substringBefore("</template>")
        Regex("src=\"(/[^\"]+)\"")
            .findAll(catalog)
            .map { it.groupValues[1] }
            .toList()
            .takeLast(RELEASE_DEPENDENCY_CATALOG.size) shouldBe RELEASE_DEPENDENCY_CATALOG
    }

    @Test
    fun `a NAMED version rides the data attribute channel, and every version link is a prepared main-content navigation`() {
        val html = workspacePage(namedVersion = 2)

        html shouldContain "data-dp-dashboard-version=\"2\""
        // The chip names the viewed version — the #369 banner's successor.
        html shouldContain "data-dp-viewed-label"
        html shouldContain "v2 · draft"
        // The selector rows are boosted: the shell stays and the main content is replaced.
        Regex("<a[^>]*hx-boost=\"true\"[^>]*href=\"/dashboards/${board1.id}\\?version=[12][^>]*>").findAll(html).toList().size shouldBe 2
        html shouldContain "href=\"/dashboards/${board1.id}?version=1"
    }

    @Test
    fun `#409 - the choose-a-version state with no admitted draft shows the NAME and loads no chart script`() {
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
                    setVariable(
                        "versions",
                        listOf(DashboardWorkspaceController.VersionRow(2, "DISCARDED", isServed = false, isSelected = false)),
                    )
                },
            )

        // The old board page rendered an empty h1 and "not found" for a dashboard the tree
        // had just linked (#409): with no admitted draft the workspace still names it and
        // offers its history; #537 handles the admitted-draft shape in the model.
        html shouldContain ">" + board1.name + "<"
        html shouldContain "Choose a version"
        html shouldContain "has no release yet"
        html shouldContain "href=\"/dashboards/${board1.id}?version=2&amp;tab=board\""
        val body = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
        body shouldNotContain JsRenderedAssetConfig.PREFIX
        body shouldNotContain "dashboards-page.js"
        body shouldNotContain "dashboard.not_found"
    }

    @Test
    fun `a board that cannot run renders the refusal state and no chart script at all`() {
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

        // Never a blank page: the refusal region, server-filled — and not one chart script
        // loaded for a board that has nothing to render into. The NAME stays in the h1.
        html shouldContain "This dashboard has no release to view yet."
        html shouldContain "dashboard.not_found"
        html shouldContain ">" + board1.name + "<"
        // The refusal is SHOWN: the hidden attribute is gone from the region's open tag.
        html shouldNotContain Regex("id=\"dp-board-refusal\"[^>]*hidden")
        val body = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
        body shouldNotContain JsRenderedAssetConfig.PREFIX
        body shouldNotContain "/js/datapipelines-dashboard-chart.js"
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
    private fun workspacePage(namedVersion: Int? = null): String =
        engine.process(
            "dashboards/board",
            webContext("/dashboards/${board1.id}").apply {
                setVariable("dashboardId", board1.id.toString())
                setVariable("dashboardName", board1.name)
                setVariable("chartEngineScripts", JsRenderedAssetConfig.scripts)
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
