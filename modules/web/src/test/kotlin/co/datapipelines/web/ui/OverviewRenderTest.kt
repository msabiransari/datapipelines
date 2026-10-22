package co.datapipelines.web.ui

import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.MaturityBin
import co.datapipelines.pipeline.ReleaseBin
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.pipeline.WeekActivity
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToLong

/**
 * #556 — the family overview (`overview/family.html`) pinned at the RENDER: every number on the
 * page comes from the one [OverviewPage] the controller builds, so the four bars, the four counts
 * and the hidden table read the same [Inventory]; an unknown inventory shows its reason and no
 * number; the Pipelines guide substitutes the workspace through `th:text`; a family without a
 * guide renders the framework and nothing else (amendment 1: no placeholder prose).
 *
 * #598a: the Pipelines home reads a [ChartsResult] instead. #638 re-cut the Pipelines home on
 * the approved 2026-10-20 direction: the summary strip (four exclusive lifecycle groups summing to
 * Total, plus Actively developed), the activity columns, the release history bars, the task rows
 * and the page's one dialog — each invariant of #598 kept below. #599: the four siblings are
 * lifecycle homes too, so the family cases run over all five, each in its own words and routes;
 * the four-count and four-bar cases were removed with the STANDARD panel (#599, #645).
 */
class OverviewRenderTest {
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

    /**
     * #599 — every family's strip is its own: the four exclusive groups sum to Total under the
     * family's plural, the three live groups link that family's filtered catalog, Retired names the
     * family and stays unlinked, View all counts the three live groups, and the task rows open the
     * family's draft and released filters in its words.
     */
    @ParameterizedTest
    @EnumSource(OverviewPage.Family::class)
    fun `every family's strip sums to Total and links its own catalog in its own words`(family: OverviewPage.Family) {
        val doc =
            render(
                page(
                    InventoryResult.Available(Inventory(draftOnly = 4, releasedOnly = 2, releasedWithDraft = 1, discardedOnly = 3)),
                    family = family,
                ),
            )
        val area = doc.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        val route = family.catalogHref

        doc.selectFirst(".app-overview")!!.attr("data-overview") shouldBe family.navigation.route
        val strip = area.select("dl.app-overview-counts [data-count]").associate { it.attr("data-count") to value(it) }
        val foot = area.select("[data-overview-foot] [data-count]").associate { it.attr("data-count") to value(it) }
        strip shouldBe mapOf("total" to 10L, "live" to 2L, "in-development" to 4L, "actively-developed" to 0L)
        foot shouldBe mapOf("live-changes" to 1L, "retired" to 3L)
        LIFECYCLE_GROUPS.sumOf { (strip + foot).getValue(it) } shouldBe strip.getValue("total")
        area.selectFirst("dl.app-overview-counts dt")!!.text() shouldBe "Total ${family.plural}"

        area
            .select("[data-count]")
            .filter {
                it.selectFirst("a") != null
            }.map { it.attr("data-count") to it.selectFirst("a")!!.attr("href") } shouldContainExactly
            listOf(
                "live" to "$route?lifecycle=released",
                "in-development" to "$route?lifecycle=draft",
                "live-changes" to "$route?lifecycle=released-with-draft",
            )
        val retired = doc.selectFirst("[data-count=retired]").shouldNotBeNull()
        retired.select("a").shouldBeEmpty()
        retired.attr("title") shouldBe "Retired ${family.plural} have no page."
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe route
        doc.selectFirst("[data-overview-view-all-count]")!!.text() shouldBe "7"
        doc.select("[data-task]").map { it.attr("data-task") to it.selectFirst("a")!!.attr("href") } shouldContainExactly
            listOf("drafts" to "$route?lifecycle=draft", "live" to "$route?lifecycle=released")
        doc.select("[data-task] b").map { it.text() } shouldContainExactly listOf("Review your drafts", "Explore live ${family.plural}")
        doc.selectFirst("[data-task=drafts] small")!!.text() shouldBe "4 ${family.plural} are working toward their first release."
        doc.select("[data-overview-panel]").map { it.attr("data-overview-panel") } shouldContainExactly listOf("activity", "maturity")
        doc.select("[data-bar=draft], [data-count=objects], [data-overview-review]").shouldBeEmpty()
        doc.select("[style]").shouldBeEmpty()
    }

