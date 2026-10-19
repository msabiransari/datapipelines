package co.datapipelines.web.ui

import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.web.ui.OverviewPage.Bar
import co.datapipelines.web.ui.OverviewPage.Chart
import co.datapipelines.web.ui.OverviewPage.Count
import co.datapipelines.web.ui.OverviewPage.InventoryPanel
import co.datapipelines.web.ui.OverviewPage.RecentItem
import co.datapipelines.web.ui.OverviewPage.RecentPanel
import co.datapipelines.web.ui.OverviewPage.RecentSection
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

/**
 * #584 — the Schedules home at the RENDER (`overview/family.html` + `overview/guide-schedules.html`):
 * What schedules are for and How to create one — a person's steps, the New schedule link only for
 * an authoring role, and NO agent card, starter prompt or Copy button (no agent tool exists); the
 * two charts with their table twins; Up next and Recently changed linking the explorer's leaf; the
 * related links by role; and the frame's Unavailable state with no digit.
 */
class OverviewSchedulesRenderTest {
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
    fun `the guide is a person's how-to - no agent card, no starter prompt, no copy button`() {
        val doc = render(page())

        doc.selectFirst("h1")!!.text() shouldBe "Schedules"
        doc.selectFirst("[data-overview-intro]")!!.text() shouldContain
            "A schedule runs a pipeline's current version at the times a cron pattern names"
        val create = doc.selectFirst("[data-overview-create]").shouldNotBeNull()
        create.select(".app-overview-steps li").size shouldBe 3
        create.text() shouldContain "nothing sends"
        doc.select("[data-overview-prompt], textarea, [data-overview-copy]").shouldBeEmpty()
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/schedules"
    }

    @Test
    fun `New schedule renders for an authoring role only - everyone else reads the steps`() {
        val author = render(page())
        author.selectFirst("[data-overview-new-schedule]").shouldNotBeNull().attr("href") shouldBe "/schedules"
        author.selectFirst("[data-overview-create-note]")!!.text() shouldContain "opens the Schedules list"

        val viewer = render(page(), canAuthor = false)
        viewer.selectFirst("[data-overview-new-schedule]").shouldBeNull()
        viewer.select("[data-overview-create] .app-overview-steps li").size shouldBe 3
        viewer.selectFirst("[data-overview-create-note]")!!.text() shouldContain "an author or a workspace admin creates"
    }

    @Test
    fun `related links pipelines, executions for a role that reads them, and the REST reference`() {
        render(page()).select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly
            listOf("/overview/pipelines", "/executions", "/docs/rest-api")
        // The promoter reads no executions (D11): no link it cannot open.
        val promoter = render(page(), canAuthor = false, canReadExecutions = false)
        promoter.select("[data-overview-related] a").map { it.attr("href") } shouldContainExactly
            listOf("/overview/pipelines", "/docs/rest-api")
    }

    @Test
    fun `the two charts render with their own captions, bars and table twins, unlinked`() {
        val doc = render(page())

        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("4", "2", "1", "1")
        doc.select("[data-overview-chart]").map { it.attr("aria-label") } shouldContainExactly listOf("By condition", "Last run")
        doc.select("[data-overview-chart][data-chart=condition] [data-bar]").map { it.attr("data-bar") } shouldContainExactly
            listOf("enabled", "paused", "blocked")
        doc.select("[data-overview-chart][data-chart=last-run] [data-bar]").map { it.attr("data-bar") } shouldContainExactly
            listOf("succeeded", "failed", "in-progress", "did-not-run", "never-ran")
        doc.select("[data-overview-chart] a").shouldBeEmpty()
        doc.select("[data-chart-table=last-run] caption").text() shouldBe "Last run"
        doc.select("[data-chart-table=last-run] thead th").map { it.text() } shouldContainExactly listOf("Last run", "Schedules")
        doc.select("[data-chart-table=condition] tbody td").map { it.text() } shouldContainExactly listOf("2", "1", "1")
        doc.selectFirst("[data-overview-review]").shouldBeNull()
    }

    @Test
    fun `up next and recently changed link the explorer's leaf, names as text`() {
        val html = html(page())
        val doc = Jsoup.parse(html)

        doc.select("[data-overview-recent] h3").map { it.text() } shouldContainExactly listOf("Up next", "Recently changed")
        doc.select("[data-overview-recent] li a").map { it.attr("href") } shouldContainExactly
            listOf("/schedules?id=$ID_A", "/schedules?id=$ID_B")
        doc.select("[data-overview-recent] time").map { it.text() } shouldContainExactly listOf("in 3 hours", "2 hours ago")
        doc.select("[data-overview-recent] .ds-badge").shouldBeEmpty()
        html shouldNotContain "<b>x</b>"
        doc.select("[data-overview-recent] li a").last()!!.text() shouldBe "ops/<b>x</b>"
    }

