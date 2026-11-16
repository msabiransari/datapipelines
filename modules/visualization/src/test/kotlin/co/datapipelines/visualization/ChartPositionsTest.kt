package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one walk over a type's channel descriptor (design record 2026-11-14-js-rendered-charts §4.2, O-12; brief 986 A.3):
 * bar's descriptor lists every field-bearing position with its JSON Pointer, in descriptor order — `/x/field`,
 * `/y/field` or `/y/fields/<i>/field`, `/color/field`, `/tooltip/<i>/field` under `/config/encoding` — and the shape
 * that holds it, which is what the save rules, the served columns and the derived bindings read.
 */
class ChartPositionsTest {
    @Test
    fun `bar's descriptor lists every field-bearing position with its pointer and shape - the record's example`() {
        val config =
            config(
                """
                {"x": {"field": "month", "type": "nominal", "sort": null},
                 "y": {"type": "quantitative", "aggregate": "sum", "fields": [{"field": "north"}, {"field": "south"}]},
                 "color": {"field": "region", "type": "nominal"},
                 "tooltip": [{"field": "note"}, {"field": "owner"}]}
                """,
            )
        listed(config) shouldBe
            listOf(
                Triple("month", "/config/encoding/x/field", "band"),
                Triple("north", "/config/encoding/y/fields/0/field", "value"),
                Triple("south", "/config/encoding/y/fields/1/field", "value"),
                Triple("region", "/config/encoding/color/field", "series"),
                Triple("note", "/config/encoding/tooltip/0/field", "detail"),
                Triple("owner", "/config/encoding/tooltip/1/field", "detail"),
            )
    }

    @Test
    fun `the object's own type selects the shape - a horizontal bar's x is the value, its y the band`() {
        val config =
            config(
                """
                {"x": {"field": "amount", "type": "quantitative", "aggregate": "sum"},
                 "y": {"field": "month", "type": "temporal", "sort": null}}
                """,
            )
        listed(config) shouldBe
            listOf(
                Triple("amount", "/config/encoding/x/field", "value"),
                Triple("month", "/config/encoding/y/field", "band"),
            )
    }

    @Test
    fun `a quantitative color is the shade shape, and a count value is a shape with no position`() {
        val config =
            config(
                """
                {"x": {"field": "month", "type": "ordinal", "sort": null},
                 "y": {"type": "quantitative", "aggregate": "count"},
                 "color": {"field": "intensity", "type": "quantitative"}}
                """,
            )
        ChartPositions.shapes(config).map { it.shape to it.pointer } shouldBe
            listOf(
                "band" to "/config/encoding/x",
                "value" to "/config/encoding/y",
                "shade" to "/config/encoding/color",
            )
        listed(config) shouldBe
            listOf(
                Triple("month", "/config/encoding/x/field", "band"),
                Triple("intensity", "/config/encoding/color/field", "shade"),
            )
    }

    @Test
    fun `a mark type no chart type names, or a type no alternative takes, yields nothing - the schema refuses those`() {
        val unknownMark =
            ArtifactJson.mapper.readTree("""{"mark": {"type": "pie"}, "encoding": {"x": {"field": "a", "type": "nominal"}}}""")
        ChartPositions.of(unknownMark).shouldBeEmpty()
        val unknownType = config("""{"x": {"field": "a", "type": "geographic"}}""")
        ChartPositions.of(unknownType).shouldBeEmpty()
    }

    @Test
    fun `the descriptor drives the save - a nullable category column is refused at the pointer of its position (G6)`() {
        val columns = listOf(InputColumn("month", LogicalType.STRING, nullable = true), InputColumn("amount", LogicalType.DECIMAL))
        val band = """{"field": "month", "type": "nominal", "sort": null}"""
        val value = """{"field": "amount", "type": "quantitative", "aggregate": "sum"}"""
        val vertical = """{"x": $band, "y": $value}"""
        val horizontal = """{"x": $value, "y": $band}"""
        refusals(vertical, columns) shouldBe listOf("chart.category.nullable" to "/config/encoding/x/field")
        refusals(horizontal, columns) shouldBe listOf("chart.category.nullable" to "/config/encoding/y/field")
    }

    private fun refusals(
        encoding: String,
        columns: List<InputColumn>,
    ): List<Pair<String, String>> {
        val body = ArtifactJson.mapper.createObjectNode()
        body.putObject("inputs").putObject("sales")
        body.set<JsonNode>("config", config(encoding))
        return ChartSaveRules.check(body, columns).refusals.map { it.code to it.pointer }
    }

    private fun config(encoding: String): JsonNode =
        ArtifactJson.mapper.readTree("""{"mark": {"type": "bar"}, "encoding": ${encoding.trimIndent()}}""")

    private fun listed(config: JsonNode): List<Triple<String, String, String>> =
        ChartPositions.of(config).map { Triple(it.field, it.pointer, it.holder.shape) }
}
