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
 * #10 L3b — the dashboards pages pinned at the RENDER (ShellRenderTest's mould): the tree
 * level's contract (folders first, root holds no leaves, a leaf navigates un-boosted, the lens
 * decides what renders), the board page's ONE-bundle contract (exactly one Plotly script tag,
 * its declaration equal to the server's `renderer.bundle`, for a 2d board and a 3d board), and
 * the refusal state that stands in for a board that cannot run.
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

    private val folderA = DashboardBrowseModel.DashboardFolderView("acme/dashboards", "acme", 2, "dash-level-page-aaaa", "page")
    private val folderB = DashboardBrowseModel.DashboardFolderView("ops", "ops", 1, "dash-level-page-bbbb", "page")
    private val board1 =
        DashboardBrowseModel.DashboardLeafView(UUID.randomUUID(), "acme/dashboards/revenue", "revenue", "Revenue", 3, true)
    private val board2 =
        DashboardBrowseModel.DashboardLeafView(UUID.randomUUID(), "acme/dashboards/costs", "costs", "Costs", 1, false)

    @Test
    fun `the root level lists folders only - a dashboard name needs a folder`() {
        val html =
            engine.process(
                "partials/dashboard-tree-level",
                webContext("/dashboards").apply {
                    setVariable("prefix", "")
                    setVariable("scope", "page")
                    setVariable("levelId", DashboardBrowseModel.PAGE_ROOT_ID)
                    setVariable("folders", listOf(folderA, folderB))
                    setVariable("dashboards", emptyList<DashboardBrowseModel.DashboardLeafView>())
                    setVariable("offset", 0)
                    setVariable("hasMore", false)
                    setVariable("total", 0)
                },
            )

        // Two folders, their lazy one-level requests riding the summaries, the page scope on
        // every URL; and NO leaf at the root.
        Regex("hx-target=\"next .tpl-level\"").findAll(html).toList().size shouldBe 2
        html shouldContain "/partials/dashboards/tree?prefix=acme/dashboards&amp;scope=page"
        html shouldContain "/partials/dashboards/tree?prefix=ops&amp;scope=page"
        html shouldNotContain "tpl-leaf"
    }

    @Test
    fun `a level's children render as un-boosted links to the board page`() {
        val html =
            engine.process(
                "partials/dashboard-tree-level",
                webContext("/dashboards").apply {
                    setVariable("prefix", "acme/dashboards")
                    setVariable("scope", "page")
                    setVariable("levelId", "dash-level-page-x")
                    setVariable("folders", emptyList<DashboardBrowseModel.DashboardFolderView>())
                    setVariable("dashboards", listOf(board1, board2))
                    setVariable("offset", 0)
                    setVariable("hasMore", false)
                    setVariable("total", 2)
                },
            )

        // A leaf NAVIGATES: a real href (the board page), full navigation — hx-boost="false",
        // the §6.4 one-bundle rule — and the full path on `title`.
        html shouldContain "hx-boost=\"false\""
        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldContain "title=\"acme/dashboards/revenue\""
        // versioning §7: the unreleased edits stay visible, marked.
        html shouldContain "tpl-leaf-draft"
        html shouldContain ">v3<"
    }

    @Test
    fun `a dashboard the lens hides from the model is absent from the HTML`() {
        // The promoter-lens fixture: the model carries only the admitted dashboard — the same
        // shape the service's lensed read hands the template.
        val html =
            engine.process(
                "partials/dashboard-tree-level",
                webContext("/dashboards").apply {
                    setVariable("prefix", "acme/dashboards")
                    setVariable("scope", "page")
                    setVariable("levelId", "dash-level-page-x")
                    setVariable("folders", emptyList<DashboardBrowseModel.DashboardFolderView>())
                    setVariable("dashboards", listOf(board1))
                    setVariable("offset", 0)
                    setVariable("hasMore", false)
                    setVariable("total", 1)
                },
            )

        html shouldContain "href=\"/dashboards/${board1.id}\""
        html shouldNotContain "href=\"/dashboards/${board2.id}\""
    }

    @Test
    fun `a 2d board renders exactly one bundle script tag and its declaration is the server's value`() {
        val html = boardPage(bundle = "2d")

        Regex("<script[^>]*plotly-\\d+d?\\.min\\.js[^>]*>").findAll(html).toList().size shouldBe 1
        html shouldContain "src=\"/vendor/plotly/plotly-2d.min.js\""
        html shouldContain "data-dp-plotly-bundle=\"2d\""
        html shouldNotContain "plotly-3d.min.js"
        // The runtime and its three renderers ride the page, then the glue — and the container
        // carries the dashboard id, the only server value the glue reads.
        html shouldContain "/js/datapipelines-dashboard.js"
        html shouldContain "/js/datapipelines-dashboard-plotly.js"
        html shouldContain "/js/datapipelines-dashboard-table.js"
        html shouldContain "/js/datapipelines-dashboard-kpi.js"
        html shouldContain "/js/dashboards-page.js"
        html shouldContain "data-dp-dashboard-id="
        // A booting board has no refusal to show: the region stays hidden, server-side.
        html shouldContain "id=\"dp-board-refusal\" class=\"ds-empty\" hidden=\"hidden\""
    }

    @Test
    fun `a 3d board renders the 3d bundle and its declaration names it`() {
        val html = boardPage(bundle = "3d")

        Regex("<script[^>]*plotly-\\d+d?\\.min\\.js[^>]*>").findAll(html).toList().size shouldBe 1
        html shouldContain "src=\"/vendor/plotly/plotly-3d.min.js\""
        html shouldContain "data-dp-plotly-bundle=\"3d\""
        html shouldNotContain "plotly-2d.min.js"
    }

    @Test
    fun `the draft preview's way back to the released view is an un-boosted link`() {
        // #369 review F1: preview -> board is board-to-board, the swap the one-bundle rule forbids
        // (a draft's bundle can differ from the release's); the banner's link is a FULL navigation.
        val html =
            engine.process(
                "dashboards/board",
                webContext("/dashboards/${board1.id}/preview").apply {
                    setVariable("dashboardId", board1.id.toString())
                    setVariable("bundle", "2d")
                    setVariable("dashboardName", board1.name)
                    setVariable("previewVersion", 2)
                    setVariable("previewStatus", "DRAFT")
                    setVariable("refreshes", emptyList<DashboardBrowseModel.RefreshRowView>())
                },
            )

        html shouldContain "data-dp-dashboard-version=\"2\""
        val back = Regex("<a[^>]*>Back to the released view</a>").find(html)?.value ?: error("no way back in: $html")
        back shouldContain "href=\"/dashboards/${board1.id}\""
        back shouldContain "hx-boost=\"false\""
    }

    @Test
    fun `a board that cannot run renders the refusal state and no bundle at all`() {
        val html =
            engine.process(
                "dashboards/board",
                webContext("/dashboards/${board1.id}").apply {
                    setVariable("dashboardId", board1.id.toString())
                    setVariable("refusalCode", "dashboard.not_found")
                    setVariable("refusalMessage", "This dashboard has no release to view yet.")
                    setVariable("dashboardName", board1.name)
                },
            )

        // Never a blank page: the refusal region, server-filled — and not one byte of Plotly
        // loaded for a board that has nothing to render into.
        html shouldContain "This dashboard has no release to view yet."
        html shouldContain "dashboard.not_found"
        // The refusal is SHOWN: the hidden attribute is gone from the region's open tag.
        html shouldNotContain Regex("id=\"dp-board-refusal\"[^>]*hidden")
        html shouldNotContain "plotly-2d.min.js"
        html shouldNotContain "plotly-3d.min.js"
        html shouldNotContain "dashboards-page.js"
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
        // The bounded poll: the first re-fetch is delayed (the server just rendered), then
        // every 15s, and the pane re-fetches ITSELF so the poll survives the swap.
        html shouldContain "hx-trigger=\"load delay:15s, every 15s\""
        html shouldContain "hx-swap=\"outerHTML\""
        html shouldContain "shared"
    }

    /** The board page through the real layout: the chrome plus the board's own model. */
    private fun boardPage(bundle: String): String =
        engine.process(
            "dashboards/board",
            webContext("/dashboards/${board1.id}").apply {
                setVariable("dashboardId", board1.id.toString())
                setVariable("bundle", bundle)
                setVariable("dashboardName", board1.name)
                setVariable("refreshes", emptyList<DashboardBrowseModel.RefreshRowView>())
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
