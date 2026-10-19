package co.datapipelines.web.ui

import co.datapipelines.pipeline.Inventory
import co.datapipelines.pipeline.MaturityBin
import co.datapipelines.pipeline.ReleaseBin
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * #598a — the stacked charts' arithmetic over random inventories: every row's parts start where
 * the one before ended and end exactly at the row's width (in tenths, no gap, no overlap however
 * the shares round), every width is a share of the longest row, and both charts total the same.
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
    }
}
