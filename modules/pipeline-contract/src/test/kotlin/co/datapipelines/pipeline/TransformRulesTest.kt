package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * §12.13 — the `TRANSFORM`-node validation rules (§4.12, transform-nodes design §3.1, R3–R5).
 *
 * One case per code, plus the positive: the design record's own example pipeline saves clean.
 * The stub contract below mirrors the record's §2.2 example (`acme/shape/order_lines.jsonata`
 * v3 — `row` mode, one table input plus two value inputs, a table output, rejects declared),
 * so a test reading green proves the rule against the record's own shape, not a fixture
 * invented to pass.
 */
class TransformRulesTest {
    private val workspaceId = UUID.randomUUID()

    private fun validate(
        pipeline: Pipeline,
        templates: TemplateDryRenderer = stub(),
    ): ValidationResult = Fixtures.validator(templates = templates).validate(pipeline, workspaceId)

    // ---------------------------------------------------------------- fixtures

    private companion object {
        const val TRANSFORM_TEMPLATE = "acme/shape/order_lines.jsonata"
        const val READER_TEMPLATE = "acme/shape/read_back.jsonata"
        const val SQL_TEMPLATE = "test/stage_orders.sql"
        const val REPORT_TEMPLATE = "test/report.sql"

        /** The record §2.2 example's contract, as the validator's view. */
        val CONTRACT =
            TransformContractView(
                mode = TransformContractView.Mode.ROW,
                inputs =
                    mapOf(
                        "orders" to
                            TransformContractView.Input.Table(
                                listOf(
                                    TransformContractView.Column("order_id", LogicalType.INTEGER, nullable = false),
                                    TransformContractView.Column("amount_cents", LogicalType.INTEGER, nullable = false),
                                    TransformContractView.Column("customer_id", LogicalType.STRING, nullable = true),
                                ),
                            ),
                        "tz" to TransformContractView.Input.Value(LogicalType.STRING),
                        "min_total" to TransformContractView.Input.Value(LogicalType.DECIMAL),
                    ),
                output =
                    TransformContractView.Output.Table(
                        listOf(
                            TransformContractView.Column("order_id", LogicalType.INTEGER, nullable = false),
                            TransformContractView.Column("amount", LogicalType.DECIMAL, nullable = false),
                            TransformContractView.Column("customer_id", LogicalType.STRING, nullable = false),
                        ),
                    ),
                rejects = true,
            )

        val VALUE_CONTRACT =
            TransformContractView(
                mode = TransformContractView.Mode.VALUE,
                inputs = mapOf("orders" to TransformContractView.Input.Table(emptyList())),
                output = TransformContractView.Output.Value(LogicalType.DECIMAL),
                rejects = false,
            )

        val OBJECT_CONTRACT =
            TransformContractView(
                mode = TransformContractView.Mode.VALUE,
                inputs = mapOf("orders" to TransformContractView.Input.Table(emptyList())),
                output = TransformContractView.Output.Obj,
                rejects = false,
            )

        /** A downstream reader's contract — one value input, one value output. */
        val READER_CONTRACT =
            TransformContractView(
                mode = TransformContractView.Mode.VALUE,
                inputs = mapOf("v" to TransformContractView.Input.Value(LogicalType.STRING)),
                output = TransformContractView.Output.Value(LogicalType.STRING),
                rejects = false,
            )
    }

    private fun stub(
        contract: TransformContractView = CONTRACT,
        templateType: TemplateType = TemplateType.JSONATA,
        bound: Map<String, List<String>> = emptyMap(),
        readerContract: TransformContractView? = null,
    ): StubTemplates =
        StubTemplates(
            lookups =
                mapOf(
                    TRANSFORM_TEMPLATE to TemplateLookup.Found(dialect = null, type = templateType),
                    READER_TEMPLATE to TemplateLookup.Found(dialect = null, type = TemplateType.JSONATA),
                    SQL_TEMPLATE to TemplateLookup.Found(Dialect.POSTGRES, TemplateType.SQL),
                    REPORT_TEMPLATE to TemplateLookup.Found(Dialect.POSTGRES, TemplateType.SQL),
                ),
            contracts =
                mapOf(TRANSFORM_TEMPLATE to contract) +
                    listOfNotNull(readerContract?.let { READER_TEMPLATE to it }),
            bound = bound,
        )

