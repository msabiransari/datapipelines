package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The server's bar model (design record 2026-11-06 §4.1, §5.1) on the record's own cases: order by the COLUMN'S type
 * (an exact column by exact value, never by code point or float64), exact aggregates (the mean HALF-EVEN at scale + 6,
 * the record's correction of the reference's truncation), the JavaScript number text, the gap rule, and what proves a
 * text. Each case names the wrong answer the rule exists to prevent; the parity fixtures (modules/web) pin the same
 * answers against the browser's core.
 */
class ChartModelTest {
    @Test
    fun `an exact band orders 2 before 10 by value - a code-point order would put 10 first`() {
        val model = model(config(band = "n", sort = "\"ascending\""), mapOf("n" to LogicalType.BIGINTEGER), rows("10", "2"))
        model.categories.map { it.full } shouldBe listOf("2", "10")
        // The same strings under a STRING column ARE code-point ordered.
        model(
            config(band = "n", sort = "\"ascending\""),
            mapOf("n" to LogicalType.STRING),
            rows("10", "2"),
        ).categories.map { it.full } shouldBe
            listOf("10", "2")
    }

    @Test
    fun `two exact values float64 cannot tell apart stay two ordered bands and sum exactly`() {
        val big = listOf("9007199254740993", "9007199254740992")
        val model = model(config(band = "n", sort = "\"ascending\""), mapOf("n" to LogicalType.BIGINTEGER), rows(*big.toTypedArray()))
        model.categories.map { it.full } shouldBe listOf("9007199254740992", "9007199254740993")
        model.markCount shouldBe 2
        // As a measure: float64 would give 18014398509481984; the exact sum keeps the 5.
        val summed =
            ChartModel.of(
                tree(
                    """{"mark":{"type":"bar"},"encoding":{"x":{"field":"b","type":"nominal","sort":null},
                       "y":{"field":"v","type":"quantitative","aggregate":"sum"}}}""",
                ),
                mapOf("b" to LogicalType.STRING, "v" to LogicalType.BIGINTEGER),
                big.map { mapOf("b" to "all", "v" to it) },
            )
        summed.cells
            .single()
            .single()
            .exact shouldBe "18014398509481985"
        summed.proves("18014398509481985") shouldBe true
    }

    @Test
    fun `an exact mean rounds HALF-EVEN at the inputs' scale plus six - 1 1 0 is 0_666667 and a tie at 1 over 128 is 0_007812`() {
        mean(listOf("1", "1", "0")) shouldBe "0.666667"
        // 1/128 = 0.0078125: HALF_UP (or the reference's truncation corrected the wrong way) would give 0.007813.
        mean(listOf("1") + List(127) { "0" }) shouldBe "0.007812"
        // The inputs' scale carries: 0.10 and 0.20 average to 0.15 (trailing zeros dropped from the eight-digit result).
        mean(listOf("0.10", "0.20")) shouldBe "0.15"
        // sum, min and max keep the largest input scale.
        exact(listOf("1.5", "2.25"), "sum") shouldBe "3.75"
        exact(listOf("1.50", "2"), "min") shouldBe "1.50"
        exact(listOf("1.5", "2"), "max") shouldBe "2.0"
    }

    @Test
    fun `a number's text is ECMAScript's Number-toString - the browser's label, not the JDK's`() {
        mapOf(
            1.0 to "1",
            -0.0 to "0",
            10.5 to "10.5",
            0.1 + 0.2 to "0.30000000000000004",
            1e21 to "1e+21",
            1.2345678901234568e20 to "123456789012345680000",
            1e-7 to "1e-7",
            0.000001 to "0.000001",
            -1.5e-9 to "-1.5e-9",
            123e25 to "1.23e+27",
            Double.NaN to "NaN",
            Double.NEGATIVE_INFINITY to "-Infinity",
        ).forEach { (d, text) -> withClue(d) { ChartValues.number(d) shouldBe text } }
    }

    @Test
    fun `a missing band x series pair is a gap - never a zero mark - and null categories and series are counted, not drawn`() {
        val model =
            ChartModel.of(
                tree(
                    """{"mark":{"type":"bar"},"encoding":{"x":{"field":"b","type":"nominal","sort":null},
                       "y":{"field":"v","type":"quantitative","aggregate":"sum","stack":null},
                       "color":{"field":"s","type":"nominal","sort":null}}}""",
                ),
                mapOf("b" to LogicalType.STRING, "s" to LogicalType.STRING, "v" to LogicalType.DECIMAL),
                listOf(
                    mapOf("b" to "North", "s" to "A", "v" to 1),
                    mapOf("b" to "South", "s" to "B", "v" to 2),
                    mapOf("b" to null, "s" to "A", "v" to 3),
                    mapOf("b" to "North", "s" to null, "v" to 4),
                ),
            )
        model.categories.map { it.full } shouldBe listOf("North", "South")
        model.series.map { it.full } shouldBe listOf("A", "B")
        model.cells.map { row -> row.map { it.num } } shouldBe listOf(listOf(1.0, null), listOf(null, 2.0))
        model.markCount shouldBe 2
        model.nullCategories shouldBe 1
        model.nullSeries shouldBe 1
        model.noData shouldBe false
    }

