package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.DEFAULT_LIMITS
import co.datapipelines.scripting.ScriptingTestSupport.limits
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The conformance suite (§4.1): one battery parameterised over every ScriptEngine
 * implementation. A second engine is ADDED to the list below and inherits every case —
 * it never adds cases (engine-specific facts live in engine-specific suites).
 *
 * Each case carries its program PER LANGUAGE — the JSONata expression and its JavaScript twin,
 * the statements of `main(input)` (#8) — so every case means the same thing in both. Results
 * compare under [CanonicalJson.equal]: the engines may spell a number differently (an `Int` from
 * JSONata, a `Long` from JavaScript), never differ in its value.
 *
 * Cases: input shape, each limit firing (heap and statements are SKIPPED with the
 * capability flag as the printed reason where an engine cannot enforce them),
 * cancellation from outside, a syntax error at compile, determinism (serial and the
 * 32-thread edition — see [JsonataEngineParallelDeterminismTest] for the full sweep),
 * no host access, and an eval'd overrun under the pool's bound.
 */
class ScriptEngineConformanceTest {
    /** The engines under conformance — JSONata and JavaScript (#8). */
    private val engines: List<ScriptEngine> = listOf(ScriptingTestSupport.engine, ScriptingTestSupport.javaScript)

    private fun ScriptEngine.program(programs: Map<ScriptLanguage, String>): CompiledScript = compile(programs.getValue(type))

    @Test
    fun `the battery covers both languages`() {
        engines.map { it.type }.toSet() shouldBe ScriptLanguage.entries.toSet()
    }

    @Test
    fun `every engine round-trips the input shapes`() {
        val identity = mapOf(ScriptLanguage.JSONATA to "$", ScriptLanguage.JAVASCRIPT to "return input;")
        engines.forEach { engine ->
            val script = engine.program(identity)
            val shapes: List<Any?> =
                listOf(
                    "s",
                    1,
                    2.5,
                    true,
                    listOf(1, listOf(2, linkedMapOf("a" to null))),
                    linkedMapOf("k" to listOf(true, null)),
                )
            shapes.forEach { shape ->
                withClue("${engine.type} $shape") {
                    CanonicalJson.equal(engine.evaluate(script, shape, DEFAULT_LIMITS), shape) shouldBe true
                }
            }
        }
    }

    @Test
    fun `every engine enforces the limits its capabilities claim - and skips the rest honestly`() {
        engines.forEach { engine ->
            val caps = engine.capabilities

            if (caps.boundsWallClockBetweenSteps) {
                // Bounded nesting, unbounded steps: the wall clock fires where no depth can. The JS twin
                // spends its time in builtins, a few statements per pass, so the statement budget
                // cannot fire first.
                val busy =
                    mapOf(
                        ScriptLanguage.JSONATA to "${'$'}reduce(rows, function(${'$'}a, ${'$'}b){ ${'$'}a + 1 })",
                        ScriptLanguage.JAVASCRIPT to
                            "const s = 'x'.repeat(1 << 16); for (;;) { s.split('').reverse().join(''); }",
                    )
                val caught =
                    runCatching {
                        engine.evaluate(engine.program(busy), mapOf("rows" to List(2_000_000) { 1 }), limits(Duration.ofMillis(300)))
                    }.exceptionOrNull()
                withClue("${engine.type} wall clock: $caught") { (caught is ScriptTimeoutException) shouldBe true }
            }
            if (caps.boundsDepth) {
                val deep =
                    mapOf(
                        ScriptLanguage.JSONATA to "[".repeat(500) + "0" + "]".repeat(500),
                        ScriptLanguage.JAVASCRIPT to "function r(n) { return n === 0 ? 0 : 1 + r(n - 1); } return r(500);",
                    )
                val caught =
                    runCatching {
                        engine.evaluate(engine.program(deep), null, limits(Duration.ofSeconds(30), maxDepth = 20))
                    }.exceptionOrNull()
                withClue("${engine.type} depth: $caught") {
                    (caught as? ScriptResourceLimitException)?.kind shouldBe ScriptResourceLimitException.Kind.DEPTH
                }
            }
            if (caps.boundsHeap) {
                val hog =
                    mapOf(
                        ScriptLanguage.JSONATA to "",
                        ScriptLanguage.JAVASCRIPT to "const a = []; for (;;) { a.push('x'.repeat(1000) + a.length); }",
                    )
                val caught =
                    runCatching {
                        engine.evaluate(engine.program(hog), null, limits(maxHeapBytes = 32L * 1024 * 1024))
                    }.exceptionOrNull()
                withClue("${engine.type} heap: $caught") {
                    (caught as? ScriptResourceLimitException)?.kind shouldBe ScriptResourceLimitException.Kind.HEAP
                }
            }
            if (caps.boundsStatements) {
                val spin = mapOf(ScriptLanguage.JSONATA to "", ScriptLanguage.JAVASCRIPT to "let i = 0; for (;;) { i++; }")
                val caught =
                    runCatching { engine.evaluate(engine.program(spin), null, limits(maxStatements = 10_000)) }.exceptionOrNull()
                withClue("${engine.type} statements: $caught") {
                    (caught as? ScriptResourceLimitException)?.kind shouldBe ScriptResourceLimitException.Kind.STATEMENTS
                }
            }
            reportSkips(engine.type, caps)
        }
    }

    /**
     * Skipped with the capability flag as the printed reason — these are refusals the seam CANNOT
     * make in-process, and the suite says so instead of pretending to test them.
     */
    private fun reportSkips(
        type: ScriptLanguage,
        caps: EngineCapabilities,
    ) {
        listOf("heap" to caps.boundsHeap, "statements" to caps.boundsStatements).forEach { (name, bounded) ->
            println(
                "[conformance] engine=$type limit=$name bounded=$bounded" +
                    if (bounded) "" else " — SKIPPED: EngineCapabilities.bounds$name is false (not enforceable in-process)",
            )
        }
    }

    @Test
    fun `every engine surfaces cancellation from outside through the pool`() {
        val endless =
            mapOf(
                ScriptLanguage.JSONATA to "${'$'}reduce(rows, function(${'$'}a, ${'$'}b){ ${'$'}a + 1 })",
                ScriptLanguage.JAVASCRIPT to "const s = 'x'.repeat(1 << 16); for (;;) { s.split('').reverse().join(''); }",
            )
        engines.forEach { engine ->
            // The body never refuses ITSELF (60-second clock, bounded nesting): it keeps stepping for
            // seconds, so the only bound that fires is the pool's — the caller's timeout plus the
            // abandoned thread's slot (#260: the old lambda-loop body is a DEPTH refusal now and could
            // not exercise this path).
            val pool = ScriptEvaluationPool(1, 1, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM)
            val script = engine.program(endless)
            val timeout = AtomicReference<ScriptTimeoutException>()
            val caller =
                Thread {
                    try {
                        pool.run(EvaluationLimits(Duration.ofMillis(200), 100), "conformance-cancel") {
                            engine.evaluate(script, mapOf("rows" to List(2_000_000) { 1 }), limits(Duration.ofSeconds(60)))
                        }
                    } catch (err: ScriptTimeoutException) {
                        timeout.set(err)
                    }
                }
            caller.start()
            caller.join(10_000)
            withClue(engine.type) { timeout.get().shouldNotBeNull() }
            pool.abandoned.sum() shouldBe 1
        }
    }

    @Test
    fun `every engine refuses a syntax error at compile with position`() {
        val broken = mapOf(ScriptLanguage.JSONATA to "1 +", ScriptLanguage.JAVASCRIPT to "return 1 +;")
        engines.forEach { engine ->
            val caught = runCatching { engine.program(broken) }.exceptionOrNull()
            val syntax = caught.shouldNotBeNull() as ScriptSyntaxException
            syntax.line shouldBe 1
            (syntax.column >= 1) shouldBe true
        }
    }

    @Test
    fun `every engine is deterministic - same input, same output, twice`() {
        val double = mapOf(ScriptLanguage.JSONATA to "\$ * 2", ScriptLanguage.JAVASCRIPT to "return input * 2;")
        engines.forEach { engine ->
            val script = engine.program(double)
            val first = engine.evaluate(script, 21, DEFAULT_LIMITS)
            val second = engine.evaluate(script, 21, DEFAULT_LIMITS)
            first shouldBe second
            CanonicalJson.equal(first, 42) shouldBe true
        }
    }

    @Test
    fun `every engine has no host access - the deny-list names are absent from the catalogue`() {
        // JSONata: `$eval` of a Java name finds nothing (null). JavaScript: every name a host or
        // module bridge would answer to is undefined in the guest (`import(` is a breach-suite row —
        // its refusal is asynchronous, so it cannot be read inside one evaluation).
        val probe =
            mapOf(
                ScriptLanguage.JSONATA to "\$not(\$exists(\$eval(\"java.io.File\")))",
                ScriptLanguage.JAVASCRIPT to
                    "return [typeof Java, typeof Polyglot, typeof load, typeof require, typeof fetch, typeof process, " +
                    "typeof Packages, typeof java, typeof Graal].every(t => t === 'undefined');",
            )
        engines.forEach { engine ->
            withClue(engine.type) { engine.evaluate(engine.program(probe), null, DEFAULT_LIMITS) shouldBe true }
        }
    }

    @Test
    fun `every engine runs eval under the same bound - the pool's, for a builtin overrun`() {
        // JSONata: `$eval` of a builtin that never reaches a step boundary. JavaScript: `eval` of the
        // same builtin-bound loop — the eval'd code runs in the same Context, under the same bounds.
        val evalOverrun =
            mapOf(
                ScriptLanguage.JSONATA to "\$eval(\"\$pad(\\\"x\\\", 100000000)\")",
                ScriptLanguage.JAVASCRIPT to
                    "return eval(\"const s = 'x'.repeat(1 << 16); for (;;) { s.split('').reverse().join(''); }\");",
            )
        engines.forEach { engine ->
            val pool = ScriptEvaluationPool(1, 1, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM)
            val script = engine.program(evalOverrun)
            val timeout = AtomicReference<ScriptTimeoutException>()
            val caller =
                Thread {
                    try {
                        pool.run(limits(Duration.ofMillis(400)), "conformance-eval") {
                            engine.evaluate(script, null, limits(Duration.ofSeconds(60)))
                        }
                    } catch (err: ScriptTimeoutException) {
                        timeout.set(err)
                    }
                }
            caller.start()
            caller.join(10_000)
            withClue(engine.type) { timeout.get().shouldNotBeNull().code shouldBe "pipeline.script.timeout" }
        }
    }
}
