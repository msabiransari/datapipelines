package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.pipeline.ScriptRulesFixtures.CONTRACT
import co.datapipelines.pipeline.ScriptRulesFixtures.OBJECT_CONTRACT
import co.datapipelines.pipeline.ScriptRulesFixtures.READER_CONTRACT
import co.datapipelines.pipeline.ScriptRulesFixtures.READER_TEMPLATE
import co.datapipelines.pipeline.ScriptRulesFixtures.REPORT_TEMPLATE
import co.datapipelines.pipeline.ScriptRulesFixtures.SCRIPT_TEMPLATE
import co.datapipelines.pipeline.ScriptRulesFixtures.VALUE_CONTRACT
import co.datapipelines.pipeline.ScriptRulesFixtures.parameters
import co.datapipelines.pipeline.ScriptRulesFixtures.scriptNode
import co.datapipelines.pipeline.ScriptRulesFixtures.stageNode
import co.datapipelines.pipeline.ScriptRulesFixtures.stub
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * §12.13 — the `SCRIPT`-node validation rules (§4.12, transform-nodes design §3.1, R3–R5).
 *
 * One case per code, plus the positive: the design record's own example pipeline saves clean.
 * The stub contract below mirrors the record's §2.2 example (`acme/shape/order_lines.jsonata`
 * v3 — `row` mode, one table input plus two value inputs, a table output, rejects declared),
 * so a test reading green proves the rule against the record's own shape, not a fixture
 * invented to pass.
 */
class ScriptRulesTest {
    private val workspaceId = UUID.randomUUID()

    private fun validate(
        pipeline: Pipeline,
        templates: TemplateDryRenderer = stub(),
    ): ValidationResult = Fixtures.validator(templates = templates).validate(pipeline, workspaceId)
    // ---------------------------------------------------------------- the positive

