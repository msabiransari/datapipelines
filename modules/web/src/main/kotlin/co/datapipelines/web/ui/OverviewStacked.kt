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
 * #638 — one panel of an overview's analytics grid (`overview/family.html`, the `panels`
 * loop; ui-screens §4.25 Hierarchy). Every panel: [heading] (visible, and the chart's accessible
 * name), [description] the line under it, [period] an optional chip ("Last 12 weeks"), [explain]
 * the dialog key its info button opens (null: no button), [footer] the line under the chart, and
 * [table] the hidden table twin. [kind] is the template's switch.
 */
sealed interface Panel {
    val kind: String
    val key: String
    val heading: String
    val description: String?
    val period: String?
    val explain: String?
    val footer: String?
    val table: Table

    /**
     * Horizontal bars: name · track · value. [notes] are the sentences under the bars (the
     * release-history panel's two computed sentences, a sibling chart's caveat), none when empty.
     */
    data class Bars(
        override val key: String,
        override val heading: String,
        override val description: String?,
        val bars: List<OverviewPage.Bar>,
        val notes: List<String>,
        override val table: Table,
        override val period: String? = null,
        override val explain: String? = null,
        override val footer: String? = null,
    ) : Panel {
        override val kind: String get() = "bars"

        companion object {
            /** A sibling's [OverviewPage.Chart] as a bars panel: its caption is the visible heading (#620), its rows the table. */
            fun of(chart: OverviewPage.Chart): Bars =
                Bars(
                    key = chart.key,
                    heading = chart.caption,
                    description = null,
                    bars = chart.bars,
                    notes = listOfNotNull(chart.note),
                    table =
                        Table(
                            chart.key,
                            chart.caption,
                            chart.headings,
                            chart.bars.map { Row(it.key, it.name, listOf(Cell("value", it.value))) },
                        ),
                )
        }
    }

    /**
     * Vertical columns, oldest first: each [Column] stacks its [Stack]s bottom up inside a 0–100
     * viewBox. [headline] is the period's total, [legend] the series with their sums, [axis] the
     * labels of the three gridlines top to bottom (max, half, 0), [viewData] the dialog key whose
     * section receives the [table] twin.
     */
    data class Columns(
        override val key: String,
        override val heading: String,
        override val description: String?,
        override val period: String?,
        val headline: Long,
        val unit: String,
        val legend: List<Legend>,
        val axis: List<String>,
        val columns: List<Column>,
        override val footer: String?,
        val viewData: String,
        override val table: Table,
        override val explain: String? = null,
    ) : Panel {
        override val kind: String get() = "columns"
    }

    /**
     * One column: [label] is its sentence (the SVG's `<title>`, equal to its table row), [current]
     * marks the week in progress (its name strong), [sparse] the names the phone band keeps.
     */
    data class Column(
        val key: String,
        val name: String,
        val current: Boolean,
        val sparse: Boolean,
        val label: String,
        val stacks: List<Stack>,
    )

    /** One stacked rect: [y] where it starts and [height] how far it runs, SVG attributes in tenths of the viewBox. */
    data class Stack(
        val key: String,
        val value: Long,
        val y: String,
        val height: String,
        val tone: String,
    )

    /** One legend entry: a swatch of [tone], the count and the words — never colour alone. */
    data class Legend(
        val key: String,
        val name: String,
        val tone: String,
        val value: Long,
    )

    /** The hidden table twin: [caption] names it, [headings] the columns (the row heading first). */
    data class Table(
        val key: String,
        val caption: String,
        val headings: List<String>,
        val rows: List<Row>,
    )

    data class Row(
        val key: String,
        val name: String,
        val cells: List<Cell>,
    )

    data class Cell(
        val key: String,
        val value: Long,
    )
}

/**
 * #598a/#598b, re-cut by #638 for the approved 2026-10-20 direction — the Pipelines home's
 * inventory from ONE [ChartInventory], so no number disagrees with another: [metrics] are the
 * summary strip (the four exclusive lifecycle groups plus Actively developed, #593's resolution),
 * [activity] the twelve weekly columns, [maturity] the release-history bars and [releaseNote] the
 * sentences under them. The words are the owner's; the catalog's lifecycle select keeps its own
 * (`CatalogLifecycle.label`), and the definitions dialog names both (#626).
 */
