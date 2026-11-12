package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.CompiledScript
import co.datapipelines.scripting.EngineCapabilities
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.scripting.ScriptPoolExhaustedException
import co.datapipelines.scripting.ScriptTimeoutException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * #770: `suite-timeout-seconds` is ONE hard budget D for the whole suite, taken before the
 * runner compiles. Every pool call is clipped to `min(evaluateTimeout, D - now)`; a clipped call
 * that times out is the suite failure (never a case's own `pipeline.script.timeout`
 * expectation); a case whose work finishes after D fails as suite even when it matched; every
 * later case fails with no pool call. The elapsed time is bounded (D + one clipped slot wait +
 * one grace), not strict.
 *
 * The contract, the body and the `empty input` case are `ScriptTestRunnerTest`'s record §2.2
 * example (its green `the record's section-2_2 example saves`). [RecordingEngine] delegates to a
 * real [JsonataEngine], so outputs, gates and invariants are real; it records each evaluation's
 * wall clock by kind and can move the fake clock or throw the engine timeout.
 *
 * Falsified at birth (store evidence `notes/evidence/770-script-suite-hard-budget/`): D after
 * the compiles reds T1; an unclipped body reds T2, T3, T5; skipping the suite mapping reds T4;
 * unclipped invariants red T6; dropping the invariant catch's suite detail reds T7 with T4 green;
 * removing the completion check reds T8 and T9.
 */
class ScriptSuiteBudgetTest {
    private val evaluateTimeout = Duration.ofSeconds(5)
    private val suiteTimeout = Duration.ofSeconds(1)
    private val clock = AtomicLong(0)

    private enum class Kind { BODY, INVARIANT }

    /** A real JSONata engine that records and steers each call; the body is told apart by its source. */
    private class RecordingEngine(
        private val bodySource: String,
    ) : ScriptEngine {
        private val delegate = JsonataEngine()
        override val type: ScriptLanguage get() = delegate.type
        override val capabilities: EngineCapabilities get() = delegate.capabilities

        @Volatile var onCompile: (Kind) -> Unit = {}

        @Volatile var onEvaluate: (Kind, EvaluationLimits) -> Unit = { _, _ -> }
        val wallClocks: Map<Kind, MutableList<Duration>> = Kind.entries.associateWith { CopyOnWriteArrayList() }

        private class Tagged(
            val inner: CompiledScript,
            val kind: Kind,
        ) : CompiledScript

        override fun compile(body: String): CompiledScript {
            val kind = if (body == bodySource) Kind.BODY else Kind.INVARIANT
            onCompile(kind)
            return Tagged(delegate.compile(body), kind)
        }

        override fun evaluate(
            script: CompiledScript,
            input: Any?,
            limits: EvaluationLimits,
        ): Any? {
            val tagged = script as Tagged
            wallClocks.getValue(tagged.kind) += limits.wallClock
            onEvaluate(tagged.kind, limits)
            return delegate.evaluate(tagged.inner, input, limits)
        }

        fun count(kind: Kind) = wallClocks.getValue(kind).size
    }

    private fun pool(size: Int = 4) = ScriptEvaluationPool(size, 64, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM)

    private fun runner(
        engine: RecordingEngine,
        pool: ScriptEvaluationPool = pool(),
        fakeClock: Boolean = true,
    ) = if (fakeClock) {
        ScriptTestRunner(
            engines = mapOf(ScriptLanguage.JSONATA to engine),
            pool = pool,
            evaluateTimeout = evaluateTimeout,
            suiteTimeout = suiteTimeout,
            nanoClock = clock::get,
        )
    } else {
        ScriptTestRunner(
            engines = mapOf(ScriptLanguage.JSONATA to engine),
            pool = pool,
            evaluateTimeout = evaluateTimeout,
            suiteTimeout = suiteTimeout,
        )
    }

    private fun advance(duration: Duration) {
        clock.addAndGet(duration.toNanos())
    }

    private val contract =
        ScriptContract(
            mode = ScriptMode.ROW,
            inputs =
                mapOf(
                    "orders" to
                        ScriptInput.Table(
                            listOf(
                                ContractColumn("order_id", LogicalType.INTEGER),
                                ContractColumn("amount_cents", LogicalType.INTEGER),
                                ContractColumn("customer_id", LogicalType.STRING, nullable = true),
                            ),
                        ),
                    "tz" to ScriptInput.Value(LogicalType.STRING),
                    "min_total" to ScriptInput.Value(LogicalType.DECIMAL, precision = 12, scale = 2),
                ),
            output =
                ScriptOutput.Table(
                    listOf(
                        ContractColumn("order_id", LogicalType.INTEGER),
                        ContractColumn("amount", LogicalType.DECIMAL, precision = 12, scale = 2),
                        ContractColumn("customer_id", LogicalType.STRING),
                    ),
                ),
            rejects = true,
        )

