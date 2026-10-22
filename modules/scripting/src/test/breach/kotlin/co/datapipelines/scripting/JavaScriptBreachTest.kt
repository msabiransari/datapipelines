package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The JavaScript breach suite (transform-nodes design §4.5, #8 lane 8a A.4): every bomb and every
 * escape attempt the brief lists, each through [JavaScriptEngine] on a fresh 1/1
 * [ScriptEvaluationPool], each in its own Context (one per evaluation on the runtime's one Engine), with the
 * three-way outcome of [JsonataBreachTest] — REFUSED (a typed refusal), BOUNDED (the timeout fired
 * and the thread ended inside the grace, or the body completed inside every budget), UNBOUNDED (the
 * thread outlived the grace, or the heap blew past the JVM).
 *
 * Every row asserts the EXACT refusal, never "it threw": the predicted outcome AND a pattern over
 * the evidence naming the [ScriptResourceLimitException.Kind], [ScriptTimeoutException] or the
 * guest's own text. A probe that passes on any exception proves nothing — a body refused for the
 * wrong reason (a typo, a syntax error) would read as a wall that held.
 *
 * The predictions were MEASURED (2026-10-21, GraalJS 25.4.4.1.1 isolate, this JVM at 512m, the
 * engine's default [JavaScriptRuntimeOptions]); the report `build/reports/javascript-breach.md` is
 * the record 8b publishes in dag-executor.md. Five of them answer or correct the brief:
 *
 *  - `while (true) {}` under the defaults is a STATEMENTS refusal in well under a second (100 M
 *    statements), not a timeout; the watchdog row lifts the statement cap to reach the wall clock;
 *  - string doubling to 1 GB is neither HEAP nor RESULT_BYTES: GraalJS's own string-length cap
 *    throws `RangeError: Invalid string length` at the 2^30 step;
 *  - `console.log` of 2 MB is OUTPUT (the stream's cap cancels the Context), not the delivery's
 *    2 000-character truncation, which applies only to output inside the cap;
 *  - `new Array(1e9).fill(0)` is HEAP by one of two messages, varying run to run (the row accepts
 *    both, and nothing else);
 *  - `Atomics.wait` is refused (`TypeError: Unsupported operation`): the evaluation thread may not
 *    block, so no wait outlives the watchdog. The isolate's heap is NOT this JVM's: its bombs are
 * bounded by `sandbox.MaxHeapMemory` inside the isolate, and the JVM's headroom check guards the
 * host side (the input encoder, the result decoder) exactly as it does for JSONata.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaScriptBreachTest {
    /**
     * One case: the body, the input it gets (built inside the measured block, so its heap is the
     * case's), the limits, the runtime (the defaults unless the case needs one bound out of the way),
     * the predicted outcome and the pattern the evidence must match.
     */
    private class Case(
        val name: String,
        val body: String,
        val predicted: BreachOutcome,
        expect: String,
        val limits: EvaluationLimits = LIMITS,
        val runtime: JavaScriptRuntime = DEFAULTS,
        val input: () -> Any? = { null },
    ) {
        val expect = Regex(expect)
    }

    private val cases =
        listOf(
            // ---- the resource bounds ---------------------------------------------------------
            // The statement cap lifted, so the watchdog (and MaxCPUTime beside it) is the only bound.
            Case(
                "while-true (watchdog)",
                "while (true) {}",
                BreachOutcome.BOUNDED,
                "^ScriptTimeoutException at 2000 ms; thread ended inside the grace$",
                limits = LIMITS.copy(wallClock = Duration.ofSeconds(2)),
                runtime = WATCHDOG_ONLY,
            ),
            // The same loop under the defaults: 100 M statements trip long before any wall clock.
            Case("while-true (defaults)", "while (true) {}", BreachOutcome.REFUSED, KIND_STATEMENTS),
            Case(
                "for-i++ (statements low, CPU high)",
                "let i = 0;\nfor (;;) i++;",
                BreachOutcome.REFUSED,
                KIND_STATEMENTS,
                limits = LIMITS.copy(wallClock = Duration.ofSeconds(30), maxStatements = 1_000_000),
            ),
            // HEAP either way, by two different messages: the sandbox's cap ("Maximum heap memory limit",
            // most runs) or the isolate's own allocator ("Array allocation too large.", one breach run in two).
            Case("array-fill 1e9", "return new Array(1e9).fill(0).length;", BreachOutcome.REFUSED, KIND_HEAP),
            // A loop inside ONE builtin over a sparse array: no statement and no retained heap, so only
            // the wall clock (the watchdog and MaxCPUTime) stops it.
            Case(
                "builtin fill over 2^31 sparse slots",
                "return new Array(2 ** 31).fill(0).length;",
                BreachOutcome.BOUNDED,
                "^ScriptTimeoutException at 2000 ms; thread ended inside the grace$",
                limits = LIMITS.copy(wallClock = Duration.ofSeconds(2)),
            ),
            // Neither HEAP nor RESULT_BYTES (the brief asked which): the language's own string-length
            // cap refuses the 2^30-character step first, a guest RangeError the body could catch.
            Case(
                "string-doubling to 1 GB",
                "let s = 'x';\nwhile (s.length < 1 << 30) s += s;\nreturn s;",
                BreachOutcome.REFUSED,
                guest("RangeError: Invalid string length"),
            ),
            Case(
                "recursion 10 000 deep (maxDepth 100)",
                "function f(n) { return n === 0 ? 0 : 1 + f(n - 1); }\nreturn f(10000);",
                BreachOutcome.REFUSED,
                "^ScriptResourceLimitException: resource limit hit \\(DEPTH\\): Maximum frame limit of 104 exceeded",
            ),
            Case(
                "nested literal 5 000 deep (parse)",
                "return " + "[".repeat(DEEP_LITERAL) + "]".repeat(DEEP_LITERAL) + ";",
                BreachOutcome.REFUSED,
                "^ScriptResourceLimitException: resource limit hit \\(DEPTH\\): the body nests deeper than the parser allows",
            ),
            Case(
                "input of 1 GB",
                "return 1;",
                BreachOutcome.REFUSED,
                "^ScriptResourceLimitException: resource limit hit \\(INPUT_BYTES\\)",
                // A million references to one 1 KiB string: ~1 GiB serialized, ~8 MB held by the JVM.
                input = { List(1 shl 20) { KIB_STRING } },
            ),
            Case(
                "result of 100 MB",
                "return 'x'.repeat(100 * 1024 * 1024);",
                BreachOutcome.REFUSED,
                "^ScriptResourceLimitException: resource limit hit \\(RESULT_BYTES\\)",
            ),
            Case(
                "console.log of 2 MB",
                "console.log('x'.repeat(2e6));\nreturn 1;",
                BreachOutcome.REFUSED,
                "^ScriptResourceLimitException: resource limit hit \\(OUTPUT\\): Maximum output stream size of 1048576 exceeded",
            ),
            // ---- the escapes ------------------------------------------------------------------
            Case(
                "Java.type('java.lang.Runtime')",
                "return Java.type('java.lang.Runtime').getRuntime();",
                BreachOutcome.REFUSED,
                guest("ReferenceError: Java is not defined"),
            ),
            Case(
                "Polyglot.eval('js', '1')",
                "return Polyglot.eval('js', '1');",
                BreachOutcome.REFUSED,
                guest("ReferenceError: Polyglot is not defined"),
            ),
            Case("fetch('http://x')", "return fetch('http://x');", BreachOutcome.REFUSED, guest("ReferenceError: fetch is not defined")),
            Case(
                "load('file:///etc/passwd')",
                "return load('file:///etc/passwd');",
                BreachOutcome.REFUSED,
                guest("ReferenceError: load is not defined"),
            ),
            Case(
                "import('x')",
                "return import('x');",
                BreachOutcome.REFUSED,
                shape("result is not JSON-shaped: result is a Promise \\(main must return synchronously\\)"),
            ),
            Case("require('fs')", "return require('fs');", BreachOutcome.REFUSED, guest("ReferenceError: require is not defined")),
            Case("process.exit()", "process.exit(1);", BreachOutcome.REFUSED, guest("ReferenceError: process is not defined")),
            Case("globalThis.Java", "return globalThis.Java;", BreachOutcome.REFUSED, shape("main returned nothing")),
            Case(
                "new WebAssembly.Module",
                "return new WebAssembly.Module(new Uint8Array());",
                BreachOutcome.REFUSED,
                guest("ReferenceError: WebAssembly is not defined"),
            ),
            // The one blocking call the globals offer: the evaluation thread's agent may not block, so
            // it is refused outright rather than parked past the watchdog (a wait excluded from CPU time).
            Case(
                "Atomics.wait on the evaluation thread",
                "return Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0);",
                BreachOutcome.REFUSED,
                guest("TypeError: Unsupported operation"),
            ),
            // ---- determinism ------------------------------------------------------------------
            Case(
                "Date.now() unpinned",
                "return Date.now();",
                BreachOutcome.REFUSED,
                refused("Date.now\\(\\) refused: no pinned clock"),
                limits = LIMITS.copy(now = null),
            ),
            Case("Math.random()", "return Math.random();", BreachOutcome.REFUSED, refused("Math.random\\(\\) refused")),
            Case(
                "Math.random() caught",
                "try { Math.random(); } catch (e) { /* swallowed */ }\nreturn 1;",
                BreachOutcome.REFUSED,
                refused("Math.random\\(\\) refused"),
            ),
            Case(
                "busy wait on a pinned clock",
                "const t = Date.now();\nwhile (Date.now() - t < 5000) {}\nreturn 1;",
                BreachOutcome.REFUSED,
                KIND_STATEMENTS,
            ),
            // ---- the frame and the result's shape ----------------------------------------------
            Case("a body that never returns", "let x = 1;", BreachOutcome.REFUSED, shape("main returned nothing")),
            Case(
                "closes main's brace, top-level code",
                "} globalThis.x = 1; function y() {",
                BreachOutcome.REFUSED,
                shape("main returned nothing"),
            ),
            Case(
                "a nested function main (shadowing)",
                "function main() { return 'inner'; }\nreturn main();",
                BreachOutcome.BOUNDED,
                "^completed: inner$",
            ),
            Case("return {x: NaN}", "return {x: NaN};", BreachOutcome.REFUSED, shape("result is not JSON-shaped: result\\.x is NaN")),
            Case("return new Map()", "return new Map();", BreachOutcome.REFUSED, shape("result is not JSON-shaped: result is a Map")),
            Case(
                "return Promise.resolve(1)",
                "return Promise.resolve(1);",
                BreachOutcome.REFUSED,
                shape("result is not JSON-shaped: result is a Promise \\(main must return synchronously\\)"),
            ),
            // The prelude's check is an aid: its intrinsics are captured before the body runs, so a
            // body that reassigns them changes nothing it uses — and the host's strict decode stays the wall.
            Case(
                "reassigns JSON.stringify, then a Map",
                "JSON.stringify = () => '{}';\nglobalThis.__dp_check = () => true;\nreturn new Map();",
                BreachOutcome.REFUSED,
                shape("result is not JSON-shaped: result is a Map"),
            ),
            // ---- the baseline -----------------------------------------------------------------
            Case(
                "100 000 rows, identity",
                "return input;",
                BreachOutcome.BOUNDED,
                "^completed: a list of 100000 items$",
                input = { List(BASELINE_ROWS) { mapOf("id" to it.toLong(), "name" to "row $it", "amount" to it * 2L) } },
            ),
        )

    @AfterAll
    fun closeRuntimes() {
        DEFAULTS.close()
        WATCHDOG_ONLY.close()
    }

    @Test
    fun `every case's measured outcome and refusal match the prediction, and the report lands`() {
        BreachHarness.requireCalibratedHeap()
        withClue("the isolate must start here, or every row would read the same unavailable refusal") {
            DEFAULTS.availability() shouldBe Availability.Available
        }

        val rows = cases.map { case -> BreachHarness.measure(case.name) { heapBefore, start -> classify(case, heapBefore, start) } }
        val report = BreachHarness.writeReport("javascript-breach.md", "JavaScript breach suite — measured outcomes (#8 lane 8a)", rows)

        rows.zip(cases).forEach { (row, case) ->
            withClue("case '${case.name}': measured $row") {
                row.outcome shouldBe case.predicted
                case.expect.containsMatchIn(row.evidence) shouldBe true
            }
        }

        // Non-vacuity: the brief's floor is 24 rows, every one materialised in the report.
        rows.size shouldBe cases.size
        rows.size shouldBeGreaterThanOrEqual CORPUS_FLOOR
        Files.readAllLines(report).size shouldBe (rows.size + BreachHarness.REPORT_HEADER_LINES)
    }

    /**
     * The bomb and everything after it ([BreachHarness.measure]'s guarded body). Compile is inside
     * the classified block: a refusal at parse is a REFUSED row like any other. A timeout's
     * seconds are read when the caller gets it; the grace is waited only when the pool reports the
     * thread still alive, so "seconds ≈ wall clock" is what the row shows.
     */
    private fun classify(
        case: Case,
        heapBefore: Long,
        start: Long,
    ): BreachRow {
        val pool = ScriptEvaluationPool(1, 1, GRACE, ScriptEvaluationPool.SYSTEM)
        val engine = JavaScriptEngine(case.runtime)
        var evidence: String
        var outcome: BreachOutcome
        var seconds: Long? = null
        var result: Any? = null
        val threadHeapBlowout = AtomicReference<OutOfMemoryError?>(null)

        try {
            val script = engine.compile(case.body)
            val input = case.input()
            result =
                pool.run(case.limits, case.name) {
                    try {
                        engine.evaluate(script, input, case.limits)
                    } catch (err: OutOfMemoryError) {
                        threadHeapBlowout.set(err)
                        throw err
                    }
                }
            outcome = BreachOutcome.BOUNDED
            evidence = "completed: ${summary(result)}"
        } catch (
            @Suppress("SwallowedException") err: ScriptTimeoutException,
        ) {
            seconds = (System.nanoTime() - start) / BreachHarness.NANOS_PER_SECOND
            if (pool.abandonedThreadsAlive() > 0) Thread.sleep(GRACE.toMillis())
            val wall = case.limits.wallClock.toMillis()
            val blowout = threadHeapBlowout.get()
            when {
                blowout != null -> {
                    outcome = BreachOutcome.UNBOUNDED
                    evidence = "ScriptTimeoutException at $wall ms; the thread then died of OutOfMemoryError: ${blowout.message?.take(40)}"
                }

                pool.abandonedThreadsAlive() > 0 -> {
                    outcome = BreachOutcome.UNBOUNDED
                    evidence = "ScriptTimeoutException at $wall ms; thread still alive at the grace"
                }

                else -> {
                    outcome = BreachOutcome.BOUNDED
                    evidence = "ScriptTimeoutException at $wall ms; thread ended inside the grace"
                }
            }
        } catch (
            @Suppress("SwallowedException") err: OutOfMemoryError,
        ) {
            outcome = BreachOutcome.UNBOUNDED
            evidence = "OutOfMemoryError: ${err.message?.take(60)}"
        } catch (err: ScriptingException) {
            outcome = BreachOutcome.REFUSED
            evidence = "${err.javaClass.simpleName}: ${err.message?.take(EVIDENCE_CHARS)}"
        }

        val lived = seconds ?: ((System.nanoTime() - start) / BreachHarness.NANOS_PER_SECOND)
        val heapDelta = BreachHarness.usedHeapMb() - heapBefore
        result = null
        return BreachRow(case.name, outcome, evidence, lived, heapDelta)
    }

    private fun summary(result: Any?): String =
        when (result) {
            is List<*> -> "a list of ${result.size} items"
            else -> result.toString().take(EVIDENCE_CHARS)
        }

    private companion object {
        /** The pool's abandonment grace, as in [JsonataBreachTest]. */
        val GRACE: Duration = Duration.ofSeconds(5)

        /** The suite's budget: 10 s, depth 100, a pinned clock; each case lowers what it measures. */
        val LIMITS = EvaluationLimits(wallClock = Duration.ofSeconds(10), maxDepth = 100, now = Instant.parse("2026-10-21T12:00:00Z"))

        /** The engine's defaults: what production gets. */
        val DEFAULTS = JavaScriptRuntime()

        /** The defaults with the statement cap out of the way, so a loop meets the watchdog. */
        val WATCHDOG_ONLY = JavaScriptRuntime(JavaScriptRuntimeOptions(maxStatements = Long.MAX_VALUE))

        const val CORPUS_FLOOR = 24
        const val DEEP_LITERAL = 5000
        const val BASELINE_ROWS = 100_000
        const val EVIDENCE_CHARS = 160
        val KIB_STRING = "x".repeat(1023)

        const val KIND_STATEMENTS = "^ScriptResourceLimitException: resource limit hit \\(STATEMENTS\\): Maximum statement limit of"
        const val KIND_HEAP =
            "^ScriptResourceLimitException: resource limit hit \\(HEAP\\): " +
                "(Maximum heap memory limit of 268435456|Array allocation too large)"

        /** A guest error, as the engine maps it: the guest's own message, then its body line. */
        fun guest(message: String): String = "^ScriptEvaluationException: ${Regex.escape(message)} \\(line \\d+\\)"

        /** One of the prelude's determinism refusals. */
        fun refused(pattern: String): String = "^ScriptEvaluationException: $pattern"

        /** One of the prelude's shape refusals. */
        fun shape(pattern: String): String = "^ScriptEvaluationException: $pattern"
    }
}
