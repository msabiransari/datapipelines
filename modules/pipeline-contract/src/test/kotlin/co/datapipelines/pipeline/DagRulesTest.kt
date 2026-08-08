package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** pipeline-contract §12.2 (DAG) and §12.3 (caller node). */
class DagRulesTest {
    private val validator = Fixtures.validator()

    @Test
    fun `an empty node list is rejected`() {
        validator.validate(Fixtures.pipeline(nodes = emptyList())).codes shouldContainExactly
            listOf(Validation.EMPTY_PIPELINE)
    }

    @Test
    fun `a dependency on a node that does not exist is rejected`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a", dependsOn = listOf("ghost"))))

        val failure = validator.validate(pipeline).withCode(Validation.DANGLING_DEPENDENCY).single()

        failure.details["missing"] shouldBe "ghost"
    }

    @Test
    fun `a valid diamond DAG is accepted`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "root", output = NodeOutput.Tempdb("stg_root")),
                        Fixtures.node(id = "left", output = NodeOutput.Tempdb("int_left"), dependsOn = listOf("root")),
                        Fixtures.node(id = "right", output = NodeOutput.Tempdb("int_right"), dependsOn = listOf("root")),
                        Fixtures.node(id = "join", dependsOn = listOf("left", "right")),
                    ),
            )

        validator.validate(pipeline).failures shouldContainExactly emptyList()
    }

    @Test
    fun `a cycle is reported with its path reconstructed`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "a", output = NodeOutput.Tempdb("t_a"), dependsOn = listOf("c")),
                        Fixtures.node(id = "b", output = NodeOutput.Tempdb("t_b"), dependsOn = listOf("a")),
                        Fixtures.node(id = "c", output = NodeOutput.Tempdb("t_c"), dependsOn = listOf("b")),
                    ),
            )

        val failure = validator.validate(pipeline).withCode(Validation.CYCLE_DETECTED).single()

        // The path, not just the fact — an author with fifteen nodes cannot act on "there is a
        // cycle somewhere".
        failure.message shouldContain "->"
        @Suppress("UNCHECKED_CAST")
        (failure.details["cycle"] as List<String>) shouldContainExactlyInAnyOrder listOf("a", "b", "c")
    }

    @Test
    fun `a self-dependency is a cycle`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a", dependsOn = listOf("a"))))

        validator.validate(pipeline).codes shouldContain Validation.CYCLE_DETECTED
    }

    @Test
    fun `one cycle reached from two roots is reported once`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "x", output = NodeOutput.Tempdb("t_x"), dependsOn = listOf("a")),
                        Fixtures.node(id = "y", output = NodeOutput.Tempdb("t_y"), dependsOn = listOf("a")),
                        Fixtures.node(id = "a", output = NodeOutput.Tempdb("t_a"), dependsOn = listOf("b")),
                        Fixtures.node(id = "b", output = NodeOutput.Tempdb("t_b"), dependsOn = listOf("a")),
                    ),
            )

        val failure = validator.validate(pipeline).withCode(Validation.CYCLE_DETECTED).single()

        // The reported cycle is exactly {a, b} — not the path that reached it. Asserting only
        // `size == 1` let a mutation that reported `[x, a, b]` (the whole DFS path, roots
        // included) survive: still one failure, but it names two innocent nodes.
        @Suppress("UNCHECKED_CAST")
        (failure.details["cycle"] as List<String>) shouldContainExactlyInAnyOrder listOf("a", "b")
    }

    @Test
    fun `a deep reversed dependency chain does not exhaust the stack`() {
        // §12.2 crash-safety. Two things make this test discriminating, and both were measured
        // rather than assumed:
        //
        //  1. DIRECTION. Node i depends on i+1, so the DFS descends the whole chain from the
        //     first root it picks. A forward chain (i depends on i-1) holds traversal depth at
        //     ~1 and the recursive implementation this replaced passes it happily.
        //
        //  2. STACK SIZE. On this JVM's default stack the old recursive version SURVIVED 5000
        //     and only died at ~10000 — so a plain 5000-node test would have passed before the
        //     fix and proven nothing, and any threshold picked against a default stack is a
        //     threshold that moves with the runner. Running on an explicit 256 KB stack makes
        //     the verdict deterministic: measured on the pre-fix code, StackOverflowError at
        //     depth 1000/2000/5000 on 128 KB and 256 KB stacks; the iterative version below
        //     survives all of them because its frames are on the heap.
        //
        // A request thread is the realistic setting anyway — Tomcat's default is 512 KB, where
        // the old code died at depth 2000 from a ~60 KB payload.
        val nodes = chain(DEEP, cycleAtEnd = false)

        val result = onSmallStack { validator.validate(Fixtures.pipeline(nodes = nodes)) }

        // Survived — no StackOverflowError — and correctly found no cycle in a chain.
        result.codes shouldNotContain Validation.CYCLE_DETECTED
        result.codes shouldNotContain Validation.DANGLING_DEPENDENCY
    }

    @Test
    fun `a cycle at the end of a deep chain is still found, and still reported with its path`() {
        // Guards against "fixed the crash by not traversing": the iterative walk must reach the
        // bottom of a 5000-node chain, on the same small stack, and report the loop it closes.
        val nodes = chain(DEEP, cycleAtEnd = true)

        val result = onSmallStack { validator.validate(Fixtures.pipeline(nodes = nodes)) }
        val failure = result.withCode(Validation.CYCLE_DETECTED).single()

        @Suppress("UNCHECKED_CAST")
        (failure.details["cycle"] as List<String>) shouldContainExactlyInAnyOrder
            listOf("n${DEEP - 2}", "n${DEEP - 1}")
    }

    /** A chain where node `i` depends on `i+1`; the tail optionally closes a 2-cycle. */
    private fun chain(
        depth: Int,
        cycleAtEnd: Boolean,
    ): List<Node> =
        (0 until depth).map { i ->
            Fixtures.node(
                id = "n$i",
                output = if (i == 0) NodeOutput.Caller else NodeOutput.Tempdb("t$i"),
                dependsOn =
                    when {
                        i < depth - 1 -> listOf("n${i + 1}")
                        cycleAtEnd -> listOf("n${depth - 2}")
                        else -> emptyList()
                    },
            )
        }

    /**
     * Runs [body] on a thread with a deliberately small stack and rethrows whatever it threw.
     *
     * This is what turns "did not crash on my machine" into an assertion: the budget is fixed
     * by the test, not by the runner's `-Xss`, so a future re-introduction of recursion fails
     * here on every machine rather than only on the ones with small stacks.
     */
    private fun onSmallStack(body: () -> ValidationResult): ValidationResult {
        var result: Result<ValidationResult>? = null
        val thread = Thread(null, { result = runCatching(body) }, "dag-depth-probe", PROBE_STACK_BYTES)
        thread.start()
        thread.join()
        return checkNotNull(result) { "probe thread produced no result" }.getOrThrow()
    }

    @Test
    fun `more than the node cap is rejected`() {
        val nodes = (0..DagRules.MAX_NODES).map { Fixtures.node(id = "n$it", output = NodeOutput.Tempdb("t$it")) }

        val failure =
            validator.validate(Fixtures.pipeline(nodes = nodes)).withCode(Validation.PIPELINE_TOO_LARGE).single()

        failure.details["max"] shouldBe DagRules.MAX_NODES
        failure.details["nodes"] shouldBe DagRules.MAX_NODES + 1
    }

    @Test
    fun `exactly the node cap is accepted`() {
        val nodes =
            (0 until DagRules.MAX_NODES).map {
                Fixtures.node(id = "n$it", output = if (it == 0) NodeOutput.Caller else NodeOutput.Tempdb("t$it"))
            }

        validator.validate(Fixtures.pipeline(nodes = nodes)).codes shouldNotContain Validation.PIPELINE_TOO_LARGE
    }

    @Test
    fun `a dangling dependency does not also manufacture a cycle`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a", dependsOn = listOf("ghost"))))

        validator.validate(pipeline).codes shouldNotContain Validation.CYCLE_DETECTED
    }

    @Test
    fun `two nodes resolving to caller are rejected`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        // Both resolve to caller. In a real payload the second one usually gets
                        // there by OMITTING its output block — that path is proved end to end by
                        // `PipelineDeserializerTest`, and by the time the validator runs the two
                        // are indistinguishable, which is the point of resolving D1's default at
                        // deserialization.
                        Fixtures.node(id = "a", output = NodeOutput.Caller),
                        Fixtures.node(id = "b", output = NodeOutput.Caller),
                    ),
            )

        val failure = validator.validate(pipeline).withCode(Validation.MULTIPLE_CALLER_NODES).single()

        @Suppress("UNCHECKED_CAST")
        (failure.details["nodes"] as List<String>) shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `zero caller nodes is legal`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "a", output = NodeOutput.Tempdb("stg_orders")),
                        Fixtures.node(
                            id = "b",
                            output = NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.REPLACE),
                            dependsOn = listOf("a"),
                        ),
                    ),
            )

        validator.validate(pipeline).failures shouldContainExactly emptyList()
    }

    private companion object {
        /** Deep enough to matter, small enough to stay fast. See the deep-chain test's comment. */
        const val DEEP = 5000

        /**
         * 256 KB — measured to overflow the pre-fix recursive traversal at every depth tried
         * (1000/2000/5000), and comfortably below Tomcat's 512 KB request-thread default.
         */
        const val PROBE_STACK_BYTES = 256L * 1024
    }
}
