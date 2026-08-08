package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** pipeline-contract §12.4 — the node-type ↔ output-block rules. */
class NodeTypeRulesTest {
    private val validator = Fixtures.validator()

    @Test
    fun `a DML node with an output block is rejected`() {
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(dqlSink(), Fixtures.node(id = "b", type = NodeType.DML).copy(output = NodeOutput.Caller)),
            )

        validator.validate(pipeline).codes shouldContain Validation.DML_HAS_OUTPUT
    }

    @Test
    fun `a DDL node with an output block is rejected`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        dqlSink(),
                        Fixtures.node(id = "b", type = NodeType.DDL).copy(output = NodeOutput.Tempdb("t")),
                    ),
            )

        validator.validate(pipeline).codes shouldContain Validation.DDL_HAS_OUTPUT
    }

    @Test
    fun `a DML or DDL node with no output block is fine - the side effect is the output`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        dqlSink(),
                        Fixtures.node(id = "b", type = NodeType.DML, dependsOn = listOf("a")),
                        Fixtures.node(id = "c", type = NodeType.DDL, dependsOn = listOf("a")),
                    ),
            )

        validator.validate(pipeline).failures shouldContainExactly emptyList()
    }

    @Test
    fun `a tempdb output with no table is rejected`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(output = NodeOutput.Tempdb(""))))

        validator.validate(pipeline).codes shouldContainExactly listOf(Validation.OUTPUT_TABLE_MISSING)
    }

    @Test
    fun `a datasource output naming neither datasource nor table is rejected once, listing both`() {
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(Fixtures.node(output = NodeOutput.Datasource("", "", WriteMode.APPEND))),
            )

        val failure = validator.validate(pipeline).withCode(Validation.OUTPUT_DATASOURCE_MISSING).single()

        @Suppress("UNCHECKED_CAST")
        (failure.details["missing"] as List<String>) shouldContainExactly listOf("datasource", "table")
    }

    @Test
    fun `the caller-side alias code is never emitted - §9-2 defines it as the same check`() {
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(dqlSink(), Fixtures.node(id = "b", type = NodeType.DML).copy(output = NodeOutput.Caller)),
            )

        validator.validate(pipeline).codes.contains(Validation.NON_DQL_CALLER_TARGET) shouldBe false
    }

    private fun dqlSink() = Fixtures.node(id = "a", output = NodeOutput.Tempdb("stg_orders"))
}
