package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Each declared limit's honest behaviour for the JSONata engine, on measured facts
 * (§4.5, and the record's source reads re-verified here):
 *
 *  - the lambda-recursion loop hits step boundaries, so the WALL CLOCK fires (the
 *    library's depth counter deliberately skips lambda calls — they mark
 *    `isParallelCall`, and the Timebox honours that by not counting them);
 *  - the DEPTH bound fires on nested-EXPRESSION recursion (500 nested arrays against
 *    a depth of 20) — a typed resource refusal, in milliseconds;
 *  - `$eval` cannot see a bind-expression (`:=`) at all — the library's eval-string
 *    parser refuses it — so an eval'd body that overruns is proven bounded THROUGH
 *    THE POOL, which is the bound §4.3 documents for anything inside a builtin.
 */
class JsonataEngineBoundsTest {
    /** A self-recursive lambda — the canonical JSONata non-terminating expression. */
    private val infiniteLoop = "(\$f := function(\$x){ \$f(\$x + 1) }; \$f(0))"

    /** 500 nested array constructors — expression nesting the depth counter sees. */
    private val nestedArrays: String = "[".repeat(500) + "0" + "]".repeat(500)

    private fun tight(
        wallClock: Duration,
        maxDepth: Int,
    ) = EvaluationLimits(wallClock, maxDepth, now = ScriptingTestSupport.FIXED_NOW)

    @Test
    fun `the wall clock bound fires on a non-terminating body`() {
        val caught =
            runCatching {
                engine.evaluate(engine.compile(infiniteLoop), null, tight(Duration.ofMillis(300), 100))
            }.exceptionOrNull()
        val timeout = caught.shouldNotBeNull() as ScriptTimeoutException
        timeout.code shouldBe "pipeline.transform.timeout"
    }

    @Test
    fun `the depth bound is a typed resource refusal on nested expressions`() {
        val caught =
            runCatching {
                engine.evaluate(engine.compile(nestedArrays), null, tight(Duration.ofSeconds(30), 20))
            }.exceptionOrNull()
        val limit = caught.shouldNotBeNull() as ScriptResourceLimitException
        limit.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        limit.code shouldBe "pipeline.transform.resource_limit"
    }

    @Test
    fun `a lambda-recursive loop is caught by time, not depth - the measured fact`() {
        // The record §4.5's "depth 100 000" recursion: lambda calls skip the depth
        // counter (isParallelCall), so the honest bound that fires is the wall clock.
        // Recorded here so the docs table stays true to the measurement.
        val caught =
            runCatching {
                engine.evaluate(engine.compile(infiniteLoop), null, tight(Duration.ofMillis(500), 100))
            }.exceptionOrNull()
        caught.shouldNotBeNull() as ScriptTimeoutException
    }

    @Test
    fun `an eval'd body that overruns is bounded through the pool`() {
        // `$eval` inherits the evaluation's timebox, but a single builtin that never
        // returns reaches no step boundary - the pool's abandonment is the bound §4.3
        // names for exactly this shape. (The eval'd pad bomb's thread stays alive;
        // it is a daemon and the pool has already replaced it.)
        val pool = ScriptEvaluationPool(1, 1, Duration.ofMillis(250), ScriptEvaluationPool.SYSTEM)
        val caught =
            java.util.concurrent.atomic
                .AtomicReference<ScriptTimeoutException>()
        val caller =
            Thread {
                try {
                    pool.run(EvaluationLimits(Duration.ofMillis(400), 100), "eval-bomb@1") {
                        engine.evaluate(
                            engine.compile("\$eval(\"\$pad(\\\"x\\\", 100000000)\")"),
                            null,
                            tight(Duration.ofSeconds(60), 100),
                        )
                    }
                } catch (err: ScriptTimeoutException) {
                    caught.set(err)
                }
            }
        caller.start()
        caller.join(10_000)
        caught.get().shouldNotBeNull().code shouldBe "pipeline.transform.timeout"
        pool.abandoned.sum() shouldBe 1
    }

    @Test
    fun `capabilities state exactly what the library bounds - measured, not intended`() {
        engine.capabilities shouldBe
            EngineCapabilities(
                boundsWallClockBetweenSteps = true,
                boundsDepth = true,
                boundsHeap = false,
                boundsStatements = false,
                interruptible = false,
            )
    }

    @Test
    fun `a body finishing within its budget is unaffected by the bound`() {
        engine.evaluate(engine.compile("1 + 1"), null, tight(Duration.ofMillis(300), 10)) shouldBe 2
    }
}
