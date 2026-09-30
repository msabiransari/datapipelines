package co.datapipelines.application.dashboards

import co.datapipelines.application.dashboards.RefreshFixtures.columns
import co.datapipelines.executor.ResultBytes
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * [BoundedCollector] — the D54 caps (spec §9.5). The properties: rows are counted with the result store's own
 * accounting BEFORE they are kept; the collector STOPS at the first row that would cross a cap — never buffering past
 * it — and names which cap; the refresh cap is shared by every collector of one refresh; and a dropped partial table
 * gives its bytes back.
 *
 * Falsified at birth: moving the check to after the row is added (measure, then compare) makes the "stops before the
 * row is kept" case red — the collector held the offending row.
 */
class BoundedCollectorTest {
    private val schema = columns("x" to LogicalType.INTEGER)
    private val rowBytes = ResultBytes.rowBytes(listOf(1), schema)

    @Test
    fun `a result within both caps is kept whole and counted the way the store counts it`() =
        runBlocking<Unit> {
            val budget = RefreshByteBudget(maxBytes = 1_000)
            val collector = BoundedCollector(maxBytes = 100, budget = budget)

            collector.accept(schema, sequenceOf(listOf(1), listOf(2), listOf(3)))

            val table = collector.table.shouldNotBeNull()
            table.rows.size shouldBe 3
            table.bytes shouldBe rowBytes * 3
            budget.usedBytes shouldBe rowBytes * 3
            collector.overflow.shouldBeNull()
        }

    @Test
    fun `it stops at the first row that would cross the source cap - the offending row is never kept or pulled past`() =
        runBlocking<Unit> {
            val budget = RefreshByteBudget(maxBytes = 1_000)
            val collector = BoundedCollector(maxBytes = rowBytes * 2, budget = budget)
            var pulled = 0
            // A cursor far larger than the cap: a collector that measures AFTER materialising pulls all of it.
            val rows =
                (1..1_000_000).asSequence().map {
                    pulled++
                    listOf<Any?>(it)
                }

            val stop = shouldThrow<ResultTooLarge> { collector.accept(schema, rows) }

            stop.cap shouldBe Overflow.SOURCE
            collector.overflow shouldBe Overflow.SOURCE
            collector.table.shouldBeNull()
            pulled shouldBe 3 // two kept, the third measured and refused — a million-row cursor is not drained
            budget.usedBytes shouldBe 0L // the dropped partial table gave its bytes back
        }

    @Test
    fun `the refresh cap is shared - the second collector stops where the first left the budget`() =
        runBlocking<Unit> {
            val budget = RefreshByteBudget(maxBytes = rowBytes * 3)
            val first = BoundedCollector(maxBytes = 1_000, budget = budget)
            val second = BoundedCollector(maxBytes = 1_000, budget = budget)

            first.accept(schema, sequenceOf(listOf(1), listOf(2)))
            val stop = shouldThrow<ResultTooLarge> { second.accept(schema, sequenceOf(listOf(1), listOf(2))) }

            stop.cap shouldBe Overflow.REFRESH
            second.overflow shouldBe Overflow.REFRESH
            first.table.shouldNotBeNull()
            budget.usedBytes shouldBe rowBytes * 2 // the first's rows only; the second's partial row was returned
        }

    @Test
    fun `an empty result is a complete empty table, not a missing one`() =
        runBlocking<Unit> {
            val collector = BoundedCollector(maxBytes = 10, budget = RefreshByteBudget(10))

            collector.accept(schema, emptySequence())

            collector.table.shouldNotBeNull().rows shouldBe emptyList()
        }

    @Test
    fun `the budget refuses what does not fit and takes nothing`() {
        val budget = RefreshByteBudget(maxBytes = 10)

        budget.tryTake(8) shouldBe true
        budget.tryTake(3) shouldBe false
        budget.usedBytes shouldBe 8L
        budget.give(8)
        budget.usedBytes shouldBe 0L
    }
}
