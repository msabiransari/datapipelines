package co.datapipelines.parameters

import co.datapipelines.parameters.EvaluatorFixtures.attempt
import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.parameters.EvaluatorFixtures.version
import co.datapipelines.parameters.ParameterEvaluationEvent.Ended
import co.datapipelines.parameters.ParameterEvaluationEvent.ParameterAdmitted
import co.datapipelines.parameters.ParameterEvaluationEvent.ParameterFailed
import co.datapipelines.parameters.ParameterEvaluationEvent.ParameterResolved
import co.datapipelines.parameters.ParameterEvaluationEvent.ParameterRunning
import co.datapipelines.parameters.ParameterEvaluationEvent.ParameterWaiting
import co.datapipelines.parameters.ParameterEvaluationEvent.Started
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The observed evaluation's engine half (#375; the parameter-set workspace spec §4.2/§4.3, decisions D1–D3):
 * the exact event sequence per parameter kind, one terminal event per parameter, `Started` first and `Ended`
 * exactly once and last, the saturated pool, a parent's failure, the deadline (no terminal for the held
 * parameter), the caller's cancellation (`ABORTED`, the statement abandoned once), and the ordinary evaluate's
 * zero cost — every assertion an EXACT list, so a missing or an extra event cannot pass.
 */
class ParameterEvaluationObserverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val released = AtomicBoolean(false)
    private val clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC)

    @AfterEach
    fun stop() {
        released.set(true)
        scope.cancel()
    }

    /** Every event, in delivery order, from whatever thread delivered it. */
    private class Recording : ParameterEvaluationObserver {
        val events: MutableList<ParameterEvaluationEvent> = CopyOnWriteArrayList()

        override fun on(event: ParameterEvaluationEvent) {
            events += event
        }

        fun of(parameter: String): List<String> =
            events.mapNotNull { event ->
                when (event) {
                    is ParameterWaiting -> "waiting".takeIf { event.parameter == parameter }
                    is ParameterAdmitted -> "admitted".takeIf { event.parameter == parameter }
                    is ParameterRunning -> "running".takeIf { event.parameter == parameter }
                    is ParameterResolved -> "resolved".takeIf { event.parameter == parameter }
                    is ParameterFailed -> "failed".takeIf { event.parameter == parameter }
                    is Started, is Ended -> null
                }
            }

        fun terminals(): List<String> =
            events.mapNotNull {
                when (it) {
                    is ParameterResolved -> it.parameter
                    is ParameterFailed -> it.parameter
                    else -> null
                }
            }
    }

    private fun evaluator(
        selectors: SelectorTasks,
        pool: SelectorPool = SelectorPool(4, 64),
        config: ParametersConfig = ParametersConfig(),
    ) = ParameterEvaluator(selectors, pool, config, OrgContext.DEFAULTS, clock)

    private val hidden =
        """
        { "name": "note", "label": "Note", "type": "STRING", "kind": "INPUT", "depends_on": ["country"],
          "hidden_expression": { "op": "not", "arg": { "op": "is_null", "arg": { "ref": "country" } } } }
        """.trimIndent()

    private val disabled =
        """
        { "name": "channel", "label": "Channel", "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE",
          "source": { "constants": [ { "value": "web", "display_value": "Web", "is_default": true } ] },
          "depends_on": ["country"],
          "disabled_expression": { "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } } }
        """.trimIndent()

    private val everyKind =
        version(
            ParameterSetFixtures.countryJson(),
            templateSelect("state", dependsOn = listOf("country")),
            EvaluatorFixtures.startDate(),
            ParameterSetFixtures.amountJson(),
            hidden,
            disabled,
        )

    private fun everyKindSelectors() =
        ScriptedSelectors().apply {
            this["acme/sales/state.sql"] = { ScriptedSelectors.options("NY", "NJ") }
            this["acme/sales/start_date.sql"] = { ScriptedSelectors.inputRows(LogicalType.DATE, java.time.LocalDate.parse("2026-01-01")) }
        }

    @Test
    fun `every parameter kind reports its exact sequence - Started first, Ended last, one terminal each`() {
        val recording = Recording()

        val response =
            runBlocking {
                evaluator(
                    everyKindSelectors(),
                ).evaluate(EvaluatorFixtures.WORKSPACE, everyKind, emptyMap(), attempt(), recording)
            }

        recording.events.first() shouldBe Started(everyKind.body.parameters.map { it.name }, Instant.parse("2026-10-02T00:00:30Z"))
        recording.events.last() shouldBe Ended(EvaluationOutcome.COMPLETED, null, response)
        recording.of("country") shouldContainExactly listOf("resolved")
        recording.of("state") shouldContainExactly listOf("waiting", "admitted", "running", "resolved")
        recording.of("start_date") shouldContainExactly listOf("waiting", "admitted", "running", "resolved")
        recording.of("min_order_amount") shouldContainExactly listOf("resolved")
        recording.of("note") shouldContainExactly listOf("waiting", "resolved")
        recording.of("channel") shouldContainExactly listOf("waiting", "resolved")
        withClue("the coverage rule: every parameter, hidden and disabled included, exactly one terminal event") {
            recording.terminals().sorted() shouldContainExactly
                everyKind.body.parameters
                    .map { it.name }
                    .sorted()
        }
        recording.events.filterIsInstance<Started>() shouldHaveSize 1
        recording.events.filterIsInstance<Ended>() shouldHaveSize 1
    }

    @Test
    fun `the events carry identity and progress - the pin, the datasource, the origin, the reset and the query's row count`() {
        val recording = Recording()

        runBlocking {
            evaluator(everyKindSelectors()).evaluate(
                EvaluatorFixtures.WORKSPACE,
                everyKind,
                EvaluatorFixtures.selections("state" to "ZZ"),
                attempt(),
                recording,
            )
        }

        recording.events
            .filterIsInstance<ParameterWaiting>()
            .single { it.parameter == "state" }
            .waitingOn shouldContainExactly
            listOf("country")
        recording.events.filterIsInstance<ParameterRunning>().single { it.parameter == "state" } shouldBe
            ParameterRunning("state", "warehouse", TemplateRef("acme/sales/state.sql", 1))
        val resolved = recording.events.filterIsInstance<ParameterResolved>().associateBy { it.parameter }
        resolved.getValue("state") shouldBe ParameterResolved("state", "first", reset = true, rows = 2)
        withClue("rows only where a query produced options: a constants select and a sourced input carry none") {
            resolved.getValue("country") shouldBe ParameterResolved("country", "default", reset = false, rows = null)
            resolved.getValue("start_date") shouldBe ParameterResolved("start_date", "source", reset = false, rows = null)
        }
    }

    @Test
    fun `a saturated pool is the parameter's failed event with selectors_saturated - never an admitted one`() {
        val pool = SelectorPool(size = 1, waiting = 0)
        val holding = AtomicInteger()
        val occupant =
            scope.async {
                pool.run(
                    SelectorLabel("x", "y", "z"),
                    object : SelectorTask {
                        override fun run(): SelectorRun {
                            holding.incrementAndGet()
                            while (!released.get()) Thread.onSpinWait()
                            return ScriptedSelectors.options("X")
                        }

                        override fun abandon() = Unit
                    },
                )
            }
        waitUntil { holding.get() == 1 }
        val recording = Recording()

        runBlocking {
            evaluator(
                ScriptedSelectors(),
                pool,
            ).evaluate(EvaluatorFixtures.WORKSPACE, version(templateSelect("a")), emptyMap(), attempt(), recording)
        }

        recording.of("a") shouldContainExactly listOf("failed")
        recording.events.filterIsInstance<ParameterFailed>().single() shouldBe
            ParameterFailed("a", ParameterErrorCodes.EVALUATE_SELECTORS_SATURATED, null)
        released.set(true)
        runBlocking { withTimeout(COMPLETES_MS) { occupant.await() } }
    }

    @Test
    fun `a parent's failure is its failed event and the child resolves against null`() {
        val selectors =
            ScriptedSelectors().apply {
                this["acme/sales/a.sql"] =
                    { SelectorRun.Failed("pipeline.node.query_execution_failed", "driver text", mapOf("reason" to "read_only_gate")) }
                this["acme/sales/b.sql"] =
                    { request -> if (request.binds["a"] == null) ScriptedSelectors.options("NULL-FED") else ScriptedSelectors.options() }
            }
        val recording = Recording()

        runBlocking {
            evaluator(selectors).evaluate(
                EvaluatorFixtures.WORKSPACE,
                version(templateSelect("a"), templateSelect("b", dependsOn = listOf("a"))),
                emptyMap(),
                attempt(),
                recording,
            )
        }

        recording.of("a") shouldContainExactly listOf("admitted", "running", "failed")
        withClue("the detail is the code's own reason word, never the driver's text") {
            recording.events.filterIsInstance<ParameterFailed>().single() shouldBe
                ParameterFailed("a", "pipeline.node.query_execution_failed", "read_only_gate")
        }
        recording.of("b") shouldContainExactly listOf("waiting", "admitted", "running", "resolved")
        recording.events
            .last()
            .shouldBeInstanceOf<Ended>()
            .outcome shouldBe EvaluationOutcome.COMPLETED
    }

    @Test
    fun `the deadline ends the evaluation TIMEOUT with its code - and the held parameter gets no terminal event`() {
        val started = AtomicInteger()
        val selectors = ScriptedSelectors().apply { this["acme/sales/a.sql"] = spinning(started) }
        val oneSecond = ParametersConfig(evaluateTimeoutSeconds = 1, selectorQueryTimeoutSeconds = 1)
        val recording = Recording()

        val failure =
            runCatching {
                runBlocking {
                    evaluator(
                        selectors,
                        config = oneSecond,
                    ).evaluate(EvaluatorFixtures.WORKSPACE, version(templateSelect("a")), emptyMap(), attempt(), recording)
                }
            }.exceptionOrNull()

        failure.shouldBeInstanceOf<DatapipelinesException>().code shouldBe ParameterErrorCodes.EVALUATE_TIMEOUT
        recording.of("a") shouldContainExactly listOf("admitted", "running")
        recording.events.last() shouldBe Ended(EvaluationOutcome.TIMEOUT, ParameterErrorCodes.EVALUATE_TIMEOUT, null)
    }

    @Test
    fun `the caller's cancellation ends the evaluation ABORTED - the statement abandoned exactly once and no timeout claimed`() {
        val abandons = AtomicInteger()
        val running = AtomicInteger()
        val selectors = AbandonCounting(running, abandons)
        val recording = Recording()
        val begun = System.nanoTime()

        val job =
            scope.async {
                evaluator(
                    selectors,
                ).evaluate(EvaluatorFixtures.WORKSPACE, version(templateSelect("a")), emptyMap(), attempt(), recording)
            }
        waitUntil { running.get() == 1 }
        job.cancel()
        val outcome = runBlocking { withTimeout(COMPLETES_MS) { runCatching { job.await() } } }

        outcome.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
        recording.events.last() shouldBe Ended(EvaluationOutcome.ABORTED, null, null)
        recording.events.filterIsInstance<Ended>() shouldHaveSize 1
        waitUntil { abandons.get() == 1 }
        abandons.get() shouldBe 1
        withClue("the evaluate deadline is 30 s — the abort, not the deadline, ended it") {
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) shouldBeLessThan ABORTED_WITHIN_MS
        }
    }

    @Test
    fun `a response over the budget ends the evaluation FAILED with its catalogued code`() {
        val selectors =
            ScriptedSelectors().apply {
                // 150 options of 1,000-character values (a display value is at most 256): ~150 KB against a 64 KiB budget.
                this["acme/sales/a.sql"] = {
                    SelectorRun.Rows(ScriptedSelectors.SELECT_COLUMNS, (1..150).map { listOf("x".repeat(1_000) + it, "option $it", false) })
                }
            }
        val recording = Recording()

        val failure =
            runCatching {
                runBlocking {
                    evaluator(selectors, config = ParametersConfig(maxEvaluateResponseBytes = 65_536))
                        .evaluate(EvaluatorFixtures.WORKSPACE, version(templateSelect("a")), emptyMap(), attempt(), recording)
                }
            }.exceptionOrNull()

        failure.shouldBeInstanceOf<DatapipelinesException>().code shouldBe ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE
        recording.events.last() shouldBe Ended(EvaluationOutcome.FAILED, ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE, null)
    }

    @Test
    fun `an ordinary evaluate hands the pool the bare task and no callback - an observed one wraps it and passes one`() {
        val produced = Collections.synchronizedList(mutableListOf<SelectorTask>())
        val selectors = Producing(ScriptedSelectors(), produced)
        val pool = spyk(SelectorPool(4, 64))
        val set = version(templateSelect("a"))

        evaluator(selectors, pool).evaluateBlocking(EvaluatorFixtures.WORKSPACE, set, emptyMap(), attempt())

        coVerify(exactly = 1) { pool.run(any(), match { it === produced.single() }, null) }
        coVerify(exactly = 0) { pool.run(any(), any(), isNull(inverse = true)) }

        // Non-vacuity: the double does see the difference when an observer IS attached.
        runBlocking { evaluator(selectors, pool).evaluate(EvaluatorFixtures.WORKSPACE, set, emptyMap(), attempt(), Recording()) }
        coVerify(exactly = 1) { pool.run(any(), match { it !== produced.last() }, isNull(inverse = true)) }
    }

    @Test
    fun `an observer that throws is detached - the evaluation completes and is never the observer's casualty`() {
        val calls = AtomicInteger()
        val throwing = ParameterEvaluationObserver { calls.incrementAndGet().also { error("the stream broke") } }

        val response =
            runBlocking {
                evaluator(
                    everyKindSelectors(),
                ).evaluate(EvaluatorFixtures.WORKSPACE, everyKind, emptyMap(), attempt(), throwing)
            }

        response.valid shouldBe true
        calls.get() shouldBe 1
    }

    @Test
    fun `nothing is delivered after Ended - a worker's late running event is dropped`() {
        val recording = Recording()
        val delivery = ObservedEvaluation(recording)
        val ended = Ended(EvaluationOutcome.ABORTED, null, null)

        delivery.emit(ended)
        delivery.emit(ParameterRunning("a", "warehouse", TemplateRef("t", 1)))
        delivery.emit(Ended(EvaluationOutcome.COMPLETED, null, null))

        recording.events.single() shouldBeSameInstanceAs ended
    }

    /** A script that spins until the test ends — it reads no interrupt (the stand-in models "never returns"). */
    private fun spinning(started: AtomicInteger): (SelectorRequest) -> SelectorRun =
        {
            started.incrementAndGet()
            while (!released.get()) Thread.onSpinWait()
            ScriptedSelectors.options("X")
        }

    /** One spinning task per request that counts its own abandons — the property the abort must reach. */
    private inner class AbandonCounting(
        private val running: AtomicInteger,
        private val abandons: AtomicInteger,
    ) : SelectorTasks {
        override fun resolver(workspaceId: UUID) = DatasourceResolver { null }

        override fun task(
            request: SelectorRequest,
            resolver: DatasourceResolver,
        ): SelectorTask =
            object : SelectorTask {
                override fun run(): SelectorRun {
                    running.incrementAndGet()
                    while (!released.get()) Thread.onSpinWait()
                    return ScriptedSelectors.options("X")
                }

                override fun abandon() {
                    abandons.incrementAndGet()
                }
            }
    }

    /** Records every task it produces, so the pool's argument can be compared by identity. */
    private class Producing(
        private val inner: SelectorTasks,
        private val produced: MutableList<SelectorTask>,
    ) : SelectorTasks by inner {
        override fun task(
            request: SelectorRequest,
            resolver: DatasourceResolver,
        ): SelectorTask = inner.task(request, resolver).also { produced += it }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMPLETES_MS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val COMPLETES_MS = 10_000L
        const val ABORTED_WITHIN_MS = 5_000L
        const val POLL_MS = 10L
    }
}