    /** Amendment 1: the Unavailable case runs on every family, one fixture each. */
    @ParameterizedTest
    @EnumSource(OverviewPage.Family::class)
    fun `an unknown inventory says why and shows no number in the inventory area`(family: OverviewPage.Family) {
        val doc = render(page(InventoryResult.Unavailable("Inventory could not be read."), family = family))

        val area = doc.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        area.selectFirst("[data-overview-unavailable]").shouldNotBeNull().text() shouldContain "Inventory could not be read."
        area.select("[data-overview-chart], [data-count], [data-overview-table], [data-stacked], [data-stacked-total]").shouldBeEmpty()
        area.text() shouldNotContain "0"
        Regex("""\d""").containsMatchIn(area.text()) shouldBe false
    }

    /**
     * #583 — the frame is loops, not slots: a panel carrying two charts renders BOTH, each with
     * its own caption (on the list's label and the hidden table's), bars, note and headings —
     * a template that read the first chart for every chart renders its data twice and fails here.
     */
    @Test
    fun `a panel renders both of its charts, each reading its own model`() {
        val page =
            OverviewPage(
                family = "datasources",
                title = "Datasources",
                catalogHref = "/datasources",
                lensed = false,
                inventory =
                    OverviewPage.InventoryPanel(
                        counts = emptyList(),
                        charts =
                            listOf(
                                OverviewPage.Chart(
                                    "health",
                                    "Last connection test",
                                    listOf(datasourceBar("ok", "Ok", 4)),
                                    "A shared outcome.",
                                ),
                                OverviewPage.Chart(
                                    "dialect",
                                    "By dialect",
                                    listOf(
                                        datasourceBar("POSTGRES", "POSTGRES", 3, "/datasources?dialect=POSTGRES"),
                                        datasourceBar("H2", "H2", 1),
                                    ),
                                    null,
                                    listOf("Dialect", "Datasources"),
                                ),
                            ),
                    ),
                unavailable = null,
                recent = null,
                guided = false,
            )
        val doc = render(page)

        val charts = doc.select("[data-overview-chart]")
        charts.map { it.attr("data-chart") } shouldContainExactly listOf("health", "dialect")
        charts.map { doc.getElementById(it.attr("aria-labelledby"))!!.text() } shouldContainExactly
            listOf("Last connection test", "By dialect")
        charts.map { it.select("[data-bar]").size } shouldContainExactly listOf(1, 2)
        doc.select("[data-overview-note]").map { it.text() } shouldContainExactly listOf("A shared outcome.")
        doc.select("[data-overview-table]").map { it.selectFirst("caption")!!.text() } shouldContainExactly
            listOf("Last connection test", "By dialect")
        doc.select("[data-overview-table] thead th").map { it.text() } shouldContainExactly
            listOf("Lifecycle", "Objects", "Dialect", "Datasources")
        doc.select("[data-chart-table=dialect] tbody td").map { it.text() } shouldContainExactly listOf("3", "1")
        doc.select("[data-overview-chart][data-chart=dialect] [data-bar=POSTGRES] a").attr("href") shouldBe "/datasources?dialect=POSTGRES"
        doc.selectFirst("[data-overview-recent]").shouldBeNull()
    }

    /**
     * #583 — the recent card is a loop too: two sections render both, each with its own heading,
     * note and list — and a section whose read failed says so without touching its neighbour,
     * while a model without a Review link renders none (the lifecycle families always do).
     */
    @Test
    fun `a recent card renders both of its sections, each reading its own model`() {
        val page =
            OverviewPage(
                family = "datasources",
                title = "Datasources",
                catalogHref = "/datasources",
                lensed = false,
                inventory = OverviewPage.InventoryPanel(emptyList(), emptyList()),
                unavailable = null,
                recent =
                    OverviewPage.RecentPanel(
                        review = null,
                        sections =
                            listOf(
                                OverviewPage.RecentSection(
                                    "Recently added to this workspace",
                                    null,
                                    listOf(
                                        OverviewPage.RecentItem(
                                            "dw-sales",
                                            "/datasources/dw-sales",
                                            NOW.toString(),
                                            "an hour ago",
                                            NOW.toString(),
                                            false,
                                        ),
                                    ),
                                    null,
                                    null,
                                ),
                                OverviewPage.RecentSection(
                                    "Recently tested",
                                    "The latest outcomes.",
                                    null,
                                    "Recent tests could not be read.",
                                    null,
                                ),
                            ),
                    ),
                guided = false,
            )
        val doc = render(page)

        doc.select("[data-overview-recent] h3").map { it.text() } shouldContainExactly
            listOf("Recently added to this workspace", "Recently tested")
        doc.select("[data-overview-recent] .app-card-note").map { it.text() } shouldContain "The latest outcomes."
        doc.select("[data-overview-recent] li a").map { it.attr("href") } shouldContainExactly listOf("/datasources/dw-sales")
        doc.select("[data-overview-recent] .app-overview-unavailable").single().text() shouldContain
            "Recently tested is unavailable. Recent tests could not be read."
        doc.selectFirst("[data-overview-review]").shouldBeNull()
    }

