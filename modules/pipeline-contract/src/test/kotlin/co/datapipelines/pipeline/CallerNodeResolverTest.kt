package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** pipeline-contract §9 — caller-node resolution (D1). */
class CallerNodeResolverTest {
    @Test
    fun `the caller node is the node whose output is Caller`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "stage", output = NodeOutput.Tempdb("stg_orders")),
                        Fixtures.node(id = "report", output = NodeOutput.Caller, dependsOn = listOf("stage")),
                    ),
            )

        CallerNodeResolver.resolve(pipeline)?.id shouldBe "report"
        CallerNodeResolver.hasCallerNode(pipeline) shouldBe true
    }

    @Test
    fun `DAG position is irrelevant - a mid-DAG caller node resolves the same way`() {
        // §9: "the caller node may be a sink, or sit mid-DAG". There is no topology check left
        // anywhere in the system (D1 deleted terminal-node auto-detection outright).
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "middle", output = NodeOutput.Caller),
                        Fixtures.node(
                            id = "sink",
                            output = NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.APPEND),
                            dependsOn = listOf("middle"),
                        ),
                    ),
            )

        CallerNodeResolver.resolve(pipeline)?.id shouldBe "middle"
    }

    @Test
    fun `zero caller nodes resolves to null - a pure write-back pipeline`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(
                            id = "write",
                            output = NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.REPLACE),
                        ),
                    ),
            )

        CallerNodeResolver.resolve(pipeline).shouldBeNull()
        CallerNodeResolver.hasCallerNode(pipeline) shouldBe false
    }

    @Test
    fun `DML and DDL nodes never resolve to caller`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "a", type = NodeType.DML),
                        Fixtures.node(id = "b", type = NodeType.DDL),
                    ),
            )

        CallerNodeResolver.resolve(pipeline).shouldBeNull()
    }

    @Test
    fun `two caller nodes fail loudly rather than picking one`() {
        // Defensive, not a validation path: PipelineValidator rejects this at save time. If it
        // reaches the executor, returning an arbitrary one of the two ResultSets would send a
        // caller the wrong data with no error at all.
        val nodes = listOf(Fixtures.node(id = "a"), Fixtures.node(id = "b"))

        val thrown = shouldThrow<DatapipelinesException> { CallerNodeResolver.resolve(nodes) }

        thrown.code shouldBe PipelineErrorCodes.Validation.MULTIPLE_CALLER_NODES
    }

    @Test
    fun `hasCallerNode cannot disagree with resolve`() {
        // It used to. `count { … } == 1` answered false for a two-caller pipeline while resolve
        // threw — so an executor asking "is there a result to materialise?" would have been told
        // "no" and run a pipeline it must refuse outright, silently returning stats for a
        // pipeline the caller expected data from.
        val twoCallers = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a"), Fixtures.node(id = "b")))

        shouldThrow<DatapipelinesException> { CallerNodeResolver.hasCallerNode(twoCallers) }

        // And they agree on the legal cases.
        listOf(
            Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a"))) to true,
            Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a", output = NodeOutput.Tempdb("t")))) to false,
        ).forEach { (pipeline, expected) ->
            CallerNodeResolver.hasCallerNode(pipeline) shouldBe expected
            (CallerNodeResolver.resolve(pipeline) != null) shouldBe expected
        }
    }
}