object OverviewStacked {
    /** Which inventory a family's overview carries. */
    enum class ChartSet {
        /** The lifecycle strip, the activity columns and the release history (#598, #638). */
        LIFECYCLE,
    }

    /** The inventory panel for a [ChartSet.LIFECYCLE] family: the strip, its foot, the two panels and their dialogs. */
    fun panel(
        family: OverviewPage.Family,
        charts: ChartInventory,
        lensed: Boolean,
    ): OverviewPage.InventoryPanel {
        val inventory = charts.lifecycle
        return OverviewPage.InventoryPanel(
            counts = emptyList(),
            charts = emptyList(),
            metrics = metrics(family, charts, lensed),
            foot = foot(family, inventory, lensed),
            panels = listOf(activity(family, charts.activity), maturity(family, charts.maturity)),
            viewAll = live(inventory),
            explanations =
                listOf(
                    OverviewWords.definitions(family, inventory, charts.activity.windowDays),
                    OverviewWords.releases(family, releaseNote(family, charts.maturity)),
                    OverviewWords.activity(family),
                ),
        )
    }

    /** The objects the resource browser and View all count: every group but Retired (#593). */
    fun live(inventory: Inventory): Long = inventory.objects - inventory.discardedOnly

    /** Total, Live, In development, Actively developed — the first three from the same [Inventory] the foot reads. */
    fun metrics(
        family: OverviewPage.Family,
        charts: ChartInventory,
        lensed: Boolean,
    ): List<OverviewPage.Metric> {
        val inventory = charts.lifecycle
        val days = charts.activity.windowDays
        return listOf(
            OverviewPage.Metric("total", "Total ${family.plural}", inventory.objects, "Across this workspace", null, lensed = lensed),
            bucket(family, "live", LifecycleBucket.RELEASED_ONLY, inventory, "Released, with no open changes", lensed),
            bucket(family, "in-development", LifecycleBucket.DRAFT_ONLY, inventory, "Awaiting their first release", lensed),
            OverviewPage.Metric(
                "actively-developed",
                "Actively developed",
                charts.activity.activelyDeveloped,
                "Draft edited in the last ${OverviewWords.days(days)}",
                null,
                lensed = lensed,
            ),
        )
    }

    /** Live with changes (linked) and Retired (unlinked: a retired object has no page, its title says so). */
    fun foot(
        family: OverviewPage.Family,
        inventory: Inventory,
        lensed: Boolean,
    ): List<OverviewPage.Metric> =
        listOf(
            bucket(family, "live-changes", LifecycleBucket.RELEASED_WITH_DRAFT, inventory, note = null, lensed = lensed),
            OverviewPage.Metric(
                "retired",
                LIFECYCLE_NAMES.getValue(LifecycleBucket.DISCARDED_ONLY),
                inventory.discardedOnly,
                note = null,
                href = null,
                tone = TONES.getValue(LifecycleBucket.DISCARDED_ONLY),
                lensed = lensed,
                title = "Retired ${family.plural} have no page.",
            ),
        )

    private fun bucket(
        family: OverviewPage.Family,
        key: String,
        bucket: LifecycleBucket,
        inventory: Inventory,
        note: String?,
        lensed: Boolean,
    ): OverviewPage.Metric =
        OverviewPage.Metric(
            key,
            LIFECYCLE_NAMES.getValue(bucket),
            bucket.countIn(inventory),
            note,
            family.filtered(bucket),
            tone = TONES.getValue(bucket),
            lensed = lensed,
        )

