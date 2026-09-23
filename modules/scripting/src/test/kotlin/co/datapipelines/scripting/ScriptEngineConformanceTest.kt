package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.DEFAULT_LIMITS
import co.datapipelines.scripting.ScriptingTestSupport.engine
import co.datapipelines.scripting.ScriptingTestSupport.limits
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The conformance suite (§4.1): one battery parameterised over every ScriptEngine
 * implementation. A second engine is ADDED to the list below and inherits every case —
 * it never adds cases (engine-specific facts live in engine-specific suites).
 *
 * Cases: input shape, each limit firing (heap and statements are SKIPPED with the
 * capability flag as the printed reason — the JSONata engine cannot enforce them),
 * cancellation from outside, a syntax error at compile, determinism (serial and the
 * 32-thread edition — see [JsonataEngineParallelDeterminismTest] for the full sweep),
 * no host access, and an eval'd overrun under the pool's bound.
 */
class ScriptEngineConformanceTest {
    /** The engines under conformance — one today; the JS lane adds its instance here. */
    private val engines: List<ScriptEngine> = listOf(ScriptingTestSupport.engine)

    @Test
    fun `every engine round-trips the input shapes`() {
        engines.forEach { engine ->
            val shapes: List<Any?> =
                listOf(
                    null,
                    "s",
                    1,
                    2.5,
                    true,
                    listOf(1, listOf(2, linkedMapOf("a" to null))),
                    linkedMapOf("k" to listOf(true, null)),
                )
            shapes.forEach { shape ->
                engine.evaluate(engine.compile("$"), shape, DEFAULT_LIMITS) shouldBe shape
            }
        }
    }

    @Test
    fun `every engine enforces the limits its capabilities claim - and skips the rest honestly`() {
        engines.forEach { engine ->
            val caps = engine.capabilities
            val loop = "(\$f := function(\$x){ \$f(\$x) }; \$f(0))"

            if (caps.boundsWallClockBetweenSteps) {
                val caught =
                    runCatching {
                        engine.evaluate(engine.compile(loop), null, limits(Duration.ofMillis(300)))
                    }.exceptionOrNull()
                caught.shouldNotBeNull() as ScriptTimeoutException
            }
            if (caps.boundsDepth) {
                // Nested EXPRESSIONS drive the depth counter (lambda calls skip it -
                // the library marks them isParallelCall; measured, lane 7a).
                val nested = "[".repeat(500) + "0" + "]".repeat(500)
                val caught =
                    runCatching {
                        engine.evaluate(engine.compile(nested), null, limits(Duration.ofSeconds(30), maxDepth = 20))
                    }.exceptionOrNull()
                val refusal = caught.shouldNotBeNull() as ScriptResourceLimitException
                refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
            }
            // Skipped with the capability flag as the printed reason — these are
            // refusals the seam CANNOT make in-process, and the suite says so instead
            // of pretending to test them.
            listOf("heap" to caps.boundsHeap, "statements" to caps.boundsStatements).forEach { (name, bounded) ->
                println(
                    "[conformance] engine=${engine.type} limit=$name bounded=$bounded" +
                        if (bounded) "" else " — SKIPPED: EngineCapabilities.bounds$name is false (not enforceable in-process)",
                )
            }
        }
    }

    @Test
    fun `every engine surfaces cancellation from outside through the pool`() {
        engines.forEach { engine ->
            val pool = ScriptEvaluationPool(1, 1, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM)
            val timeout = AtomicReference<ScriptTimeoutException>()
            val caller =
                Thread {
                    try {
                        pool.run(
                            EvaluationLimits(Duration.ofMillis(200), 100),
                            "conformance-cancel",
                        ) {
                            val loop = "(\$f := function(\$x){ \$f(\$x) }; \$f(0))"
                            engine.evaluate(engine.compile(loop), null, limits(Duration.ofSeconds(60)))
                        }
                    } catch (err: ScriptTimeoutException) {
                        timeout.set(err)
                    }
                }
            caller.start()
            caller.join(10_000)
            timeout.get().shouldNotBeNull()
            pool.abandoned.sum() shouldBe 1
        }
    }

    @Test
    fun `every engine refuses a syntax error at compile with position`() {
        engines.forEach { engine ->
            val caught = runCatching { engine.compile("1 +") }.exceptionOrNull()
            val syntax = caught.shouldNotBeNull() as ScriptSyntaxException
            syntax.line shouldBe 1
            (syntax.column >= 1) shouldBe true
        }
    }

    @Test
    fun `every engine is deterministic - same input, same output, twice`() {
        engines.forEach { engine ->
            val script = engine.compile("\$ * 2")
            val first = engine.evaluate(script, 21, DEFAULT_LIMITS)
            val second = engine.evaluate(script, 21, DEFAULT_LIMITS)
            first shouldBe second
            first shouldBe 42
        }
    }

    @Test
    fun `every engine has no host access - the deny-list names are absent from the catalogue`() {
        engines.forEach { engine ->
            val probe = engine.compile("\$eval(\"java.io.File\")")
            engine.evaluate(probe, null, DEFAULT_LIMITS).shouldBeNull()
        }
    }

    @Test
    fun `every engine runs eval under the same bound - the pool's, for a builtin overrun`() {
        engines.forEach { engine ->
            // $eval cannot parse bind-expressions (the library's eval-string parser
            // refuses :=), so the proof uses an eval'd builtin overrun. The eval'd
            // work inherits the evaluation's timebox, but a builtin that never
            // returns reaches no step boundary - the POOL is the bound §4.3 names
            // for exactly this shape.
            val pool = ScriptEvaluationPool(1, 1, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM)
            val timeout =
                java.util.concurrent.atomic
                    .AtomicReference<ScriptTimeoutException>()
            val caller =
                Thread {
                    try {
                        pool.run(limits(Duration.ofMillis(400)), "conformance-eval") {
                            engine.evaluate(
                                engine.compile("\$eval(\"\$pad(\\\"x\\\", 100000000)\")"),
                                null,
                                limits(Duration.ofSeconds(60)),
                            )
                        }
                    } catch (err: ScriptTimeoutException) {
                        timeout.set(err)
                    }
                }
            caller.start()
            caller.join(10_000)
            timeout.get().shouldNotBeNull().code shouldBe "pipeline.transform.timeout"
        }
    }
}
