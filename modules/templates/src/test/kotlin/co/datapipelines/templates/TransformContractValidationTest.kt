package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The transform-nodes design §2.2 model rules, one test per rule, each naming the detail
 * (`details.rule`). The matrix the record fixes: contract shape, declared types per
 * type-system.md §4, mode/output agreement, invariants compiling, and the tests block's
 * three structural rules (the mandatory empty case, R2's no-table-in-row-cases, exactly-one
 * expectation). Falsified at birth: removing the empty-case rule turns its test red.
 */
class TransformContractValidationTest {
    private val workspaceId = java.util.UUID.randomUUID()

    private fun validator() = TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })

    /** A legal row-mode transform draft; a test mutates exactly what it is about. */
    private fun draftOf(
        contract: TransformContract = rowContract(),
        invariants: List<TransformInvariant>? = emptyList(),
        tests: List<TransformTestCase>? = listOf(emptyCase()),
    ) = TemplateFixtures.draft(
        id = "test/xform.jsonata",
        type = TemplateType.JSONATA,
        engine = Template.NONE_ENGINE,
        dialect = null,
        body = "rows",
        contract = contract,
        invariants = invariants,
        tests = tests,
    )

    private fun rowContract(
        inputs: Map<String, TransformInput> =
            mapOf(
                "orders" to TransformInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
                "tz" to TransformInput.Value(LogicalType.STRING),
            ),
        output: TransformOutput = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
        mode: TransformMode = TransformMode.ROW,
        rejects: Boolean = false,
    ) = TransformContract(mode = mode, inputs = inputs, output = output, rejects = rejects)

    private fun emptyCase(
        inputs: Map<String, Any?>? = mapOf("tz" to "UTC"),
        rows: List<Map<String, Any?>>? = emptyList(),
    ) = TransformTestCase(
        name = "empty",
        input = TransformTestInput(rows = rows, inputs = inputs),
        expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
    )

    private fun failuresOf(draft: TemplateDraft) = validator().validate(draft, workspaceId).failures

    private fun ruleOf(draft: TemplateDraft): String? =
        failuresOf(draft).firstOrNull { it.code == PipelineErrorCodes.Template.CONTRACT_INVALID }?.details?.get("rule") as? String

    @Test
    fun `the record's row-mode shape is accepted`() {
        failuresOf(draftOf()).filter { it.code.startsWith("template.") } shouldBe emptyList()
    }

    @Test
    fun `sql and html refuse the blocks with blocks_not_allowed`() {
        listOf(TemplateType.SQL, TemplateType.HTML).forEach { type ->
            withClue(type) {
                val draft =
                    TemplateFixtures.draft(
                        type = type,
                        engine = Template.FREEMARKER_ENGINE,
                        dialect = if (type == TemplateType.SQL) co.datapipelines.typesystem.Dialect.POSTGRES else null,
                        body = if (type == TemplateType.SQL) "SELECT 1" else "<p>x</p>",
                        contract = rowContract(),
                        tests = listOf(emptyCase()),
                    )
                failuresOf(draft).map { it.code } shouldContain PipelineErrorCodes.Template.BLOCKS_NOT_ALLOWED
            }
        }
    }

    @Test
    fun `a transform missing any block is blocks_missing`() {
        failuresOf(draftOf().copy(contract = null)).map { it.details["rule"] } shouldContain "blocks_missing"
        failuresOf(draftOf().copy(invariants = null)).map { it.details["rule"] } shouldContain "blocks_missing"
        failuresOf(draftOf().copy(tests = null)).map { it.details["rule"] } shouldContain "blocks_missing"
    }

    @Test
    fun `inputs_empty refuses a contract with no inputs`() {
        ruleOf(draftOf(contract = rowContract(inputs = emptyMap()))) shouldBe "inputs_empty"
    }

    @Test
    fun `name_invalid refuses a bad input name`() {
        ruleOf(draftOf(contract = rowContract(inputs = mapOf("Bad-Name" to TransformInput.Value(LogicalType.STRING))))) shouldBe
            "name_invalid"
    }

    @Test
    fun `row_mode_inputs refuses zero or two table inputs in row mode`() {
        ruleOf(draftOf(contract = rowContract(inputs = mapOf("tz" to TransformInput.Value(LogicalType.STRING))))) shouldBe
            "row_mode_inputs"
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        inputs =
                            mapOf(
                                "orders" to TransformInput.Table(listOf(ContractColumn("a", LogicalType.INTEGER))),
                                "returns" to TransformInput.Table(listOf(ContractColumn("b", LogicalType.INTEGER))),
                            ),
                    ),
            ),
        ) shouldBe "row_mode_inputs"
    }

    @Test
    fun `type_unsupported refuses BINARY and NULL as declared types`() {
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        output = TransformOutput.Table(listOf(ContractColumn("payload", LogicalType.BINARY))),
                    ),
            ),
        ) shouldBe "type_unsupported"
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        inputs = mapOf("x" to TransformInput.Table(listOf(ContractColumn("n", LogicalType.NULL)))),
                    ),
            ),
        ) shouldBe "type_unsupported"
    }

    @Test
    fun `precision_scale_invalid enforces type-system section 4`() {
        // scale without precision
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        inputs =
                            mapOf("orders" to TransformInput.Table(listOf(ContractColumn("a", LogicalType.DECIMAL, scale = 2)))),
                    ),
            ),
        ) shouldBe "precision_scale_invalid"
        // BIGDECIMAL precision without scale
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        inputs =
                            mapOf("orders" to TransformInput.Table(listOf(ContractColumn("a", LogicalType.BIGDECIMAL, precision = 20)))),
                    ),
            ),
        ) shouldBe "precision_scale_invalid"
        // DECIMAL past 15
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        inputs =
                            mapOf(
                                "orders" to
                                    TransformInput.Table(listOf(ContractColumn("a", LogicalType.DECIMAL, precision = 16, scale = 2))),
                            ),
                    ),
            ),
        ) shouldBe "precision_scale_invalid"
        // precision on a non-decimal
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        inputs =
                            mapOf("orders" to TransformInput.Table(listOf(ContractColumn("a", LogicalType.STRING, precision = 4)))),
                    ),
            ),
        ) shouldBe "precision_scale_invalid"
    }

    @Test
    fun `mode_output_mismatch refuses a value output in row mode and a table output in value mode`() {
        ruleOf(draftOf(contract = rowContract(output = TransformOutput.Value(LogicalType.STRING)))) shouldBe "mode_output_mismatch"
        ruleOf(
            draftOf(
                contract = rowContract(mode = TransformMode.VALUE),
            ),
        ) shouldBe "mode_output_mismatch"
    }

    @Test
    fun `rejects_without_table refuses rejects with a non-table output`() {
        ruleOf(
            draftOf(
                contract =
                    rowContract(
                        mode = TransformMode.VALUE,
                        output = TransformOutput.Obj(),
                        rejects = true,
                    ),
            ),
        ) shouldBe "rejects_without_table"
    }

    @Test
    fun `invariant_invalid refuses a non-compiling invariant, naming it`() {
        val failures =
            failuresOf(
                draftOf(invariants = listOf(TransformInvariant("bad", "\$count(", "broken"))),
            )
        failures.map { it.code } shouldContain PipelineErrorCodes.Template.INVARIANT_INVALID
        failures.single { it.code == PipelineErrorCodes.Template.INVARIANT_INVALID }.details["invariant"] shouldBe "bad"
    }

    @Test
    fun `empty_case_missing refuses no tests and no empty case`() {
        ruleOf(draftOf(tests = emptyList())) shouldBe "empty_case_missing"
        ruleOf(
            draftOf(
                tests =
                    listOf(
                        TransformTestCase(
                            name = "one row",
                            input = TransformTestInput(rows = listOf(mapOf("order_id" to 1)), inputs = mapOf("tz" to "UTC")),
                            expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
                        ),
                    ),
            ),
        ) shouldBe "empty_case_missing"
    }

    @Test
    fun `row_case_lists_table refuses a row-mode case listing its table (R2)`() {
        ruleOf(
            draftOf(
                tests =
                    listOf(
                        emptyCase(),
                        TransformTestCase(
                            name = "lists table",
                            input = TransformTestInput(rows = emptyList(), inputs = mapOf("orders" to emptyList<Any>(), "tz" to "UTC")),
                            expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
                        ),
                    ),
            ),
        ) shouldBe "row_case_lists_table"
    }

    @Test
    fun `expect_shape refuses both and neither expectation`() {
        ruleOf(
            draftOf(tests = listOf(emptyCase().copy(expect = TransformTestExpect(output = null, refusal = null)))),
        ) shouldBe "expect_shape"
        ruleOf(
            draftOf(
                tests =
                    listOf(
                        emptyCase().copy(
                            expect =
                                TransformTestExpect(
                                    output = TransformBlocks.mapper.readTree("""{"rows": []}"""),
                                    refusal = "pipeline.transform.evaluation_failed",
                                ),
                        ),
                    ),
            ),
        ) shouldBe "expect_shape"
    }

    @Test
    fun `a typo inside a block is unknown_field, never a silent drop`() {
        val outcome =
            TemplateDeserializer().read(
                """{"type":"jsonata","display_name":"X","description":"Y","body":"rows",
                    "contract":{"mode":"row","inputs":{"orders":{"kind":"table","collumns":[]}},"output":{"kind":"table","columns":[]}}}""",
            )
        val rejected = (outcome as TemplateDeserializationOutcome.Rejected).result
        rejected.failures.single().code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
        rejected.failures.single().details["rule"] shouldBe "unknown_field"
    }
}
