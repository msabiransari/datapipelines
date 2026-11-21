package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The save rule of each new shape, written once per shape (design record 2026-11-14-js-rendered-charts §4.4, §9 G2;
 * brief 1050 D2, A.1), through a descriptor of this test's own: one channel per shape, so no rule is proven only under
 * bar's `sequential` (the descriptor admits `diverging`, which no served schema draws yet).
 */
class ChartShapeRulesTest {
    private val channels =
        listOf(
            ChartChannel("shade", required = false, node = ChannelNode.Shape("shade")),
            ChartChannel("size", required = false, node = ChannelNode.Shape("size")),
            ChartChannel("count", required = false, node = ChannelNode.Shape("count")),
            ChartChannel("level", required = false, node = ChannelNode.Shape("level")),
        )

    private val columns =
        listOf(
            InputColumn("amount", LogicalType.DECIMAL),
            InputColumn("units", LogicalType.INTEGER, nullable = true),
            InputColumn("name", LogicalType.STRING),
            InputColumn("segment", LogicalType.STRING, nullable = true),
            InputColumn("payload", LogicalType.BINARY, nullable = true),
        )

    @Test
    fun `shade - a text column is refused with every aggregate, count among them, at the field's pointer`() {
        for (aggregate in listOf("sum", "mean", "min", "max", "count")) {
            refusals("""{"shade": ${shade("name", aggregate)}}""") shouldBe
                listOf("chart.measure.not_numeric" to "/config/encoding/shade/field")
        }
    }

    @Test
    fun `shade - a BINARY column is refused as any encoded field is, before the numeric rule`() {
        refusals("""{"shade": ${shade("payload", "count")}}""") shouldBe listOf("chart.field.binary" to "/config/encoding/shade/field")
        refusals("""{"shade": ${shade("missing", "sum")}}""") shouldBe listOf("chart.field.unknown" to "/config/encoding/shade/field")
    }

    @Test
    fun `shade - a numeric column is admitted with count and with sum, nullable or not`() {
        refusals("""{"shade": ${shade("units", "count")}}""").shouldBeEmpty()
        refusals("""{"shade": ${shade("amount", "sum")}}""").shouldBeEmpty()
    }

    @Test
    fun `shade - a mid past a double's range on either side is refused at its pointer, a finite one or none admitted`() {
        for (mid in listOf("1e400", "-1e400")) {
            refusals("""{"shade": ${shade("amount", "sum", "diverging", mid)}}""") shouldBe
                listOf("chart.shade.mid_invalid" to "/config/encoding/shade/mid")
        }
        refusals("""{"shade": ${shade("amount", "sum", "diverging", "0")}}""").shouldBeEmpty()
        refusals("""{"shade": ${shade("amount", "sum", "diverging")}}""").shouldBeEmpty()
        refusals("""{"shade": ${shade("amount", "sum", "diverging", "-12.5")}}""").shouldBeEmpty()
    }

    @Test
    fun `size - a text column is refused, a numeric one admitted`() {
        refusals("""{"size": {"field": "name"}}""") shouldBe listOf("chart.measure.not_numeric" to "/config/encoding/size/field")
        refusals("""{"size": {"field": "units"}}""").shouldBeEmpty()
    }

    @Test
    fun `size - a range that does not increase is refused at its pointer, an increasing one admitted`() {
        for (range in listOf("[20, 10]", "[10, 10]")) {
            refusals("""{"size": {"field": "amount", "range": $range}}""") shouldBe
                listOf("chart.size.range_invalid" to "/config/encoding/size/range")
        }
        for (range in listOf("[4, 64]", "[6.5, 40.25]")) refusals("""{"size": {"field": "amount", "range": $range}}""").shouldBeEmpty()
    }

    @Test
    fun `level - a nullable column is refused, a column that is not nullable admitted`() {
        refusals("""{"level": {"field": "segment"}}""") shouldBe listOf("chart.category.nullable" to "/config/encoding/level/field")
        refusals("""{"level": {"field": "name"}}""").shouldBeEmpty()
    }

    @Test
    fun `count - yields a shape with no position and no column rule`() {
        val config = config("""{"count": {"aggregate": "share"}}""")
        ChartPositions.shapes(config, channels).map { it.shape to it.positions } shouldBe listOf("count" to emptyList())
        refusals("""{"count": {"aggregate": "count"}}""").shouldBeEmpty()
    }

    @Test
    fun `the served columns and the derived bindings hold a shade's field - bar's color shade`() {
        val bar =
            ArtifactJson.mapper.readTree(
                """
                {"mark": {"type": "bar"}, "encoding": {
                  "x": {"field": "name", "type": "nominal", "sort": null},
                  "y": {"field": "amount", "type": "quantitative", "aggregate": "sum"},
                  "color": {"field": "units", "type": "quantitative", "aggregate": "count", "scale": "sequential"}}}
                """.trimIndent(),
            )
        ChartPositions.of(bar).map { Triple(it.field, it.pointer, it.holder.shape) } shouldBe
            listOf(
                Triple("name", "/config/encoding/x/field", "band"),
                Triple("amount", "/config/encoding/y/field", "value"),
                Triple("units", "/config/encoding/color/field", "shade"),
            )
        val body = ArtifactJson.mapper.createObjectNode()
        body.putObject("inputs").putObject("sales")
        body.set<JsonNode>("config", bar)
        val verdict = ChartSaveRules.check(body, columns)
        verdict.refusals.shouldBeEmpty()
        verdict.derivedBindings shouldBe mapOf("amount" to "amount", "name" to "name", "units" to "units")
    }

    private fun shade(
        field: String,
        aggregate: String,
        scale: String = "sequential",
        mid: String? = null,
    ): String {
        val midPart = mid?.let { """, "mid": $it""" } ?: ""
        return """{"field": "$field", "aggregate": "$aggregate", "type": "quantitative", "scale": "$scale"$midPart}"""
    }

    private fun config(encoding: String): JsonNode =
        ArtifactJson.mapper.readTree("""{"mark": {"type": "test_shapes"}, "encoding": $encoding}""")

    private fun refusals(encoding: String): List<Pair<String, String>> {
        val body = ArtifactJson.mapper.createObjectNode()
        body.putObject("inputs").putObject("sales")
        body.set<JsonNode>("config", config(encoding))
        return ChartSaveRules.check(body, columns, channels, policy = null).refusals.map { it.code to it.pointer }
    }
}