    private val body =
        """
        {
          "rows": [ rows[customer_id != null].{
            "order_id": order_id,
            "amount": amount_cents / 100,
            "customer_id": customer_id
          } ],
          "rejects": [ rows[customer_id = null].{
            "row": {
              "order_id": order_id,
              "amount_cents": amount_cents,
              "customer_id": customer_id
            },
            "reason": "customer_id missing"
          } ]
        }
        """.trimIndent()

    private fun case(
        name: String = "empty input",
        expect: ScriptTestExpect = ScriptTestExpect(output = ScriptBlocks.mapper.readTree("""{"rows": [], "rejects": []}""")),
    ) = ScriptTestCase(
        name = name,
        input = ScriptTestInput(rows = emptyList(), inputs = mapOf("tz" to "UTC", "min_total" to 0.00)),
        expect = expect,
    )

    private val expectTimeout = ScriptTestExpect(refusal = PipelineErrorCodes.Script.TIMEOUT)

    private val trueInvariants =
        listOf(
            ScriptInvariant("no_rows", "\$count(rows) = 0", "no rows"),
            ScriptInvariant("no_rejects", "\$count(rejects) = 0", "no rejects"),
            ScriptInvariant("no_orders", "\$count(inputs.orders) = 0", "no orders"),
            ScriptInvariant("rows_balance", "\$count(rows) + \$count(rejects) = \$count(inputs.orders)", "balanced"),
        )

    private fun RecordingEngine.suite(
        tests: List<ScriptTestCase>,
        invariants: List<ScriptInvariant> = emptyList(),
        runner: ScriptTestRunner = runner(this),
    ) = runner.runSuite("test/budget.jsonata", body, TemplateType.JSONATA, contract, invariants, tests)

    private fun List<TemplateValidationFailure>.shouldAllBeSuite(count: Int) {
        size shouldBe count
        forEach {
            it.code shouldBe PipelineErrorCodes.Template.TEST_FAILED
            it.details["timeout"] shouldBe "suite"
        }
    }

    private fun timeoutBelowEvaluateTimeout(limits: EvaluationLimits) {
        if (limits.wallClock < evaluateTimeout) throw ScriptTimeoutException(limits.wallClock, "clipped")
    }

    @Test
    fun `the fixture's suite passes with budget to spare`() {
        val engine = RecordingEngine(body)
        engine.suite(listOf(case()), trueInvariants).shouldBeEmpty()
    }

    @Test
    fun `T1 a compile that spends the budget fails every case with no evaluation`() {
        val engine = RecordingEngine(body)
        engine.onCompile = { kind -> if (kind == Kind.BODY) advance(Duration.ofMillis(1100)) }
        val failures = engine.suite(listOf(case("a"), case("b"), case("c")))
        failures.shouldAllBeSuite(3)
        engine.count(Kind.BODY) shouldBe 0
    }

    @Test
    fun `T2 a runaway body is clipped to the remaining budget`() {
        val pool = pool()
        val engine = RecordingEngine(RUNAWAY)
        val runner = runner(engine, pool, fakeClock = false)
        val started = System.nanoTime()
        val failures = runner.runSuite("test/runaway.jsonata", RUNAWAY, TemplateType.JSONATA, contract, emptyList(), listOf(case()))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        println(
            "T2 elapsed_ms=${elapsed.toMillis()} wall_clock_ms=${engine.wallClocks.getValue(Kind.BODY).map { it.toMillis() }} " +
                "abandoned=${pool.abandoned.sum()} abandoned_alive=${pool.abandonedThreadsAlive()}",
        )
        elapsed shouldBeLessThanOrEqualTo suiteTimeout + Duration.ofMillis(200) + MARGIN
        failures.shouldAllBeSuite(1)
    }

    @Test
    fun `T3 each body evaluation gets what remains and a case finishing after D fails`() {
        val engine = RecordingEngine(body)
        engine.onEvaluate = { kind, _ -> if (kind == Kind.BODY) advance(Duration.ofMillis(400)) }
        val failures = engine.suite(listOf(case("one"), case("two"), case("three"), case("four")))
        val clocks = engine.wallClocks.getValue(Kind.BODY)
        clocks.size shouldBe 3
        clocks[0] shouldBeLessThanOrEqualTo Duration.ofMillis(1000)
        clocks[1] shouldBeLessThanOrEqualTo Duration.ofMillis(600)
        clocks[2] shouldBeLessThanOrEqualTo Duration.ofMillis(200)
        engine.count(Kind.BODY) shouldBe 3
        failures.shouldAllBeSuite(2)
    }

    @Test
    fun `T4 a clipped body timeout never satisfies a timeout expectation`() {
        val engine = RecordingEngine(body)
        engine.onCompile = { kind -> if (kind == Kind.BODY) advance(Duration.ofMillis(700)) }
        engine.onEvaluate = { _, limits -> timeoutBelowEvaluateTimeout(limits) }
        val failures = engine.suite(listOf(case(expect = expectTimeout)))
        engine.wallClocks.getValue(Kind.BODY) shouldBe listOf(Duration.ofMillis(300))
        failures.shouldAllBeSuite(1)
    }