    @Test
    fun `a normalized stack draws shares of the absolute total and proves the pre-normalization aggregate`() {
        val model =
            ChartModel.of(
                tree(
                    """{"mark":{"type":"bar"},"encoding":{"x":{"field":"b","type":"nominal","sort":null},
                       "y":{"field":"v","type":"quantitative","aggregate":"sum","stack":"normalize"},
                       "color":{"field":"s","type":"nominal","sort":null}}}""",
                ),
                mapOf("b" to LogicalType.STRING, "s" to LogicalType.STRING, "v" to LogicalType.DECIMAL),
                listOf(mapOf("b" to "x", "s" to "A", "v" to 3), mapOf("b" to "x", "s" to "B", "v" to -1)),
            )
        model.stack shouldBe "normalize"
        model.cells.map { row -> row.single().num } shouldBe listOf(0.75, -0.25)
        model.proves("3") shouldBe true
        model.proves("0.75") shouldBe false
    }

    @Test
    fun `sorts - an explicit list first then first appearance, a value total with ties in first appearance, descending reversed`() {
        val types = mapOf("b" to LogicalType.STRING, "v" to LogicalType.DECIMAL)
        val data = listOf("c" to 2, "a" to 5, "b" to 2, "d" to 1).map { (b, v) -> mapOf("b" to b, "v" to v) }
        ChartModel.of(config(band = "b", sort = """["b","zz","a"]""", measure = "v"), types, data).categories.map { it.full } shouldBe
            listOf("b", "a", "c", "d")
        ChartModel.of(config(band = "b", sort = "\"-y\"", measure = "v"), types, data).categories.map { it.full } shouldBe
            listOf("a", "c", "b", "d")
        ChartModel.of(config(band = "b", sort = "\"y\"", measure = "v"), types, data).categories.map { it.full } shouldBe
            listOf("d", "c", "b", "a")
        ChartModel.of(config(band = "b", sort = "\"descending\"", measure = "v"), types, data).categories.map { it.full } shouldBe
            listOf("d", "c", "b", "a")
    }

    @Test
    fun `a temporal band orders by its UTC digits and labels at its granularity`() {
        val model =
            ChartModel.of(
                tree(
                    """{"mark":{"type":"bar"},"encoding":{"x":{"field":"t","type":"temporal","sort":"ascending","format":"month"},
                       "y":{"type":"quantitative","aggregate":"count"}}}""",
                ),
                mapOf("t" to LogicalType.TIMESTAMP),
                listOf("2026-02-01T00:00:00.5Z", "2026-02-01T00:00:00.123456789Z", "2025-12-31T23:59Z").map { mapOf("t" to it) },
            )
        model.categories.map { it.raw } shouldBe listOf("2025-12-31T23:59Z", "2026-02-01T00:00:00.123456789Z", "2026-02-01T00:00:00.5Z")
        model.categories.map { it.full } shouldBe listOf("2025-12", "2026-02", "2026-02")
        ChartTemporal.label("2026-03-04T05:06:07Z", null) shouldBe "2026-03-04 05:06 UTC"
        ChartTemporal.label("2026-03-04", "year") shouldBe "2026"
    }

    @Test
    fun `text is proven by a category, by a series only through color, and by an aggregate - a label past 32 code points is cut`() {
        val long = "A region name that is far too long for an axis"
        val model = model(config(band = "n", sort = "null"), mapOf("n" to LogicalType.STRING), rows(long))
        model.categories.single().label shouldBe ChartModel.cut(long)
        model.categories
            .single()
            .label
            .codePointCount(
                0,
                model.categories
                    .single()
                    .label.length,
            ) shouldBe ChartModel.LABEL_CODE_POINTS
        model.proves("far too long") shouldBe true
        model.proves("1") shouldBe true // the count aggregate's text
        // Without color the series is the measure's name, which proves nothing.
        model.proves("count") shouldBe false
        ChartModel.cut("😀".repeat(40)).codePointCount(0, ChartModel.cut("😀".repeat(40)).length) shouldBe
            ChartModel.LABEL_CODE_POINTS
    }

    @Test
    fun `a measure value outside its wire form is refused by the model, naming the field and never the value`() {
        val error =
            shouldThrow<ChartModel.NotAMeasure> {
                ChartModel.of(
                    config(band = "b", sort = "null", measure = "v"),
                    mapOf("b" to LogicalType.STRING, "v" to LogicalType.BIGDECIMAL),
                    listOf(mapOf("b" to "x", "v" to "12 apples")),
                )
            }
        error.message shouldBe "A value of 'v' is not a number in its column's wire form."
    }

    private fun mean(values: List<String>): String? = exact(values, "mean")

    private fun exact(
        values: List<String>,
        aggregate: String,
    ): String? = ChartAggregates.of(values, aggregate, "v").exact

    private fun rows(vararg values: String): List<Map<String, Any?>> = values.map { mapOf("n" to it) }

    private fun model(
        config: JsonNode,
        types: Map<String, LogicalType>,
        rows: List<Map<String, Any?>>,
    ): ChartModel = ChartModel.of(config, types, rows)

    /** A vertical bar: band [band] sorted by [sort] (JSON), value = `sum` of [measure], or `count` when null. */
    private fun config(
        band: String,
        sort: String,
        measure: String? = null,
    ): JsonNode {
        val y =
            if (measure == null) {
                """{"type":"quantitative","aggregate":"count"}"""
            } else {
                """{"field":"$measure","type":"quantitative","aggregate":"sum"}"""
            }
        return tree("""{"mark":{"type":"bar"},"encoding":{"x":{"field":"$band","type":"nominal","sort":$sort},"y":$y}}""")
    }

    private fun tree(json: String): JsonNode = ArtifactJson.mapper.readTree(json)
}
