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
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

/**
 * #556 — the family overview (`overview/family.html`) pinned at the RENDER: every number on the
 * page comes from the one [OverviewPage] the controller builds, so the four bars, the four counts
 * and the hidden table read the same [Inventory]; an unknown inventory shows its reason and no
 * number; the Pipelines guide substitutes the workspace through `th:text`; a family without a
 * guide renders the framework and nothing else (amendment 1: no placeholder prose).
 *
 * #598a: the Pipelines home carries the stacked charts instead (the total, the lifecycle bar and
 * the maturity chart, `OverviewStacked`), so its cases build a [ChartsResult]; the four-count
 * and four-bar cases run on the four siblings, which keep that card until #599. #598b adds the
 * third chart, the twelve weeks of activity under the actively developed headline.
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

    /** Amendment 1: the structural case runs on every family that carries the four counts (#598a: not Pipelines). */
    @ParameterizedTest
    @EnumSource(OverviewPage.Family::class, names = ["PIPELINES"], mode = EnumSource.Mode.EXCLUDE)
    fun `one inventory feeds the bars, the counts and the table`(family: OverviewPage.Family) {
        val doc = render(page(InventoryResult.Available(Inventory(1, 1, 1, 1)), family = family))

        val bars = doc.select("[data-overview-chart] [data-bar]")
        bars.map { it.attr("data-bar") } shouldContainExactly listOf("draft", "released", "released-with-draft", "discarded")
        bars.map { it.selectFirst(".app-overview-bar-v")!!.text() } shouldContainExactly listOf("1", "1", "1", "1")
        bars.map { it.selectFirst("rect")!!.attr("width") } shouldContainExactly listOf("100.0", "100.0", "100.0", "100.0")
        bars.map { it.attr("data-tone") } shouldContainExactly listOf("draft", "released", "mixed", "discarded")
        bars[0].selectFirst("svg")!!.attr("aria-label") shouldBe "Draft only: 1 ${family.singular}"
        doc.selectFirst(".app-overview")!!.attr("data-overview") shouldBe family.navigation.route

        val counts =
            doc
                .select(
                    "[data-overview-inventory] [data-count]",
                ).associate { it.attr("data-count") to it.selectFirst("dd")!!.text() }
        counts shouldBe mapOf("objects" to "4", "with-drafts" to "2", "with-releases" to "2", "discarded" to "1")

        doc.select("[data-overview-table] tbody td").map { it.text() } shouldContainExactly listOf("1", "1", "1", "1")
        doc.select("[data-overview-unavailable]").shouldBeEmpty()
    }

    @Test
    fun `bar widths are shares of the tallest bar and a zero is a zero-width rect beside its 0`() {
        val doc = render(page(Inventory(draftOnly = 4, releasedOnly = 2, releasedWithDraft = 0, discardedOnly = 1), family = STANDARD))

        val bars = doc.select("[data-overview-chart] [data-bar]")
        bars.map { it.selectFirst("rect")!!.attr("width") } shouldContainExactly listOf("100.0", "50.0", "0.0", "25.0")
        bars[2].selectFirst(".app-overview-bar-v")!!.text() shouldBe "0"
        // The SVG width is an attribute: the CSP admits no style attribute (InlineWidthAuditTest).
        bars.forEach { it.select("[style]").shouldBeEmpty() }
    }

    @Test
    fun `an empty family draws an all-zero chart, not a divide by zero`() {
        val doc = render(page(Inventory(0, 0, 0, 0), family = STANDARD))

        doc.select("[data-overview-chart] rect").map { it.attr("width") } shouldContainExactly listOf("0", "0", "0", "0")
        doc.select("[data-overview-inventory] [data-count] dd.app-overview-count-v").map { it.text() } shouldContainExactly
            listOf("0", "0", "0", "0")
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
        charts.map { it.attr("aria-label") } shouldContainExactly listOf("Last connection test", "By dialect")
        charts.map { it.select("[data-bar]").size } shouldContainExactly listOf(1, 2)
        doc.select(".app-card-note").map { it.text() } shouldContain "A shared outcome."
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

    /** #598a — the Pipelines card: the total, the stacked charts in order (#598b: three), and none of the four counts or bars. */
    @Test
    fun `the pipelines panel is the total, the lifecycle bar, the maturity chart and the activity chart, with no count`() {
        val doc = render(page(Inventory(2, 3, 1, 1)))
        val area = doc.selectFirst("[data-overview-inventory]").shouldNotBeNull()
        val captions = listOf("Pipelines by lifecycle", "Pipelines by number of releases", "Pipelines activity, last 12 weeks")

        area.select("[data-count], [data-overview-chart], dl.app-overview-counts").shouldBeEmpty()
        area.select("[data-stacked-chart]").map { it.attr("data-stacked-chart") } shouldContainExactly
            listOf("lifecycle", "maturity", "activity")
        area.select("[data-stacked]").map { it.attr("data-stacked") } shouldContainExactly listOf("lifecycle", "maturity", "activity")
        area.select("[data-stacked-total]").map { it.attr("data-stacked-total") to it.text() } shouldContainExactly
            listOf("lifecycle" to "7 pipelines", "activity" to "0 pipelines actively developed")
        area.select("h3").map { it.text() } shouldContainExactly captions
        area.select("[data-stacked]").map { it.attr("aria-label") } shouldContainExactly captions
        area.select("[data-overview-table]").map { it.selectFirst("caption")!!.text() } shouldContainExactly captions
        area.select("[data-chart-table=activity] thead th").map { it.text() } shouldContainExactly
            listOf("Week", "Released", "Created", "Events")
        area.select("[data-chart-table=lifecycle] thead th").map { it.text() } shouldContainExactly
            listOf("Lifecycle", "In development", "Live", "Live, changes in progress", "Retired", "Pipelines")
        area.select("[data-chart-table=maturity] thead th").map { it.text() } shouldContainExactly
            listOf("Releases", "No draft", "Draft open", "Pipelines")
        area.select("[style]").shouldBeEmpty()
    }

    /**
     * #598a / #593 — the lifecycle bar's four parts tile 0–100 in order and sum to the total, and
     * each number is the SAME number in the rect's sentence, the legend, the hidden table and the
     * model — a template reading any of them from elsewhere disagrees here.
     */
    @Test
    fun `the lifecycle parts tile the bar and the legend, the table and the model agree`() {
        val inventory = Inventory(draftOnly = 2, releasedOnly = 3, releasedWithDraft = 1, discardedOnly = 1)
        val page = page(inventory)
        val model = page.inventory!!.stacked.first { it.key == "lifecycle" }
        val doc = render(page)

        val rects = doc.select("[data-stacked=lifecycle] [data-row=all] rect")
        rects.map { it.attr("data-part") } shouldContainExactly listOf("in-development", "live", "live-changes", "retired")
        rects.map { it.attr("data-tone") } shouldContainExactly listOf("draft", "released", "mixed", "discarded")
        rects.map { it.attr("x") } shouldContainExactly listOf("0", "28.6", "71.4", "85.7")
        rects.map { it.attr("width") } shouldContainExactly listOf("28.6", "42.8", "14.3", "14.3")
        tiles(rects.map { it.attr("x") to it.attr("width") }) shouldBe 100.0
        rects.map { it.selectFirst("title")!!.text() } shouldContainExactly
            listOf("In development: 2 pipelines", "Live: 3 pipelines", "Live, changes in progress: 1 pipeline", "Retired: 1 pipeline")

        val legend =
            doc
                .select("[data-stacked-chart=lifecycle] [data-legend]")
                .associate { it.attr("data-legend") to it.selectFirst(".app-overview-legend-v")!!.text() }
        val table = doc.select("[data-chart-table=lifecycle] tbody td[data-part]").associate { it.attr("data-part") to it.text() }
        val modelled =
            model.rows
                .single()
                .parts
                .associate { it.key to it.value.toString() }
        legend shouldBe mapOf("in-development" to "2", "live" to "3", "live-changes" to "1", "retired" to "1")
        table shouldBe legend
        modelled shouldBe legend
        doc.select("[data-chart-table=lifecycle] tbody td[data-row-total]").single().text() shouldBe "7"
        legend.values.sumOf { it.toLong() } shouldBe inventory.objects
    }

    @Test
    fun `the three live parts link the filtered catalog, Retired stays unlinked, and the sentence relates the total`() {
        val doc = render(page(Inventory(1, 1, 1, 1)))

        doc.select("[data-stacked-chart=lifecycle] [data-legend] a").map { it.attr("href") } shouldContainExactly
            listOf("/pipelines?lifecycle=draft", "/pipelines?lifecycle=released", "/pipelines?lifecycle=released-with-draft")
        doc.selectFirst("[data-legend=retired]")!!.select("a").shouldBeEmpty()
        doc.select("[data-stacked-chart=maturity] a, [data-stacked] a").shouldBeEmpty()
        doc.select("[data-stacked-chart=lifecycle] .app-card-note").single().text() shouldBe
            "The resource browser and View all count the three live parts; retired pipelines have no page."
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/pipelines"
        doc.selectFirst("[data-overview-review]")!!.attr("href") shouldBe "/pipelines?lifecycle=with-drafts"
    }

    /** #598a — five rows by number of releases; every row's total is its two parts and the rows sum to the lifecycle total. */
    @Test
    fun `the maturity rows split no draft and draft open and sum to the lifecycle total`() {
        val bins =
            listOf(
                bin(ReleaseBin.ZERO, 2, 2),
                bin(ReleaseBin.ONE, 2, 2),
                bin(ReleaseBin.TWO_TO_THREE, 0, 1),
                bin(ReleaseBin.FOUR_TO_NINE, 0, 1),
                bin(ReleaseBin.TEN_PLUS, 1, 0),
            )
        val page = pipelines(ChartInventory(Inventory(2, 4, 3, 2), bins, Activity.empty(NOW, ACTIVE_DAYS)))
        val model = page.inventory!!.stacked.first { it.key == "maturity" }
        val doc = render(page)

        val rows = doc.select("[data-stacked=maturity] [data-row]")
        rows.map { it.attr("data-row") } shouldContainExactly listOf("r0", "r1", "r2-3", "r4-9", "r10")
        rows.map { it.selectFirst(".app-overview-stack-name")!!.text() } shouldContainExactly
            listOf("0 releases", "1 release", "2–3 releases", "4–9 releases", "10+ releases")
        rows.map { it.selectFirst(".app-overview-stack-v")!!.text() } shouldContainExactly
            listOf("4 (2 with drafts)", "4 (2 with drafts)", "1 (0 with drafts)", "1 (0 with drafts)", "1 (1 with a draft)")
        rows.map { row -> row.select("rect").map { it.attr("data-part") } }.distinct().single() shouldContainExactly
            listOf("no-draft", "draft-open")
        rows.map { it.attr("data-row") } shouldContainExactly model.rows.map { it.key }
        model.rows.map { it.width } shouldContainExactly listOf("100.0", "100.0", "25.0", "25.0", "25.0")
        rows[0].select("rect").map { it.attr("width") } shouldContainExactly listOf("50.0", "50.0")
        rows[2].select("rect").map { it.attr("x") to it.attr("width") } shouldContainExactly listOf("0" to "25.0", "25.0" to "0")
        rows.zip(model.rows).forEach { (row, modelled) ->
            tiles(row.select("rect").map { it.attr("x") to it.attr("width") }) shouldBe modelled.width.toDouble()
        }
        doc.select("[data-chart-table=maturity] tbody td[data-row-total]").sumOf { it.text().toLong() } shouldBe 11L
        val legend = doc.select("[data-stacked-chart=maturity] [data-legend]")
        legend.map { it.attr("data-legend") to it.attr("data-tone") } shouldContainExactly
            listOf("no-draft" to "released", "draft-open" to "draft")
        doc.select("[data-stacked-chart=maturity] .app-overview-legend-v").shouldBeEmpty()
        doc.select("[data-stacked-chart=maturity] .app-card-note").single().text() shouldBe
            "A release since discarded is not counted; a retired pipeline has 0."
    }

    @Test
    fun `an empty pipelines workspace draws all-zero stacked charts, not a divide by zero`() {
        val doc = render(pipelines(ChartInventory.empty(NOW, ACTIVE_DAYS)))

        doc.select("[data-stacked] rect").map { it.attr("width") }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-stacked] rect").map { it.attr("x") }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-stacked-total]").map { it.text() } shouldContainExactly
            listOf("0 pipelines", "0 pipelines actively developed")
        doc.select("[data-overview-table] tbody td").map { it.text() }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-stacked-chart=lifecycle] .app-overview-legend-v").map { it.text() } shouldContainExactly
            listOf("0", "0", "0", "0")
        // #598b: an empty quarter is twelve all-zero weeks and zero sums, never a divide by zero.
        doc.select("[data-stacked=activity] [data-row]").size shouldBe Activity.WEEKS
        doc.select("[data-stacked=activity] .app-overview-stack-v b").map { it.text() }.distinct() shouldContainExactly listOf("0")
        doc.select("[data-stacked-chart=activity] .app-overview-legend-v").map { it.text() } shouldContainExactly listOf("0", "0")
    }

    /** #598b — the headline counts the actively developed pipelines in the family's words: 0, 1 and many. */
    @Test
    fun `the activity headline words 0, 1 and 3 pipelines actively developed`() {
        listOf(0L to "0 pipelines actively developed", 1L to "1 pipeline actively developed", 3L to "3 pipelines actively developed")
            .forEach { (active, words) ->
                val activity = Activity.empty(NOW, ACTIVE_DAYS).copy(activelyDeveloped = active)
                val doc = render(pipelines(charts(Inventory(2, 3, 1, 1), activity)))

                doc.select("[data-stacked-total=activity]").single().text() shouldBe words
            }
    }

    /**
     * #598b — twelve weekly rows, OLDEST first, keyed by the UTC Monday: `NOW` (Saturday 17 Oct)
     * sits in the week of Monday 12 Oct, the newest and the only one "so far"; eleven weeks earlier
     * is 27 Jul. Each row's numbers are the model's in the SVG, beside the bar and in the hidden
     * table; the legend sums the twelve weeks.
     */
    @Test
    fun `the activity rows are the twelve weeks oldest first and every number is the model's`() {
        val activity = Activity(weeks(), activelyDeveloped = 2, windowDays = ACTIVE_DAYS)
        val page = pipelines(charts(Inventory(2, 3, 1, 1), activity))
        val model = page.inventory!!.stacked.first { it.key == "activity" }
        val doc = render(page)

        val rows = doc.select("[data-stacked=activity] [data-row]")
        rows.map { it.attr("data-row") }.first() shouldBe "week-2026-07-27"
        rows.map { it.attr("data-row") }.last() shouldBe "week-2026-10-12"
        rows.map { it.attr("data-row") } shouldContainExactly Activity.weekStarts(NOW).map { "week-$it" }
        rows.map { it.selectFirst(".app-overview-stack-name")!!.text() }.takeLast(3) shouldContainExactly
            listOf("28 Sep", "5 Oct", "12 Oct")
        rows.map { it.select(".app-overview-stack-detail").text() } shouldContainExactly List(Activity.WEEKS - 1) { "" } +
            "(this week so far)"
        rows.map { row -> row.select("rect").map { it.attr("data-part") to it.attr("data-tone") } }.distinct().single() shouldContainExactly
            listOf("released" to "released", "created" to "draft")
        rows.map { it.selectFirst(".app-overview-stack-v b")!!.text().toLong() } shouldContainExactly activity.weeks.map { it.events }
        rows.zip(model.rows).forEach { (row, modelled) ->
            row.select("rect").map { it.attr("width") } shouldContainExactly modelled.parts.map { it.width }
            tiles(row.select("rect").map { it.attr("x") to it.attr("width") }) shouldBe modelled.width.toDouble()
        }
        rows[Activity.WEEKS - 1].selectFirst("svg")!!.attr("aria-label") shouldBe "12 Oct: 4 events; Released 3; Created 1"
        rows[1].selectFirst("svg")!!.attr("aria-label") shouldBe "3 Aug: 1 event; Released 1; Created 0"
        model.rows.maxOf { it.width.toDouble() } shouldBe 100.0

        val table = doc.select("[data-chart-table=activity] tbody tr")
        table.map { it.attr("data-row") } shouldContainExactly rows.map { it.attr("data-row") }
        table.map { tr -> tr.select("td[data-part]").map { it.text().toLong() } } shouldContainExactly
            activity.weeks.map { listOf(it.released, it.created) }
        table.map { it.selectFirst("td[data-row-total]")!!.text().toLong() } shouldContainExactly activity.weeks.map { it.events }
        doc.select("[data-stacked-chart=activity] [data-legend]").map { it.attr("data-legend") to it.text() } shouldContainExactly
            listOf(
                "released" to "Released ${activity.weeks.sumOf { it.released }}",
                "created" to "Created ${activity.weeks.sumOf { it.created }}",
            )
        doc.select("[data-stacked-chart=activity] a").shouldBeEmpty()
    }

    /** #598b — the note states the configured window: the default 14 days, and 7 when configured so. */
    @Test
    fun `the activity note names the configured actively developed window`() {
        listOf(ACTIVE_DAYS, 7).forEach { days ->
            val doc = render(pipelines(charts(Inventory(2, 3, 1, 1), Activity.empty(NOW, days))))

            doc.select("[data-stacked-chart=activity] .app-card-note").single().text() shouldBe
                "Weeks start on Monday (UTC). A release since discarded is not counted. Actively developed: a draft written " +
                "in the last $days days — a draft keeps only its last write, so this is a count today, not a history."
        }
    }

    @Test
    fun `the templates links filter the templates catalog and the discarded bar stays unlinked (#579)`() {
        val doc = render(page(InventoryResult.Available(Inventory(1, 1, 1, 1)), family = OverviewPage.Family.TEMPLATES))

        doc.select("[data-overview-chart] [data-bar] a").map { it.attr("href") } shouldContainExactly
            listOf(
                "/templates?lifecycle=draft",
                "/templates?lifecycle=released",
                "/templates?lifecycle=released-with-draft",
            )
        doc.selectFirst("[data-bar=discarded] a").shouldBeNull()
        doc.select("[data-overview-inventory] [data-count] a").map { it.attr("href") } shouldContainExactly
            listOf("/templates?lifecycle=with-drafts", "/templates?lifecycle=with-releases")
        doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe "/templates"
        doc.selectFirst("[data-overview-review]")!!.attr("href") shouldBe "/templates?lifecycle=with-drafts"
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

    @Test
    fun `a family without a guide renders the framework and no placeholder prose`() {
        OverviewPage.Family.entries.filter { !it.guided }.forEach { family ->
            val doc = render(OverviewPage.of(family, false, InventoryResult.Available(Inventory(1, 2, 3, 4)), null, NOW))

            doc.selectFirst("h1")!!.text() shouldBe family.title
            doc.selectFirst("[data-overview-view-all]")!!.attr("href") shouldBe family.catalogHref
            doc.select("[data-overview-chart] [data-bar]").size shouldBe 4
            doc.select("[data-overview-chart] a, [data-overview-inventory] [data-count] a").shouldBeEmpty()
            doc.select("[data-overview-intro], [data-overview-prompt], [data-overview-recent], [data-overview-related]").shouldBeEmpty()
            doc.select("template[data-page-assets]").shouldBeEmpty()
        }
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
        val row = listed.selectFirst("[data-overview-recent] li").shouldNotBeNull()
        row.selectFirst("a")!!.attr("href") shouldBe "/pipelines/$id"
        row.selectFirst("a")!!.text() shouldBe "acme/sales/daily"
        row.selectFirst("time")!!.attr("datetime") shouldBe NOW.minusSeconds(3600).toString()
        row.selectFirst(".ds-badge")!!.text() shouldBe "draft"

        val empty = render(page(Inventory(0, 0, 0, 0), RecentResult.Available(emptyList())))
        empty.selectFirst("[data-overview-recent-empty]").shouldNotBeNull()
        empty.select("[data-overview-recent] li").shouldBeEmpty()

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

    private fun page(
        inventory: Inventory,
        recent: RecentResult? = RecentResult.Available(emptyList()),
        family: OverviewPage.Family = OverviewPage.Family.PIPELINES,
    ): OverviewPage = page(InventoryResult.Available(inventory), recent, family)

    /** One inventory result for any family: a #598a chart family reads it as its chart group ([charts]). */
    private fun page(
        inventory: InventoryResult,
        recent: RecentResult? = RecentResult.Available(emptyList()),
        family: OverviewPage.Family = OverviewPage.Family.PIPELINES,
    ): OverviewPage =
        when (family.charts) {
            OverviewStacked.ChartSet.STANDARD -> {
                OverviewPage.of(family, false, inventory, recent, NOW)
            }

            OverviewStacked.ChartSet.LIFECYCLE -> {
                val charts =
                    when (inventory) {
                        is InventoryResult.Available -> ChartsResult.Available(charts(inventory.inventory))
                        is InventoryResult.Unavailable -> ChartsResult.Unavailable(inventory.reason)
                    }
                OverviewPage.of(family, false, charts, recent, NOW)
            }
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

    /** Where the last of [parts] ends, after checking each starts where the one before it ended. */
    private fun tiles(parts: List<Pair<String, String>>): Double =
        parts
            .fold(0.0) { end, (x, width) ->
                x.toDouble() shouldBe (end plusOrMinus EPSILON)
                end + width.toDouble()
            }.let { Math.round(it * TENTHS) / TENTHS }

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
        val STANDARD = OverviewPage.Family.TEMPLATES
        const val EPSILON = 0.001
        const val TENTHS = 10.0
    }
}