    /** Five rows, one per [ReleaseBin]; No releases in the draft tone, the rest released; no row links (no such filter). */
    fun maturity(
        family: OverviewPage.Family,
        bins: List<MaturityBin>,
    ): Panel.Bars {
        val tallest = bins.maxOfOrNull { it.total } ?: 0L
        val caption = "${family.title} by number of releases"
        val bars =
            bins.map { bin ->
                val name = binName(bin.releases)
                OverviewPage.Bar(
                    key = binKey(bin.releases),
                    name = name,
                    value = bin.total,
                    width = OverviewPage.width(bin.total, tallest),
                    tone = if (bin.releases == ReleaseBin.ZERO) DRAFT else RELEASED,
                    label = "$name: ${family.count(bin.total)}",
                    href = null,
                )
            }
        val rows =
            bins.map { bin ->
                Panel.Row(
                    binKey(bin.releases),
                    binName(bin.releases),
                    listOf(Panel.Cell(NO_DRAFT, bin.noDraft), Panel.Cell(DRAFT_OPEN, bin.draftOpen), Panel.Cell(TOTAL, bin.total)),
                )
            }
        return Panel.Bars(
            key = "maturity",
            heading = "Release history",
            description = caption,
            bars = bars,
            notes = releaseNote(family, bins),
            table = Panel.Table("maturity", caption, listOf("Releases", "No draft", "Draft open", family.title), rows),
            explain = OverviewWords.RELEASES,
        )
    }

    /**
     * The release-history panel's two sentences, from the bins alone: the unreleased bin's drafts,
     * then the released bins' open changes — each worded for none, one, some and all, so the three
     * numbers it states are the bins' sums.
     */
    fun releaseNote(
        family: OverviewPage.Family,
        bins: List<MaturityBin>,
    ): List<String> {
        if (bins.sumOf { it.total } == 0L) return listOf("No ${family.plural} yet.")
        val unreleased = bins.filter { it.releases == ReleaseBin.ZERO }
        val released = bins.filter { it.releases != ReleaseBin.ZERO }
        return listOf(
            OverviewWords.share(unreleased.sumOf { it.draftOpen }, unreleased.sumOf { it.total }, "unreleased", family, "a draft")
                ?: "Every ${family.singular} has at least one release.",
            OverviewWords.share(released.sumOf { it.draftOpen }, released.sumOf { it.total }, "released", family, "open changes")
                ?: "No ${family.singular} has been released yet.",
        )
    }

    /**
     * #598b — twelve columns, one per UTC week, OLDEST first; each stacks the week's releases
     * (bottom) then its new objects (top; a new object is a draft, so it takes the draft tone)
     * into one column of events, the busiest week full height. A count at the moment of the read:
     * the window rules live in the activity dialog's sentence.
     */
    fun activity(
        family: OverviewPage.Family,
        activity: Activity,
    ): Panel.Columns {
        val busiest = activity.weeks.maxOf { it.events }
        val last = activity.weeks.lastIndex
        val columns =
            activity.weeks.mapIndexed { index, week ->
                val name = WEEK_NAME.format(week.weekStart)
                val current = index == last
                Panel.Column(
                    key = "week-${week.weekStart}",
                    name = name,
                    current = current,
                    sparse = (last - index) % PHONE_EVERY == 0,
                    label = weekLabel(name, week.created, week.released, current),
                    stacks = stacks(week.released, week.created, busiest),
                )
            }
        val rows =
            activity.weeks.mapIndexed { index, week ->
                val name = WEEK_NAME.format(week.weekStart) + if (index == last) " (so far)" else ""
                Panel.Row(
                    "week-${week.weekStart}",
                    name,
                    listOf(Panel.Cell(CREATED, week.created), Panel.Cell(RELEASED, week.released), Panel.Cell(TOTAL, week.events)),
                )
            }
        val thisWeek = activity.weeks.last().events
        return Panel.Columns(
            key = "activity",
            heading = "Development activity",
            description = "Creation and release events over time",
            period = "Last ${Activity.WEEKS} weeks",
            headline = activity.weeks.sumOf { it.events },
            unit = if (activity.weeks.sumOf { it.events } == 1L) "event" else "events",
            legend =
                listOf(
                    Panel.Legend(CREATED, "created", DRAFT, activity.weeks.sumOf { it.created }),
                    Panel.Legend(RELEASED, "released", RELEASED, activity.weeks.sumOf { it.released }),
                ),
            axis = axis(busiest),
            columns = columns,
            footer = if (thisWeek == 0L) "No events this week" else "${events(thisWeek)} this week so far",
            viewData = OverviewWords.ACTIVITY,
            table =
                Panel.Table(
                    "activity",
                    "${family.title} activity, last ${Activity.WEEKS} weeks",
                    listOf("Week of", "Created", "Released", "Events"),
                    rows,
                ),
        )
    }

