package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Each declared limit's honest behaviour for the JSONata engine, on measured facts
 * (§4.5, re-measured at #260 with library-level probes):
 *
 *  - DEPTH fires on nested-expression recursion AND on non-tail lambda recursion: the
 *    engine counts every evaluate entry and exit itself. (The library's `Timebox`
 *    returned early on `isParallelCall` frames — the second and later object pairs /
 *    arguments — which leaked one depth unit per item on set-level shapes and skipped
 *    the clock check in exactly those frames. That is what #260 fixed.)
 *  - a TAIL-recursive lambda loop is TRAMPOLINED by the library — its depth stays
 *    flat, so the WALL CLOCK is the bound that fires (unchanged from the 7a
 *    measurement, now explained by the trampoline); the clock is checked at every
 *    step, including a long `$reduce`, which no depth can catch;
 *  - a single builtin call that overruns (`$pad`, `$join`, a backtracking regex) runs
 *    to completion first — for that shape the evaluation pool's abandonment is the
 *    bound, proven through the pool below;
 *  - an eval'd body that overruns inside a builtin is proven bounded THROUGH THE POOL,
 *    which is the bound §4.3 documents for anything inside a builtin. (The 7a note that
 *    `$eval` "cannot see a bind-expression" was wrong — measured 2026-09-28, #272: it
 *    parses binds and a top-level eval'd bind lands in the CALLING frame; the engine's
 *    `$eval` shadow refuses a reserved one, `JsonataReservedNamesTest`.)
 */
class JsonataEngineBoundsTest {
    /** A self-recursive lambda — the canonical JSONata non-terminating expression. */
    private val infiniteLoop = "(\$f := function(\$x){ \$f(\$x + 1) }; \$f(0))"

    /** 500 nested array constructors — expression nesting the depth counter sees. */
    private val nestedArrays: String = "[".repeat(500) + "0" + "]".repeat(500)

    /** A long `$reduce`: bounded nesting (the lambda never recurses), unbounded steps. */
    private val longReduce = """${'$'}reduce(rows, function(${'$'}a, ${'$'}b){ ${'$'}a + 1 })"""

    private fun reduceInput(rows: Int): Map<String, Any?> = mapOf("rows" to List(rows) { 1 })

    private fun tight(
        wallClock: Duration,
        maxDepth: Int,
    ) = EvaluationLimits(wallClock, maxDepth, now = ScriptingTestSupport.FIXED_NOW)

    @Test
    fun `the wall clock fires on a body no depth can catch - a long reduce under a tiny clock`() {
        val caught =
            runCatching {
                engine.evaluate(
                    engine.compile(longReduce),
                    reduceInput(2_000_000),
                    tight(Duration.ofMillis(300), 100),
                )
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
    fun `a tail-recursive lambda loop is trampolined - depth stays flat and the wall clock catches it`() {
        // Measured at #260 (library probe): the library trampolines a call in TAIL
        // position, so the loop never nests and no depth counter can see it — the 7a
        // "time catches recursion" fact, now explained by the trampoline rather than
        // isParallelCall. The engine checks the clock at every entry and exit inside
        // the trampoline loop, so the 300 ms budget fires in milliseconds.
        val caught =
            runCatching {
                engine.evaluate(engine.compile(infiniteLoop), null, tight(Duration.ofMillis(300), 100))
            }.exceptionOrNull()
        caught.shouldNotBeNull() as ScriptTimeoutException
    }

    @Test
    fun `a non-tail-recursive lambda nests for real - depth catches it at max-depth nested calls`() {
        // Measured at #260: with the recursive call as an OPERAND (not the tail
        // expression), applications nest for real and the engine's own counter fires
        // the typed refusal at 101 nested calls — milliseconds into this 30-second
        // clock. Lambda recursion is included in the depth bound.
        val nonTail = "(\$f := function(\$x){ \$x <= 0 ? 0 : 1 + \$f(\$x - 1) }; \$f(1000))"
        val caught =
            runCatching {
                engine.evaluate(engine.compile(nonTail), null, tight(Duration.ofSeconds(30), 100))
            }.exceptionOrNull()
        val limit = caught.shouldNotBeNull() as ScriptResourceLimitException
        limit.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        limit.code shouldBe "pipeline.transform.resource_limit"
    }

    @Test
    fun `an eval'd body that overruns is bounded through the pool`() {
        // `$eval` inherits the evaluation's timebox, but a single builtin that never
        // returns reaches no step boundary - the pool's abandonment is the bound §4.3
        // names for exactly this shape. (The eval'd pad bomb's thread stays alive;
        // it is a daemon and keeps this pool's one slot until it ends.)
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
