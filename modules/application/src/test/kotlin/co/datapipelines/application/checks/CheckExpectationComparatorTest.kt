package co.datapipelines.application.checks

import co.datapipelines.datasources.QueryRows
import co.datapipelines.datasources.ResultSchema
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The verdict logic of a check run, proven without JDBC, a datasource or a connection — that is
 * why [CheckExpectationComparator] is its own class: every boundary the brief names (tolerance
 * edges, the numeric coercion, the shape refusals, the three rows-count branches) is a fact
 * about a [QueryRows] value, and a value is what a unit test builds.
 */
class CheckExpectationComparatorTest {
    // ------------------------------------------------------------------------------- value

    @Test
    fun `a value exactly at the tolerance boundary passes`() {
        val comparison = compare(expectation(value = 74.62, tolerance = 0.01), singleCell(BigDecimal("74.63")))

        comparison.verdict shouldBe CheckRunVerdict.PASS
        comparison.observed shouldBe "74.63"
        comparison.observedKind shouldBe CheckComparison.ObservedKind.VALUE
        comparison.message.shouldBeNull()
    }

    @Test
    fun `one ulp beyond the tolerance fails - the boundary is inclusive, not fuzzy`() {
        val comparison = compare(expectation(value = 74.62, tolerance = 0.01), singleCell(BigDecimal("74.63000000000001")))

        comparison.verdict shouldBe CheckRunVerdict.FAIL
        comparison.message shouldBe "expected 74.62 ± 0.01, observed 74.63000000000001"
    }

    @Test
    fun `a null tolerance is zero tolerance`() {
        compare(expectation(value = 74.62), singleCell(BigDecimal("74.62"))).verdict shouldBe CheckRunVerdict.PASS

        val off = compare(expectation(value = 74.62), singleCell(BigDecimal("74.6200001")))
        off.verdict shouldBe CheckRunVerdict.FAIL
        // No tolerance member, so the fail message does not invent one.
        off.message shouldBe "expected 74.62, observed 74.6200001"
    }

    @Test
    fun `a numeric cell coerces across the canonical numeric family`() {
        // The DECIMAL cell: BigDecimal through the wire unchanged.
        compare(expectation(value = 74.62), singleCell(BigDecimal("74.62"))).verdict shouldBe CheckRunVerdict.PASS
        // The BIGINTEGER / BIGDECIMAL wire form: a decimal STRING.
        compare(expectation(value = 42.0), singleCell("42")).verdict shouldBe CheckRunVerdict.PASS
        compare(expectation(value = 42.0), singleCell(BigInteger("42"))).verdict shouldBe CheckRunVerdict.PASS
        // The approximate family: a Java double — valueOf's shortest round-trip, never the binary expansion.
        compare(expectation(value = 74.62, tolerance = 0.001), singleCell(74.62)).verdict shouldBe CheckRunVerdict.PASS
        // The exact small-integer family.
        compare(expectation(value = 7.0), singleCell(7)).verdict shouldBe CheckRunVerdict.PASS
        compare(expectation(value = 7.0), singleCell(7L)).verdict shouldBe CheckRunVerdict.PASS
    }

    @Test
    fun `a non-numeric single cell is an error, never a fail`() {
        val comparison = compare(expectation(value = 74.62), singleCell("abc"))

        comparison.verdict shouldBe CheckRunVerdict.ERROR
        comparison.observed.shouldBeNull()
        comparison.message shouldContain "not numeric"
    }

    @Test
    fun `a NULL single cell is an error - NULL has no numeric reading`() {
        val comparison = compare(expectation(value = 74.62), singleCell(null))

        comparison.verdict shouldBe CheckRunVerdict.ERROR
        comparison.message shouldContain "NULL"
    }

    // ------------------------------------------------------------------------------- shape

    @Test
    fun `a two-column result is an error naming the shape`() {
        val rows =
            QueryRows(
                ResultSchema(listOf(ColumnSchema("a", LogicalType.INTEGER), ColumnSchema("b", LogicalType.INTEGER)), emptyList()),
                listOf(mapOf("a" to 1, "b" to 2)),
                truncated = false,
            )

        val comparison = compare(expectation(value = 1.0), rows)

        comparison.verdict shouldBe CheckRunVerdict.ERROR
        comparison.message shouldBe "expected one row and one column, got 1 row x 2 columns"
    }

    @Test
    fun `zero rows is an error for value and range - there is no cell to compare`() {
        val comparison = compare(expectation(value = 1.0), QueryRows(ONE_COLUMN, emptyList(), truncated = false))

        comparison.verdict shouldBe CheckRunVerdict.ERROR
        comparison.message shouldBe "expected one row and one column, got 0 rows x 1 column"
    }

