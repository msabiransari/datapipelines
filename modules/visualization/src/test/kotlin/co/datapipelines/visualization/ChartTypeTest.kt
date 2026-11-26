package co.datapipelines.visualization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * `ChartType` (enums.md §63; design record 2026-11-14-js-rendered-charts §4.2): the wire values against the document's
 * table, bar's channel descriptor against the record's example, the derived names, and the descriptor's own rules (a
 * choice's `type` sets are disjoint; a list's bounds are ordered). The comparison with the engine's registered
 * descriptor is `ChartTypeRegistryTest`'s (`modules/application`), where the sandbox is.
 */
class ChartTypeTest {
    @Test
    fun `the wire values are exactly enums md section 63's`() {
        val enums = VisualizationTestFiles.read("docs/enums.md")
        val start = enums.indexOf("## 63. `ChartType`")
        check(start >= 0) { "§63 ChartType not found in enums.md" }
        val rows =
            ROW
                .findAll(enums.substring(start).substringBefore("\n---"))
                .map { it.groupValues[1] }
                .toSet()
        check(rows.isNotEmpty()) { "no rows parsed under §63" }
        rows shouldBe ChartType.WIRE_VALUES.toSet()
    }

    @Test
    fun `fromWire maps each wire value to its entry and refuses any other`() {
        ChartType.entries.forEach { ChartType.fromWire(it.wire) shouldBe it }
        ChartType.fromWire("Bar") shouldBe null
        ChartType.fromWire("not_a_type") shouldBe null
    }

    @Test
    fun `bar's descriptor is the record's example, node for node`() {
        ChartType.BAR.channels.map { it.wire() } shouldBe
            listOf(
                mapOf("key" to "x", "required" to true, "node" to BAND_OR_VALUE),
                mapOf("key" to "y", "required" to true, "node" to BAND_OR_VALUE),
                mapOf(
                    "key" to "color",
                    "required" to false,
                    "node" to
                        mapOf(
                            "node" to "oneOf",
                            "of" to
                                listOf(
                                    mapOf("shape" to "series", "types" to listOf("nominal", "ordinal")),
                                    mapOf("shape" to "shade", "types" to listOf("quantitative")),
                                ),
                        ),
                ),
                mapOf(
                    "key" to "tooltip",
                    "required" to false,
                    "node" to mapOf("node" to "list", "min" to 0, "max" to 6, "of" to mapOf("node" to "shape", "name" to "detail")),
                ),
            )
    }

    @Test
    fun `line's and area's descriptor is the record's line example - a temporal x has ONE form, position`() {
        val expected =
            listOf(
                mapOf(
                    "key" to "x",
                    "required" to true,
                    "node" to
                        mapOf(
                            "node" to "oneOf",
                            "of" to
                                listOf(
                                    mapOf("shape" to "band", "types" to listOf("nominal", "ordinal")),
                                    mapOf("shape" to "position", "types" to listOf("temporal", "quantitative")),
                                ),
                        ),
                ),
                mapOf("key" to "y", "required" to true, "node" to mapOf("node" to "shape", "name" to "value")),
                mapOf("key" to "color", "required" to false, "node" to mapOf("node" to "shape", "name" to "series")),
                mapOf(
                    "key" to "tooltip",
                    "required" to false,
                    "node" to mapOf("node" to "list", "min" to 0, "max" to 6, "of" to mapOf("node" to "shape", "name" to "detail")),
                ),
            )
        ChartType.LINE.channels.map { it.wire() } shouldBe expected
        ChartType.AREA.channels.map { it.wire() } shouldBe expected
        ChartType.WIRE_VALUES shouldBe listOf("bar", "line", "area", "scatter", "pie", "heatmap", "box", "waterfall", "funnel")
    }

    @Test
    fun `box has an optional band and a required quantitative position under the point policy`() {
        ChartType.BOX.channels.map { it.wire() } shouldBe
            listOf(
                mapOf("key" to "x", "required" to false, "node" to mapOf("node" to "shape", "name" to "band")),
                mapOf("key" to "y", "required" to true, "node" to mapOf("node" to "shape", "name" to "position")),
            )
        ChartType.BOX.policy shouldBe ChartMarkPolicy.POINT
    }

    @Test
    fun `pie has one value and one series under the area policy`() {
        ChartType.PIE.channels.map { it.wire() } shouldBe
            listOf(
                mapOf("key" to "theta", "required" to true, "node" to mapOf("node" to "shape", "name" to "value")),
                mapOf("key" to "color", "required" to true, "node" to mapOf("node" to "shape", "name" to "series")),
            )
        ChartType.PIE.policy shouldBe ChartMarkPolicy.AREA
    }

