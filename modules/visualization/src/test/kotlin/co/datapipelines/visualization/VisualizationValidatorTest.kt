package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [VisualizationValidator]: the spec's worked document passes unchanged; each validation code the validator
 * owns is reached by exactly one case (the non-vacuity test holds the case list to the catalog); every
 * `details.reason` of each rule is pinned.
 */
class VisualizationValidatorTest {
    private val fakes = ValidatorFakes.Fakes()

    @Test
    fun `the spec's worked document is valid unchanged - and a DRAFT transform pin is accepted at save`() {
        valid(DocumentFixtures.visualization())
        fakes.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.Transform(PipelineVersionStatus.DRAFT, ValidatorFakes.CONTRACT)
        valid(DocumentFixtures.visualization())
    }

    @Test
    fun `every validation code the validator owns is reached by exactly one case, and each case raises only its code`() {
        CASES.forEach { case ->
            withClue(case.code) {
                val tree = DocumentFixtures.visualization().also(case.mutate)
                val failures = failures(tree)
                failures.map { it.code }.toSet() shouldBe setOf(case.code)
                failures.map { it.path to it.details["reason"] } shouldBe listOf(case.path to case.reason)
            }
        }
    }

    @Test
    fun `non-vacuity - the cases cover exactly the validation codes this validator raises`() {
        CASES.map { it.code }.toSet() shouldBe VALIDATOR_CODES
        CASES.size shouldBe VALIDATOR_CODES.size
    }

    @Test
    fun `the transform pin's reasons - missing, not a transform, and each way the contract fails to bind`() {
        reasons({ it.templates.clear() }) shouldBe listOf("transform.template" to "template_not_found")
        reasons({ it.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.VersionNotFound }) shouldBe
            listOf("transform.template" to "template_version_not_found")
        reasons({ it.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.NotTransform(TemplateType.SQL) }) shouldBe
            listOf("transform.template" to "not_a_transform")
        reasons(mutate = { it.obj("transform").putObject("inputs").put("table", "revenue") }) shouldBe
            listOf("transform.inputs" to "inputs_mismatch")
        reasons(mutate = { it.obj("transform.inputs").put("rows", "sales") }) shouldContainExactlyInAnyOrder
            listOf("transform.inputs.rows" to "input_unknown", "inputs.revenue" to "input_unused")
        val valueInput = ValidatorFakes.CONTRACT.copy(inputs = mapOf("rows" to TransformContractView.Input.Value(LogicalType.DATE)))
        reasons({ it.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.Transform(PipelineVersionStatus.RELEASED, valueInput) }) shouldBe
            listOf("transform.inputs.rows" to "value_input")
        reasons(mutate = { it.obj("inputs").set<ObjectNode>("target", it.obj("inputs.revenue").deepCopy()) }) shouldContainExactlyInAnyOrder
            listOf("inputs.target" to "input_unused", "tests.cases[0].fixtures.target" to "fixture_missing")
        reasons(mutate = {
            it.remove("transform")
            it.obj("inputs").set<ObjectNode>("target", it.obj("inputs.revenue").deepCopy())
            it.obj("tests.cases[0].fixtures").set<ArrayNode>("target", it.obj("tests.cases[0].fixtures").get("revenue"))
        }) shouldBe listOf("transform" to "transform_required")
    }

    @Test
    fun `without a transform the renderer binds to the one input's columns`() {
        val tree = DocumentFixtures.visualization()
        tree.remove("transform")
        tree.obj("bindings").put("data[0].x", "month").put("data[0].y", "amount")
        valid(tree)
        tree.obj("bindings").put("data[0].y", "amounts")
        reasons(tree) shouldBe listOf("bindings.data[0].y" to "column_unknown")
    }

    @Test
    fun `a binding path must be the grammar and resolve inside config - a value output binds nothing`() {
        reasons(mutate = { it.obj("bindings").put("data[0]..x", "amounts") }) shouldBe listOf("bindings.data[0]..x" to "path_invalid")
        reasons(mutate = { it.obj("bindings").put("data[3].x", "amounts") }) shouldBe listOf("bindings.data[3].x" to "path_unresolved")
        val valueOutput = ValidatorFakes.CONTRACT.copy(output = TransformContractView.Output.Value(LogicalType.DECIMAL))
        reasons(
            { it.templates[ValidatorFakes.TRANSFORM_REF] = TemplatePin.Transform(PipelineVersionStatus.RELEASED, valueOutput) },
        ) shouldBe
            listOf("bindings.data[0].x" to "column_unknown", "bindings.data[0].y" to "column_unknown")
    }

    @Test
    fun `the renderer, the inputs and the display text refuse each malformed part with its reason`() {
        reasons(mutate = { it.obj("renderer").put("version", "4.1") }) shouldBe listOf("renderer.version" to "version_invalid")
        reasons(mutate = { it.obj("inputs.revenue").putArray("columns") }) shouldContainExactlyInAnyOrder
            listOf(
                "inputs.revenue.columns" to "no_columns",
                "transform.inputs.rows" to "column_mismatch",
                "transform.inputs.rows" to "column_mismatch",
                "tests.cases[0].fixtures.revenue[0].month" to "column_unknown",
                "tests.cases[0].fixtures.revenue[0].amount" to "column_unknown",
            )
        reasons(mutate = { it.obj("inputs.revenue.columns[0]").put("name", "a\u0007b") }).first() shouldBe
            ("inputs.revenue.columns[0].name" to "column_name_invalid")
        reasons(mutate = { it.put("description", "x".repeat(2_001)) }) shouldBe listOf("description" to "too_long")
        reasons(mutate = { it.obj("presentation").put("title", "x".repeat(121)) }) shouldBe listOf("presentation.title" to "too_long")
        reasons(mutate = { it.obj("presentation.tokens").put("Series", "categorical") }) shouldBe
            listOf("presentation.tokens.Series" to "grammar")
    }

