package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.scheduler.LastRunOutcome
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.ScheduleSummary
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
import io.mockk.every
import io.mockk.mockk
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

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

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    /**
     * #639 — the page the CONTROLLER builds, rendered: four metrics whose notes are the tiles'
     * help sentences and whose dots are their condition bars' tones, the View all badge equal to
     * the total, the two panels with descriptions and info dialogs, the See what runs next row
     * above Up next and Recently changed, and the guide's v2 words.
     */
    @Test
    fun `the controller's page renders the v2 regions - metrics, panels with dialogs, the task row, the quick start`() {
        val doc = Jsoup.parse(controllerHtml(WorkspaceRole.AUTHOR, blocked = 1))

        doc.select("[data-overview-summary] [data-count]").map { it.attr("data-count") } shouldContainExactly
            listOf("schedules", "enabled", "paused", "blocked")
        doc.select("[data-overview-summary] .app-overview-count-help").map { it.text() } shouldContainExactly
            listOf(
                "Each live schedule once: enabled, paused and blocked added together.",
                "Firing on their pattern.",
                "Kept, but not firing until resumed.",
                "Stopped by a failure until someone unblocks them.",
            )
        doc.select("[data-count=schedules] .app-overview-dot").shouldBeEmpty()
        listOf("enabled", "paused", "blocked").forEach { key ->
            doc.selectFirst("[data-count=$key] .app-overview-dot")!!.attr("data-tone") shouldBe
                doc.selectFirst("[data-chart=condition] [data-bar=$key]")!!.attr("data-tone")
        }
        doc.select("[data-overview-summary] .app-overview-dot").map { it.attr("data-tone") } shouldContainExactly
            listOf("success", "warning", "danger")
        doc.select("[data-chart=last-run] [data-bar]").map { it.attr("data-tone") } shouldContainExactly
            listOf("success", "danger", "mixed", "discarded", "discarded")
        doc.selectFirst("[data-overview-view-all-count]")!!.text() shouldBe "4"
        doc.select("[data-overview-panel] .app-overview-panel-sub").map { it.text() } shouldContainExactly
            listOf("Every live schedule once, by the condition the explorer shows.", "Each schedule once, by how its latest run ended.")
        doc.select("[data-overview-panel] [data-open]").map { it.attr("data-open") } shouldContainExactly listOf("condition", "last-run")
        doc.selectFirst("[data-dialog-section=condition]")!!.text() shouldContain "blocked wins"
        doc.selectFirst("[data-dialog-section=last-run]")!!.text() shouldContain "Not a history"
        // #641: one blocked schedule puts Unblock first (its own case below pins the row).
        doc.select("[data-overview-recent] [data-task]").map { it.attr("data-task") } shouldContainExactly listOf("unblock", "next")
        val task = doc.selectFirst("[data-task=next] a").shouldNotBeNull()
        task.attr("href") shouldBe "/schedules"
        task.selectFirst("b")!!.text() shouldBe "See what runs next"
        doc.select("[data-overview-recent] .app-overview-recent-section h3").map { it.text() } shouldContainExactly
            listOf("Up next", "Recently changed")
        doc.selectFirst(".app-overview-subtitle").shouldNotBeNull()
        doc.select("[data-overview-create] .app-overview-steps li b").map { it.text() } shouldContainExactly
            listOf("Pick the pipeline.", "Set when it runs.", "Choose who is told.")
        doc.selectFirst(".app-overview-actions [data-overview-new-schedule]").shouldNotBeNull()
    }

    /**
     * #641 — the explorer filters by condition, so a blocked workspace gets its next step first:
     * Unblock N schedules, linking the blocked explorer, N worded from the one summary read.
     */
    @Test
    fun `blocked schedules put Unblock first - linking the explorer filtered to blocked, the count worded`() {
        val doc = Jsoup.parse(controllerHtml(WorkspaceRole.AUTHOR, blocked = 2))
        doc.select("[data-overview-recent] [data-task]").map { it.attr("data-task") } shouldContainExactly listOf("unblock", "next")
        val unblock = doc.selectFirst("[data-task=unblock] a").shouldNotBeNull()
        unblock.attr("href") shouldBe "/schedules?condition=blocked"
        unblock.selectFirst("b")!!.text() shouldBe "Unblock 2 schedules"
        unblock.selectFirst("small")!!.text() shouldBe "2 schedules are stopped by a failure until someone unblocks them."
        unblock.selectFirst(".app-overview-task-icon use")!!.attr("href") shouldBe "/vendor/icons/lucide-sprite.svg#octagon-alert"

        val one = Jsoup.parse(controllerHtml(WorkspaceRole.AUTHOR, blocked = 1)).selectFirst("[data-task=unblock] a").shouldNotBeNull()
        one.selectFirst("b")!!.text() shouldBe "Unblock 1 schedule"
        one.selectFirst("small")!!.text() shouldBe "1 schedule is stopped by a failure until someone unblocks it."
    }

    @Test
    fun `no blocked schedule - no Unblock row and no blocked link in the recent panel`() {
        val doc = Jsoup.parse(controllerHtml(WorkspaceRole.AUTHOR, blocked = 0))
        doc.select("[data-overview-recent] [data-task]").map { it.attr("data-task") } shouldContainExactly listOf("next")
        doc.select("[data-overview-recent] a[href*=blocked]").shouldBeEmpty()
    }

    /** #641 — the condition tiles and bars open the explorer filtered to their bucket; Schedules and Last run stay unlinked. */
    @Test
    fun `the condition tiles and bars link the filtered explorer - the total and the last-run bars do not`() {
        val doc = Jsoup.parse(controllerHtml(WorkspaceRole.AUTHOR, blocked = 1))
        listOf("enabled", "paused", "blocked").forEach { key ->
            doc.selectFirst("[data-overview-summary] [data-count=$key] a")!!.attr("href") shouldBe "/schedules?condition=$key"
            doc.selectFirst("[data-chart=condition] [data-bar=$key] a")!!.attr("href") shouldBe "/schedules?condition=$key"
        }
        doc.select("[data-overview-summary] [data-count=schedules] a").shouldBeEmpty()
        doc.select("[data-chart=last-run] a").shouldBeEmpty()
        doc.selectFirst("[data-dialog-section=condition]")!!.text() shouldContain
            "Each bar and each condition tile opens the explorer filtered to that condition; the Last run bars are not filters."
        doc.selectFirst("[data-dialog-section=condition]")!!.text() shouldNotContain "The bars are not filters"
    }

    /** The New schedule action is the canAuthor set's: a viewer and a promoter read the steps and no button. */
    @Test
    fun `the controller's page offers New schedule to an author and never to a viewer`() {
        Jsoup.parse(controllerHtml(WorkspaceRole.AUTHOR)).select("[data-overview-new-schedule]").size shouldBe 1
        listOf(WorkspaceRole.VIEWER, WorkspaceRole.PROMOTER).forEach { role ->
            val doc = Jsoup.parse(controllerHtml(role))
            doc.select("[data-overview-new-schedule]").shouldBeEmpty()
            doc.select("[data-overview-create] .app-overview-steps li").size shouldBe 3
        }
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
        doc.select("[data-overview-chart]").map { doc.getElementById(it.attr("aria-labelledby"))!!.text() } shouldContainExactly
            listOf("By condition", "Last run")
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

        doc.select("[data-overview-recent] .app-overview-recent-section h3").map { it.text() } shouldContainExactly
            listOf("Up next", "Recently changed")
        doc.select("[data-overview-recent] .app-overview-recent li a").map { it.attr("href") } shouldContainExactly
            listOf("/schedules?id=$ID_A", "/schedules?id=$ID_B")
        doc.select("[data-overview-recent] time").map { it.text() } shouldContainExactly listOf("in 3 hours", "2 hours ago")
        doc.select("[data-overview-recent] .ds-badge").shouldBeEmpty()
        html shouldNotContain "<b>x</b>"
        doc.select("[data-overview-recent] .app-overview-recent li a").last()!!.text() shouldBe "ops/<b>x</b>"
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

    /** The controller's model over [ScheduleService.summary] answering four schedules, rendered with its own attributes. */
    private fun controllerHtml(
        role: WorkspaceRole,
        blocked: Int = 1,
    ): String {
        val workspaceId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", role),
                ),
                null,
            )
        val service = mockk<ScheduleService>()
        every { service.summary(workspaceId, any()) } returns
            ScheduleSummary(
                complete = true,
                total = 3 + blocked,
                enabled = 2,
                paused = 1,
                blocked = blocked,
                lastRun = LastRunOutcome.entries.associateWith { if (it == LastRunOutcome.NEVER_RAN) 3 + blocked else 0 },
                upNext = emptyList(),
                recentlyChanged = emptyList(),
            )
        val model = ExtendedModelMap()
        ScheduleOverviewController(service, mockk { every { resolve(any()) } returns "saas" }).overview(model, MockHttpServletRequest())
        return html(model[OverviewController.MODEL] as OverviewPage, extra = model)
    }

    private fun html(
        page: OverviewPage,
        canAuthor: Boolean = true,
        canReadExecutions: Boolean = true,
        extra: Map<String, Any?> = emptyMap(),
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
        extra.forEach { (name, value) -> context.setVariable(name, value) }
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
