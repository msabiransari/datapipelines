package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.NullNode
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * #236 — a refusal-expecting test case survives its own storage.
 *
 * The stored jsonb writes an absent `expect.output` as `"output": null` (the writer does not
 * change: the body hash is computed in Postgres over that text), and a JSON null binds a
 * `JsonNode` as `NullNode`. Before the fix every stored refusal case read back declaring BOTH
 * an output and a refusal, so `expect_shape` refused it on release (which re-validates the
 * stored blocks) and on every get → update round trip. Found by lane 7d's browser walk
 * (create → edit → save → release of the design record's own example).
 *
 * Falsified: with `TransformTestExpect.of`'s normalization removed, the first three cases go red.
 */
class TransformTestExpectRoundTripTest {
    private val workspaceId = java.util.UUID.randomUUID()

    private val refusalCase =
        TransformTestCase(
            name = "wrong shape is refused",
            input = TransformTestInput(rows = listOf(mapOf("order_id" to "x"))),
            expect = TransformTestExpect(refusal = "pipeline.transform.input_contract_violation"),
        )

    private val emptyCase =
        TransformTestCase(
            name = "empty input",
            input = TransformTestInput(rows = emptyList()),
            expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("[]")),
        )

    @Test
    fun `a stored refusal case reads back as refusal-only - the writer's output null is absent on read`() {
        val stored = requireNotNull(TransformBlocks.writeTests(listOf(refusalCase)))
        // The storage text is what it always was — the hash is computed over it.
        stored shouldContain "\"output\":null"

        val back = requireNotNull(TransformBlocks.readTests(stored)).single()

        back.expect.output.shouldBeNull()
        back.expect.refusal shouldBe "pipeline.transform.input_contract_violation"
        back shouldBe refusalCase
    }

    @Test
    fun `the stored blocks re-validate - no expect_shape on release's re-validation`() {
        val stored = requireNotNull(TransformBlocks.readTests(TransformBlocks.writeTests(listOf(emptyCase, refusalCase))))
        val draft =
            TemplateFixtures.draft(
                id = "test/xform.jsonata",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "rows",
                contract =
                    TransformContract(
                        mode = TransformMode.ROW,
                        inputs = mapOf("orders" to TransformInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER)))),
                        output = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
                    ),
                invariants = emptyList(),
                tests = stored,
            )

        val rules =
            TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })
                .validate(draft, workspaceId)
                .failures
                .map { it.details["rule"] }

        rules.contains("expect_shape") shouldBe false
    }

    @Test
    fun `the wire round trip - output null beside a refusal, echoed back by a client, binds as refusal-only`() {
        val echoed = """{ "output": null, "refusal": "pipeline.transform.timeout" }"""

        val expect = TransformBlocks.mapper.readValue(echoed, TransformTestExpect::class.java)

        expect.output.shouldBeNull()
        expect.refusal shouldBe "pipeline.transform.timeout"
    }

    @Test
    fun `a JSON-null expected output with no refusal is still a JSON-null expectation`() {
        val expect = TransformBlocks.mapper.readValue("""{ "output": null }""", TransformTestExpect::class.java)

        expect.output.shouldBeInstanceOf<NullNode>()
        expect.refusal.shouldBeNull()
    }

    @Test
    fun `a non-null output beside a refusal is still expect_shape - the rule is not weakened`() {
        val both = TransformBlocks.mapper.readValue("""{ "output": [], "refusal": "x" }""", TransformTestExpect::class.java)
        val draft =
            TemplateFixtures.draft(
                id = "test/xform.jsonata",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "rows",
                contract =
                    TransformContract(
                        mode = TransformMode.ROW,
                        inputs = mapOf("orders" to TransformInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER)))),
                        output = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
                    ),
                invariants = emptyList(),
                tests = listOf(emptyCase, TransformTestCase("both", TransformTestInput(rows = emptyList()), both)),
            )

        val failures =
            TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })
                .validate(draft, workspaceId)
                .failures

        failures.map { it.details["rule"] } shouldContain "expect_shape"
        failures.map { it.code } shouldContain PipelineErrorCodes.Template.CONTRACT_INVALID
    }

    @Test
    fun `a typo inside expect is still refused - the creator keeps the strict binding`() {
        val refused =
            runCatching { TransformBlocks.mapper.readValue("""{ "refusl": "x" }""", TransformTestExpect::class.java) }
        refused.isFailure shouldBe true
    }
}
