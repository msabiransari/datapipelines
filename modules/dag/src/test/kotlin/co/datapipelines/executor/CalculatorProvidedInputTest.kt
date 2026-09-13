package co.datapipelines.executor

import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.TemplateRef
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 078 A5 (owner ruling 2026-09-05): a calculator `context_key` is an implicit OPTIONAL execute
 * input. Supplied → the node is SKIPPED and the supplied value flows downstream, marked
 * `provided_by: "caller"` in the node's stats; unsupplied → the node runs and computes it.
 *
 * The sibling [CalculatorNodeExecutionTest] proves the compute half; this one proves both halves
 * of the ruling on the SAME pipeline: one run with the key in the request's `parameters`, one
 * without. H2 tempdb only — the claim is about the Context and the stats, not an engine.
 */
class CalculatorProvidedInputTest {
    private val org =
        OrgContext.of(
            currencyName = "Pound",
            currencySymbol = "£",
            fiscalStartDate = "09-15",
            weekStart = "monday",
            timezone = "UTC",
        )

    @Test
    fun `a caller-supplied calculator key skips the node and the supplied value binds downstream`() =
        runBlocking<Unit> {
            val result = execute(inputs = mapOf("run_fiscal_quarter" to number(SUPPLIED.toInt())))

            result.status shouldBe ExecutionStatus.SUCCESS

            // The computed value would be expectedQuarter() — anything else proves the value on
            // the row travelled from the request, not from the kind's evaluation.
            val ready = harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.DataReady>().single()
            ready.rows
                .single()
                .single()
                .toString() shouldBe SUPPLIED

            val stats = result.nodeStats.single { it.nodeId == "fiscal_q" }
            stats.status shouldBe NodeStatus.SUCCESS
            stats.contextKey shouldBe "run_fiscal_quarter"
            stats.contextValue shouldBe SUPPLIED
            stats.providedBy shouldBe "caller"
        }

    @Test
    fun `the node_completed event and the persisted node_stats_json both carry provided_by`() =
        runBlocking<Unit> {
            val result = execute(inputs = mapOf("run_fiscal_quarter" to number(SUPPLIED.toInt())))

            // The SSE half: node_completed carries the same projection the run detail page reads.
            val completed =
                harnessEmitter!!
                    .events
                    .filterIsInstance<co.datapipelines.events.NodeCompleted>()
                    .single { it.nodeId == "fiscal_q" }
            completed.stats.providedBy shouldBe "caller"

            // The persistence half: node_stats_json is ExecutorJson over List<NodeStats>
            // (WebEventEmitter writes exactly this), so what this string holds is what the run
            // detail page renders from.
            ExecutorJson.write(result.nodeStats).shouldContain("\"provided_by\":\"caller\"")
        }

    @Test
    fun `the same pipeline without the input computes the value itself`() =
        runBlocking<Unit> {
            val result = execute()

            result.status shouldBe ExecutionStatus.SUCCESS

            val ready = harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.DataReady>().single()
            ready.rows
                .single()
                .single()
                .toString() shouldBe expectedQuarter().toString()

            val stats = result.nodeStats.single { it.nodeId == "fiscal_q" }
            stats.contextValue shouldBe expectedQuarter().toString()
            // A calculator that RAN has no provenance marker — and writes no nulls into the JSON.
            stats.providedBy.shouldBeNull()
            ExecutorJson.write(result.nodeStats).shouldNotContain("provided_by")
        }

    @Test
    fun `a JSON null on the calculator key reads as unsupplied - the node runs`() =
        runBlocking<Unit> {
            val result = execute(inputs = mapOf("run_fiscal_quarter" to JsonNodeFactory.instance.nullNode()))

            result.nodeStats
                .single { it.nodeId == "fiscal_q" }
                .providedBy
                .shouldBeNull()
            result.nodeStats.single { it.nodeId == "fiscal_q" }.contextValue shouldBe expectedQuarter().toString()
        }

    // ------------------------------------------------------------------ fixture

    // ---- 121 D5: a multi-output node's keys override all-or-nothing ----

    @Test
    fun `every key of a multi-output node supplied skips it - the supplied values bind downstream`() =
        runBlocking<Unit> {
            val result = executeMulti(inputs = mapOf("window_start" to text(SUPPLIED_START), "window_end" to text(SUPPLIED_END)))

            result.status shouldBe ExecutionStatus.SUCCESS

            // The computed window would be 2026-08-01 … 2026-08-31 — anything else proves the
            // values on the row travelled from the request, not from the kind's evaluation.
            val ready = harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.DataReady>().single()
            ready.rows
                .single()
                .map { it.toString() } shouldBe listOf(SUPPLIED_START, SUPPLIED_END)

            val stats = result.nodeStats.single { it.nodeId == "window" }
            stats.status shouldBe NodeStatus.SUCCESS
            stats.contextValues shouldBe mapOf("window_start" to SUPPLIED_START, "window_end" to SUPPLIED_END)
            stats.providedBy shouldBe "caller"
        }

