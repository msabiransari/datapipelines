package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import co.datapipelines.scripting.ScriptingTestSupport.limits
import com.dashjoin.jsonata.Jsonata
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        // merely SUCCEEDS is recorded as SKIPPED — an aborted test in the XML, counted by
        // test-recount.sh's `skipped=` (#322), never a pass: the suite does not pretend to
        // have tested the guard on a state that never reached it. The two DETERMINISTIC
        // boundary tests below pin both catches whatever this one does.
        val nonTail = "(\$f := function(\$x){ \$x <= 0 ? 0 : 1 + \$f(\$x - 1) }; \$f(100000))"
        warmEvaluate()
        val caught =
            onSmallStack(256) {
                runCatching {
                    engine.evaluate(engine.compile(nonTail), null, limits(Duration.ofSeconds(30), maxDepth = 1_000_000))
                }.exceptionOrNull()
            }
        assumeTrue(caught != null) {
            "[nesting-limit] SKIPPED: the evaluator ran the deep non-tail body to completion on the " +
                "256 KB probe thread (no overflow in this JVM state) — the boundary guard had no input here"
        }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        refusal.code shouldBe "pipeline.transform.resource_limit"
    }

    /**
     * The COMPILE boundary, deterministically (#322). The pre-scan is a bound, not a parser, and
     * `//` is its blind spot: it skips `//` to the end of the line as a comment, but JSONata has
     * no line comment — the library reads `1 / /x/[…]`, a division whose right side is a regex
     * literal with a predicate holding the nested arrays. So the scan counts nothing past it and
     * the library's recursive parse meets every level.
     *
     * [BOUNDARY_DEPTH] levels on a 256 KB thread overflow by construction, in any JIT state:
     * measured 2026-09-29 (lane 321), this library's parse carries at most 89 levels on 256 KB
     * cold and 577 once compiled — ~272 bytes a level at best (30,454 levels on 8 MB, 246,337 on
     * 64 MB). A stack that carried the parse would make the test FAIL, not pass: a compiled script
     * is not the refusal this test exists to reach.
     */
    @Test
    fun `a body that defeats the pre-scan is refused by the compile boundary, never an Error`() {
        val body = HIDDEN_BY_LINE_COMMENT + "[".repeat(BOUNDARY_DEPTH) + "0" + "]".repeat(BOUNDARY_DEPTH)
        // Non-vacuity, the scan: it passes this body, so the scan is not what refuses it.
        JsonataNestingScan.exceeds(body) shouldBe false
        // Non-vacuity, the grammar: the same shape past the ceiling (100 levels) COMPILES on the
        // full stack — the deep body is refused for its depth alone, never for its syntax.
        engine.compile(HIDDEN_BY_LINE_COMMENT + "[".repeat(100) + "0" + "]".repeat(100))
        warmCompile()
        val caught =
            onSmallStack(256) {
                runCatching { engine.compile(body) }.exceptionOrNull()
            }
        withClue("the parse must overflow the probe thread — a compiled script means the catch was never reached") {
            caught.shouldNotBeNull()
        }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        refusal.code shouldBe "pipeline.transform.resource_limit"
        // The boundary's wording, not the scan's ("refused before parsing"): the library parsed.
        refusal.message shouldContain "overflowed the evaluation stack at compile"
    }

    /**
     * The EVALUATE boundary, deterministically (#322). The library's evaluator recurses per
     * nesting level as its parser does, and the catch at evaluate turns its overflow into the
     * same refusal. A body this deep cannot pass compile — the ceiling refuses it, which is the
     * ceiling's point (asserted below) — so the tree is parsed here on a [PARSE_STACK_KB] thread
     * and handed to evaluate as the compiled script a body that defeated the pre-scan would be.
     *
     * [EVALUATE_DEPTH] levels on a 256 KB thread overflow in any JIT state: measured 2026-09-29
     * (lane 321), the evaluator carries at most 232 levels on 256 KB cold and 444 warm (~356
     * bytes a level at best: 23,531 on 8 MB). The parse needs at most ~3 KB a level cold, so
     * ~30 MB of the 64 MB thread.
     */
    @Test
    fun `an evaluation that overflows the stack is refused by the evaluate boundary, never an Error`() {
        val body = "[".repeat(EVALUATE_DEPTH) + "0" + "]".repeat(EVALUATE_DEPTH)
        // Why the script is built here and not compiled: compile refuses this body before parsing.
        JsonataNestingScan.exceeds(body) shouldBe true
        val parsed = onSmallStack(PARSE_STACK_KB) { Jsonata.jsonata(body) }
        val script = JsonataCompiledScript(body, parsed.shouldBeInstanceOf<Jsonata>())
        warmEvaluate()
        val caught =
            onSmallStack(256) {
                runCatching {
                    engine.evaluate(script, null, limits(Duration.ofSeconds(30), maxDepth = 1_000_000))
                }.exceptionOrNull()
            }
        withClue("the evaluation must overflow the probe thread — a value means the catch was never reached") {
            caught.shouldNotBeNull()
        }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        refusal.code shouldBe "pipeline.transform.resource_limit"
        // The direct catch's wording — not the counter's (maxDepth is out of reach) and not the
        // belt's "mid-evaluation" (nothing wrapped the Error on this path).
        refusal.message shouldContain "overflowed the evaluation stack at evaluate"
    }

    /**
     * The `$eval` route, end to end (#322): a `$eval` string is a string literal to the pre-scan
     * (skipped by design) and is parsed at EVALUATE time — the engine's `$eval` shadow parses it
     * with the library's parser for the reserved-bind check before the library runs it.
     * [BOUNDARY_DEPTH] levels overflow the 256 KB thread in that parse, whatever the JIT state
     * (the same parser, the same measurement as the compile case). The library WRAPS what a bound
     * function throws, so this overflow reaches the guard's belt (`JsonataEvaluationGuard.escaped`),
     * not the evaluate catch — measured by falsification: the evaluate catch replaced by a rethrow
     * left this test green, the belt's branch removed turned it red.
     */
    @Test
    fun `a $eval string the pre-scan cannot see is refused at evaluate, never an Error`() {
        val body = "\$eval('" + "[".repeat(BOUNDARY_DEPTH) + "0" + "]".repeat(BOUNDARY_DEPTH) + "')"
        JsonataNestingScan.exceeds(body) shouldBe false
        // Non-vacuity, the grammar: a shallow `$eval` string evaluates on the full stack.
        engine.evaluate(engine.compile("\$eval('[[0]]')"), null, limits()) shouldBe listOf(listOf(0))
        warmEvaluate()
        // Compiled on the caller's full stack: to the library's tokenizer the string is one token.
        val script = engine.compile(body)
        val caught =
            onSmallStack(256) {
                runCatching { engine.evaluate(script, null, limits()) }.exceptionOrNull()
            }
        withClue("the \$eval parse must overflow the probe thread — a value means the catch was never reached") {
            caught.shouldNotBeNull()
        }
        val refusal = caught.shouldBeInstanceOf<ScriptResourceLimitException>()
        refusal.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
        refusal.code shouldBe "pipeline.transform.resource_limit"
        refusal.message shouldContain "overflowed the evaluation stack mid-evaluation"
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

    private companion object {
        /**
         * What the pre-scan reads as a line comment and the library reads as `1 / /x/` — a
         * division, then a regex literal whatever follows is a predicate on (see the compile
         * boundary test).
         */
        const val HIDDEN_BY_LINE_COMMENT = "1 //x/ "

        /** ~86× the deepest parse a 256 KB thread was measured to carry (577 levels, JIT-compiled). */
        const val BOUNDARY_DEPTH = 50_000

        /** ~22× the deepest evaluation a 256 KB thread was measured to carry (444 levels, warm). */
        const val EVALUATE_DEPTH = 10_000

        /** The thread the evaluate case parses its tree on: 64 MB, twice the cold parse's need. */
        const val PARSE_STACK_KB = 65_536
    }
}