    @Test
    fun `a test case refuses each unrunnable part with its reason`() {
        reasons(mutate = { (it.obj("tests").get("cases") as ArrayNode).add(it.obj("tests.cases[0]").deepCopy()) }) shouldBe
            listOf("tests.cases[1].name" to "duplicate_case")
        reasons(mutate = { it.obj("tests.cases[0].fixtures.revenue[0]").put("region", "EU") }) shouldBe
            listOf("tests.cases[0].fixtures.revenue[0].region" to "column_unknown")
        reasons(mutate = { it.obj("tests.cases[0].fixtures.revenue[0]").putNull("amount") }) shouldBe
            listOf("tests.cases[0].fixtures.revenue[0].amount" to "null_not_allowed")
        reasons(mutate = { it.obj("tests.cases[0].fixtures.revenue[0]").remove("month") }) shouldBe
            listOf("tests.cases[0].fixtures.revenue[0].month" to "null_not_allowed")
        reasons(mutate = { it.obj("tests.cases[0].fixtures").putArray("sales") }) shouldBe
            listOf("tests.cases[0].fixtures.sales" to "fixture_unknown")
        reasons(mutate = { it.obj("tests.cases[0]").putArray("assertions") }) shouldBe
            listOf("tests.cases[0].assertions" to "no_assertions")
        reasons(mutate = { it.obj("tests.cases[0].assertions[1]").remove("equals") }) shouldBe
            listOf("tests.cases[0].assertions[1].equals" to "argument_missing")
        reasons(mutate = { it.obj("tests.cases[0].assertions[0]").put("text", "Revenue") }) shouldBe
            listOf("tests.cases[0].assertions[0]" to "argument_not_allowed")
        reasons(mutate = { it.obj("tests.cases[0].assertions[0]").put("kind", "text_visible") }) shouldBe
            listOf("tests.cases[0].assertions[0].text" to "argument_missing")
        reasons(mutate = { it.obj("tests.cases[0]").put("name", " ") }) shouldBe listOf("tests.cases[0].name" to "name_missing")
    }

    private fun valid(tree: ObjectNode) {
        fakes
            .visualizationValidator()
            .validate(ValidatorFakes.WORKSPACE, ValidatorFakes.visualizationDocument(tree))
            .shouldBeInstanceOf<ArtifactValidation.Valid<VisualizationDocument>>()
    }

    private fun failures(
        tree: ObjectNode,
        with: ValidatorFakes.Fakes = fakes,
    ): List<ValidationFailure> =
        with
            .visualizationValidator()
            .validate(ValidatorFakes.WORKSPACE, ValidatorFakes.visualizationDocument(tree))
            .shouldBeInstanceOf<ArtifactValidation.Invalid>()
            .result.failures

    private fun reasons(
        tree: ObjectNode,
        with: ValidatorFakes.Fakes = fakes,
    ): List<Pair<String, Any?>> = failures(tree, with).map { it.path to it.details["reason"] }

    private fun reasons(
        setup: (ValidatorFakes.Fakes) -> Unit = {},
        mutate: (ObjectNode) -> Unit = {},
    ): List<Pair<String, Any?>> {
        // Fresh fakes per call: a setup never leaks into the next assertion of the same test.
        val fresh = ValidatorFakes.Fakes().also(setup)
        return reasons(DocumentFixtures.visualization().also(mutate), fresh)
    }

    private data class Case(
        val code: String,
        val path: String,
        val reason: String,
        val mutate: (ObjectNode) -> Unit,
    )

    private companion object {
        /** The validation codes the VALIDATOR raises — `new_root_requires_confirmation` is L1b's, `name_taken` the repository's. */
        val VALIDATOR_CODES: Set<String> =
            VisualizationErrorCodes.ALL.filter { it.startsWith("visualization.validation.") }.toSet() -
                setOf(VisualizationErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION, VisualizationErrorCodes.NAME_TAKEN)

        val CASES: List<Case> =
            listOf(
                Case(VisualizationErrorCodes.NAME_INVALID, "name", "folder_required") { it.put("name", "flat") },
                Case(VisualizationErrorCodes.BODY_INVALID, "display_name", "blank") { it.put("display_name", "  ") },
                Case(VisualizationErrorCodes.RENDERER_UNSUPPORTED, "renderer.kind", "reserved") { it.obj("renderer").put("kind", "svg") },
                Case(VisualizationErrorCodes.INPUT_CONTRACT_INVALID, "inputs.revenue.columns[2].name", "duplicate_column") {
                    (it.obj("inputs.revenue").get("columns") as ArrayNode).add(it.obj("inputs.revenue.columns[0]").deepCopy())
                },
                Case(VisualizationErrorCodes.TRANSFORM_BINDING_INVALID, "transform.inputs.rows", "column_mismatch") {
                    it.obj("inputs.revenue.columns[1]").put("type", "BIGDECIMAL")
                },
                Case(VisualizationErrorCodes.CONFIG_SCHEMA_INVALID, "config.data[0].type", "trace_type_unsupported") {
                    it.obj("config.data[0]").put("type", "sankey")
                },
                Case(
                    VisualizationErrorCodes.BINDING_UNBOUND,
                    "bindings.data[0].y",
                    "column_unknown",
                ) { it.obj("bindings").put("data[0].y", "profit") },
                Case(VisualizationErrorCodes.TEST_CASE_INVALID, "tests.cases[0].fixtures.revenue", "fixture_missing") {
                    it.obj("tests.cases[0].fixtures").remove("revenue")
                },
            )
    }
}
