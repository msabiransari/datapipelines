package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TempdbSettings
import com.fasterxml.jackson.databind.node.IntNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The per-execution tempdb memory budget (B2, D6, staging §8.2) — clamping, and enforcement on
 * **both** write paths.
 */
class StagingBudgetTest {
    /**
     * B2: a pipeline's `settings.tempdb.config.max_memory_mb` may only ever **lower** the operator's
     * global ceiling.
     *
     * Save-time validation only checks `> 0`, and on the `withConnection` paths
     * `ctx.stagingMaxMemoryMb` is the *only* ceiling — staging never sees those writes. So an author
     * declaring `max_memory_mb: 1_000_000` disabled `checkStagingBudget` outright, and one
     * `CREATE TABLE AS SELECT` over a generated range then filled the heap: a whole-instance OOM,
     * taking down every other tenant's executions with it, from a pipeline field anyone can set.
     *
     * Reading D6's override as "may also raise the operator's limit" is not a reading any
     * deployment could safely run, so the clamp is the safe direction.
     */
    @Test
    fun `a pipeline budget above the operator global is clamped down to the global`() =
        runBlocking<Unit> {
            val budgets = runWithBudgets(pipelineMaxMemoryMb = HUGE_MB, globalMaxMemoryMb = SMALL_MB)

            budgets.effective shouldBe SMALL_MB
            // The render budget is derived from the same effective value, so it is clamped too —
            // otherwise the author would have raised the render ceiling by the same trick.
            budgets.renderChars shouldBe ExecutorConfig(stagingMaxMemoryMb = SMALL_MB).renderOutputBudgetChars(SMALL_MB)
        }

    @Test
    fun `a pipeline budget below the global is honoured`() =
        runBlocking<Unit> {
            val budgets = runWithBudgets(pipelineMaxMemoryMb = SMALL_MB.toInt(), globalMaxMemoryMb = LARGE_MB)

            budgets.effective shouldBe SMALL_MB
        }

    @Test
    fun `no pipeline override falls back to the operator global`() =
        runBlocking<Unit> {
            val budgets = runWithBudgets(pipelineMaxMemoryMb = null, globalMaxMemoryMb = LARGE_MB)

            budgets.effective shouldBe LARGE_MB
        }

    /**
     * B2 (second half) + F13: the effective budget is enforced on the `stage()` path too.
     *
     * `StagingFactory.create(executionId, engine)` takes no budget, so staging enforces the budget
     * it was **constructed** with — the operator global. A *lower* per-pipeline override therefore
     * never reached it. The executor re-checks the measured footprint after every staged write,
     * which closes that without any staging signature change.
     *
     * A 1 MB budget with a real staged table is over it immediately, so this exercises the check
     * rather than merely the arithmetic.
     */
    @Test
    fun `an execution over its own lower budget fails with memory_limit_exceeded on the stage path`() =
        runBlocking<Unit> {
            val ddl =
                listOf(
                    "CREATE TABLE b (n INT, pad VARCHAR(400))",
                    """INSERT INTO b SELECT "X", RPAD('x', 400, 'x') FROM SYSTEM_RANGE(1, 20000)""",
                )
            val source = h2Datasource("budget_src", ddl)
            val nodes = listOf(Fixtures.node("stage", source = "budget_src", output = NodeOutput.Tempdb("staged")))

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("stage" to "SELECT n, pad FROM b")),
                registry = FakeDatasourceRegistry(mapOf("budget_src" to source)),
                config = ExecutorConfig(stagingMaxMemoryMb = 1),
            ).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }.errorCode shouldBe PipelineErrorCodes.Staging.MEMORY_LIMIT_EXCEEDED
            }
        }

    @Test
    fun `the same budget bounds the withConnection path`() =
        runBlocking<Unit> {
            // `CREATE TABLE … AS SELECT` never goes through `stage()`, so before B2 the pipeline
            // override was the only ceiling here — and an author-supplied one at that.
            val nodes =
                listOf(
                    Fixtures.node("seed", type = co.datapipelines.pipeline.NodeType.DDL),
                    Fixtures.node("fill", type = co.datapipelines.pipeline.NodeType.DML, dependsOn = listOf("seed")),
                    Fixtures.node("grow", output = NodeOutput.Tempdb("grown"), dependsOn = listOf("fill")),
                )
            val sql =
                mapOf(
                    "seed" to """CREATE TABLE "raw" (n INT, pad VARCHAR(400))""",
                    "fill" to """INSERT INTO "raw" SELECT "X", RPAD('x', 400, 'x') FROM SYSTEM_RANGE(1, 20000)""",
                    "grow" to """SELECT n, pad FROM "raw"""",
                )

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                config = ExecutorConfig(stagingMaxMemoryMb = 1),
            ).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }.errorCode shouldBe PipelineErrorCodes.Staging.MEMORY_LIMIT_EXCEEDED
            }
        }

    // ------------------------------------------------------------------ helpers

    private data class Budgets(
        val effective: Long,
        val renderChars: Long,
    )

    /**
     * Runs one trivial node and reads back the budgets the executor resolved for it.
     *
     * The render budget is captured from the engine (which records the third argument it was
     * given), and the staging budget is asserted through it — the two are derived from the same
     * effective value, so one observation pins both.
     */
    private suspend fun runWithBudgets(
        pipelineMaxMemoryMb: Int?,
        globalMaxMemoryMb: Long,
    ): Budgets {
        val (engine, renderBudgets) = Fixtures.templateEngine("SELECT 1 AS n")
        val settings =
            PipelineSettings(
                tempdb =
                    TempdbSettings(
                        config = pipelineMaxMemoryMb?.let { mapOf(TempdbSettings.MAX_MEMORY_MB_KEY to IntNode(it)) } ?: emptyMap(),
                    ),
            )
        val config = ExecutorConfig(stagingMaxMemoryMb = globalMaxMemoryMb)

        ExecutorHarness(templateEngine = engine, config = config).use { h ->
            h.executor.execute(
                Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("only")), settings = settings)),
            )
        }
        val renderChars = renderBudgets.single()
        // renderOutputBudgetChars is monotonic in the budget below the engine backstop, so the
        // effective MB is recoverable from it — and asserting through the real derivation is
        // stronger than reading a field back.
        val effective =
            listOf(SMALL_MB, LARGE_MB, HUGE_MB.toLong(), globalMaxMemoryMb).distinct().single { candidate ->
                config.renderOutputBudgetChars(candidate) == renderChars &&
                    candidate <= globalMaxMemoryMb
            }
        return Budgets(effective, renderChars)
    }

    private companion object {
        const val SMALL_MB = 4L
        const val LARGE_MB = 64L

        /** Far past anything an operator would allow — the shape that disabled the ceiling. */
        const val HUGE_MB = 1_000_000
    }
}