    @Test
    fun `T5 a contended slot wait is clipped and the abandoned worker keeps its slot`() {
        val pool = pool(size = 1)
        val holderIn = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val bodyIn = CountDownLatch(1)
        val bodyRelease = CountDownLatch(1)
        val holder =
            Thread {
                pool.run(EvaluationLimits(wallClock = Duration.ofSeconds(10), maxDepth = 100), "holder") {
                    holderIn.countDown()
                    holderRelease.await()
                }
            }
        holder.start()
        holderIn.await(5, TimeUnit.SECONDS) shouldBe true
        val engine = RecordingEngine(body)
        engine.onEvaluate = { kind, _ ->
            if (kind == Kind.BODY) {
                bodyIn.countDown()
                awaitIgnoringInterrupts(bodyRelease)
            }
        }
        try {
            val started = System.nanoTime()
            val releaser =
                Thread {
                    Thread.sleep(HOLDER_RELEASE_MS)
                    holderRelease.countDown()
                }
            releaser.start()
            val failures = engine.suite(listOf(case()), runner = runner(engine, pool, fakeClock = false))
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            println(
                "T5 elapsed_ms=${elapsed.toMillis()} wall_clock_ms=${engine.wallClocks.getValue(Kind.BODY).map { it.toMillis() }} " +
                    "abandoned=${pool.abandoned.sum()} abandoned_alive=${pool.abandonedThreadsAlive()}",
            )
            failures.shouldAllBeSuite(1)
            elapsed shouldBeGreaterThan suiteTimeout
            elapsed shouldBeLessThanOrEqualTo suiteTimeout + suiteTimeout + Duration.ofMillis(200) + MARGIN
            bodyIn.await(0, TimeUnit.SECONDS) shouldBe true
            shouldThrow<ScriptPoolExhaustedException> {
                pool.run(EvaluationLimits(wallClock = Duration.ofMillis(100), maxDepth = 100), "probe") { 1 }
            }
            bodyRelease.countDown()
            pool.run(EvaluationLimits(wallClock = Duration.ofSeconds(5), maxDepth = 100), "after") { 2 } shouldBe 2
        } finally {
            holderRelease.countDown()
            bodyRelease.countDown()
            holder.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    @Test
    fun `T6 each invariant gets what remains and none runs past D`() {
        val engine = RecordingEngine(body)
        engine.onEvaluate = { kind, _ -> if (kind == Kind.INVARIANT) advance(Duration.ofMillis(400)) }
        val failures = engine.suite(listOf(case()), trueInvariants)
        val clocks = engine.wallClocks.getValue(Kind.INVARIANT)
        clocks.size shouldBe 3
        clocks[0] shouldBeLessThanOrEqualTo Duration.ofMillis(1000)
        clocks[1] shouldBeLessThanOrEqualTo Duration.ofMillis(600)
        clocks[2] shouldBeLessThanOrEqualTo Duration.ofMillis(200)
        engine.count(Kind.INVARIANT) shouldBe 3
        failures.shouldAllBeSuite(1)
    }

    @Test
    fun `T7 a clipped invariant timeout never satisfies a timeout expectation`() {
        val engine = RecordingEngine(body)
        engine.onCompile = { kind -> if (kind == Kind.BODY) advance(Duration.ofMillis(700)) }
        engine.onEvaluate = { kind, limits -> if (kind == Kind.INVARIANT) timeoutBelowEvaluateTimeout(limits) }
        val failures = engine.suite(listOf(case(expect = expectTimeout)), trueInvariants.take(1))
        engine.wallClocks.getValue(Kind.INVARIANT) shouldBe listOf(Duration.ofMillis(300))
        failures.shouldAllBeSuite(1)
    }

    @Test
    fun `T8 a matching output finished after D fails as suite`() {
        val engine = RecordingEngine(body)
        engine.onEvaluate = { kind, _ -> if (kind == Kind.BODY) advance(Duration.ofMillis(1100)) }
        engine.suite(listOf(case())).shouldAllBeSuite(1)
    }

    @Test
    fun `T9 a true invariant finished after D fails as suite`() {
        val engine = RecordingEngine(body)
        engine.onEvaluate = { kind, _ -> if (kind == Kind.INVARIANT) advance(Duration.ofMillis(1100)) }
        engine.suite(listOf(case()), trueInvariants.take(1)).shouldAllBeSuite(1)
    }

    private companion object {
        const val RUNAWAY = "\$pad('x', 100000000)"
        const val HOLDER_RELEASE_MS = 700L
        val MARGIN: Duration = Duration.ofMillis(1500)

        /** The blocked evaluation the pool abandons: it ignores the interrupt `task.cancel(true)` sends. */
        fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (ignored: InterruptedException) {
                    // Deliberately ignored: the stand-in models a builtin that cannot be interrupted.
                }
            }
        }
    }
}
