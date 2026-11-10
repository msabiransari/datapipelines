package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The ONE `columns` resolver (#860 A.4, design record 2026-11-06 §4.1 "Types at render"): a chart's encoded fields and
 * their types, from the contract the save rules read — the pinned transform's output table, else the one input's
 * columns. Every surface that hands a chart to the runtime (the board's configuration, the capability preview, the
 * Preview tab) reads it through [VisualizationService.chartColumns], so these cases are the whole of its behaviour.
 */
class ChartColumnsTest {
    private val fakes = ValidatorFakes.Fakes()
    private val columns = ChartColumns(fakes.templateFacts)

    private fun body(tree: ObjectNode): VisualizationBody = ValidatorFakes.visualizationDocument(tree).body

    private fun example(name: String): ObjectNode {
        val tree =
            checkNotNull(ChartColumnsTest::class.java.getResourceAsStream("/chart-examples/valid/$name.json")) { "no example $name" }
                .use(ArtifactJson.mapper::readTree) as ObjectNode
        return tree.put("name", "examples/visualizations/$name")
    }

    @Test
    fun `a transform's chart reads the pinned output table - only the encoded fields, by name`() {
        columns.of(ValidatorFakes.WORKSPACE, body(DocumentFixtures.visualization())) shouldBe
            mapOf("amounts" to LogicalType.DECIMAL, "month_labels" to LogicalType.STRING)
        columns.of(ValidatorFakes.WORKSPACE, body(DocumentFixtures.visualization())).keys.toList() shouldBe
            listOf("amounts", "month_labels")
    }

    @Test
    fun `a chart without a transform reads its one input - the color field included, unencoded columns left out`() {
        // bar-grouped's input also declares month, units, payload and segment; none is encoded.
        columns.of(ValidatorFakes.WORKSPACE, body(example("bar-grouped"))) shouldBe
            mapOf("amount" to LogicalType.DECIMAL, "channel" to LogicalType.STRING, "region" to LogicalType.STRING)
    }

    @Test
    fun `the save rules' own contract read agrees - the transform output and the single input are the same lists`() {
        val pin = fakes.templates.getValue(ValidatorFakes.TRANSFORM_REF) as TemplatePin.Transform
        ChartColumns.output(pin).map { it.name to it.type } shouldBe
            listOf("month_labels" to LogicalType.STRING, "amounts" to LogicalType.DECIMAL)
        ChartColumns.inputs(body(example("bar-grouped")))?.map { it.name } shouldBe
            listOf("region", "month", "channel", "amount", "units", "payload", "segment")
    }

    @Test
    fun `a pin that no longer resolves, or resolves to no transform, is the runtime's no-contract`() {
        val chart = body(DocumentFixtures.visualization())
        fakes.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.NotFound
        columns.of(ValidatorFakes.WORKSPACE, chart).shouldBeEmpty()
        fakes.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.VersionNotFound
        columns.of(ValidatorFakes.WORKSPACE, chart).shouldBeEmpty()
        fakes.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.NotScript(TemplateType.SQL)
        columns.of(ValidatorFakes.WORKSPACE, chart).shouldBeEmpty()
        // A pin whose output is one value, not a table, carries no columns either.
        fakes.templates[ValidatorFakes.TRANSFORM_REF] =
            TemplatePin.Transform(
                PipelineVersionStatus.RELEASED,
                ValidatorFakes.CONTRACT.copy(output = ScriptContractView.Output.Value(LogicalType.DECIMAL)),
            )
        columns.of(ValidatorFakes.WORKSPACE, chart).shouldBeEmpty()
    }

    @Test
    fun `several inputs without a transform leave the contract unknowable`() {
        val tree = example("bar-grouped")
        (tree.get("inputs") as ObjectNode).set<ObjectNode>("other", tree.get("inputs").get("sales").deepCopy())
        columns.of(ValidatorFakes.WORKSPACE, body(tree)).shouldBeEmpty()
    }

    @Test
    fun `a table or kpi carries no columns`() {
        columns.of(ValidatorFakes.WORKSPACE, body(DocumentFixtures.table())).shouldBeEmpty()
    }
}
