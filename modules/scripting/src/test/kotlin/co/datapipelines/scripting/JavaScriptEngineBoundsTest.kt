package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.js
import co.datapipelines.scripting.ScriptingTestSupport.limits
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** The JavaScript engine's limits (#8 A.3): each fires as its own catalogued kind. */
class JavaScriptEngineBoundsTest {
    private fun kind(
        body: String,
        input: Any? = null,
        limits: EvaluationLimits,
    ): ScriptResourceLimitException.Kind = refusal<ScriptResourceLimitException> { js(body, input, limits) }.kind

    @Test
    fun `the wall clock stops a busy body at its budget`() {
        // Builtin-bound: a few statements per pass, so the statement budget cannot fire first (an
        // empty `for (;;) {}` spends the default 100 000 000 statements in under 500 ms, measured).
        val started = System.nanoTime()
        val busy = "const s = 'x'.repeat(1 << 16); for (;;) { s.split('').reverse().join(''); }"
        refusal<ScriptTimeoutException> { js(busy, limits = limits(Duration.ofMillis(500))) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        withClue("stopped after $elapsedMs ms") { (elapsedMs in 450L..2_000L) shouldBe true }
    }

    @Test
    fun `statements are counted - a spin past the budget is STATEMENTS`() {
        kind("let i = 0; for (;;) { i++; }", limits = limits(maxStatements = 10_000)) shouldBe ScriptResourceLimitException.Kind.STATEMENTS
    }

    @Test
    fun `the engine's own prelude is not counted against the body's statements`() {
        js("return 1;", limits = limits(maxStatements = 2)) shouldBe 1L
    }

    @Test
    fun `heap is bounded - an allocation loop is HEAP`() {
        kind("const a = []; for (;;) { a.push('x'.repeat(1000) + a.length); }", limits = limits(maxHeapBytes = 16L * 1024 * 1024)) shouldBe
            ScriptResourceLimitException.Kind.HEAP
    }

    @Test
    fun `depth - maxDepth frames of the body's own fit, a few more are DEPTH`() {
        val recurse = "function r(n) { return n === 0 ? 0 : 1 + r(n - 1); } return r(input);"
        // main + r(k)'s k + 1 frames = k + 2 frames of the body's own.
        js(recurse, 18, limits(maxDepth = 20)) shouldBe 18L
        kind(recurse, 22, limits(maxDepth = 20)) shouldBe ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `recursion past the native stack is DEPTH too`() {
        val runtime = JavaScriptRuntime(JavaScriptRuntimeOptions(maxStackFrames = 1_000_000))
        val engine = JavaScriptEngine(runtime)
        val script = engine.compile("function r(n) { return n === 0 ? 0 : 1 + r(n - 1); } return r(1000000);")
        refusal<ScriptResourceLimitException> { engine.evaluate(script, null, limits(maxDepth = 900_000)) }.kind shouldBe
            ScriptResourceLimitException.Kind.DEPTH
    }

    @Test
    fun `the console stream is capped - past it the evaluation is OUTPUT`() {
        kind("console.log('x'.repeat(2e6)); return 1;", limits = limits()) shouldBe ScriptResourceLimitException.Kind.OUTPUT
    }

    @Test
    fun `an input past its budget is refused before any sandbox exists`() {
        val engines = AtomicInteger()
        val runtime = JavaScriptRuntime(JavaScriptRuntimeOptions()) { _: Engine -> engines.incrementAndGet() }
        val engine = JavaScriptEngine(runtime)
        val script = engine.compile("return input;")
        val built = engines.get()
        val refused = refusal<ScriptResourceLimitException> { engine.evaluate(script, "x".repeat(2000), limits(maxInputBytes = 1000)) }
        refused.kind shouldBe ScriptResourceLimitException.Kind.INPUT_BYTES
        engines.get() shouldBe built
    }

    @Test
    fun `a result past its budget is RESULT_BYTES - the prelude's character count first`() {
        kind("return 'x'.repeat(2000);", limits = limits(maxResultBytes = 1000)) shouldBe ScriptResourceLimitException.Kind.RESULT_BYTES
    }

    @Test
    fun `a result past its budget in UTF-8 bytes is RESULT_BYTES - the host's count is the boundary`() {
        // 602 characters pass the prelude's aid; 1 202 UTF-8 bytes do not pass the host.
        val refused = refusal<ScriptResourceLimitException> { js("return 'é'.repeat(600);", limits = limits(maxResultBytes = 1000)) }
        refused.kind shouldBe ScriptResourceLimitException.Kind.RESULT_BYTES
        refused.message shouldContain "1202 bytes"
    }

    @Test
    fun `a per-call limit lowers the runtime's ceiling, never raises it`() {
        val engine = JavaScriptEngine(JavaScriptRuntime(JavaScriptRuntimeOptions(maxStatements = 10_000)))
        val script = engine.compile("let i = 0; for (;;) { i++; }")
        refusal<ScriptResourceLimitException> { engine.evaluate(script, null, limits(maxStatements = 1_000_000_000)) }.kind shouldBe
            ScriptResourceLimitException.Kind.STATEMENTS
    }
}