    /**
     * #638 / #593 — the summary strip: Total, Live, In development and Actively developed,
     * then the foot's Live with changes and Retired. The four exclusive lifecycle groups (two in
     * the strip, two in its foot) sum to Total, every number read from the one [ChartInventory];
     * the #556 counts, bars and #598 stacked hooks are gone.
     */
    @Test
    fun `the strip's four lifecycle metrics sum to Total and Actively developed rides beside them`() {
        val activity = Activity.empty(NOW, ACTIVE_DAYS).copy(activelyDeveloped = 2)
        val doc = render(pipelines(charts(Inventory(draftOnly = 2, releasedOnly = 3, releasedWithDraft = 1, discardedOnly = 1), activity)))
        val area = doc.selectFirst("[data-overview-inventory]").shouldNotBeNull()

        val strip = area.select("dl.app-overview-counts [data-count]").associate { it.attr("data-count") to value(it) }
        strip.keys.toList() shouldContainExactly listOf("total", "live", "in-development", "actively-developed")
        strip shouldBe mapOf("total" to 7L, "live" to 3L, "in-development" to 2L, "actively-developed" to 2L)
        val foot = area.select("[data-overview-foot] [data-count]").associate { it.attr("data-count") to value(it) }
        foot shouldBe mapOf("live-changes" to 1L, "retired" to 1L)
        LIFECYCLE_GROUPS.sumOf { (strip + foot).getValue(it) } shouldBe strip.getValue("total")
        area.select("dl.app-overview-counts dt").map { it.text() } shouldContainExactly
            listOf("Total pipelines", "Live", "In development", "Actively developed")
        area.select("dl.app-overview-counts .app-overview-count-help").map { it.text() } shouldContainExactly
            listOf(
                "Across this workspace",
                "Released, with no open changes",
                "Awaiting their first release",
                "Draft edited in the last 14 days",
            )
        area.select("[data-count] .app-overview-dot").map { it.attr("data-tone") } shouldContainExactly
            listOf("released", "draft", "mixed", "discarded")

        area.select("[data-stacked-chart], [data-stacked], [data-stacked-total], [data-bar=draft], [data-count=objects]").shouldBeEmpty()
        doc.select("[data-overview-panel]").map { it.attr("data-overview-panel") to it.attr("data-panel-kind") } shouldContainExactly
            listOf("activity" to "columns", "maturity" to "bars")
        doc.select("[data-overview-panel] h3").map { it.text() } shouldContainExactly listOf("Development activity", "Release history")
        doc.select("[style]").shouldBeEmpty()
    }

    /**
     * The three live groups link the filtered catalog; Retired and the two measures stay unlinked
     * (a retired pipeline has no page, its title says so); View all's badge is the browser's count,
     * the three live groups (#593); the task rows open the draft and released filters.
     */
    @Test
    fun `the live groups link the filtered catalog, Retired stays unlinked, and View all counts the three live groups`() {
        val doc = render(page(Inventory(draftOnly = 4, releasedOnly = 2, releasedWithDraft = 1, discardedOnly = 3)))

        val linked = doc.select("[data-overview-inventory] [data-count]").filter { it.selectFirst("a") != null }
        linked.map { it.attr("data-count") to it.selectFirst("a")!!.attr("href") } shouldContainExactly
            listOf(
                "live" to "/pipelines?lifecycle=released",
                "in-development" to "/pipelines?lifecycle=draft",
                "live-changes" to "/pipelines?lifecycle=released-with-draft",
            )
        val retired = doc.selectFirst("[data-count=retired]").shouldNotBeNull()
        retired.select("a").shouldBeEmpty()
        retired.attr("title") shouldBe "Retired pipelines have no page."
        doc.select("[data-count=total] a, [data-count=actively-developed] a, [data-overview-panel] [data-overview-chart] a").shouldBeEmpty()

        val viewAll = doc.selectFirst("[data-overview-view-all]").shouldNotBeNull()
        viewAll.attr("href") shouldBe "/pipelines"
        viewAll.selectFirst("[data-overview-view-all-count]")!!.text() shouldBe "7"
        doc.select("[data-task]").map { it.attr("data-task") to it.selectFirst("a")!!.attr("href") } shouldContainExactly
            listOf("drafts" to "/pipelines?lifecycle=draft", "live" to "/pipelines?lifecycle=released")
        doc.select("[data-task] b").map { it.text() } shouldContainExactly listOf("Review your drafts", "Explore live pipelines")
        doc.selectFirst("[data-task=drafts] small")!!.text() shouldBe "4 pipelines are working toward their first release."
        doc.selectFirst("[data-overview-review]").shouldBeNull()
        doc.selectFirst("[data-overview-agent]")!!.attr("data-open") shouldBe "prompt"
    }