    @Test
    fun `the design record's example pipeline saves clean`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(stageNode(), scriptNode()),
                    parameters = parameters,
                ),
            )

        result.failures.map { "${it.code} ${it.path} ${it.message}" }.shouldBeEmpty()
    }

    // ---------------------------------------------------------------- one case per code

    @Test
    fun `a non-script pin is script_template_type`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), scriptNode()), parameters = parameters),
                templates = stub(templateType = TemplateType.SQL),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_TEMPLATE_TYPE)
    }

    @Test
    fun `a source on a SCRIPT node is script_source_forbidden`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), scriptNode(source = "pg-prod")), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_SOURCE_FORBIDDEN)
    }

    @Test
    fun `a table no ancestor stages is script_input_unknown`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("\"elsewhere\""),
                                        "tz" to Fixtures.json("\"\$org_timezone\""),
                                        "min_total" to Fixtures.json("\"\$min_total\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_INPUT_UNKNOWN)
    }

    @Test
    fun `a table staged only AFTER the node is not visible to it`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            scriptNode(dependsOn = emptyList()),
                            stageNode(),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_INPUT_UNKNOWN)
    }

    @Test
    fun `a key nobody writes is script_input_unknown`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("\"stg_orders\""),
                                        "tz" to Fixtures.json("\"\$org_nowhere\""),
                                        "min_total" to Fixtures.json("\"\$min_total\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_INPUT_UNKNOWN)
    }

    @Test
    fun `an inputs key set that differs from the contract is script_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("\"stg_orders\""),
                                        "tz" to Fixtures.json("\"\$org_timezone\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_INPUT_CONTRACT)
        result.withCode(Validation.SCRIPT_INPUT_CONTRACT).single().details["missing"] shouldBe listOf("min_total")
    }

    @Test
    fun `a value input whose Context type does not fit is script_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("\"stg_orders\""),
                                        "tz" to Fixtures.json("\"\$min_total\""),
                                        "min_total" to Fixtures.json("\"\$org_timezone\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_INPUT_CONTRACT)
        result.withCode(Validation.SCRIPT_INPUT_CONTRACT).size shouldBe 2
    }

    @Test
    fun `a table input bound to a Context key is script_input_contract`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(
                                inputs =
                                    mapOf(
                                        "orders" to Fixtures.json("\"\$org_timezone\""),
                                        "tz" to Fixtures.json("\"\$org_timezone\""),
                                        "min_total" to Fixtures.json("\"\$min_total\""),
                                    ),
                            ),
                        ),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_INPUT_CONTRACT)
    }

    @Test
    fun `a target on value mode is script_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(
                                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                                output = NodeOutput.Tempdb("x"),
                                contextKey = "threshold",
                            ),
                        ),
                ),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_OUTPUT_SHAPE)
    }

    @Test
    fun `a context_key on table mode is script_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), scriptNode(contextKey = "threshold")), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_OUTPUT_SHAPE)
    }

    @Test
    fun `value mode without a context_key is script_output_shape`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes =
                        listOf(
                            stageNode(),
                            scriptNode(inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")), output = null),
                        ),
                ),
                templates = stub(contract = VALUE_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_OUTPUT_SHAPE)
    }

    @Test
    fun `a rejects table the contract does not declare is script_rejects_undeclared`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), scriptNode()), parameters = parameters),
                templates = stub(contract = CONTRACT.copy(rejects = false)),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_REJECTS_UNDECLARED)
    }

    @Test
    fun `a declared rejects contract with no rejects table is script_rejects_missing`() {
        val result =
            validate(
                Fixtures.pipeline(
                    nodes = listOf(stageNode(), scriptNode(output = NodeOutput.Tempdb("order_lines"))),
                    parameters = parameters,
                ),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_REJECTS_MISSING)
    }

    @Test
    fun `a rejects-declaring contract on a caller node is script_rejects_on_caller`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), scriptNode(output = NodeOutput.Caller)), parameters = parameters),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_REJECTS_ON_CALLER)
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
                    { "id": "shape", "description": "x", "type": "SCRIPT",
                      "template": {"id": "$SCRIPT_TEMPLATE", "version": 3},
                      "inputs": {"orders": "stg_orders"},
                      "output": {"target": "caller", "rejects": "nope"},
                      "depends_on": [] }
                  ]
                }
                """.trimIndent(),
            )

        when (outcome) {
            is DeserializationOutcome.Parsed -> {
                error("expected the wire scan to refuse rejects on a caller output")
            }

            is DeserializationOutcome.Rejected -> {
                outcome.result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_REJECTS_ON_CALLER)
            }
        }
    }

    @Test
    fun `strict without a rejects contract is script_strict_without_rejects`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(stageNode(), scriptNode(strict = true)), parameters = parameters),
                templates = stub(contract = CONTRACT.copy(rejects = false)),
            )

        result.codes shouldContainExactlyInAnyOrder
            listOf(
                Validation.SCRIPT_STRICT_WITHOUT_REJECTS,
                Validation.SCRIPT_REJECTS_UNDECLARED,
            )
    }

    @Test
    fun `strict on a non-script node is refused`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(Fixtures.node("q").copy(strict = true))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_STRICT_WITHOUT_REJECTS)
    }

    @Test
    fun `rejects on a non-script node's tempdb output is refused`() {
        val result =
            validate(
                Fixtures.pipeline(nodes = listOf(Fixtures.node("q", output = NodeOutput.Tempdb("t", rejects = "r")))),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_REJECTS_UNDECLARED)
    }

    @Test
    fun `a SQL node binding an object key is script_object_key_bound - R4`() {
        val writer =
            scriptNode(
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

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_OBJECT_KEY_BOUND)
        result.withCode(Validation.SCRIPT_OBJECT_KEY_BOUND).single().details["context_key"] shouldBe "payload"
    }

    @Test
    fun `a calculator input referencing an object key is script_object_key_bound - R4`() {
        val writer =
            scriptNode(
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
                                inputs =
                                    mapOf(
                                        "date" to Fixtures.json("\"\$payload\""),
                                        "fiscal_start" to Fixtures.json("\"\$org_fiscal_start_date\""),
                                    ),
                                dependsOn = listOf("wrap"),
                            ),
                        ),
                ),
                templates = stub(contract = OBJECT_CONTRACT),
            )

        result.codes shouldContainExactlyInAnyOrder listOf(Validation.SCRIPT_OBJECT_KEY_BOUND)
    }

    @Test
    fun `a downstream SCRIPT reading an object key is exactly what R4 allows`() {
        val writer =
            scriptNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "payload",
            )
        val reader =
            scriptNode(
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
    fun `a script key bound without a depends_on path is calculator_input_unordered`() {
        val writer =
            scriptNode(
                id = "wrap",
                inputs = mapOf("orders" to Fixtures.json("\"stg_orders\"")),
                output = null,
                contextKey = "threshold",
            )
        val reader =
            scriptNode(
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
    fun `a script context_key colliding with a parameter or another writer is refused`() {
        val writer =
            scriptNode(
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
    fun `a script context_key with a bad name is calculator_output_name_invalid`() {
        val writer =
            scriptNode(
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
                    nodes = listOf(stageNode(), scriptNode(kind = "fiscal_quarter", contextKeys = mapOf("a" to "b"))),
                    parameters = parameters,
                ),
            )

        // kind/context_keys refused here; the output-shape verdicts the fields provoke are §12.13's own.
        result.withCode(Validation.CALCULATOR_FIELDS_ON_NON_CALCULATOR).single().details["fields"] shouldBe listOf("kind", "context_keys")
    }
}
