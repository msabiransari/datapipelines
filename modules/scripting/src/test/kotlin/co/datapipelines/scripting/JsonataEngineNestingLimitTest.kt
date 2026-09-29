package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import co.datapipelines.scripting.ScriptingTestSupport.limits
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The compile-time nesting ceiling and the stack-overflow boundary guards (#314).
 *
 * The library's parser AND evaluator recurse per level: 500 nested brackets
 * overflowed a 256 KB stack inside the PARSE alone (measured 2026-09-29, the
 * recorded frames dominated by the library's parser), and 297's conformance run
 * saw the same shape once on a default stack under load — the 1-in-13 red. The
 * engine therefore refuses static nesting past `JsonataNestingScan.CEILING`
 * before the library sees the body, and turns any residual stack overflow at
 * either boundary into the catalogued DEPTH refusal. The small stacks below make
 * the overflow DETERMINISTIC — that is the point: a fixed sleep of a stack budget
 * is a coin toss, a thread sized under the requirement is not.
 */
class JsonataEngineNestingLimitTest {
    /** The #314 conformance shape: 500 nested array constructors. */
    private val deep = "[".repeat(500) + "0" + "]".repeat(500)

    /**
     * Runs [work] on a thread whose stack is [kilobytes] KB and rethrows what escapes.
     *
     * The caller must have WARMED every class [work] loads (see the tests: one
     * compile/evaluate on the caller's full stack first) — a cold class load parses
     * URLs in the app classloader and needs more stack than these threads carry;
     * measured 2026-09-29: a first-use class load on a 128 KB thread overflowed
     * inside `URLClassPath.getResource` before the scan ever ran. That is the JVM's
     * floor, not the scan recursing — the scan is a flat loop.
     */
    private fun onSmallStack(
        kilobytes: Int,
        work: () -> Any?,
    ): Any? {
        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable>()
        val thread =
            Thread(
                null,
                {
                    try {
                        result.set(work())
                    } catch (err: Throwable) {
                        failure.set(err)
                    }
                },
                "nesting-limit-probe",
                kilobytes * 1024L,
            )
        thread.start()
        thread.join(60_000)
        thread.isAlive.let { alive -> if (alive) error("the probe thread never finished") }
        failure.get()?.let { throw it }
        return result.get()
    }

    /** Loads the compile path's classes (and the refusal's) on the caller's full stack. */
    private fun warmCompile() {
        engine.compile("1 + 1")
        val over = "[".repeat(JsonataNestingScan.CEILING + 6) + "0" + "]".repeat(JsonataNestingScan.CEILING + 6)
        val caught = runCatching { engine.compile(over) }.exceptionOrNull()
        caught.shouldBeInstanceOf<ScriptResourceLimitException>()
    }

    /** Loads the evaluate path's classes on the caller's full stack. */
    private fun warmEvaluate() {
        warmCompile()
        engine.evaluate(engine.compile("1 + 1"), null, limits())
    }

    @Test
    fun `a body past the ceiling is refused at compile with the catalogued depth refusal, on a small stack`() {
        // The #314 reproduction, sized: 256 KB overflowed inside Jsonata.jsonata
        // before the fix (probe evidence, 2026-09-29). The refusal must be the
        // engine's own typed one, thrown at COMPILE, never an Error.
        warmCompile()
        val caught =
            onSmallStack(256) {
                runCatching { engine.compile(deep) }.exceptionOrNull()
            }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        refusal.code shouldBe "pipeline.transform.resource_limit"
        // WHO refused matters (#314): the pre-scan's wording proves the body was
        // refused BEFORE the library parsed it — the boundary guard's fallback
        // wording ("the library's recursion overflowed…") would mean the library
        // saw the body. This sentence is the scan-vs-guard discriminator.
        refusal.message?.contains("refused before parsing") shouldBe true
    }

    @Test
    fun `the library never parses a body past the ceiling - a clean refusal on a stack far too small to parse it`() {
        // 192 KB cannot carry the library's recursive parse of 500 levels (256 KB
        // overflowed at it) while carrying the warmed scan path (see onSmallStack).
        // A TYPED refusal here is therefore proof the pre-scan refused before
        // Jsonata.jsonata was called: had the library been invoked, this thread
        // would have overflowed and the test would see the Error.
        warmCompile()
        val caught =
            onSmallStack(192) {
                runCatching { engine.compile(deep) }.exceptionOrNull()
            }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        // The pre-scan's wording — the boundary guard's overflow wording here would
        // mean the library parsed (and overflowed) on this thread after all.
        refusal.message?.contains("refused before parsing") shouldBe true
    }

    @Test
    fun `a body under the ceiling but over the evaluate maxDepth reaches the evaluate-time refusal`() {
        // The conformance expectation's shape, INSIDE the ceiling: 40 static levels
        // parse (under 64), evaluate, and the engine's own counter refuses at the
        // declared maxDepth — the evaluate bound is not displaced by the compile one.
        val body = "[".repeat(40) + "0" + "]".repeat(40)
        val caught =
            runCatching {
                engine.evaluate(engine.compile(body), null, limits(Duration.ofSeconds(30), maxDepth = 20))
            }.exceptionOrNull()
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `a body at exactly the ceiling compiles and evaluates`() {
        val body = "[".repeat(64) + "1+1" + "]".repeat(64)
        val script = engine.compile(body)
        // 64 nested single-element arrays around the inner 2 — the boundary body
        // evaluates to its full structure, flattened by nothing.
        val expected = (1..64).fold(2 as Any?) { acc, _ -> listOf(acc) }
        engine.evaluate(script, null, limits(Duration.ofSeconds(30), maxDepth = 100)) shouldBe expected
    }

    @Test
    fun `an evaluation stack overflow surfaces as the catalogued refusal, never the error`() {
        // The evaluate boundary: the recursion is a LAMBDA's (static nesting stays
        // tiny, so the pre-scan correctly passes it) and the declared maxDepth is far
        // above what the small stack carries. WHEN the library's evaluator overflows
        // the thread — it did on the pre-fix engine and on cold-JIT runs (the #314
        // red, 2026-09-29) — the boundary guard must answer with the typed refusal.
        // The overflow itself is JIT-state dependent (measured: a warmed JVM ran this
        // shape to completion iteratively even on 256 KB), so a run where the body
        // merely SUCCEEDS skips honestly — the suite does not pretend to have tested
        // the guard on a state that never reached it (the conformance suite's
        // capability-skip precedent).
        val nonTail = "(\$f := function(\$x){ \$x <= 0 ? 0 : 1 + \$f(\$x - 1) }; \$f(100000))"
        warmEvaluate()
        val caught =
            onSmallStack(256) {
                runCatching {
                    engine.evaluate(engine.compile(nonTail), null, limits(Duration.ofSeconds(30), maxDepth = 1_000_000))
                }.exceptionOrNull()
            }
        if (caught == null) {
            println(
                "[nesting-limit] SKIPPED: the evaluator ran the deep non-tail body to completion on the " +
                    "256 KB probe thread (no overflow in this JVM state) — the boundary guard had no input here",
            )
            return
        }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        refusal.code shouldBe "pipeline.transform.resource_limit"
    }

    @Test
    fun `brackets the scan must not count do not false-refuse - strings, comments and regex literals`() {
        // 70 unbalanced opens inside each construct: if the scan counted them, every
        // case here would falsely refuse at compile. Under-counting is the scan's
        // stated bias (JsonataNestingScan KDoc) — these pin it. The regex case
        // carries 70 nested (balanced) groups, the deepest shape the library's own
        // regex tokenizer accepts, so only the scan's skip keeps it from the ceiling.
        val inString = "'" + "(".repeat(70) + "'"
        engine.evaluate(engine.compile(inString), null, limits()) shouldBe "(".repeat(70)

        val inComment = "/*" + "(".repeat(70) + "*/ 1"
        engine.evaluate(engine.compile(inComment), null, limits()) shouldBe 1

        val inRegex = "\$contains(\"x\", /" + "(".repeat(70) + ")".repeat(70) + "/)"
        engine.evaluate(engine.compile(inRegex), null, limits()) shouldBe true
    }

    @Test
    fun `a division slash is not mistaken for a regex literal - brackets after it still count`() {
        // `a / b` is division: the scan must keep counting what follows it, or a
        // body could hide nesting inside "division spans". A deep body after a
        // real division therefore still refuses at compile.
        val hidden = "(1 / 2) + " + "[".repeat(70) + "0" + "]".repeat(70)
        val caught = runCatching { engine.compile(hidden) }.exceptionOrNull()
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
    }
}