    @Test
    fun `no key supplied and the multi node computes both`() =
        runBlocking<Unit> {
            val result = executeMulti()

            result.nodeStats
                .single { it.nodeId == "window" }
                .providedBy
                .shouldBeNull()
            result.nodeStats.single { it.nodeId == "window" }.contextValues shouldBe
                mapOf("window_start" to "2026-08-01", "window_end" to "2026-08-31")
        }

    @Test
    fun `a proper subset is refused before any node runs - the run record shows none started`() =
        runBlocking<Unit> {
            val thrown =
                io.kotest.assertions.throwables
                    .shouldThrow<co.datapipelines.pipeline.PipelineValidationException> {
                        executeMulti(inputs = mapOf("window_end" to text(SUPPLIED_END)))
                    }

            thrown.code shouldBe "pipeline.execution.calculator_keys_partial"
            val failure = thrown.result.failures.single()
            failure.code shouldBe "pipeline.execution.calculator_keys_partial"
            failure.details["node"] shouldBe "window"
            failure.details["supplied"] shouldBe listOf("window_end")
            failure.details["missing"] shouldBe listOf("window_start")

            // The evidence is the run record, not a mock: NO node started — and not even the
            // execution's opening event was emitted, because the refusal rides the same bind
            // rejection as an ill-typed parameter, before the stream opens.
            harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.NodeStarted>().shouldBeEmpty()
            harnessEmitter!!.events.filterIsInstance<co.datapipelines.events.ExecutionStarted>().shouldBeEmpty()
        }

    private suspend fun executeMulti(inputs: Map<String, JsonNode> = emptyMap()): ExecutionResult {
        val engine = Fixtures.templateEngine(mapOf(TEMPLATE_ID to "SELECT :window_start AS s, :window_end AS e"))
        val harness =
            ExecutorHarness(
                templateEngine = engine,
                config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60, orgContext = org),
                calculatorKinds = WindowKindFixture.lookup,
            )
        harnessEmitter = harness.emitter
        return harness.use {
            it.executor.execute(
                Fixtures
                    .request(
                        Fixtures.pipeline(
                            nodes =
                                listOf(
                                    windowNode(),
                                    Fixtures.node(
                                        "report",
                                        source = "tempdb",
                                        output = NodeOutput.Caller,
                                        dependsOn = listOf("window"),
                                    ),
                                ),
                        ),
                    ).copy(executionId = UUID.randomUUID(), parameters = inputs),
            )
        }
    }

    private fun windowNode(): Node =
        Node(
            id = "window",
            description = "the run's window",
            type = NodeType.CALCULATOR,
            source = "",
            template = TemplateRef(),
            output = null,
            dependsOn = emptyList(),
            kind = WindowKindFixture.kind,
            inputs = mapOf("date" to text(AS_OF.toString())),
            contextKey = null,
            contextKeys = mapOf("start" to "window_start", "end" to "window_end"),
        )

    private fun expectedQuarter(): Int =
        co.datapipelines.calculators.CalculatorRegistry
            .require("fiscal_quarter")
            .evaluate(mapOf("date" to AS_OF, "fiscal_start" to "09-15")) as Int

    private var harnessEmitter: RecordingEmitter? = null

    private suspend fun execute(inputs: Map<String, JsonNode> = emptyMap()): ExecutionResult {
        val engine = Fixtures.templateEngine(mapOf(TEMPLATE_ID to "SELECT :run_fiscal_quarter AS q"))
        val harness =
            ExecutorHarness(
                templateEngine = engine,
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
                                    calculatorNode(),
                                    Fixtures.node(
                                        "report",
                                        source = "tempdb",
                                        output = NodeOutput.Caller,
                                        dependsOn = listOf("fiscal_q"),
                                    ),
                                ),
                        ),
                    ).copy(executionId = UUID.randomUUID(), parameters = inputs),
            )
        }
    }

    private fun calculatorNode(): Node =
        Node(
            id = "fiscal_q",
            description = "the run's fiscal quarter",
            type = NodeType.CALCULATOR,
            source = "",
            template = TemplateRef(),
            output = null,
            dependsOn = emptyList(),
            kind = "fiscal_quarter",
            inputs = mapOf("date" to text(AS_OF.toString()), "fiscal_start" to ref(OrgContext.FISCAL_START_DATE)),
            contextKey = "run_fiscal_quarter",
        )

    private fun ref(key: String): JsonNode = JsonNodeFactory.instance.textNode("$$key")

    private fun text(value: String): JsonNode = JsonNodeFactory.instance.textNode(value)

    /** INTEGER is number-on-wire (§6.3) — a supplied calculator key obeys the same coercion. */
    private fun number(value: Int): JsonNode = JsonNodeFactory.instance.numberNode(value)

    private companion object {
        const val TEMPLATE_ID = "report"

        /** Distinct from the computed quarter (4), so the two halves cannot pass on each other's value. */
        const val SUPPLIED = "7"

        /** The supplied window — distinct from the computed 2026-08-01 … 2026-08-31, same reason. */
        const val SUPPLIED_START = "2026-01-05"
        const val SUPPLIED_END = "2026-02-20"

        /** Fixed, never "today": a test whose expectation depends on the day it runs gets deleted. */
        val AS_OF: java.time.LocalDate = java.time.LocalDate.of(2026, 8, 14)
    }
}