    /** "Week of 14 Sep: 14 created, 10 released" — the column's title, read beside its table row. */
    fun weekLabel(
        name: String,
        created: Long,
        released: Long,
        current: Boolean,
    ): String = "Week of $name: $created created, $released released" + if (current) ", this week so far" else ""

    /**
     * The column's arithmetic: its height is its events' share of [busiest] (`OverviewPage.width`),
     * and its two stacks tile exactly that height from the bottom — each runs from the rounded
     * running sum before it to the rounded running sum through it, in tenths, so the top stack ends
     * at the column's height with no gap or overlap however the shares round. A quiet week is two
     * zero-height rects at the baseline, never a divide by zero.
     */
    private fun stacks(
        released: Long,
        created: Long,
        busiest: Long,
    ): List<Panel.Stack> {
        val total = released + created
        val tenths = (OverviewPage.width(total, busiest).toDouble() * TENTHS).roundToLong()
        val bounds =
            listOf(0L, released, total).map { through -> if (total == 0L) 0L else (tenths * through.toDouble() / total).roundToLong() }
        val series = listOf(Triple(RELEASED, released, RELEASED), Triple(CREATED, created, DRAFT))
        return series.mapIndexed { index, (key, value, tone) ->
            Panel.Stack(key, value, format(FULL - bounds[index + 1]), format(bounds[index + 1] - bounds[index]), tone)
        }
    }

    /** The gridlines' labels top to bottom; a quiet quarter labels the baseline only. */
    private fun axis(busiest: Long): List<String> =
        if (busiest == 0L) {
            listOf("", "", "0")
        } else {
            listOf(busiest.toString(), if (busiest % 2 == 0L) (busiest / 2).toString() else "${busiest / 2}.5", "0")
        }

    private fun binName(bin: ReleaseBin): String =
        when (bin) {
            ReleaseBin.ZERO -> "No releases"
            ReleaseBin.ONE -> "1 release"
            else -> "${bin.label} releases"
        }

    private fun binKey(bin: ReleaseBin): String = bin.column.replace('_', '-')

    /** "1 event", "3 events" — an activity column counts releases and new objects together. */
    fun events(value: Long): String = "$value ${if (value == 1L) "event" else "events"}"

    /** Tenths of the viewBox as an attribute value: "0" for nothing, otherwise one decimal. */
    private fun format(tenths: Long): String = if (tenths == 0L) "0" else String.format(Locale.ROOT, "%.1f", tenths / TENTHS)

    private const val TENTHS = 10.0
    private const val FULL = 1_000L
    private const val PHONE_EVERY = 3
    private const val NO_DRAFT = "no-draft"
    private const val DRAFT_OPEN = "draft-open"
    private const val TOTAL = "total"
    private const val RELEASED = "released"
    private const val CREATED = "created"
    private const val DRAFT = "draft"

    /** A column's name: the week's UTC Monday as "6 Oct", in the root locale so no server locale renames it. */
    private val WEEK_NAME = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT)

    /** The owner's words for the four groups (#598); the catalog's select keeps `CatalogLifecycle.label`'s (#626). */
    val LIFECYCLE_NAMES =
        mapOf(
            LifecycleBucket.DRAFT_ONLY to "In development",
            LifecycleBucket.RELEASED_ONLY to "Live",
            LifecycleBucket.RELEASED_WITH_DRAFT to "Live with changes",
            LifecycleBucket.DISCARDED_ONLY to "Retired",
        )

    /** Warning, success, accent and muted, as the bars of #556 (shell design §8: no danger red on a draft). */
    private val TONES =
        mapOf(
            LifecycleBucket.DRAFT_ONLY to "draft",
            LifecycleBucket.RELEASED_ONLY to "released",
            LifecycleBucket.RELEASED_WITH_DRAFT to "mixed",
            LifecycleBucket.DISCARDED_ONLY to "discarded",
        )
}
