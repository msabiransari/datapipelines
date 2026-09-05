package co.datapipelines.executor

import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.TemplateRef
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A CALCULATOR node end to end over real staging: evaluate → write the Context key → a DQL node
 * downstream BINDS it and the bound value is the computed one.
 *
 * This is the test the round exists to pass. Everything above it — the live [RunContext], the
 * validator's ordering rule, the executor's dispatch — is machinery whose only observable purpose
 * is that `:run_fiscal_quarter` in a SQL node resolves to a number a calculator worked out
 * moments earlier, in the same execution, with nothing passed between the two nodes but the DAG.
 *
 * H2 tempdb only: no Postgres, no container. The claim under test is about the Context, and a
 * `SELECT :run_fiscal_quarter AS q` proves it on any engine.
 */
class CalculatorNodeExecutionTest {
    private val org =
        OrgContext.of(
            currencyName = "Pound",
            currencySymbol = "£",
            // A fiscal year starting 09-15 makes the computed answer differ from the calendar
            // quarter, so a test that accidentally read the wrong thing cannot pass.
            fiscalStartDate = "09-15",
            weekStart = "monday",
            timezone = "UTC",
        )

    @Test
    fun `a calculator's value reaches a downstream SQL node's bind parameter`() =
        runBlocking<Unit> {
            val result = execute()

            result.status shouldBe ExecutionStatus.SUCCESS

            // 2026-08-14 with a 09-15 fiscal start is fiscal quarter 4 — the calendar quarter is 3,
            // so the number below could not have come from anywhere else.
            val expected = expectedQuarter()
            withClue("the sanity of the fixture itself") { expected shouldBe 4 }

            val ready = harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.DataReady>().single()
            ready.totalRows shouldBe 1
            ready.rows
                .single()
                .single()
                .toString() shouldBe expected.toString()
        }

    @Test
    fun `the calculator node reports its key and value in its own stats`() =
        runBlocking<Unit> {
            val stats = execute().nodeStats.single { it.nodeId == "fiscal_q" }

            stats.status shouldBe NodeStatus.SUCCESS
            // rows_out 0 is honest — a calculator produces no rows. What it DID produce is here,
            // which is what makes it visible on the run detail page and through executions_get.
            stats.rowsOut shouldBe 0
            stats.contextKey shouldBe "run_fiscal_quarter"
            stats.contextValue shouldBe expectedQuarter().toString()
        }

    @Test
    fun `the terminal event carries the fully resolved Context - every tier of it`() =
        runBlocking<Unit> {
            execute()
            val completed = harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.PipelineCompleted>().single()

            val snapshot = completed.contextSnapshot
            withClue("tier 1 — org config") { snapshot[OrgContext.FISCAL_START_DATE] shouldBe "09-15" }
            // Tier 2 is asserted by SHAPE, not by value: `current_date` is the real execution
            // date and pinning it would date the test. What matters is that it is there, typed,
            // and that `execution_id` is this run's.
            withClue("tier 2 — platform") {
                (snapshot[ContextKeys.CURRENT_DATE] is java.time.LocalDate) shouldBe true
                (snapshot[ContextKeys.CURRENT_TIMESTAMP] is java.time.Instant) shouldBe true
                snapshot[ContextKeys.EXECUTION_ID] shouldBe completed.executionId.toString()
            }
            withClue("tier 5 — the calculator's own output, which is the point") {
                snapshot["run_fiscal_quarter"] shouldBe expectedQuarter()
            }
        }

    @Test
    fun `a calculator that cannot evaluate fails its node with calculator_failed, naming the input`() =
        runBlocking<Unit> {
            // A literal the kind cannot read: `unit` is not one of the period units. Save-time
            // validation refuses this shape, so the only way to reach the runtime path is a body
            // that never went through it — which is exactly what this constructs.
            val failure =
                runCatching {
                    execute(
                        calculator =
                            calculatorNode(
                                kind = "period_start",
                                inputs = mapOf("date" to text(AS_OF.toString()), "unit" to text("fortnight")),
                            ),
                    )
                }.exceptionOrNull()

            val stats = harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.NodeFailed>().single()
            stats.error.code shouldBe "pipeline.node.calculator_failed"
            stats.error.message.shouldContain("period_start")
            stats.error.details["input"] shouldBe "unit"
            failure shouldBe failure // the throw itself is the executor's ordinary fail-fast
        }

    // ------------------------------------------------------------------ fixture

    private fun expectedQuarter(): Int =
        CalculatorRegistry
            .require("fiscal_quarter")
            .evaluate(mapOf("date" to AS_OF, "fiscal_start" to "09-15")) as Int

    private var harnessEmitter: RecordingEmitter? = null

    private suspend fun execute(calculator: Node = calculatorNode()): ExecutionResult {
        val engine = Fixtures.templateEngine(mapOf(TEMPLATE_ID to "SELECT :run_fiscal_quarter AS q"))
        val harness =
            ExecutorHarness(
                templateEngine = engine,
                // The org tier rides on the executor's resolved config, like every other
                // already-resolved setting (ExecutorConfig's KDoc).
                config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60, orgContext = org),
            )
        harnessEmitter = harness.emitter
        return harness.use {
            it.executor.execute(
                Fixtures
                    .request(
                        Fixtures.pipeline(
                            nodes =
                                listOf(
                                    calculator,
                                    Fixtures.node(
                                        "report",
                                        source = "tempdb",
                                        output = NodeOutput.Caller,
                                        dependsOn = listOf("fiscal_q"),
                                    ),
                                ),
                        ),
                    ).copy(executionId = UUID.randomUUID()),
            )
        }
    }

    /**
     * `date` is a LITERAL, deliberately. Referencing `$current_date` would make the expected
     * quarter depend on the day the suite runs — and it would pass anyway most weeks, which is
     * worse than failing: the first draft of this test did exactly that, and only the snapshot
     * assertion noticed. `fiscal_start` stays a reference, so the org tier is still exercised.
     */
    private fun calculatorNode(
        kind: String = "fiscal_quarter",
        inputs: Map<String, JsonNode> = mapOf("date" to text(AS_OF.toString()), "fiscal_start" to ref(OrgContext.FISCAL_START_DATE)),
    ): Node =
        Node(
            id = "fiscal_q",
            description = "the run's fiscal quarter",
            type = NodeType.CALCULATOR,
            source = "",
            template = TemplateRef(),
            output = null,
            dependsOn = emptyList(),
            kind = kind,
            inputs = inputs,
            contextKey = "run_fiscal_quarter",
        )

    private fun ref(key: String): JsonNode = JsonNodeFactory.instance.textNode("$$key")

    private fun text(value: String): JsonNode = JsonNodeFactory.instance.textNode(value)

    private companion object {
        const val TEMPLATE_ID = "report"

        /** Fixed, never "today": a test whose expectation depends on the day it runs gets deleted. */
        val AS_OF: java.time.LocalDate = java.time.LocalDate.of(2026, 8, 14)
    }
}
