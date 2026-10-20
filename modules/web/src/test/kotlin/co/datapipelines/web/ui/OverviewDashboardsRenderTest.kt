package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.RecentRow
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
 * #580 — the Dashboards overview at the RENDER (`overview/family.html` + `overview/guide-dashboards.html`):
 * the guide's introduction, steps, starter prompt and related links; the bars, counts and Review
 * drafts filtering the dashboards catalog; the framework's Unavailable and empty states reached
 * through UiConfig's production [FamilyInventory] and [FamilyRecent] over a [DashboardRepository]
 * stub; and the path rule's singular trap.
 */
class OverviewDashboardsRenderTest {
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

    @Test
    fun `the guide renders the introduction, the steps, the starter prompt and the related families`() {
        val html = html(page(Inventory(1, 0, 0, 0)), workspace = "<b>acme</b>")
        val doc = Jsoup.parse(html)

        doc.selectFirst("h1")!!.text() shouldBe "Dashboards"
        doc.selectFirst("[data-overview-intro]")!!.text() shouldContain
            "Dashboards bring pipeline results, visualizations and controls together in one view."
        doc.select("[data-overview-quickstart] .app-overview-steps li").size shouldBe 3
        // Escaped on the wire: a workspace name is text, never markup (th:text, not th:utext).
        html shouldNotContain "<b>acme</b>"
        val prompt = doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
        prompt.`val`() shouldContain "In workspace <b>acme</b>, build a draft dashboard that helps [audience] answer [question]."
        prompt.`val`() shouldContain "validate the dashboard and run the supported visualization tests."
        prompt.`val`() shouldContain "Leave release to me."
        prompt.children().shouldBeEmpty()
        doc.selectFirst("[data-overview-copy]")!!.attr("type") shouldBe "button"
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
        doc.select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly
            listOf("/overview/pipelines", "/overview/visualizations", "/overview/parameter-sets", "/docs/mcp-server")
        listOf("dpk_", "password", "secret", "token", "://").forEach { prompt.`val`().lowercase() shouldNotContain it }
    }

    @Test
    fun `the note names the agent's key for an author and a collaborator for everyone else`() {
        Jsoup.parse(html(page(Inventory(1, 0, 0, 0)))).selectFirst("#overview-prompt-note")!!.text() shouldContain
            "Your agent works with the permissions of its own API key."
        val viewer = Jsoup.parse(html(page(Inventory(1, 0, 0, 0)), canAuthor = false)).selectFirst("#overview-prompt-note")!!.text()
        viewer shouldContain "Your role here does not author dashboards"
        viewer shouldNotContain "its own API key"
    }

    @Test
    fun `the dashboards links filter the catalog and the discarded bar stays unlinked`() {
        val doc = render(page(Inventory(1, 1, 1, 1)))

        doc.select("[data-overview-chart] [data-bar] a").map { it.attr("href") } shouldContainExactly
            listOf("/dashboards?lifecycle=draft", "/dashboards?lifecycle=released", "/dashboards?lifecycle=released-with-draft")
        doc.selectFirst("[data-bar=discarded] a").shouldBeNull()
        doc.select("[data-overview-inventory] [data-count] a").map { it.attr("href") } shouldContainExactly
            listOf("/dashboards?lifecycle=with-drafts", "/dashboards?lifecycle=with-releases")
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/dashboards"
        doc.selectFirst("[data-overview-review]")!!.attr("href") shouldBe "/dashboards?lifecycle=with-drafts"
    }

    /**
     * Through the production read boundaries: a throwing repository is the reason and NO digit in the
     * inventory area (and Recently updated says it is unknown); the same DOM read over an answering
     * stub finds its counts, so the no-digit assertion is not vacuous.
     */
    @Test
    fun `a throwing dashboards repository is unavailable with no digit, an answering one renders its counts`() {
        val failing =
            mockk<DashboardRepository> {
                every { inventory(any(), any()) } throws IllegalStateException("database down")
                every { recent(any(), any(), any()) } throws IllegalStateException("database down")
            }
        val down = render(through(failing))
        val area = down.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Inventory could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table]").shouldBeEmpty()
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
        down.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently updated could not be read."
        down.selectFirst("textarea#overview-prompt").shouldNotBeNull()

        val id = UUID.fromString("00000000-0000-0000-0000-000000000580")
        val answering =
            mockk<DashboardRepository> {
                every { inventory(any(), any()) } returns Inventory(4, 2, 2, 1)
                every { recent(any(), any(), any()) } returns listOf(RecentRow(id, "acme/boards/sales", NOW.minusSeconds(60), true))
            }
        val up = render(through(answering))
        up.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("9", "6", "4", "1")
        val row = up.selectFirst("[data-overview-recent] li").shouldNotBeNull()
        row.selectFirst("a")!!.attr("href") shouldBe "/dashboards/$id"
        row.selectFirst(".ds-badge")!!.text() shouldBe "draft"
    }

    @Test
    fun `an empty workspace shows zeros, the empty recent line and the prompt`() {
        val empty =
            mockk<DashboardRepository> {
                every { inventory(any(), any()) } returns Inventory(0, 0, 0, 0)
                every { recent(any(), any(), any()) } returns emptyList()
            }
        val doc = render(through(empty))

        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("0", "0", "0", "0")
        doc.select("[data-overview-chart] rect").map { it.attr("width") } shouldContainExactly listOf("0", "0", "0", "0")
        doc.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
    }

    @Test
    fun `the overview path belongs to Dashboards and the singular path to nothing`() {
        AppNav.activeFor("/overview/dashboards")!!.section shouldBe "/dashboards"
        AppNav.crumbFor("/overview/dashboards")!!.second shouldBe "Dashboards"
        val singular = AppNav.activeFor("/overview/dashboard")
        (singular?.section == "/dashboard") shouldBe false
        (singular?.section == "/dashboards") shouldBe false
    }

    private fun through(dashboards: DashboardRepository): OverviewPage {
        val workspace = UUID.randomUUID()
        val inventory = UiConfig().familyInventory(mockk(), mockk(), dashboards, mockk(), mockk())
        val recent = UiConfig().familyRecent(mockk(), mockk(), dashboards, mockk(), mockk())
        return OverviewPage.of(
            OverviewPage.Family.DASHBOARDS,
            false,
            inventory.forFamily(NavigationFamily.DASHBOARDS, workspace, LensedView.EVERYTHING),
            recent.forFamily(NavigationFamily.DASHBOARDS, workspace, LensedView.EVERYTHING),
            NOW,
        )
    }

    private fun page(inventory: Inventory): OverviewPage =
        OverviewPage.of(
            OverviewPage.Family.DASHBOARDS,
            false,
            InventoryResult.Available(inventory),
            RecentResult.Available(emptyList()),
            NOW,
        )

    private fun render(page: OverviewPage): Document = Jsoup.parse(html(page))

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
        canAuthor: Boolean = true,
    ): String {
        val path = "/overview/${page.family}"
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles(canAuthor = canAuthor)
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", workspace)
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", path)
        context.setVariable("crumbGroup", AppNav.crumbFor(path)?.first)
        context.setVariable("crumbPage", AppNav.crumbFor(path)?.second)
        context.setVariable("searchMaxLength", SearchNeedle.MAX_LENGTH)
        context.setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        context.setVariable(OverviewController.MODEL, page)
        return engine.process(OverviewController.VIEW, context)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-18T05:00:00Z")
    }
}
