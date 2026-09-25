package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.pipeline.TransformRulesFixtures.OBJECT_CONTRACT
import co.datapipelines.pipeline.TransformRulesFixtures.READER_CONTRACT
import co.datapipelines.pipeline.TransformRulesFixtures.READER_TEMPLATE
import co.datapipelines.pipeline.TransformRulesFixtures.VALUE_CONTRACT
import co.datapipelines.pipeline.TransformRulesFixtures.parameters
import co.datapipelines.pipeline.TransformRulesFixtures.stageNode
import co.datapipelines.pipeline.TransformRulesFixtures.stub
import co.datapipelines.pipeline.TransformRulesFixtures.transformNode
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The TRANSFORM validator's refusal arms the record's example never reaches (pipeline-contract
 * §12.13, the transform-nodes record R3–R5): a non-string input, a table bound to a value input,
 * a value input typed by an upstream writer (a TRANSFORM's contract output, a calculator's kind
 * output), an object key read out of order, the output shapes a TRANSFORM refuses (none, a
 * datasource, a blank tempdb table), two transforms writing one Context key, and a composition
 * parameter binding an object key (R4). Split from [TransformRulesTest] by size; the fixtures are
 * [TransformRulesFixtures]'.
 */
class TransformRulesArmsTest {
    private val workspaceId = UUID.randomUUID()

    private fun validate(
        pipeline: Pipeline,
        templates: TemplateDryRenderer = stub(),
    ): ValidationResult = Fixtures.validator(templates = templates).validate(pipeline, workspaceId)

    @Test
    fun `a non-string input value is transform_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("42"),
                                        "tz" to Fixtures.json("\"\$org_timezone\""),
                                        "min_total" to Fixtures.json("\"\$min_total\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
        result.withCode(Validation.TRANSFORM_INPUT_CONTRACT).single().details["input"] shouldBe "orders"
    }

    @Test
    fun `a table name bound to a value input is transform_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("\"stg_orders\""),
                                        "tz" to Fixtures.json("\"stg_orders\""),
                                        "min_total" to Fixtures.json("\"\$min_total\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
        result.withCode(Validation.TRANSFORM_INPUT_CONTRACT).single().details["input"] shouldBe "tz"
    }

    @Test
    fun `a value input typed by an upstream transform's contract output is checked against it`() {
        // `wrap` writes `threshold` as a DECIMAL (VALUE_CONTRACT); the reader declares `v` a STRING.
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "threshold",
            )
        val reader =
            transformNode(
                id = "read_back",
                template = READER_TEMPLATE,
                inputs = mapOf("v" to Fixtures.json("\"\$threshold\"")),
                output = null,
                contextKey = "other",
                dependsOn = listOf("wrap"),
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer, reader)),
                templates = stub(contract = VALUE_CONTRACT, readerContract = READER_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
        val failure = result.withCode(Validation.TRANSFORM_INPUT_CONTRACT).single()
        failure.details["reference"] shouldBe "threshold"
        failure.details["declared_type"] shouldBe LogicalType.STRING.wire
    }

    @Test
    fun `a value input typed by an upstream calculator's output is checked against it`() {
        // `fiscal_q` writes `run_fiscal_quarter` as an INTEGER (the kind's output); the reader declares `v` a STRING.
        val reader =
            transformNode(
                id = "read_back",
                template = READER_TEMPLATE,
                inputs = mapOf("v" to Fixtures.json("\"\$run_fiscal_quarter\"")),
                output = null,
                contextKey = "other",
                dependsOn = listOf("fiscal_q"),
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), Fixtures.calculatorNode(), reader)),
                templates = stub(contract = VALUE_CONTRACT, readerContract = READER_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
        result.withCode(Validation.TRANSFORM_INPUT_CONTRACT).single().details["reference"] shouldBe "run_fiscal_quarter"
    }

    @Test
    fun `an object key read without a depends_on path is calculator_input_unordered`() {
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "payload",
            )
        val reader =
            transformNode(
                id = "read_back",
                template = READER_TEMPLATE,
                inputs = mapOf("v" to Fixtures.json("\"\$payload\"")),
                output = null,
                contextKey = "summary",
                dependsOn = emptyList(),
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer, reader)),
                templates = stub(contract = OBJECT_CONTRACT, readerContract = READER_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.CALCULATOR_INPUT_UNORDERED)
    }

    @Test
    fun `a row-mode contract with no output is transform_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode(output = null)), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_OUTPUT_SHAPE)
        result.withCode(Validation.TRANSFORM_OUTPUT_SHAPE).single().path shouldBe "nodes[1].output"
    }

    @Test
    fun `a datasource output on a TRANSFORM is transform_output_shape - a transform never writes a datasource`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(output = NodeOutput.Datasource("pg-prod", "order_lines", WriteMode.APPEND)),
                        ),
                    parameters = parameters,
                ),
            )

        result.withCode(Validation.TRANSFORM_OUTPUT_SHAPE).single().path shouldBe "nodes[1].output"
    }

    @Test
    fun `a tempdb output with a blank table is output_table_missing`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(stageNode(), transformNode(output = NodeOutput.Tempdb("", rejects = "order_lines_rejected"))),
                    parameters = parameters,
                ),
            )

        result.withCode(Validation.OUTPUT_TABLE_MISSING).map { it.path } shouldContainExactlyInAnyOrder listOf("nodes[1].output.table")
    }

    @Test
    fun `two TRANSFORM nodes writing one context_key collide on the second writer`() {
        val first =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "threshold",
            )
        val second =
            transformNode(
                id = "wrap_again",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "threshold",
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), first, second)),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.CALCULATOR_OUTPUT_COLLISION)
        val failure = result.withCode(Validation.CALCULATOR_OUTPUT_COLLISION).single()
        failure.path shouldBe "nodes[2].context_key"
        failure.details["collides_with"] shouldBe "node"
    }

    @Test
    fun `a composition parameter referencing an object key is transform_object_key_bound - R4`() {
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "payload",
            )
        val composition =
            Node(
                id = "run_child",
                description = "Runs the child pipeline.",
                type = NodeType.PIPELINE,
                source = "",
                template = TemplateRef(),
                output = null,
                dependsOn = listOf("wrap"),
                pipeline = PipelineNodeRef("test/monthly_revenue_component", 4),
                parameters = mapOf("window" to Fixtures.json("\"\${payload}\"")),
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer, composition)),
                templates = stub(contract = OBJECT_CONTRACT),
            )

        val failure = result.withCode(Validation.TRANSFORM_OBJECT_KEY_BOUND).single()
        failure.path shouldBe "nodes[2].parameters.window"
        failure.details["written_by"] shouldBe "wrap"
    }
}
