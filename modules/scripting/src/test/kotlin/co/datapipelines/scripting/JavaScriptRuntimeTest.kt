package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.limits
import co.datapipelines.scripting.ScriptingTestSupport.refusal
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.junit.jupiter.api.Test
import java.util.Collections

/** [JavaScriptRuntime] (#8 A.2): availability, the per-JVM arm, realm and fault isolation, the closed option set. */
class JavaScriptRuntimeTest {
    @Test
    fun `the isolate is available on the test class path`() {
        JavaScriptRuntime().availability() shouldBe Availability.Available
    }

    @Test
    fun `a runtime whose options the policy refuses is unavailable, with the polyglot reason`() {
        val runtime = JavaScriptRuntime(JavaScriptRuntimeOptions(ecmascriptVersion = "staging"))
        val verdict = runtime.availability().shouldBeInstanceOf<Availability.Unavailable>()
        verdict.reason shouldContain "js.ecmascript-version option may only be set to \"staging\" in sandbox policy TRUSTED"
        val refused = refusal<ScriptEvaluationException> { JavaScriptEngine(runtime).compile("return 1;") }
        refused.message shouldContain "the JavaScript engine is unavailable: "
    }

    @Test
    fun `a closed runtime refuses every later evaluation as unavailable`() {
        val runtime = JavaScriptRuntime()
        val engine = JavaScriptEngine(runtime)
        val script = engine.compile("return 1;")
        runtime.close()
        refusal<ScriptEvaluationException> { engine.evaluate(script, null, limits()) }.message shouldContain
            "the JavaScript runtime is closed"
    }

    @Test
    fun `the scope is per JVM - every compile and evaluation shares the runtime's one Engine`() {
        val seen = Collections.synchronizedList(ArrayList<Engine>())
        val runtime = JavaScriptRuntime(JavaScriptRuntimeOptions()) { seen.add(it) }
        runtime.scope shouldBe EngineScope.PerJvm
        val engine = JavaScriptEngine(runtime)
        val script = engine.compile("return input + 1;")
        repeat(3) { engine.evaluate(script, it, limits()) shouldBe (it + 1).toLong() }
        seen shouldHaveSize 1
    }

    @Test
    fun `each evaluation gets its own realm - a global one body writes is gone for the next`() {
        val engine = JavaScriptEngine(JavaScriptRuntime())
        engine.evaluate(engine.compile("globalThis.leak = 41; Array.prototype.extra = 1; return 1;"), null, limits()) shouldBe 1L
        engine.evaluate(engine.compile("return [typeof globalThis.leak, typeof [].extra];"), null, limits()) shouldBe
            listOf("undefined", "undefined")
    }

    @Test
    fun `a heap trip in one evaluation leaves the next one untouched`() {
        val engine = JavaScriptEngine(JavaScriptRuntime())
        val hog = engine.compile("const a = []; for (;;) { a.push('x'.repeat(1000) + a.length); }")
        refusal<ScriptResourceLimitException> {
            engine.evaluate(hog, null, limits(maxHeapBytes = 16L * 1024 * 1024))
        }.kind shouldBe ScriptResourceLimitException.Kind.HEAP
        engine.evaluate(engine.compile("return 1 + 1;"), null, limits()) shouldBe 2L
    }

    @Test
    fun `an isolate-memory trip is contained - the same Engine serves the next evaluation`() {
        // maxHeapBytes at the isolate's own size: the isolate's hard cap is what trips (A.0 step 6:
        // the Context dies alone, the Engine survives — so the runtime keeps it, never rebuilds).
        val mib = 1024L * 1024
        val seen = Collections.synchronizedList(ArrayList<Engine>())
        val runtime = JavaScriptRuntime(JavaScriptRuntimeOptions(isolateMemoryBytes = 64 * mib, maxHeapBytes = 64 * mib)) { seen.add(it) }
        val engine = JavaScriptEngine(runtime)
        val hog = engine.compile("const a = []; for (;;) { a.push('x'.repeat(4096) + a.length); }")
        refusal<ScriptResourceLimitException> { engine.evaluate(hog, null, limits()) }.kind shouldBe
            ScriptResourceLimitException.Kind.HEAP
        engine.evaluate(engine.compile("return 1 + 1;"), null, limits()) shouldBe 2L
        runtime.availability() shouldBe Availability.Available
        seen shouldHaveSize 1
    }

    @Test
    fun `a polyglot system property cannot reach the Engine`() {
        // With polyglot's default the planted property reaches the Engine and the policy refuses it
        // ("engine.Compilation can only be used up to the TRUSTED sandbox policy", measured).
        val key = "polyglot.engine.Compilation"
        System.setProperty(key, "false")
        try {
            JavaScriptRuntime().availability() shouldBe Availability.Available
        } finally {
            System.clearProperty(key)
        }
    }

    @Test
    fun `every option name the factory sets exists - and a misspelt one fails construction loudly`() {
        // Available means the full option set built an Engine and a Context (sandbox.MaxStackFrames,
        // js.ecmascript-version, js.locale, js.load, js.graal-builtin, js.console among them): polyglot
        // refuses an unknown name at build, never ignores it — the misspelling below proves which.
        JavaScriptRuntime().availability() shouldBe Availability.Available
        val refused =
            runCatching {
                Context
                    .newBuilder("js")
                    .option("sandbox.MaxStackFramez", "10")
                    .build()
                    .close()
            }.exceptionOrNull()
        (refused is IllegalArgumentException) shouldBe true
        refused!!.message shouldContain "sandbox.MaxStackFramez"
    }
}
