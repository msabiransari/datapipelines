package co.datapipelines.web.ui

import co.datapipelines.pipeline.Activity
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
 * #598a — the stacked charts' arithmetic over random inventories: every row's parts start where
 * the one before ended and end exactly at the row's width (in tenths, no gap, no overlap however
 * the shares round), every width is a share of the longest row, and both charts total the same.
 * #598b adds the activity chart's twelve weekly rows: the busiest week is full width.
 */
class OverviewStackedTest {
    private val family = OverviewPage.Family.PIPELINES

    @Test
    fun `random lifecycle bars tile exactly 0 to 100 and their parts sum to the total`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val inventory = Inventory(next(random), next(random), next(random), next(random))
            val chart = OverviewStacked.lifecycle(family, inventory)
            val row = chart.rows.single()

            row.width shouldBe if (inventory.objects == 0L) "0" else "100.0"
            tile(row) shouldBe tenths(row.width)
            row.parts.sumOf { it.value } shouldBe inventory.objects
            chart.legend.map { it.value } shouldBe row.parts.map { it.value }
            chart.total shouldBe inventory.objects
        }
    }

    @Test
    fun `random maturity rows tile their own width, the longest is 100, and the rows sum to the total`() {
        val random = Random(SEED)
        repeat(RUNS) {
            val bins = ReleaseBin.entries.map { MaturityBin(it, next(random), next(random)) }
            val chart = OverviewStacked.maturity(family, bins)
            val tallest = bins.maxOf { it.total }

            chart.rows.zip(bins).forEach { (row, bin) ->
                row.width shouldBe OverviewPage.width(bin.total, tallest)
                tile(row) shouldBe tenths(row.width)
                row.parts.map { it.value } shouldBe listOf(bin.noDraft, bin.draftOpen)
            }
            if (tallest > 0) chart.rows.maxOf { tenths(it.width) } shouldBe FULL
            chart.total shouldBe bins.sumOf { it.total }
        }
    }

    @Test
    fun `random activity weeks tile their own width, the busiest is 100, and an all-zero quarter is all 0`() {
        val random = Random(SEED)
        val mondays = Activity.weekStarts(NOW)
        repeat(RUNS) { run ->
            // Every tenth run is a quiet quarter, so the all-zero branch is exercised, not hoped for.
            val quiet = run % QUIET_EVERY == 0
            val weeks = mondays.map { WeekActivity(it, if (quiet) 0 else next(random), if (quiet) 0 else next(random)) }
            val active = next(random)
            val chart = OverviewStacked.activity(family, Activity(weeks, active, WINDOW_DAYS))
            val busiest = weeks.maxOf { it.events }

            chart.rows.map { it.key } shouldContainExactly mondays.map { "week-$it" }
            chart.rows.zip(weeks).forEach { (row, week) ->
                row.width shouldBe OverviewPage.width(week.events, busiest)
                tile(row) shouldBe tenths(row.width)
                row.parts.map { it.value } shouldBe listOf(week.released, week.created)
                row.total shouldBe week.events
            }
            if (busiest > 0) chart.rows.maxOf { tenths(it.width) } shouldBe FULL
            if (busiest == 0L) chart.rows.flatMap { it.parts.map { part -> part.width } + it.width }.distinct() shouldBe listOf("0")
            chart.legend.map { it.value } shouldBe listOf(weeks.sumOf { it.released }, weeks.sumOf { it.created })
            chart.total shouldBe active
        }
    }

    /** Where the row's last part ends, in tenths, after checking each part starts where the previous ended. */
    private fun tile(row: StackedRow): Long =
        row.parts.fold(0L) { end, part ->
            tenths(part.x) shouldBe end
            end + tenths(part.width)
        }

    private fun tenths(attribute: String): Long = (attribute.toDouble() * TEN).roundToLong()

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
    }
}