    @Test
    fun `an unavailable read leaves no digit in the inventory area and the lists say why`() {
        val down =
            render(
                page(
                    inventory = null,
                    unavailable = ScheduleOverviewController.UNAVAILABLE,
                    recent =
                        RecentPanel(
                            null,
                            listOf(
                                RecentSection("Up next", "n", null, "Up next could not be read.", "e"),
                                RecentSection("Recently changed", "n", null, "Recently changed could not be read.", "e"),
                            ),
                        ),
                ),
            )
        val area = down.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Schedules could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table]").shouldBeEmpty()
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
        down.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently changed could not be read."
        down.selectFirst("[data-overview-create]").shouldNotBeNull()
    }

    @Test
    fun `a promoter's page names the admitted inventory`() {
        val doc = render(page(lensed = true), canAuthor = false, canReadExecutions = false)
        doc.selectFirst("#overview-inventory-h")!!.text() shouldBe "Admitted inventory"
        doc.selectFirst("[data-overview-context]")!!.text() shouldContain "only what your promotion view shows"
    }

    private fun page(
        inventory: InventoryPanel? = answerInventory,
        unavailable: String? = null,
        recent: RecentPanel = answerRecent,
        lensed: Boolean = false,
    ): OverviewPage =
        OverviewPage(
            family = "schedules",
            title = "Schedules",
            catalogHref = "/schedules",
            lensed = lensed,
            inventory = inventory,
            unavailable = unavailable,
            recent = recent,
            guided = true,
        )

    private fun bar(
        key: String,
        value: Long,
        tallest: Long,
    ): Bar = Bar(key, key, value, OverviewPage.width(value, tallest), "released", "$key: $value", null)

    private fun render(
        page: OverviewPage,
        canAuthor: Boolean = true,
        canReadExecutions: Boolean = true,
    ): Document = Jsoup.parse(html(page, canAuthor, canReadExecutions))

    private fun html(
        page: OverviewPage,
        canAuthor: Boolean = true,
        canReadExecutions: Boolean = true,
    ): String {
        val path = ScheduleOverviewController.PATH
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles(canAuthor = canAuthor, canReadExecutions = canReadExecutions)
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
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

    private val answerInventory =
        InventoryPanel(
            counts =
                listOf(
                    Count("schedules", "Schedules", 4, "h", null),
                    Count("enabled", "Enabled", 2, "h", null),
                    Count("paused", "Paused", 1, "h", null),
                    Count("blocked", "Blocked", 1, "h", null),
                ),
            charts =
                listOf(
                    Chart(
                        "condition",
                        "By condition",
                        listOf(bar("enabled", 2, 2), bar("paused", 1, 2), bar("blocked", 1, 2)),
                        headings = listOf("Condition", "Schedules"),
                    ),
                    Chart(
                        "last-run",
                        "Last run",
                        listOf(
                            bar("succeeded", 1, 2),
                            bar("failed", 1, 2),
                            bar("in-progress", 0, 2),
                            bar("did-not-run", 0, 2),
                            bar("never-ran", 2, 2),
                        ),
                        note = "Each schedule counts once, by how its most recent run ended. Not a history.",
                        headings = listOf("Last run", "Schedules"),
                    ),
                ),
        )

    private val answerRecent =
        RecentPanel(
            review = null,
            sections =
                listOf(
                    RecentSection(
                        "Up next",
                        "Enabled schedules by their next run, soonest first.",
                        listOf(RecentItem("ops/nightly", "/schedules?id=$ID_A", AT.toString(), "in 3 hours", ABSOLUTE, false)),
                        null,
                        "No enabled schedule is waiting to run.",
                    ),
                    RecentSection(
                        "Recently changed",
                        "The latest edits in this workspace, newest first.",
                        listOf(RecentItem("ops/<b>x</b>", "/schedules?id=$ID_B", AT.toString(), "2 hours ago", ABSOLUTE, false)),
                        null,
                        "No schedules yet.",
                    ),
                ),
        )

    private companion object {
        val AT: Instant = Instant.parse("2026-10-18T05:00:00Z")
        const val ID_A = "00000000-0000-0000-0000-0000000005a4"
        const val ID_B = "00000000-0000-0000-0000-0000000005b4"
        const val ABSOLUTE = "2026-10-18 05:00 UTC"
    }
}