    @Test
    fun `scatter's descriptor is two positions, a series or a shade, a size and the tooltip, under the point policy`() {
        val position = mapOf("node" to "shape", "name" to "position")
        ChartType.SCATTER.channels.map { it.wire() } shouldBe
            listOf(
                mapOf("key" to "x", "required" to true, "node" to position),
                mapOf("key" to "y", "required" to true, "node" to position),
                mapOf(
                    "key" to "color",
                    "required" to false,
                    "node" to
                        mapOf(
                            "node" to "oneOf",
                            "of" to
                                listOf(
                                    mapOf("shape" to "series", "types" to listOf("nominal", "ordinal")),
                                    mapOf("shape" to "shade", "types" to listOf("quantitative")),
                                ),
                        ),
                ),
                mapOf("key" to "size", "required" to false, "node" to mapOf("node" to "shape", "name" to "size")),
                mapOf(
                    "key" to "tooltip",
                    "required" to false,
                    "node" to mapOf("node" to "list", "min" to 0, "max" to 6, "of" to mapOf("node" to "shape", "name" to "detail")),
                ),
            )
        ChartType.SCATTER.policy shouldBe ChartMarkPolicy.POINT
    }

    @Test
    fun `the schema file, template file and fixtures directory derive from the wire value`() {
        ChartType.BAR.schemaFile shouldBe ChartSchemas.BAR
        ChartType.entries.forEach {
            (it.schemaFile in ChartSchemas.FILES) shouldBe true
            it.templateFile shouldBe "types/${it.wire}.js"
            it.fixturesDirectory shouldBe it.wire
        }
    }

    @Test
    fun `a choice whose alternatives share a type value is refused, naming it`() {
        val error =
            shouldThrow<IllegalArgumentException> {
                ChannelNode.OneOf(
                    listOf(ShapeAlternative("band", listOf("nominal", "temporal")), ShapeAlternative("position", listOf("temporal"))),
                )
            }
        error.message shouldContain "share a type value"
        shouldThrow<IllegalArgumentException> { ChannelNode.OneOf(listOf(ShapeAlternative("band", listOf("nominal")))) }
        shouldThrow<IllegalArgumentException> { ShapeAlternative("band", emptyList()) }
    }

    @Test
    fun `a list's bounds are ordered and an object names a member`() {
        shouldThrow<IllegalArgumentException> { ChannelNode.ListOf(min = 3, max = 1, of = ChannelNode.Shape("level")) }
        shouldThrow<IllegalArgumentException> { ChannelNode.ListOf(min = -1, max = 1, of = ChannelNode.Shape("level")) }
        shouldThrow<IllegalArgumentException> { ChannelNode.ObjectOf(emptyMap()) }
        ChannelNode.ObjectOf(mapOf("y" to ChannelNode.Shape("value"))).wire() shouldBe
            mapOf("node" to "object", "members" to mapOf("y" to mapOf("node" to "shape", "name" to "value")))
    }

    @Test
    fun `waterfall has a band and one value under the area policy`() {
        ChartType.WATERFALL.channels.map { it.wire() } shouldBe
            listOf(
                mapOf("key" to "x", "required" to true, "node" to mapOf("node" to "shape", "name" to "band")),
                mapOf("key" to "y", "required" to true, "node" to mapOf("node" to "shape", "name" to "value")),
            )
        ChartType.WATERFALL.policy shouldBe ChartMarkPolicy.AREA
    }

    private companion object {
        val ROW = Regex("^\\| `([a-z][a-z0-9_]*)` \\|", RegexOption.MULTILINE)
        val BAND_OR_VALUE =
            mapOf(
                "node" to "oneOf",
                "of" to
                    listOf(
                        mapOf("shape" to "band", "types" to listOf("nominal", "ordinal", "temporal")),
                        mapOf("shape" to "value", "types" to listOf("quantitative")),
                    ),
            )
    }

    @Test
    fun `funnel has a stage band and a single value with area policy`() {
        ChartType.FUNNEL.channels shouldBe
            listOf(
                ChartChannel("stage", required = true, node = ChannelNode.Shape("band")),
                ChartChannel("value", required = true, node = ChannelNode.Shape("value")),
            )
        ChartType.FUNNEL.policy shouldBe ChartMarkPolicy.AREA
    }
}