    /** Review your drafts' sentence states the In development count in words for 0, 1 and many. */
    @Test
    fun `the drafts task words the In development count for 0, 1 and many`() {
        listOf(
            0L to "No pipeline is waiting for its first release.",
            1L to "1 pipeline is working toward its first release.",
            3L to "3 pipelines are working toward their first release.",
        ).forEach { (drafts, words) ->
            render(page(Inventory(drafts, 1, 0, 0))).selectFirst("[data-task=drafts] small")!!.text() shouldBe words
        }
    }

    /**
     * #598b, re-cut as columns — twelve weeks OLDEST first keyed by the UTC Monday (`NOW`, Saturday
     * 17 Oct, sits in the week of 12 Oct, the only one in progress). Each column's two rects stack
     * from the baseline with no gap or overlap, the busiest week reaches the top (1000 tenths), and
     * each column's `<title>` is the sentence its hidden table row states — the template reading
     * either from elsewhere disagrees here.
     */
    @Test
    fun `the activity columns are the twelve weeks oldest first and every title is its table row`() {
        val activity = Activity(weeks(), activelyDeveloped = 2, windowDays = ACTIVE_DAYS)
        val doc = render(pipelines(charts(Inventory(2, 3, 1, 1), activity)))
        val panel = doc.selectFirst("[data-overview-panel=activity]").shouldNotBeNull()

        val columns = panel.select("[data-column]")
        columns.map { it.attr("data-column") } shouldContainExactly Activity.weekStarts(NOW).map { "week-$it" }
        columns[0].attr("data-column") shouldBe "week-2026-07-27"
        columns.map { it.selectFirst(".app-overview-column-name")!!.text() }.takeLast(3) shouldContainExactly
            listOf("28 Sep", "5 Oct", "12 Oct")
        columns.map { it.hasAttr("data-current") } shouldContainExactly List(Activity.WEEKS - 1) { false } + true
        columns.filter { it.hasAttr("data-sparse") }.map { it.selectFirst(".app-overview-column-name")!!.text() } shouldContainExactly
            listOf("10 Aug", "31 Aug", "21 Sep", "12 Oct")
        val parts = columns.map { col -> col.select("rect").map { it.attr("data-part") to it.attr("data-tone") } }
        parts.distinct().single() shouldContainExactly
            listOf("released" to "released", "created" to "draft")

        val heights = columns.map { stacked(it.select("rect").map { rect -> rect.attr("y") to rect.attr("height") }) }
        heights.max() shouldBe FULL_TENTHS
        heights shouldContainExactly activity.weeks.map { tenths(OverviewPage.width(it.events, activity.weeks.maxOf { w -> w.events })) }
        columns[Activity.WEEKS - 1].select("rect").map { it.attr("y") to it.attr("height") } shouldContainExactly
            listOf("25.0" to "75.0", "0" to "25.0")

        val table = doc.select("[data-chart-table=activity] tbody tr")
        table.map { it.attr("data-row") } shouldContainExactly columns.map { it.attr("data-column") }
        columns.zip(table).forEach { (column, row) ->
            val cells = row.select("td").associate { it.attr("data-part") to it.text().toLong() }
            val name = row.selectFirst("th")!!.text()
            val sentence =
                "Week of ${name.removeSuffix(" (so far)")}: ${cells.getValue("created")} created, ${cells.getValue("released")} released" +
                    if (name.endsWith(" (so far)")) ", this week so far" else ""
            column.selectFirst("title")!!.text() shouldBe sentence
            column.selectFirst("svg")!!.attr("aria-label") shouldBe sentence
            cells.getValue("total") shouldBe cells.getValue("created") + cells.getValue("released")
        }
        table[Activity.WEEKS - 1].selectFirst("th")!!.text() shouldBe "12 Oct (so far)"
        doc.select("[data-chart-table=activity] thead th").map { it.text() } shouldContainExactly
            listOf("Week of", "Created", "Released", "Events")

        panel.selectFirst(".app-overview-headline strong")!!.text() shouldBe "16 events"
        panel.select("[data-legend]").map { it.attr("data-legend") to it.text() } shouldContainExactly
            listOf("created" to "3 created", "released" to "13 released")
        panel.select(".app-overview-axis span").map { it.text() } shouldContainExactly listOf("4", "2", "0")
        panel.selectFirst(".app-overview-period")!!.text() shouldBe "Last 12 weeks"
        panel.selectFirst(".app-overview-panel-foot")!!.text() shouldContain "4 events this week so far"
        panel.selectFirst("[data-overview-view-data]")!!.attr("data-open") shouldBe "activity"
        panel.select("[data-overview-chart=activity]").single().attr("aria-labelledby") shouldBe "overview-panel-activity-h"
        panel.select("a").shouldBeEmpty()
    }

