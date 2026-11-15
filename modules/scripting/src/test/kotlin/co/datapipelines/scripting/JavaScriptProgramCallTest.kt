package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptResourceLimitException.Kind
import co.datapipelines.scripting.ScriptingTestSupport.limits
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The entry for trusted product code, [JavaScriptEngine.call] over a [JavaScriptProgram] (brief 986 D1, A.1; record
 * §5.3 "Named outcomes", A13): JSON text in, JSON text out; a looping program ends on the STATEMENT limit (its Source is
 * counted), an allocating one on HEAP, an input and a result past their byte bounds on their own kinds, deep recursion
 * on DEPTH; the call after each succeeds; of four calls at once with one looping, the other three succeed.
 */
class JavaScriptProgramCallTest {
    private val engine = JavaScriptEngine(ScriptingTestSupport.javaScriptRuntime)

    private fun program(body: String) = JavaScriptProgram.of("entry-test.js", "(function (json) { $body })")

    private val echo = program("var v = JSON.parse(json); return JSON.stringify({ got: v, n: v.n * 2 });")

    private fun clean() = withClue("the call after a limit") { engine.call(echo, "{\"n\":21}", limits()) shouldBe "{\"got\":{\"n\":21},\"n\":42}" }

    private fun kind(
        program: JavaScriptProgram,
        input: String = "{}",
        limits: EvaluationLimits,
    ): Kind = refusal<ScriptResourceLimitException> { engine.call(program, input, limits) }.kind

    @Test
    fun `JSON text in, JSON text out`() {
        clean()
    }

    @Test
    fun `a looping program ends on the statement limit - its Source is counted - and the next call is clean`() {
        // The wall clock (10 s) cannot fire first: 10 000 statements run in well under a millisecond.
        kind(program("for (;;) {}"), limits = limits(maxStatements = 10_000)) shouldBe Kind.STATEMENTS
        clean()
    }

    @Test
    fun `an allocating program ends on HEAP, and the next call is clean`() {
        kind(
            program("var a = []; for (;;) { a.push('x'.repeat(1000) + a.length); }"),
            limits = limits(maxHeapBytes = 16L * MIB),
        ) shouldBe Kind.HEAP
        clean()
    }

    @Test
    fun `an input past its byte bound is INPUT_BYTES before any sandbox exists, and the next call is clean`() {
        val engines = AtomicInteger()
        val runtime = JavaScriptRuntime(JavaScriptRuntimeOptions()) { _: Engine -> engines.incrementAndGet() }
        val own = JavaScriptEngine(runtime)
        own.call(echo, "{\"n\":1}", limits())
        val built = engines.get()
        val refused = refusal<ScriptResourceLimitException> { own.call(echo, "\"" + "é".repeat(600) + "\"", limits(maxInputBytes = 1000)) }
        refused.kind shouldBe Kind.INPUT_BYTES
        refused.message shouldContain "1202 bytes"
        engines.get() shouldBe built
        own.call(echo, "{\"n\":21}", limits()) shouldBe "{\"got\":{\"n\":21},\"n\":42}"
        runtime.close()
    }

    @Test
    fun `a result past its byte bound in UTF-8 is RESULT_BYTES, and the next call is clean`() {
        val refused =
            refusal<ScriptResourceLimitException> {
                engine.call(program("return JSON.stringify('é'.repeat(600));"), "{}", limits(maxResultBytes = 1000))
            }
        refused.kind shouldBe Kind.RESULT_BYTES
        refused.message shouldContain "1202 bytes"
        clean()
    }

    @Test
    fun `recursion past the depth bound is DEPTH, and the next call is clean`() {
        val recurse = program("function r(n) { return n === 0 ? 0 : 1 + r(n - 1); } return String(r(JSON.parse(json)));")
        engine.call(recurse, "10", limits(maxDepth = 50)) shouldBe "10"
        kind(recurse, "100000", limits(maxDepth = 50)) shouldBe Kind.DEPTH
        clean()
    }

    @Test
    fun `the wall clock stops a program that the statement budget cannot, and the next call is clean`() {
        // Builtin-bound: a few statements per pass (JavaScriptEngineBoundsTest's BUSY).
        refusal<ScriptTimeoutException> {
            engine.call(program(BUSY), "{}", limits(Duration.ofMillis(500)))
        }
        clean()
    }

    @Test
    fun `a program that throws, one that is not a function and one that returns a non-string are refused`() {
        refusal<ScriptEvaluationException> { engine.call(program("throw new Error('boom');"), "{}", limits()) }.message shouldContain "boom"
        refusal<ScriptEvaluationException> {
            engine.call(JavaScriptProgram.of("not-a-function.js", "42"), "{}", limits())
        }.message shouldContain "does not evaluate to a function"
        refusal<ScriptEvaluationException> { engine.call(program("return 42;"), "{}", limits()) }.message shouldContain "non-string"
        clean()
    }

    @Test
    fun `nothing survives a call - each call runs in a fresh Context`() {
        val counter = program("globalThis.k = (globalThis.k || 0) + 1; return String(globalThis.k);")
        engine.call(counter, "{}", limits()) shouldBe "1"
        engine.call(counter, "{}", limits()) shouldBe "1"
    }

    @Test
    fun `a program needs a name that is not the author source's`() {
        refusal<IllegalArgumentException> { JavaScriptProgram.of(" ", "1") }
        refusal<IllegalArgumentException> { JavaScriptProgram.of(JavaScriptEngine.AUTHOR_SOURCE_NAME, "1") }
    }

    @Test
    fun `of four calls at once on the pool with one looping, the other three succeed`() {
        val pool = ScriptEvaluationPool(POOL, POOL * 2, Duration.ofSeconds(5), ScriptEvaluationPool.SYSTEM)
        val loop = program(BUSY)
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedMap(mutableMapOf<Int, Any>())
        val threads =
            List(POOL) { index ->
                Thread {
                    start.await()
                    outcomes[index] =
                        runCatching {
                            if (index == 0) {
                                pool.run(limits(LOOP_WALL)) { engine.call(loop, "{}", limits(LOOP_WALL)) }
                            } else {
                                pool.run(limits()) { engine.call(echo, "{\"n\":$index}", limits()) }
                            }
                        }.fold({ it }, { it })
                }
            }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        withClue("the looping call") { (outcomes[0] is ScriptTimeoutException) shouldBe true }
        (1 until POOL).forEach { index ->
            withClue("call $index beside the loop") { outcomes[index] shouldBe "{\"got\":{\"n\":$index},\"n\":${index * 2}}" }
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val POOL = 4
        val LOOP_WALL: Duration = Duration.ofSeconds(2)

        /** Builtin-bound: a few statements per pass, so only the clock ends it. */
        const val BUSY = "var s = 'x'.repeat(1 << 16); for (;;) { s.split('').reverse().join(''); }"
    }
}