    @Test
    fun `three rows are named in the error - a value check never compares an arbitrary cell`() {
        val rows = QueryRows(ONE_COLUMN, listOf(cellRow(1), cellRow(2), cellRow(3)), truncated = false)

        compare(expectation(min = 1.0, max = 3.0, kind = CheckExpectation.KIND_RANGE), rows)
            .message shouldBe "expected one row and one column, got 3 rows x 1 column"
    }

    // ------------------------------------------------------------------------------- range

    @Test
    fun `a range is inclusive on both ends`() {
        val expected = expectation(min = 1.0, max = 5.0, kind = CheckExpectation.KIND_RANGE)

        compare(expected, singleCell(BigDecimal("1"))).verdict shouldBe CheckRunVerdict.PASS
        compare(expected, singleCell(BigDecimal("5"))).verdict shouldBe CheckRunVerdict.PASS

        val outside = compare(expected, singleCell(BigDecimal("5.1")))
        outside.verdict shouldBe CheckRunVerdict.FAIL
        outside.message shouldBe "expected a value in [1, 5], observed 5.1"
    }

    // ------------------------------------------------------------------------------- rows

    @Test
    fun `a rows check passes when the count equals the expectation`() {
        val rows = QueryRows(ONE_COLUMN, listOf(cellRow(1), cellRow(2), cellRow(3)), truncated = false)

        val comparison = compare(expectation(rows = 3, kind = CheckExpectation.KIND_ROWS), rows)

        comparison.verdict shouldBe CheckRunVerdict.PASS
        comparison.observed shouldBe "3"
        comparison.observedKind shouldBe CheckComparison.ObservedKind.ROWS
    }

    @Test
    fun `a rows check fails when the count differs`() {
        val rows = QueryRows(ONE_COLUMN, listOf(cellRow(1), cellRow(2), cellRow(3), cellRow(4), cellRow(5)), truncated = false)

        val comparison = compare(expectation(rows = 6, kind = CheckExpectation.KIND_ROWS), rows)

        comparison.verdict shouldBe CheckRunVerdict.FAIL
        comparison.observed shouldBe "5"
        comparison.message shouldBe "expected 6 rows, observed 5"
    }

    @Test
    fun `a truncated result at or past the expectation is a clean fail - the count is provably unequal`() {
        // The runner asked for expected+1 = 7 and the probe still flagged truncation: the true
        // count is > 7, which is provably not 6. An error here would say "unknowable" about a
        // fact the probe DID prove.
        val sevenRows = (1..7).map(::cellRow)
        val comparison =
            compare(expectation(rows = 6, kind = CheckExpectation.KIND_ROWS), QueryRows(ONE_COLUMN, sevenRows, truncated = true))

        comparison.verdict shouldBe CheckRunVerdict.FAIL
        comparison.observed shouldBe "7"
        comparison.message shouldBe "expected 6 rows, observed more than 7"
    }

    @Test
    fun `a truncated result below the expectation is an error naming the probe cap`() {
        // expected 600 but the probe's 500-row cap cut the read off: the true count could still
        // be 600, so no honest verdict exists — the truth, recorded, never a silent undercount.
        val fiveHundred = (1..500).map(::cellRow)
        val comparison =
            compare(expectation(rows = 600, kind = CheckExpectation.KIND_ROWS), QueryRows(ONE_COLUMN, fiveHundred, truncated = true))

        comparison.verdict shouldBe CheckRunVerdict.ERROR
        comparison.observed.shouldBeNull()
        comparison.message shouldContain "row cap"
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun compare(
        expected: CheckExpectation,
        rows: QueryRows,
    ) = CheckExpectationComparator.compare(expected, rows)

    private fun expectation(
        value: Double? = null,
        min: Double? = null,
        max: Double? = null,
        rows: Long? = null,
        tolerance: Double? = null,
        kind: String = CheckExpectation.KIND_VALUE,
    ) = CheckExpectation(kind = kind, value = value, min = min, max = max, rows = rows, tolerance = tolerance)

    private fun singleCell(value: Any?) = QueryRows(ONE_COLUMN, listOf(cellRow(value)), truncated = false)

    private fun cellRow(value: Any?): Map<String, Any?> = mapOf("n" to value)

    private companion object {
        val ONE_COLUMN = ResultSchema(listOf(ColumnSchema("n", LogicalType.BIGDECIMAL)), emptyList())
    }
}