    /** The record's §3.1 example node. */
    private fun transformNode(
        id: String = "shape_orders",
        template: String = TRANSFORM_TEMPLATE,
        inputs: Map<String, com.fasterxml.jackson.databind.JsonNode> =
            mapOf(
                "orders" to Fixtures.json("\"stg_orders\""),
                "tz" to Fixtures.json("\"\$org_timezone\""),
                "min_total" to Fixtures.json("\"\$min_total\""),
            ),
        output: NodeOutput? = NodeOutput.Tempdb("order_lines", rejects = "order_lines_rejected"),
        strict: Boolean? = null,
        dependsOn: List<String> = listOf("stage_orders"),
        kind: String? = null,
        contextKey: String? = null,
        contextKeys: Map<String, String>? = null,
        source: String = "",
    ): Node =
        Node(
            id = id,
            description = "Order lines at reporting grain.",
            type = NodeType.TRANSFORM,
            source = source,
            template = TemplateRef(template, 3),
            output = output,
            dependsOn = dependsOn,
            inputs = inputs,
            contextKey = contextKey,
            contextKeys = contextKeys,
            kind = kind,
            strict = strict,
        )

    private fun stageNode(
        table: String = "stg_orders",
        id: String = "stage_orders",
    ): Node =
        Fixtures.node(
            id = id,
            source = "pg-prod",
            template = TemplateRef(SQL_TEMPLATE, 1),
            output = NodeOutput.Tempdb(table),
        )

    private val parameters =
        mapOf(
            "min_total" to
                Parameter(
                    type = LogicalType.DECIMAL,
                    required = true,
                    precision = 12,
                    scale = 2,
                    description = "Minimum total.",
                ),
        )

    // ---------------------------------------------------------------- the positive