    /**
     * Release history: five bars by number of releases, shares of the tallest bin, the unreleased
     * bin in the draft tone; the note's numbers are the bins' sums; the table keeps the two parts
     * and the total; no bar links (the catalog has no release-count filter).
     */
    @Test
    fun `the release history bars are the bins and the note states the bins' sums`() {
        val bins =
            listOf(
                bin(ReleaseBin.ZERO, 2, 2),
                bin(ReleaseBin.ONE, 2, 2),
                bin(ReleaseBin.TWO_TO_THREE, 0, 1),
                bin(ReleaseBin.FOUR_TO_NINE, 0, 1),
                bin(ReleaseBin.TEN_PLUS, 1, 0),
            )
        val doc = render(pipelines(ChartInventory(Inventory(2, 4, 3, 2), bins, Activity.empty(NOW, ACTIVE_DAYS))))
        val panel = doc.selectFirst("[data-overview-panel=maturity]").shouldNotBeNull()

        val bars = panel.select("[data-bar]")
        bars.map { it.attr("data-bar") } shouldContainExactly listOf("r0", "r1", "r2-3", "r4-9", "r10")
        bars.map { it.selectFirst(".app-overview-bar-name")!!.text() } shouldContainExactly
            listOf("No releases", "1 release", "2–3 releases", "4–9 releases", "10+ releases")
        bars.map { it.selectFirst(".app-overview-bar-v")!!.text().toLong() } shouldContainExactly bins.map { it.total }
        bars.map { it.selectFirst("rect")!!.attr("width") } shouldContainExactly listOf("100.0", "100.0", "25.0", "25.0", "25.0")
        bars.map { it.attr("data-tone") } shouldContainExactly listOf("draft", "released", "released", "released", "released")
        panel.select("a").shouldBeEmpty()
        panel.selectFirst(".app-overview-panel-sub")!!.text() shouldBe "Pipelines by number of releases"
        panel.selectFirst("[data-open=releases]")!!.attr("aria-label") shouldBe "Explain release history"

        val released = bins.filter { it.releases != ReleaseBin.ZERO }
        panel.select("[data-overview-note] span").map { it.text() } shouldContainExactly
            listOf(
                "2 of the ${bins[0].total} unreleased pipelines have a draft.",
                "${released.sumOf { it.draftOpen }} of the ${released.sumOf { it.total }} released pipelines have open changes.",
            )
        doc.select("[data-chart-table=maturity] thead th").map { it.text() } shouldContainExactly
            listOf("Releases", "No draft", "Draft open", "Pipelines")
        doc.select("[data-chart-table=maturity] tbody tr").map { tr -> tr.select("td").map { it.text().toLong() } } shouldContainExactly
            bins.map { listOf(it.noDraft, it.draftOpen, it.total) }
        doc.select("[data-chart-table=maturity] td[data-part=total]").sumOf { it.text().toLong() } shouldBe 11L
    }

