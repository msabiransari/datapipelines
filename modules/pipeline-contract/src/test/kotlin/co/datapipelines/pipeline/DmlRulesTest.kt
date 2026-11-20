package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * pipeline-contract §12.4 (#1034) — a DML node's declared shape: its operation, an insert's key
 * and input, and an explicit result target; plus run admission's re-application of the same
 * verdicts ([DmlShapeCheck]) and the wire shape of the new fields.
 */
class DmlRulesTest {
    private val validator = Fixtures.validator()
    private val workspaceId = UUID.randomUUID()

    @Test
    fun `an insert reading declared parameters, keyed and discarded, is valid`() {
        codesOf(insert()) shouldContainExactly emptyList()
    }

    @Test
    fun `an insert reading a table its dependency stages is valid, delivering to tempdb or caller`() {
        codesOf(insert(input = DmlInput(table = "stg_orders"), output = NodeOutput.Tempdb("new_orders"))) shouldContainExactly
            emptyList()
        codesOf(insert(input = DmlInput(table = "stg_orders"), output = NodeOutput.Caller)) shouldContainExactly emptyList()
    }

    @Test
    fun `update and delete with any of the three result targets are valid`() {
        listOf("update", "delete").forEach { operation ->
            listOf(NodeOutput.Discard, NodeOutput.Caller, NodeOutput.Tempdb("affected")).forEach { output ->
                withClue("$operation -> $output") {
                    codesOf(dml(operation = operation, output = output)) shouldContainExactly emptyList()
                }
            }
        }
    }

    @Test
    fun `a DML node without an operation is refused, naming the allowed values`() {
        val failure = failuresOf(dml(operation = null)).single()

        failure.code shouldBe Validation.DML_OPERATION_REQUIRED
        failure.path shouldBe "nodes[1].operation"
        failure.details["allowed"] shouldBe listOf("insert", "update", "delete")
    }

    @Test
    fun `an unknown operation is refused with its value`() {
        val failure = failuresOf(dml(operation = "upsert")).single()

        failure.code shouldBe Validation.DML_OPERATION_INVALID
        failure.details["value"] shouldBe "upsert"
    }

    @Test
    fun `a DML node without an output is refused - its result rows go somewhere explicit`() {
        codesOf(dml(output = null)) shouldContainExactly listOf(Validation.DML_OUTPUT_REQUIRED)
    }

    @Test
    fun `a DML node targeting a datasource is refused - a DML result never writes back`() {
        val output = NodeOutput.Datasource("pg-prod", "orders", WriteMode.APPEND)

        codesOf(dml(output = output)) shouldContainExactly listOf(Validation.DML_HAS_OUTPUT)
    }

    @Test
    fun `an insert's key must name one SQL identifier column of a supported type`() {
        listOf(
            null,
            DmlKey(column = null, type = "BIGINTEGER"),
            DmlKey(column = "  ", type = "BIGINTEGER"),
            DmlKey(column = "order id; drop table x", type = "BIGINTEGER"),
            DmlKey(column = "\"order_id\"", type = "BIGINTEGER"),
            DmlKey(column = "order_id", type = "DECIMAL"),
            DmlKey(column = "order_id", type = null),
        ).forEach { key ->
            withClue(key) { codesOf(insert(key = key)) shouldContainExactly listOf(Validation.DML_KEY_INVALID) }
        }
    }

    @Test
    fun `a supported key type is accepted - INTEGER, BIGINTEGER and STRING`() {
        listOf("INTEGER", "BIGINTEGER", "STRING").forEach { type ->
            withClue(type) { codesOf(insert(key = DmlKey("order_id", type))) shouldContainExactly emptyList() }
        }
    }

    @Test
    fun `an insert's input declares exactly one of parameters and table, each resolvable`() {
        listOf(
            null,
            DmlInput(),
            DmlInput(parameters = listOf("customer"), table = "stg_orders"),
            DmlInput(parameters = listOf("customer", "customer")),
            DmlInput(parameters = listOf("not_declared")),
            DmlInput(table = " "),
            DmlInput(table = "nobody_stages_this"),
        ).forEach { input ->
            withClue(input) { codesOf(insert(input = input)) shouldContainExactly listOf(Validation.DML_INPUT_INVALID) }
        }
    }

    @Test
    fun `a staged input must come from a node this one depends on`() {
        val independent = insert(input = DmlInput(table = "stg_orders")).copy(dependsOn = emptyList())

        codesOf(independent) shouldContainExactly listOf(Validation.DML_INPUT_INVALID)
    }

    @Test
    fun `an update or delete declaring an insert's key or input is refused for each`() {
        val node = dml(operation = "update").copy(key = DmlKey("order_id", "BIGINTEGER"), input = DmlInput(listOf("customer")))

        codesOf(node) shouldContainExactly listOf(Validation.DML_KEY_INVALID, Validation.DML_INPUT_INVALID)
    }

    @Test
    fun `a non-DML node declaring DML fields is refused once, naming them`() {
        val node = Fixtures.node(id = "b", dependsOn = listOf("a")).copy(operation = "insert", key = DmlKey("id", "STRING"))

        val failure = failuresOf(node).single()
        failure.code shouldBe Validation.DML_FIELD_FORBIDDEN
        failure.details["fields"] shouldBe listOf("operation", "key")
    }

    @Test
    fun `discard is a DML-only target`() {
        val node = Fixtures.node(id = "b", output = NodeOutput.Discard, dependsOn = listOf("a"))

        codesOf(node) shouldContainExactly listOf(Validation.OUTPUT_TARGET_INVALID)
    }

    @Test
    fun `run admission re-applies the shape rules to a stored body - an operation-less node is refused`() {
        val legacy = pipelineWith(dml(operation = null))

        DmlShapeCheck.failures(legacy).map { it.code } shouldContainExactly listOf(Validation.DML_OPERATION_REQUIRED)
        DmlShapeCheck.failures(pipelineWith(insert())).shouldBeEmpty()
    }

    @Test
    fun `the DML fields and discard round-trip on the wire, and a node without them serializes none`() {
        val pipeline = pipelineWith(insert(input = DmlInput(table = "stg_orders"), output = NodeOutput.Discard))
        val tree = Fixtures.mapper.valueToTree<JsonNode>(pipeline)
        val node = tree.path("nodes").path(1)

        node.path("operation").asText() shouldBe "insert"
        node.path("key").toString() shouldBe """{"column":"order_id","type":"BIGINTEGER"}"""
        node.path("input").toString() shouldBe """{"table":"stg_orders"}"""
        node.path("output").toString() shouldBe """{"target":"discard"}"""
        Fixtures.mapper.treeToValue(tree, Pipeline::class.java) shouldBe pipeline

        val dql = tree.path("nodes").path(0)
        listOf("operation", "key", "input").forEach { field -> withClue(field) { dql.has(field) shouldBe false } }
    }

    private fun stage() = Fixtures.node(id = "a", output = NodeOutput.Tempdb("stg_orders"))

    private fun dml(
        operation: String? = "update",
        output: NodeOutput? = NodeOutput.Discard,
    ): Node = Fixtures.node(id = "b", type = NodeType.DML, dependsOn = listOf("a")).copy(operation = operation, output = output)

    private fun insert(
        key: DmlKey? = DmlKey("order_id", "BIGINTEGER"),
        input: DmlInput? = DmlInput(parameters = listOf("customer")),
        output: NodeOutput? = NodeOutput.Discard,
    ): Node = dml(operation = "insert", output = output).copy(key = key, input = input)

    private fun pipelineWith(node: Node) =
        Fixtures.pipeline(
            nodes = listOf(stage(), node),
            parameters = mapOf("customer" to Parameter(LogicalType.STRING)),
        )

    private fun failuresOf(node: Node) = validator.validate(pipelineWith(node), workspaceId).failures

    private fun codesOf(node: Node) = failuresOf(node).map { it.code }
}