    @Test
    fun `the design record's example pipeline saves clean`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(stageNode(), transformNode()),
                    parameters = parameters,
                ),
            )

        result.failures.map { "${it.code} ${it.path} ${it.message}" }.shouldBeEmpty()
    }

    // ---------------------------------------------------------------- one case per code

    @Test
    fun `a non-transform pin is transform_template_type`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode()), parameters = parameters),
                templates = stub(templateType = TemplateType.SQL),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_TEMPLATE_TYPE)
    }

    @Test
    fun `a source on a TRANSFORM node is transform_source_forbidden`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode(source = "pg-prod")), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_SOURCE_FORBIDDEN)
    }

    @Test
    fun `a table no ancestor stages is transform_input_unknown`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(inputs = mapOf("orders" to Fixtures.json("\"elsewhere\""), "tz" to Fixtures.json("\"\$org_timezone\""), "min_total" to Fixtures.json("\"\$min_total\""))),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_UNKNOWN)
    }

    @Test
    fun `a table staged only AFTER the node is not visible to it`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            transformNode(dependsOn = emptyList()),
                            stageNode(),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_UNKNOWN)
    }

    @Test
    fun `a key nobody writes is transform_input_unknown`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(inputs = mapOf("orders" to Fixtures.json("\"stg_orders\""), "tz" to Fixtures.json("\"\$org_nowhere\""), "min_total" to Fixtures.json("\"\$min_total\""))),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_UNKNOWN)
    }

    @Test
    fun `an inputs key set that differs from the contract is transform_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(inputs = mapOf("orders" to Fixtures.json("\"stg_orders\""), "tz" to Fixtures.json("\"\$org_timezone\""))),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
        result.withCode(Validation.TRANSFORM_INPUT_CONTRACT).single().details["missing"] shouldBe listOf("min_total")
    }

    @Test
    fun `a value input whose Context type does not fit is transform_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(inputs = mapOf("orders" to Fixtures.json("\"stg_orders\""), "tz" to Fixtures.json("\"\$min_total\""), "min_total" to Fixtures.json("\"\$org_timezone\""))),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
        result.withCode(Validation.TRANSFORM_INPUT_CONTRACT).size shouldBe 2
    }

    @Test
    fun `a table input bound to a Context key is transform_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(inputs = mapOf("orders" to Fixtures.json("\"\$org_timezone\""), "tz" to Fixtures.json("\"\$org_timezone\""), "min_total" to Fixtures.json("\"\$min_total\""))),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_INPUT_CONTRACT)
    }

    @Test
    fun `a target on value mode is transform_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(
                                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                                output = NodeOutput.Tempdb("x"),
                                contextKey = "threshold",
                            ),
                        ),
                ),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_OUTPUT_SHAPE)
    }

    @Test
    fun `a context_key on table mode is transform_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode(contextKey = "threshold")), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_OUTPUT_SHAPE)
    }

    @Test
    fun `value mode without a context_key is transform_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            transformNode(inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")), output = null),
                        ),
                ),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_OUTPUT_SHAPE)
    }

    @Test
    fun `a rejects table the contract does not declare is transform_rejects_undeclared`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode()), parameters = parameters),
                templates = stub(contract = CONTRACT.copy(rejects = false)),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_REJECTS_UNDECLARED)
    }

    @Test
    fun `a declared rejects contract with no rejects table is transform_rejects_missing`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(stageNode(), transformNode(output = NodeOutput.Tempdb("order_lines"))),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_REJECTS_MISSING)
    }

    @Test
    fun `a rejects-declaring contract on a caller node is transform_rejects_on_caller`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode(output = NodeOutput.Caller)), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_REJECTS_ON_CALLER)
    }

    @Test
    fun `rejects on a caller output are refused on the wire, before binding`() {
        val outcome =
            PipelineDeserializer().read(
                """
                {
                  "schema_version": 1,
                  "name": "test/rejects_on_caller",
                  "display_name": "x",
                  "description": "x",
                  "settings": {},
                  "parameters": {},
                  "nodes": [
                    { "id": "shape", "description": "x", "type": "TRANSFORM",
                      "template": {"id": "$TRANSFORM_TEMPLATE", "version": 3},
                      "inputs": {"orders": "stg_orders"},
                      "output": {"target": "caller", "rejects": "nope"},
                      "depends_on": [] }
                  ]
                }
                """.trimIndent(),
            )

        when (outcome) {
            is DeserializationOutcome.Parsed -> error("expected the wire scan to refuse rejects on a caller output")
            is DeserializationOutcome.Rejected -> {
                outcome.result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_REJECTS_ON_CALLER)
            }
        }
    }

    @Test
    fun `strict without a rejects contract is transform_strict_without_rejects`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), transformNode(strict = true)), parameters = parameters),
                templates = stub(contract = CONTRACT.copy(rejects = false)),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_STRICT_WITHOUT_REJECTS, Validation.TRANSFORM_REJECTS_UNDECLARED)
    }

    @Test
    fun `strict on a non-transform node is refused`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(Fixtures.node("q").copy(strict = true))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_STRICT_WITHOUT_REJECTS)
    }

    @Test
    fun `rejects on a non-transform node's tempdb output is refused`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(Fixtures.node("q", output = NodeOutput.Tempdb("t", rejects = "r")))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_REJECTS_UNDECLARED)
    }

    @Test
    fun `a SQL node binding an object key is transform_object_key_bound - R4`() {
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "payload",
            )
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            writer,
                            Fixtures.node("report", template = TemplateRef(REPORT_TEMPLATE, 1), dependsOn = listOf("wrap")),
                        ),
                ),
                templates = stub(contract = OBJECT_CONTRACT, bound = mapOf(REPORT_TEMPLATE to listOf("payload"))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_OBJECT_KEY_BOUND)
        result.withCode(Validation.TRANSFORM_OBJECT_KEY_BOUND).single().details["context_key"] shouldBe "payload"
    }

    @Test
    fun `a calculator input referencing an object key is transform_object_key_bound - R4`() {
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "payload",
            )
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            writer,
                            Fixtures.calculatorNode(
                                inputs = mapOf("date" to Fixtures.json("\"\$payload\""), "fiscal_start" to Fixtures.json("\"\$org_fiscal_start_date\"")),
                                dependsOn = listOf("wrap"),
                            ),
                        ),
                ),
                templates = stub(contract = OBJECT_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.TRANSFORM_OBJECT_KEY_BOUND)
    }

    @Test
    fun `a downstream TRANSFORM reading an object key is exactly what R4 allows`() {
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
                dependsOn = listOf("wrap"),
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer, reader)),
                templates = stub(contract = OBJECT_CONTRACT, readerContract = READER_CONTRACT),
            )

        result.failures.map { "${it.code} ${it.path}" }.shouldBeEmpty()
    }

    @Test
    fun `a transform key bound without a depends_on path is calculator_input_unordered`() {
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
                dependsOn = emptyList(),
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer, reader)),
                templates = stub(contract = VALUE_CONTRACT, readerContract = READER_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.CALCULATOR_INPUT_UNORDERED)
    }

    @Test
    fun `a transform context_key colliding with a parameter or another writer is refused`() {
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "min_total",
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer), parameters = parameters),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.CALCULATOR_OUTPUT_COLLISION)
    }

    @Test
    fun `a transform context_key with a bad name is calculator_output_name_invalid`() {
        val writer =
            transformNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "1bad",
            )
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), writer)),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.CALCULATOR_OUTPUT_NAME_INVALID)
    }

    @Test
    fun `the calculator-fields exemption refuses kind and context_keys but not inputs and context_key`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(stageNode(), transformNode(kind = "fiscal_quarter", contextKeys = mapOf("a" to "b"))),
                    parameters = parameters,
                ),
            )

        // kind/context_keys refused here; the output-shape verdicts the fields provoke are §12.13's own.
        result.withCode(Validation.CALCULATOR_FIELDS_ON_NON_CALCULATOR).single().details["fields"] shouldBe listOf("kind", "context_keys")
    }
}
