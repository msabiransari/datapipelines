package co.datapipelines.web.ui

import co.datapipelines.pipeline.Activity
import co.datapipelines.pipeline.ChartInventory
import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.MaturityBin
import co.datapipelines.pipeline.ReleaseBin
import co.datapipelines.pipeline.WeekActivity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * #598a/#598b, re-cut by #home-frame — the Pipelines home's arithmetic over random inventories:
 * the summary strip's four exclusive groups (two in the strip, two in its foot) sum to Total and
 * View all counts all but Retired; every activity column's stacks tile its height from the
 * baseline in tenths (no gap, no overlap however the shares round), the busiest week full height;
 * the release-history bars are shares of the tallest bin; and the release note's sentences are
 * worded for none, one, some and all.
 */
class OverviewStackedTest {
    private val family = OverviewPage.Family.PIPELINES

    @Test
    fun `random inventories - the strip's four groups sum to Total and View all counts all but Retired`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val inventory = Inventory(next(random), next(random), next(random), next(random))
            val panel = OverviewStacked.panel(family, charts(inventory), lensed = false)
            val values = (panel.metrics + panel.foot).associate { it.key to it.value }

            values.getValue("total") shouldBe inventory.objects
            GROUPS.sumOf { values.getValue(it)!! } shouldBe inventory.objects
            panel.viewAll shouldBe inventory.objects - inventory.discardedOnly
        }
    }

    @Test
    fun `random activity columns tile their own height, the busiest is full, and a quiet quarter is all 0`() {
        val random = Random(SEED)
        val mondays = Activity.weekStarts(NOW)
        repeat(RUNS) { run ->
            // Every tenth run is a quiet quarter, so the all-zero branch is exercised, not hoped for.
            val quiet = run % QUIET_EVERY == 0
            val weeks = mondays.map { WeekActivity(it, if (quiet) 0 else next(random), if (quiet) 0 else next(random)) }
            val chart = OverviewStacked.activity(family, Activity(weeks, next(random), WINDOW_DAYS))
            val busiest = weeks.maxOf { it.events }

            chart.columns.map { it.key } shouldContainExactly mondays.map { "week-$it" }
            chart.columns.zip(weeks).forEach { (column, week) ->
                height(column) shouldBe tenths(OverviewPage.width(week.events, busiest))
                column.stacks.map { it.value } shouldBe listOf(week.released, week.created)
            }
            if (busiest > 0) chart.columns.maxOf { height(it) } shouldBe FULL
            if (busiest == 0L) chart.columns.flatMap { c -> c.stacks.map { it.height } }.distinct() shouldBe listOf("0")
            chart.headline shouldBe weeks.sumOf { it.events }
            chart.legend.map { it.value } shouldBe listOf(weeks.sumOf { it.created }, weeks.sumOf { it.released })
            chart.table.rows.map { row -> row.cells.map { it.value } } shouldBe weeks.map { listOf(it.created, it.released, it.events) }
        }
    }

    @Test
    fun `random release bins are shares of the tallest and the table splits each into its two parts`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val bins = ReleaseBin.entries.map { MaturityBin(it, next(random), next(random)) }
            val chart = OverviewStacked.maturity(family, bins)
            val tallest = bins.maxOf { it.total }

            chart.bars.zip(bins).forEach { (bar, bin) ->
                bar.width shouldBe OverviewPage.width(bin.total, tallest)
                bar.value shouldBe bin.total
            }
            chart.table.rows.map { row -> row.cells.map { it.value } } shouldBe bins.map { listOf(it.noDraft, it.draftOpen, it.total) }
            if (tallest > 0) chart.bars.maxOf { tenths(it.width) } shouldBe FULL
        }
    }

    @Test
    fun `the release note words none, one, some and all of each side`() {
        fun note(
            unreleasedDraft: Long,
            unreleasedTotal: Long,
            releasedDraft: Long,
            releasedTotal: Long,
        ): List<String> =
            OverviewStacked.releaseNote(
                family,
                listOf(
                    MaturityBin(ReleaseBin.ZERO, unreleasedDraft, unreleasedTotal - unreleasedDraft),
                    MaturityBin(ReleaseBin.ONE, releasedDraft, releasedTotal - releasedDraft),
                ),
            )

        note(0, 0, 0, 0) shouldContainExactly listOf("No pipelines yet.")
        note(4, 4, 2, 10) shouldContainExactly
            listOf("All 4 unreleased pipelines have a draft.", "2 of the 10 released pipelines have open changes.")
        note(1, 1, 1, 1) shouldContainExactly
            listOf("The 1 unreleased pipeline has a draft.", "The 1 released pipeline has open changes.")
        note(0, 3, 0, 5) shouldContainExactly
            listOf("None of the 3 unreleased pipelines have a draft.", "None of the 5 released pipelines have open changes.")
        note(1, 3, 5, 5) shouldContainExactly
            listOf("1 of the 3 unreleased pipelines has a draft.", "All 5 released pipelines have open changes.")
        note(0, 0, 0, 2) shouldContainExactly
            listOf("Every pipeline has at least one release.", "None of the 2 released pipelines have open changes.")
        note(1, 2, 0, 0) shouldContainExactly
            listOf("1 of the 2 unreleased pipelines has a draft.", "No pipeline has been released yet.")
    }

    @Test
    fun `the axis labels the busiest week, its half and the baseline, and a quiet quarter only the baseline`() {
        fun axis(busiest: Long): List<String> {
            val weeks = Activity.weekStarts(NOW).mapIndexed { index, monday -> WeekActivity(monday, if (index == 0) busiest else 0, 0) }
            return OverviewStacked.activity(family, Activity(weeks, 0, WINDOW_DAYS)).axis
        }

        axis(0) shouldContainExactly listOf("", "", "0")
        axis(1) shouldContainExactly listOf("1", "0.5", "0")
        axis(24) shouldContainExactly listOf("24", "12", "0")
        axis(7) shouldContainExactly listOf("7", "3.5", "0")
    }

    /** The column's height in tenths, after checking each stack sits directly on the one below (y + height = previous y). */
    private fun height(column: Panel.Column): Long =
        column.stacks.fold(0L) { top, stack ->
            tenths(stack.y) + tenths(stack.height) shouldBe FULL - top
            top + tenths(stack.height)
        }

    private fun tenths(attribute: String): Long = (attribute.toDouble() * TEN).roundToLong()

    /** A chart group whose bins agree with [inventory]: never released objects in bin 0, the rest in bin 1. */
    private fun charts(inventory: Inventory): ChartInventory =
        ChartInventory(
            inventory,
            ReleaseBin.entries.map { releases ->
                when (releases) {
                    ReleaseBin.ZERO -> MaturityBin(releases, inventory.draftOnly, inventory.discardedOnly)
                    ReleaseBin.ONE -> MaturityBin(releases, inventory.releasedWithDraft, inventory.releasedOnly)
                    else -> MaturityBin(releases, 0, 0)
                }
            },
            Activity.empty(NOW, WINDOW_DAYS),
        )

    /** Small counts often (ties, zeros, odd shares), large ones sometimes. */
    private fun next(random: Random): Long = if (random.nextBoolean()) random.nextLong(0, SMALL) else random.nextLong(0, LARGE)

    private companion object {
        const val SEED = 598
        const val RUNS = 20_000
        const val SMALL = 4L
        const val LARGE = 100_000L
        const val TEN = 10.0
        const val FULL = 1_000L
        const val QUIET_EVERY = 10
        const val WINDOW_DAYS = 14
        val NOW: Instant = Instant.parse("2026-10-17T12:00:00Z")
        val GROUPS = listOf("live", "in-development", "live-changes", "retired")
    }
}
