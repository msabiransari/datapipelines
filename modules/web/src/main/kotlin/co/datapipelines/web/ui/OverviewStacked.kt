package co.datapipelines.web.ui

import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.MaturityBin
import co.datapipelines.pipeline.ReleaseBin
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * #598a — one labelled stacked-bar chart of an overview's inventory card (`overview/family.html`,
 * the `stacked` loop): every row a fixed-size bar of 0–100 split into its parts, so the SVG is the
 * same size at 10 or 10,000 objects. [caption] names the chart wherever words go (its heading,
 * the list's label, the hidden table's caption); [totalLabel] is the chart's headline line shown
 * above it — the lifecycle chart's total, the activity chart's actively developed count (#598b) —
 * and null when the chart has none; [total] is the number that line states. [note] is the one
 * sentence under the chart; [headings] the hidden table's columns — the row heading, each part,
 * the total.
 */
data class StackedChart(
    val key: String,
    val caption: String,
    val total: Long,
    val totalLabel: String?,
    val rows: List<StackedRow>,
    val legend: List<Legend>,
    val note: String?,
    val headings: List<String>,
)

/**
 * One bar: [width] its share of the chart's longest row in the 0–100 viewBox (the record's
 * attribute-width rule, `OverviewPage.width`), [parts] tiling exactly that width left to right,
 * [label] the SVG's accessible sentence, [detail] the words beside the total (null: none).
 */
data class StackedRow(
    val key: String,
    val name: String,
    val total: Long,
    val width: String,
    val parts: List<Part>,
    val label: String,
    val detail: String?,
    val href: String?,
)

/** One segment of a row: [x] where it starts and [width] how far it runs, both SVG attributes. */
data class Part(
    val key: String,
    val name: String,
    val value: Long,
    val x: String,
    val width: String,
    val tone: String,
    val label: String,
    val href: String?,
)

/** One legend entry: a swatch of [tone], the words, and — when the chart has one per series — the count and its link. */
data class Legend(
    val key: String,
    val name: String,
    val tone: String,
    val value: Long?,
    val href: String?,
)

/**
 * #598a — the Pipelines home's charts (owner ruling on #598, 2026-10-18), built from ONE
 * [ChartInventory] so none disagrees with the total or with another: [lifecycle] is one row,
 * each object counted once in one of four parts that sum to the total (#593's resolution);
 * [maturity] is five rows by number of releases, each split no draft / draft open; #598b's
 * [activity] is twelve weekly rows of releases and new objects under the actively developed
 * headline. The words are the owner's; the catalog's lifecycle select keeps its own
 * (`CatalogLifecycle.label`).
 */
object OverviewStacked {
    /** Which inventory card a family's overview carries. */
    enum class ChartSet {
        /** The four counts and the four-bar lifecycle chart (#556) — the four siblings until #599. */
        STANDARD,

        /** The total, the lifecycle stacked bar, the maturity chart and the activity chart (#598). */
        LIFECYCLE,
    }

    /** The inventory panel for a [ChartSet.LIFECYCLE] family: no counts, no bar charts, the three stacked charts. */
    fun panel(
        family: OverviewPage.Family,
        charts: ChartInventory,
    ): OverviewPage.InventoryPanel =
        OverviewPage.InventoryPanel(
            counts = emptyList(),
            charts = emptyList(),
            stacked =
                listOf(
                    lifecycle(family, charts.lifecycle),
                    maturity(family, charts.maturity),
                    activity(family, charts.activity),
                ),
        )

    /** One row of four parts — In development, Live, Live with changes, Retired — that sum to the total. */
    fun lifecycle(
        family: OverviewPage.Family,
        inventory: Inventory,
    ): StackedChart {
        val parts =
            LIFECYCLE_PARTS.map { (key, bucket) -> Segment(key, LIFECYCLE_NAMES.getValue(key), bucket.countIn(inventory)) }
        val row =
            row(family::count, "all", "All ${family.plural}", parts, tallest = inventory.objects, detail = null) { segment ->
                val bucket = LIFECYCLE_PARTS.toMap().getValue(segment.key)
                PartStyle(TONES.getValue(bucket), "${segment.name}: ${family.count(segment.value)}", family.filtered(bucket))
            }
        return StackedChart(
            key = "lifecycle",
            caption = "${family.title} by lifecycle",
            total = inventory.objects,
            totalLabel = family.count(inventory.objects),
            rows = listOf(row),
            legend = row.parts.map { Legend(it.key, it.name, it.tone, it.value, it.href) },
            note = "The resource browser and View all count the three live parts; retired ${family.plural} have no page.",
            headings = listOf("Lifecycle") + row.parts.map { it.name } + family.title,
        )
    }

    /** Five rows, one per [ReleaseBin], each split no draft then draft open; no row links (the catalog has no such filter). */
    fun maturity(
        family: OverviewPage.Family,
        bins: List<MaturityBin>,
    ): StackedChart {
        val tallest = bins.maxOfOrNull { it.total } ?: 0L
        val rows =
            bins.map { bin ->
                val name = "${bin.releases.label} ${if (bin.releases == ReleaseBin.ONE) "release" else "releases"}"
                val parts = listOf(Segment(NO_DRAFT, "No draft", bin.noDraft), Segment(DRAFT_OPEN, "Draft open", bin.draftOpen))
                val drafts = if (bin.draftOpen == 1L) "1 with a draft" else "${bin.draftOpen} with drafts"
                row(family::count, bin.releases.column.replace('_', '-'), name, parts, tallest, detail = "($drafts)") { segment ->
                    val label = "$name, ${segment.name.lowercase()}: ${family.count(segment.value)}"
                    PartStyle(MATURITY_TONES.getValue(segment.key), label, href = null)
                }
            }
        return StackedChart(
            key = "maturity",
            caption = "${family.title} by number of releases",
            total = bins.sumOf { it.total },
            totalLabel = null,
            rows = rows,
            legend =
                listOf(
                    Legend(NO_DRAFT, "No draft", MATURITY_TONES.getValue(NO_DRAFT), value = null, href = null),
                    Legend(DRAFT_OPEN, "Draft open", MATURITY_TONES.getValue(DRAFT_OPEN), value = null, href = null),
                ),
            note = "A release since discarded is not counted; a retired ${family.singular} has 0.",
            headings = listOf("Releases") + rows.first().parts.map { it.name } + family.title,
        )
    }

    /**
     * #598b — twelve rows, one per UTC week, OLDEST first so time reads left to right down the
     * card; each row stacks the week's releases then its new objects (a new object is a draft,
     * so it takes the draft tone) into one bar of events, the busiest week full width. No row
     * links (the catalog has no week filter). The headline is the actively developed count — a
     * count at the moment of the read, since a draft keeps only its last write.
     */
    fun activity(
        family: OverviewPage.Family,
        activity: Activity,
    ): StackedChart {
        val busiest = activity.weeks.maxOf { it.events }
        val newest = activity.weeks.last().weekStart
        val rows =
            activity.weeks.map { week ->
                val name = WEEK_NAME.format(week.weekStart)
                val parts = listOf(Segment(RELEASED, "Released", week.released), Segment(CREATED, "Created", week.created))
                val detail = if (week.weekStart == newest) "(this week so far)" else null
                row(::events, "week-${week.weekStart}", name, parts, busiest, detail) { segment ->
                    PartStyle(ACTIVITY_TONES.getValue(segment.key), "$name, ${segment.name.lowercase()}: ${segment.value}", href = null)
                }
            }
        return StackedChart(
            key = "activity",
            caption = "${family.title} activity, last ${Activity.WEEKS} weeks",
            total = activity.activelyDeveloped,
            totalLabel = "${family.count(activity.activelyDeveloped)} actively developed",
            rows = rows,
            legend =
                listOf(
                    Legend(RELEASED, "Released", ACTIVITY_TONES.getValue(RELEASED), activity.weeks.sumOf { it.released }, href = null),
                    Legend(CREATED, "Created", ACTIVITY_TONES.getValue(CREATED), activity.weeks.sumOf { it.created }, href = null),
                ),
            note =
                "Weeks start on Monday (UTC). A release since discarded is not counted. Actively developed: a draft written " +
                    "in the last ${activity.windowDays} days — a draft keeps only its last write, so this is a count today, " +
                    "not a history.",
            headings = listOf("Week", "Released", "Created", "Events"),
        )
    }

    /**
     * The row's arithmetic: its width is its total's share of [tallest] (`OverviewPage.width`), and
     * its parts tile exactly that width — each part runs from the rounded running sum before it to
     * the rounded running sum through it, in tenths, so the last part ends at the row's width with
     * no gap or overlap however the shares round. An all-zero row is all "0", never a divide by zero.
     * No row links today (the catalog has no release-count filter); [StackedRow.href] is the frame's.
     * [count] words the row's total in its accessible label: objects for the inventory charts,
     * events for the activity chart.
     */
    private fun row(
        count: (Long) -> String,
        key: String,
        name: String,
        segments: List<Segment>,
        tallest: Long,
        detail: String?,
        style: (Segment) -> PartStyle,
    ): StackedRow {
        val total = segments.sumOf { it.value }
        val width = OverviewPage.width(total, tallest)
        val tenths = (width.toDouble() * TENTHS).roundToLong()
        val bounds =
            segments
                .runningFold(0L) { sum, segment -> sum + segment.value }
                .map { through -> if (total == 0L) 0L else (tenths * through.toDouble() / total).roundToLong() }
        val parts =
            segments.mapIndexed { index, segment ->
                val (tone, label, link) = style(segment)
                val x = format(bounds[index])
                Part(segment.key, segment.name, segment.value, x, format(bounds[index + 1] - bounds[index]), tone, label, link)
            }
        val summary = segments.joinToString("; ") { "${it.name} ${it.value}" }
        return StackedRow(key, name, total, width, parts, "$name: ${count(total)}; $summary", detail, href = null)
    }

    /** "1 event", "3 events" — an activity row counts releases and new objects together. */
    private fun events(value: Long): String = "$value ${if (value == 1L) "event" else "events"}"

    /** Tenths of the viewBox as an attribute value: "0" for nothing, otherwise one decimal. */
    private fun format(tenths: Long): String = if (tenths == 0L) "0" else String.format(Locale.ROOT, "%.1f", tenths / TENTHS)

    private data class Segment(
        val key: String,
        val name: String,
        val value: Long,
    )

    private data class PartStyle(
        val tone: String,
        val label: String,
        val href: String?,
    )

    private const val TENTHS = 10.0
    private const val NO_DRAFT = "no-draft"
    private const val DRAFT_OPEN = "draft-open"
    private const val RELEASED = "released"
    private const val CREATED = "created"

    /** An activity row's name: the week's UTC Monday as "6 Oct", in the root locale so no server locale renames it. */
    private val WEEK_NAME = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT)

    /** The lifecycle parts left to right: the inventory's exclusive buckets A–D (shell design §8). */
    private val LIFECYCLE_PARTS =
        listOf(
            "in-development" to LifecycleBucket.DRAFT_ONLY,
            "live" to LifecycleBucket.RELEASED_ONLY,
            "live-changes" to LifecycleBucket.RELEASED_WITH_DRAFT,
            "retired" to LifecycleBucket.DISCARDED_ONLY,
        )

    /** The owner's words for the four parts (#598); the catalog's select keeps `CatalogLifecycle.label`'s. */
    private val LIFECYCLE_NAMES =
        mapOf(
            "in-development" to "In development",
            "live" to "Live",
            "live-changes" to "Live, changes in progress",
            "retired" to "Retired",
        )

    /** Warning, success, accent and muted, as the bars of #556 (shell design §8: no danger red on a draft). */
    private val TONES =
        mapOf(
            LifecycleBucket.DRAFT_ONLY to "draft",
            LifecycleBucket.RELEASED_ONLY to "released",
            LifecycleBucket.RELEASED_WITH_DRAFT to "mixed",
            LifecycleBucket.DISCARDED_ONLY to "discarded",
        )

    /** A maturity bin's settled share in the released tone, its open drafts in the draft tone. */
    private val MATURITY_TONES = mapOf(NO_DRAFT to "released", DRAFT_OPEN to "draft")

    /** A week's releases in the released tone, its new objects in the draft tone (a new object is a draft, #V18). */
    private val ACTIVITY_TONES = mapOf(RELEASED to "released", CREATED to "draft")
}