    @ParameterizedTest
    @EnumSource(OverviewPage.Family::class)
    fun `an empty workspace draws all-zero panels, not a divide by zero`(family: OverviewPage.Family) {
        val doc =
            render(
                OverviewPage.of(
                    family,
                    false,
                    ChartsResult.Available(ChartInventory.empty(NOW, ACTIVE_DAYS)),
                    RecentResult.Available(emptyList()),
                    NOW,
                ),
            )

        doc.select("[data-overview-inventory] [data-count] .app-overview-count-v").map { it.text() }.distinct() shouldContainExactly
            listOf("0")
        doc.select("[data-column] rect").map { it.attr("height") }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-column]").size shouldBe Activity.WEEKS
        doc.select("[data-overview-panel=maturity] rect").map { it.attr("width") }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-overview-table] tbody td").map { it.text() }.distinct() shouldContainExactly listOf("0")
        doc.select(".app-overview-axis span").map { it.text() } shouldContainExactly listOf("", "", "0")
        doc.selectFirst(".app-overview-headline strong")!!.text() shouldBe "0 events"
        doc.selectFirst("[data-overview-panel=activity] .app-overview-panel-foot")!!.text() shouldContain "No events this week"
        doc.select("[data-overview-note] span").map { it.text() } shouldContainExactly listOf("No ${family.plural} yet.")
        doc.selectFirst("[data-overview-view-all-count]")!!.text() shouldBe "0"
        doc.selectFirst("[data-task=drafts] small")!!.text() shouldBe "No ${family.singular} is waiting for its first release."
    }

    /** #598b — the configured actively developed window: the metric's note and the definitions dialog both name it. */
    @Test
    fun `the actively developed window is the configured one in the metric and the definitions dialog`() {
        listOf(ACTIVE_DAYS, 7, 1).forEach { days ->
            val doc = render(pipelines(charts(Inventory(2, 3, 1, 1), Activity.empty(NOW, days))))
            val window = if (days == 1) "1 day" else "$days days"

            doc.selectFirst("[data-count=actively-developed] .app-overview-count-help")!!.text() shouldBe "Draft edited in the last $window"
            doc.selectFirst("[data-dialog-section=definitions]")!!.text() shouldContain "a draft edited in the last $window."
        }
    }

    /**
     * The page's one dialog carries the five sections in order, each with its title; the
     * definitions section is §4.25's counting model with the catalog's words (#626); the activity
     * section has the slot its table twin moves into, and the page holds that table exactly once.
     */
    @Test
    fun `the dialog carries the five sections and the definitions name the four groups in both vocabularies`() {
        val doc = render(page(Inventory(draftOnly = 2, releasedOnly = 3, releasedWithDraft = 1, discardedOnly = 1)))
        val dialog = doc.select("dialog[data-overview-dialog]").single()

        dialog.attr("aria-labelledby") shouldBe "overview-dialog-title"
        dialog.select("[data-dialog-section]").map { it.attr("data-dialog-section") to it.attr("data-title") } shouldContainExactly
            listOf(
                "definitions" to "How the summary is counted",
                "releases" to "Understanding release history",
                "activity" to "Development activity · 12 weeks",
                "prompt" to "Start with a clear question",
                "howitworks" to "Create, verify, then release",
            )
        dialog.select("[data-dialog-section]").map { it.hasAttr("hidden") }.distinct() shouldContainExactly listOf(true)
        val definitions = dialog.selectFirst("[data-dialog-section=definitions]").shouldNotBeNull()
        definitions.text() shouldContain "These four groups total 7."
        definitions.text() shouldContain "The resource browser and View all count the three groups that are not retired: 6."
        definitions.select("dt").map { it.text() } shouldContainExactly
            listOf("In development 2", "Live 3", "Live with changes 1", "Retired 1")
        definitions.select(".app-overview-term-catalog").map { it.text() } shouldContainExactly
            listOf(
                "The catalog calls this Draft only.",
                "The catalog calls this Released only.",
                "The catalog calls this Released with draft changes.",
                "The catalog calls this Discarded only.",
            )
        doc.select("[data-overview-foot] [data-open=definitions]").size shouldBe 1
        dialog.selectFirst("[data-dialog-section=activity] [data-dialog-table]")!!.attr("data-dialog-table") shouldBe "activity"
        doc.select("[data-table-home=activity] table").size shouldBe 1
        doc.select("table[data-chart-table=activity]").size shouldBe 1
        dialog.select("[data-dialog-section=releases] p").map { it.text() }.takeLast(2) shouldContainExactly
            doc.select("[data-overview-panel=maturity] [data-overview-note] span").map { it.text() }
    }

    /** #621 — every metric passed the lens, so the strip's heading and the page's sentence say "admitted", no metric repeats it. */
    @Test
    fun `a lensed strip is admitted as a whole and an unlensed one is not`() {
        val lensed =
            render(OverviewPage.of(OverviewPage.Family.PIPELINES, true, ChartsResult.Available(charts(Inventory(0, 1, 0, 0))), null, NOW))
        lensed.selectFirst("#overview-inventory-h")!!.text() shouldBe "Admitted inventory"
        lensed.selectFirst("[data-overview-context] .app-overview-lens")!!.text() shouldContain "only what your promotion view shows"
        lensed.select(".app-overview-count-lens").shouldBeEmpty()

        val open = render(page(Inventory(0, 1, 0, 0)))
        open.selectFirst("#overview-inventory-h")!!.text() shouldBe "Inventory"
        open.select(".app-overview-lens, .app-overview-count-lens").shouldBeEmpty()
    }

    /** An unknown chart group says so: no digit anywhere in the page's own text, no badge, no task count. */
    @Test
    fun `an unknown pipelines inventory shows no digit anywhere on the page`() {
        val doc = render(page(InventoryResult.Unavailable("Inventory could not be read.")))
        val overview = doc.selectFirst("[data-overview]").shouldNotBeNull()

        overview.selectFirst("[data-overview-unavailable]")!!.text() shouldContain "Inventory could not be read."
        overview
            .select("[data-overview-view-all-count], [data-overview-panel], [data-count], [data-dialog-section=definitions]")
            .shouldBeEmpty()
        doc.selectFirst("[data-task=drafts] small")!!.text() shouldBe "Pipelines working toward their first release."
        val text = overview.text()
        Regex("""\d""").find(text)?.let { text.substring(maxOf(0, it.range.first - 40), it.range.last + 1) }.shouldBeNull()
    }

    @Test
    fun `the starter prompt names the active workspace through th-text`() {
        val html = html(page(Inventory(1, 0, 0, 0)), workspace = "<b>acme</b>")
        val doc = Jsoup.parse(html)

        // Escaped on the wire: a workspace name is text, never markup (th:text, not th:utext).
        html shouldNotContain "<b>acme</b>"
        html shouldContain "In workspace &lt;b&gt;acme&lt;/b&gt;, build a draft pipeline"

        val prompt = doc.selectFirst("textarea#overview-prompt").shouldNotBeNull()
        prompt.`val`() shouldContain "In workspace <b>acme</b>, build a draft pipeline to answer: [question]."
        prompt.`val`() shouldContain "Leave release to me."
        prompt.children().shouldBeEmpty()
        doc.selectFirst("[data-overview-context] b")!!.text() shouldBe "<b>acme</b>"
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
        doc.selectFirst("[data-overview-copy]")!!.attr("type") shouldBe "button"
    }

    @Test
    fun `the prompt carries no key, credential or configuration`() {
        val text = render(page(Inventory(1, 0, 0, 0))).selectFirst("textarea#overview-prompt")!!.`val`()

        listOf("dpk_", "password", "secret", "token", "://").forEach { text.lowercase() shouldNotContain it }
    }

    /**
     * A family without a guide renders the frame and none of the guide's regions (amendment 1: no
     * placeholder prose). Every family is guided today, so the case un-guides each; the script loads
     * regardless, since the dialog exists on every family (#638).
     */
    @ParameterizedTest
    @EnumSource(OverviewPage.Family::class)
    fun `a family without a guide renders the framework and no placeholder prose`(family: OverviewPage.Family) {
        val doc = render(page(InventoryResult.Available(Inventory(1, 2, 3, 4)), recent = null, family = family).copy(guided = false))

        doc.selectFirst("h1")!!.text() shouldBe family.title
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe family.catalogHref
        doc.select("dl.app-overview-counts [data-count]").size shouldBe 4
        doc
            .select(
                "[data-overview-intro], [data-overview-prompt], [data-overview-recent], [data-overview-related], " +
                    "[data-overview-quickstart], [data-overview-agent], .app-overview-subtitle",
            ).shouldBeEmpty()
        doc.select("template[data-page-assets]").single().html() shouldContain "/js/overview.mjs"
    }

    @Test
    fun `recently updated lists the rows, says when there are none, and says when it is unknown`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val listed =
            render(
                page(
                    Inventory(1, 0, 0, 0),
                    RecentResult.Available(listOf(RecentEntry("acme/sales/daily", "/pipelines/$id", NOW.minusSeconds(3600), true))),
                ),
            )
        val row = listed.selectFirst("[data-overview-recent] .app-overview-recent li").shouldNotBeNull()
        row.selectFirst("a")!!.attr("href") shouldBe "/pipelines/$id"
        row.selectFirst("a")!!.text() shouldBe "acme/sales/daily"
        row.selectFirst("time")!!.attr("datetime") shouldBe NOW.minusSeconds(3600).toString()
        row.selectFirst(".ds-badge")!!.text() shouldBe "draft"

        val empty = render(page(Inventory(0, 0, 0, 0), RecentResult.Available(emptyList())))
        empty.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        empty.select("[data-overview-recent] .app-overview-recent li").shouldBeEmpty()

        val unknown = render(page(Inventory(1, 0, 0, 0), RecentResult.Unavailable("Recently updated could not be read.")))
        unknown.selectFirst("[data-overview-recent]")!!.text() shouldContain "Recently updated could not be read."
        unknown.selectFirst("[data-overview-recent-empty]").shouldBeNull()
    }

    @Test
    fun `a lensed caller reads admitted inventory`() {
        val charts = ChartsResult.Available(charts(Inventory(0, 1, 0, 0)))
        val doc = render(OverviewPage.of(OverviewPage.Family.PIPELINES, true, charts, null, NOW))

        doc.selectFirst("#overview-inventory-h")!!.text() shouldBe "Admitted inventory"
        doc.selectFirst("[data-overview-context]")!!.text() shouldContain "only what your promotion view shows"
    }

    @Test
    fun `semantic metric tones pass through the summary strip by word`() {
        val base = page(Inventory(draftOnly = 1, releasedOnly = 0, releasedWithDraft = 0, discardedOnly = 0))
        val inventory = base.inventory.shouldNotBeNull()
        val tones = listOf("success", "warning", "danger")
        val doc =
            render(
                base.copy(
                    inventory =
                        inventory.copy(
                            metrics = tones.map { OverviewPage.Metric(it, it, 1, null, null, tone = it) },
                        ),
                ),
            )
        doc.select("[data-overview-summary] dl.app-overview-counts .app-overview-dot").map { it.attr("data-tone") } shouldContainExactly
            tones
    }

    private fun page(
        inventory: Inventory,
        recent: RecentResult? = RecentResult.Available(emptyList()),
        family: OverviewPage.Family = OverviewPage.Family.PIPELINES,
    ): OverviewPage = page(InventoryResult.Available(inventory), recent, family)

    /** One inventory result for any family, read as its chart group ([charts]): every family is a lifecycle home (#599). */
    private fun page(
        inventory: InventoryResult,
        recent: RecentResult? = RecentResult.Available(emptyList()),
        family: OverviewPage.Family = OverviewPage.Family.PIPELINES,
    ): OverviewPage {
        val charts =
            when (inventory) {
                is InventoryResult.Available -> ChartsResult.Available(charts(inventory.inventory))
                is InventoryResult.Unavailable -> ChartsResult.Unavailable(inventory.reason)
            }
        return OverviewPage.of(family, false, charts, recent, NOW)
    }

    private fun pipelines(charts: ChartInventory): OverviewPage =
        OverviewPage.of(OverviewPage.Family.PIPELINES, false, ChartsResult.Available(charts), RecentResult.Available(emptyList()), NOW)

    /** A consistent chart group for [inventory]: never released objects in bin 0, every other in bin 1. */
    private fun charts(
        inventory: Inventory,
        activity: Activity = Activity.empty(NOW, ACTIVE_DAYS),
    ): ChartInventory =
        ChartInventory(
            inventory,
            ReleaseBin.entries.map { releases ->
                when (releases) {
                    ReleaseBin.ZERO -> bin(releases, inventory.draftOnly, inventory.discardedOnly)
                    ReleaseBin.ONE -> bin(releases, inventory.releasedWithDraft, inventory.releasedOnly)
                    else -> bin(releases, 0, 0)
                }
            },
            activity,
        )

    /** Twelve weeks ending with `NOW`'s: releases 0,1,2,0,1,2,… with the newest 3, three new pipelines (≤ any test inventory's 7). */
    private fun weeks(): List<WeekActivity> =
        Activity.weekStarts(NOW).mapIndexed { index, monday ->
            val newest = index == Activity.WEEKS - 1
            WeekActivity(monday, released = if (newest) 3 else (index % 3).toLong(), created = if (index % 4 == 3 || newest) 1 else 0)
        }

    private fun bin(
        releases: ReleaseBin,
        draftOpen: Long,
        noDraft: Long,
    ): MaturityBin = MaturityBin(releases, draftOpen, noDraft)

    /** A column's height in tenths, after checking each rect sits directly on the one below it (its y + height = the previous top). */
    private fun stacked(parts: List<Pair<String, String>>): Long =
        parts.fold(0L) { top, (y, height) ->
            tenths(y) + tenths(height) shouldBe FULL_TENTHS - top
            top + tenths(height)
        }

    private fun tenths(attribute: String): Long = (attribute.toDouble() * TEN).roundToLong()

    /** A strip or foot cell's number. */
    private fun value(cell: Element): Long = cell.selectFirst(".app-overview-count-v")!!.text().toLong()

    /** A datasources-style bar (#583): tone `mixed`, share-of-tallest width, optional catalog link. */
    private fun datasourceBar(
        key: String,
        name: String,
        value: Long,
        href: String? = null,
    ): OverviewPage.Bar = OverviewPage.Bar(key, name, value, OverviewPage.width(value, 4), "mixed", "$name: $value", href)

    private fun render(page: OverviewPage): Document = Jsoup.parse(html(page))

    private fun html(
        page: OverviewPage,
        workspace: String = "acme",
    ): String {
        val path = "/overview/${page.family}"
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles()
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
        val NOW: Instant = Instant.parse("2026-10-17T12:00:00Z")
        const val ACTIVE_DAYS = 14
        const val TEN = 10.0
        const val FULL_TENTHS = 1_000L
        val LIFECYCLE_GROUPS = listOf("live", "in-development", "live-changes", "retired")
    }
}
