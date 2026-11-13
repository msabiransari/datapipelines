package co.datapipelines.visualization

import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The module's renderer schemas, pinned (Dashboards §2.1.2): the two vendored Plotly bundles' trace lists (the spec's
 * §10.4 — library facts the chart converter loads, no document vocabulary since the 860 retirement), and the `table`
 * and `kpi` schemas this module defines; a `chart` is judged as a whole document elsewhere.
 */
class RendererConfigValidatorsTest {
    private val validator = RendererConfigValidators.default()

    @Test
    fun `the trace lists are the spec's two bundles - six 2D, three 3D`() {
        RendererConfigValidators.PLOTLY_2D_TRACES shouldBe listOf("scatter", "bar", "pie", "histogram", "box", "heatmap")
        RendererConfigValidators.PLOTLY_3D_TRACES shouldBe listOf("scatter3d", "surface", "mesh3d")
    }

    @Test
    fun `a chart config is no concern of this port - the whole document is judged by its v1 schema`() {
        // Even an empty object: the chart's schema step (ChartDocumentRules) refuses it, never this validator.
        problems(RendererKind.CHART, """{}""") shouldBe emptyList()
        problems(RendererKind.CHART, """{"data":[{"type":"sankey"}]}""") shouldBe emptyList()
    }

    @Test
    fun `table - labelled columns whose values a binding fills, an optional format, alignment and page size`() {
        problems(
            RendererKind.TABLE,
            """{"columns":[{"label":"Month","values":[],"format":"date","align":"left"},{"label":"Amount","values":[]}],"page_size":25}""",
        ) shouldBe emptyList()
        problems(RendererKind.TABLE, """{"columns":[]}""") shouldBe listOf("columns" to "columns_missing")
        problems(
            RendererKind.TABLE,
            """{"columns":[{"values":[],"format":"money","width":3}],"page_size":0}""",
        ) shouldContainExactlyInAnyOrder
            listOf(
                "columns[0].width" to "unknown_key",
                "columns[0].label" to "missing",
                "columns[0].format" to "not_allowed",
                "page_size" to "out_of_range",
            )
        problems(RendererKind.TABLE, """{"columns":[{"label":"Month"}]}""") shouldBe listOf("columns[0].values" to "missing")
    }

    @Test
    fun `kpi - a value a binding fills, an optional format, unit and comparison`() {
        problems(
            RendererKind.KPI,
            """{"value":null,"format":"currency","unit":"USD","comparison":{"label":"vs last month","value":null}}""",
        ) shouldBe emptyList()
        problems(RendererKind.KPI, """{"format":"ratio","unit":"x","comparison":[]}""") shouldContainExactlyInAnyOrder
            listOf("value" to "missing", "format" to "not_allowed", "comparison" to "wrong_type")
    }

    @Test
    fun `kpi - a label is refused as an unknown key`() {
        problems(RendererKind.KPI, """{"label":"Total","value":null}""") shouldBe listOf("label" to "unknown_key")
    }

    @Test
    fun `kpi - a label-free config is valid`() {
        problems(RendererKind.KPI, """{"value":null}""") shouldBe emptyList()
    }

    @Test
    fun `a reserved kind has no schema - the validator refuses it before this is asked`() {
        problems(RendererKind.SVG, """{"anything":1}""") shouldBe emptyList()
    }

    private fun problems(
        kind: RendererKind,
        json: String,
    ): List<Pair<String, String>> = validator.validate(kind, ArtifactJson.mapper.readTree(json) as ObjectNode).map { it.path to it.reason }
}
