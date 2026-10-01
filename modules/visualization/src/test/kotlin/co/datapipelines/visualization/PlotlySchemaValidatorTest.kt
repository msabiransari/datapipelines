package co.datapipelines.visualization

import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The DEEP Plotly config validation (the spec's §11.3 step 1) over the committed reduced 4.1.1 schema:
 * one passing and one refused specimen per enforcement line — unknown attributes, wrong types,
 * unsupported traces, the shape checks, the subplot normalization, the placeholder grammar — every
 * refusal carrying the path inside `config`. Each refused specimen is FALSIFIED by flipping its one
 * offending fact back to valid: the refusal follows the fact, not the fixture.
 */
class PlotlySchemaValidatorTest {
    private val validator = PlotlySchemaValidator()

    private fun problems(config: ObjectNode): List<ConfigProblem> = validator.validate(RendererKind.PLOTLY, config)

    private fun node(json: String): ObjectNode = ArtifactJson.mapper.readTree(json) as ObjectNode

    @Test
    fun `the spec's own configuration passes - placeholders included, layout, and a plotly config object`() {
        problems(
            node(
                """{"data":[{"type":"bar","x":"$.x","y":"$.y","name":"Revenue"}],
                    "layout":{"title":{"text":"Revenue"},"xaxis2":{"title":{"text":"second"}}},
                    "config":{"displaylogo":false,"displayModeBar":"hover"}}""",
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `an unknown trace attribute is refused naming its path - and the refusal follows the fact`() {
        val json = """{"data":[{"type":"bar","x":"$.x","y":"$.y","bogus_attr":1}]}"""
        problems(node(json)).map { it.path } shouldContainExactlyInAnyOrder listOf("data[0].bogus_attr")
        // Falsification: without the unknown key there is no refusal.
        problems(node("""{"data":[{"type":"bar","x":"$.x","y":"$.y"}]}""")).shouldBeEmpty()
    }

    @Test
    fun `a wrong-typed attribute is refused - numeric, boolean, enumerated, flaglist`() {
        problems(node("""{"data":[{"type":"bar","x":"$.x","y":"$.y"}],"layout":{"width":"wide"}}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("layout.width")
        problems(node("""{"data":[{"type":"bar","x":"$.x","y":"$.y"}],"layout":{"autosize":"yes"}}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("layout.autosize")
        problems(node("""{"data":[{"type":"bar","x":"$.x","y":"$.y"}],"layout":{"xaxis":{"type":"bananas"}}}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("layout.xaxis.type")
        problems(node("""{"data":[{"type":"scatter","x":"$.x","y":"$.y","mode":[1,2]}]}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("data[0].mode")
        // Falsifications: the corrected values pass.
        val corrected = """{"data":[{"type":"scatter","x":"$.x","y":"$.y","mode":"lines+markers"}],"layout":{}}"""
        problems(node(corrected)).shouldBeEmpty()
    }

    @Test
    fun `a non-array data_array leaf is refused - but the binding placeholder is the grammar, not a value`() {
        problems(node("""{"data":[{"type":"bar","x":"$.x","y":"$.y"}]}""")).shouldBeEmpty()
        problems(node("""{"data":[{"type":"bar","x":5,"y":"$.y"}]}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("data[0].x")
    }

    @Test
    fun `an unsupported trace type is refused naming data index - supported nine are not`() {
        problems(node("""{"data":[{"type":"treemap","labels":["a"]}]}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("data[0].type")
        RendererConfigValidators.PLOTLY_TRACES.forEach { type ->
            withClue(type) {
                problems(node("""{"data":[{"type":"$type"}]}""")).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a missing or non-textual trace type is refused`() {
        problems(node("""{"data":[{"x":[1]}]}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("data[0].type")
        problems(node("""{"data":[{"type":4}]}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("data[0].type")
    }

    @Test
    fun `the house shape checks stay in force - data array, trace count, layout and config objects`() {
        problems(node("""{"layout":{}}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("data")
        problems(node("""{"data":[]}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("data")
        val sixtyFive = (0 until 65).joinToString(",") { """{"type":"bar"}""" }
        problems(node("""{"data":[$sixtyFive]}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("data")
        problems(node("""{"data":[{"type":"bar"}],"layout":[1]}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("layout")
        problems(node("""{"data":[{"type":"bar"}],"config":"auto"}""")).map { it.path } shouldContainExactlyInAnyOrder listOf("config")
    }

    @Test
    fun `an unknown layout or plotly-config key is refused - numbered subplot instances normalize onto their family`() {
        problems(node("""{"data":[{"type":"bar"}],"layout":{"bogus":1}}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("layout.bogus")
        problems(node("""{"data":[{"type":"bar"}],"config":{"bogusFlag":true}}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("config.bogusFlag")
        problems(node("""{"data":[{"type":"bar"}],"layout":{"xaxis3":{"title":{"text":"3rd"}}}}""")).shouldBeEmpty()
        problems(node("""{"data":[{"type":"bar"}],"layout":{"scene2":{"xaxis":{"title":{"text":"s2"}}}}}""")).shouldBeEmpty()
    }

    @Test
    fun `a compound array attribute validates element-wise - and an object attribute refuses a scalar`() {
        // layout.annotations is a compound array: each element judged against the annotation tree.
        problems(
            node("""{"data":[{"type":"bar"}],"layout":{"annotations":[{"text":"note","xref":"paper"},{"bogus":1}]}}"""),
        ).map { it.path } shouldContainExactlyInAnyOrder listOf("layout.annotations[1].bogus")
        // A scalar where the schema wants an object.
        problems(node("""{"data":[{"type":"bar"}],"layout":{"title":"Revenue"}}"""))
            .map { it.reason } shouldContainExactlyInAnyOrder listOf("wrong_type")
    }

    @Test
    fun `an info_array attribute judges its elements`() {
        // xaxis.domain's items are two numbers (the reduced schema's fixed-length form).
        problems(node("""{"data":[{"type":"bar"}],"layout":{"xaxis":{"domain":["a","b"]}}}"""))
            .map { it.path } shouldContainExactlyInAnyOrder listOf("layout.xaxis.domain[0]", "layout.xaxis.domain[1]")
        problems(node("""{"data":[{"type":"bar"}],"layout":{"xaxis":{"domain":[0,1]}}}""")).shouldBeEmpty()
        // xaxis.range's items are `any` in this schema — strings pass there.
        problems(node("""{"data":[{"type":"bar"}],"layout":{"xaxis":{"range":["a","b"]}}}""")).shouldBeEmpty()
    }

    @Test
    fun `a pathological configuration is refused too_complex once, never spun on`() {
        val many = (0 until 25_000).joinToString(",") { """"k$it":1""" }
        val found = problems(node("""{"data":[{"type":"bar"}],"layout":{"xaxis":{$many}}}"""))
        found.filter { it.reason == "too_complex" }.size shouldBe 1
    }

    @Test
    fun `table and kpi configs are not this validator's business`() {
        validator.validate(RendererKind.TABLE, node("""{"anything":true}""")).shouldBeEmpty()
        validator.validate(RendererKind.KPI, node("""{"anything":true}""")).shouldBeEmpty()
    }
}
