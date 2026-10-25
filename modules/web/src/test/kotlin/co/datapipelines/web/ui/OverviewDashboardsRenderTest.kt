package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.RecentRow
import co.datapipelines.visualization.DashboardRepository
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
import java.time.Instant
import java.util.UUID

/**
 * #580 — the Dashboards overview at the RENDER (`overview/family.html` + `overview/guide-dashboards.html`):
 * the guide's introduction, steps, starter prompt and related links; the strip and the task rows
 * filtering the dashboards catalog (#599: the lifecycle home); the framework's Unavailable and empty
 * states reached through UiConfig's production [FamilyInventory] and [FamilyRecent] over a
 * [DashboardRepository] stub; and the path rule's singular trap.
 */
class OverviewDashboardsRenderTest {
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
    fun `the dashboards strip links the filtered catalog and Retired stays unlinked`() {
        val doc = render(page(Inventory(1, 1, 1, 1)))

        doc.select("[data-overview-inventory] [data-count] a").map { it.attr("href") } shouldContainExactly
            listOf("/dashboards?lifecycle=released", "/dashboards?lifecycle=draft", "/dashboards?lifecycle=released-with-draft")
        doc.selectFirst("[data-count=retired] a").shouldBeNull()
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/dashboards"
        doc.select("[data-task] a").map { it.attr("href") } shouldContainExactly
            listOf("/dashboards?lifecycle=draft", "/dashboards?lifecycle=released")
        doc.selectFirst("[data-overview-review]").shouldBeNull()
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
                every { charts(any(), any(), any(), any()) } throws IllegalStateException("database down")
                every { recent(any(), any(), any()) } throws IllegalStateException("database down")
            }
        val down = render(through(failing))
        val area = down.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Inventory could not be read."
        area.select("[data-overview-panel], [data-count], [data-overview-table]").shouldBeEmpty()
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
        down.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently updated could not be read."
        down.selectFirst("textarea#overview-prompt").shouldNotBeNull()

        val id = UUID.fromString("00000000-0000-0000-0000-000000000580")
        val answering =
            mockk<DashboardRepository> {
                every { charts(any(), any(), any(), any()) } returns
                    OverviewRenderSupport.chartsOf(Inventory(4, 2, 2, 1), Activity.empty(NOW, OverviewRenderSupport.ACTIVE_DAYS))
                every { recent(any(), any(), any()) } returns listOf(RecentRow(id, "acme/boards/sales", NOW.minusSeconds(60), true))
            }
        val up = render(through(answering))
        up
            .select(
                "[data-overview-inventory] dl.app-overview-counts [data-count] .app-overview-count-v",
            ).map { it.text() } shouldContainExactly
            listOf("9", "2", "4", "0")
        up.select("[data-overview-foot] [data-count] .app-overview-count-v").map { it.text() } shouldContainExactly listOf("2", "1")
        val row = up.selectFirst("[data-overview-recent] .app-overview-recent li").shouldNotBeNull()
        row.selectFirst("a")!!.attr("href") shouldBe "/dashboards/$id"
        row.selectFirst(".ds-badge")!!.text() shouldBe "draft"
    }

    @Test
    fun `an empty workspace shows zeros, the empty recent line and the prompt`() {
        val empty =
            mockk<DashboardRepository> {
                every { charts(any(), any(), any(), any()) } returns ChartInventory.empty(NOW, OverviewRenderSupport.ACTIVE_DAYS)
                every { recent(any(), any(), any()) } returns emptyList()
            }
        val doc = render(through(empty))

        doc.select("[data-overview-inventory] [data-count] .app-overview-count-v").map { it.text() }.distinct() shouldContainExactly
            listOf("0")
        doc.select("[data-column] rect").map { it.attr("height") }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-overview-panel=maturity] rect").map { it.attr("width") }.distinct() shouldContainExactly listOf("0")
        doc.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
    }

    @Test
    fun `the overview path belongs to Dashboards and the singular path to Home`() {
        AppNav.activeFor("/overview/dashboards").shouldNotBeNull().section shouldBe "/dashboards"
        AppNav.crumbFor("/overview/dashboards").shouldNotBeNull().second shouldBe "Dashboards"
        // #659: Home follows the same boundary and overview rule as every other section.
        AppNav.activeFor("/overview/dashboard").shouldNotBeNull().section shouldBe "/dashboard"
        AppNav.crumbFor("/overview/dashboard").shouldNotBeNull().second shouldBe "Home"
        AppNav.matches("/dashboards", "/overview/dashboard") shouldBe false
    }

    private fun through(dashboards: DashboardRepository): OverviewPage {
        val workspace = UUID.randomUUID()
        val inventory = UiConfig().familyInventory(mockk(), mockk(), dashboards, mockk(), mockk())
        val recent = UiConfig().familyRecent(mockk(), mockk(), dashboards, mockk(), mockk())
        return OverviewPage.of(
            OverviewFamily.DASHBOARDS,
            false,
            inventory.chartsFor(NavigationFamily.DASHBOARDS, workspace, LensedView.EVERYTHING, NOW, OverviewRenderSupport.ACTIVE_DAYS),
            recent.forFamily(NavigationFamily.DASHBOARDS, workspace, LensedView.EVERYTHING),
            NOW,
        )
    }

    private fun page(inventory: Inventory): OverviewPage =
        OverviewPage.of(
            OverviewFamily.DASHBOARDS,
            false,
            ChartsResult.Available(OverviewRenderSupport.chartsOf(inventory, Activity.empty(NOW, OverviewRenderSupport.ACTIVE_DAYS))),
            RecentResult.Available(emptyList()),
            NOW,
        )

    private fun render(page: OverviewPage): Document = Jsoup.parse(html(page))

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
        canAuthor: Boolean = true,
    ): String = OverviewRenderSupport.html(page, workspace, canAuthor)

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-18T05:00:00Z")
    }
}
