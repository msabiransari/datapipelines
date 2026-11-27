package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The policy by mark (design record 2026-11-14-js-rendered-charts §9 G2, "The layer policy"; brief 1050 D3, A.2): one
 * layered document through a descriptor of this test's own, with a bar, a line, an area and a point layer, each layer's
 * scale judged by its own mark's row, and a shape outside any layer by the type's.
 */
class ChartMarkPolicyTest {
    private val channels =
        listOf(
            ChartChannel("x", required = false, node = ChannelNode.Shape("value")),
            ChartChannel(
                "layers",
                required = false,
                node =
                    ChannelNode.ListOf(
                        min = 1,
                        max = 4,
                        of = ChannelNode.ObjectOf(mapOf("y" to ChannelNode.Shape("value")), layer = "mark"),
                    ),
            ),
        )

    private val columns = listOf(InputColumn("amount", LogicalType.DECIMAL))

    private val marks = listOf("bar", "line", "area", "point")

    @Test
    fun `the table holds the four rows of G4 and G8, and each type names its row`() {
        ChartMarkPolicy.entries.map { Triple(it.wire, it.alwaysZero, it.logAdmitted) } shouldBe
            listOf(Triple("bar", true, true), Triple("line", false, true), Triple("area", true, false), Triple("point", false, true))
        ChartType.entries.associate { it.wire to it.policy } shouldBe
            mapOf(
                "bar" to ChartMarkPolicy.BAR,
                "line" to ChartMarkPolicy.LINE,
                "area" to ChartMarkPolicy.AREA,
                "scatter" to ChartMarkPolicy.POINT,
                "pie" to ChartMarkPolicy.AREA,
                "combo" to ChartMarkPolicy.LINE,
                "heatmap" to ChartMarkPolicy.BAR,
                "box" to ChartMarkPolicy.POINT,
                "waterfall" to ChartMarkPolicy.AREA,
                "funnel" to ChartMarkPolicy.AREA,
                "gauge" to ChartMarkPolicy.LINE,
                "treemap" to ChartMarkPolicy.AREA,
            )
    }

    @Test
    fun `log is admitted on the bar, line and point layers and refused on the area layer at that layer's scale type`() {
        refusals(layers("""{"type": "log"}"""), ChartMarkPolicy.LINE) shouldBe
            listOf("chart.scale.log_unsupported" to "/config/encoding/layers/2/y/scale/type")
    }

    @Test
    fun `a range without zero is refused on the bar and the area layer and admitted on the line and the point layer`() {
        refusals(layers("""{"domain": [10, 20]}"""), ChartMarkPolicy.LINE) shouldBe
            listOf(
                "chart.scale.domain_invalid" to "/config/encoding/layers/0/y/scale/domain",
                "chart.scale.domain_invalid" to "/config/encoding/layers/2/y/scale/domain",
            )
    }

    @Test
    fun `a shape outside any layer takes the type's policy`() {
        val x = """"x": ${value("""{"domain": [10, 20]}""")}"""
        refusals("{$x}", ChartMarkPolicy.BAR) shouldBe listOf("chart.scale.domain_invalid" to "/config/encoding/x/scale/domain")
        refusals("{$x}", ChartMarkPolicy.LINE).shouldBeEmpty()
        val logX = """"x": ${value("""{"type": "log"}""")}"""
        refusals("{$logX}", ChartMarkPolicy.AREA) shouldBe listOf("chart.scale.log_unsupported" to "/config/encoding/x/scale/type")
        refusals("{$logX}", ChartMarkPolicy.POINT).shouldBeEmpty()
    }

    @Test
    fun `the walk hands each shape inside a layer its layer's mark, and null outside`() {
        val config = config("""{"x": ${value("""{"type": "log"}""")}, ${layers("""{"type": "log"}""").removeSurrounding("{", "}")}}""")
        ChartPositions.shapes(config, channels).map { it.pointer to it.mark } shouldBe
            listOf("/config/encoding/x" to null) + marks.mapIndexed { i, m -> "/config/encoding/layers/$i/y" to m }
    }

    @Test
    fun `a layer mark that names no row is a descriptor error, not a document refusal`() {
        val error =
            shouldThrow<IllegalArgumentException> {
                refusals(
                    """{"layers": [{"mark": "pie", "y": ${value("""{"type": "log"}""")}}]}""",
                    null,
                )
            }
        error.message shouldBe "no mark policy for the layer mark pie"
    }

    @Test
    fun `an object node carries layer in its wire form only when it is set, so the registered descriptors are unchanged`() {
        val members = mapOf("y" to ChannelNode.Shape("value"))
        ChannelNode.ObjectOf(members).wire() shouldBe
            mapOf("node" to "object", "members" to mapOf("y" to mapOf("node" to "shape", "name" to "value")))
        ChannelNode.ObjectOf(members, layer = "mark").wire() shouldBe
            mapOf("node" to "object", "members" to mapOf("y" to mapOf("node" to "shape", "name" to "value")), "layer" to "mark")
        shouldThrow<IllegalArgumentException> { ChannelNode.ObjectOf(members, layer = "y") }
    }

    private fun value(scale: String): String = """{"field": "amount", "type": "quantitative", "aggregate": "sum", "scale": $scale}"""

    private fun layers(scale: String): String =
        """{"layers": [${marks.joinToString(", ") { """{"mark": "$it", "y": ${value(scale)}}""" }}]}"""

    private fun config(encoding: String): JsonNode =
        ArtifactJson.mapper.readTree("""{"mark": {"type": "test_layers"}, "encoding": $encoding}""")

    private fun refusals(
        encoding: String,
        policy: ChartMarkPolicy?,
    ): List<Pair<String, String>> {
        val body = ArtifactJson.mapper.createObjectNode()
        body.putObject("inputs").putObject("sales")
        body.set<JsonNode>("config", config(encoding))
        return ChartSaveRules.check(body, columns, channels, policy).refusals.map { it.code to it.pointer }
    }
}
