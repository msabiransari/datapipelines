package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The JSONata breach suite (transform-nodes design §4.5, R7): every bomb the record
 * lists, under one budget, with a three-way outcome record — REFUSED (a typed refusal),
 * BOUNDED (the caller's timeout fired on time and the thread ended inside the grace,
 * returning its slot — or the body completed inside every budget, which is
 * the "resistant" shape the regex case measures), UNBOUNDED (the abandoned thread was
 * still alive at the grace, or the heap blew past the JVM).
 *
 * Every prediction below was MEASURED on 2026-09-23, jsonata 0.9.10, test JVM 512m
 * (this module's build file), and three of them CORRECT the record's beliefs — the
 * corrections are stated on the cases and belong in the dag-executor.md table:
 *
 *  - the record's single-range body (`1 to 10000000`) does NOT exhaust a 512m JVM: the range
 *    list is LAZY and materialising it costs ~240 MB, which survives. Tripled, the
 *    same operator (whose 1e7 cap is the only bound) OOMs — that is the bomb;
 *  - `$join` over the range is REFUSED by the library itself (an internal argument
 *    cap the record does not mention) — a refusal, not a heap event;
 *  - the regex bomb does NOT catastrophically backtrack: `java.util.regex` fails fast
 *    on the record's pattern (measured 1–50 ms at 32 chars). No bound fires; the case
 *    is recorded as resistant, not bounded-loss.
 *
 * Re-measured 2026-09-26 (#260): the deep-recursion outcome is UNCHANGED — the loop
 * is tail-recursive and the library trampolines it, so depth stays flat and the wall
 * clock (then the pool) bounds it, exactly as in the 2026-09-23 measurement. What
 * #260 corrected is the EXPLANATION the old row carried: not `isParallelCall`, but
 * the trampoline; and the engine now counts depth with its own entry/exit hooks, so
 * the set-level leak the skip caused is gone (non-tail lambda recursion IS depth).
 *
 * The table published in dag-executor.md is pasted from this suite's report — the
 * measurement is the authority; the doc never hand-writes it.
 *
 * **Its own task, its own JVM (#289).** The suite runs alone in `:modules:scripting:breachSuite`
 * (its own source set, a 512m test JVM of its own, wired under `check`), never inside the
 * module's `test`. On CI run 36365034218 (2026-09-28, a 2-vCPU runner) an `OutOfMemoryError`
 * reached the test thread outside the old guard — at the evidence string of the OOM branch
 * itself — JUnit rethrew it (the one error it treats as unrecoverable), the Gradle test worker
 * died, and the module's 62 other tests lost their verdict with it. The two rules that keep a
 * heap event a ROW rather than a dead worker — the `OutOfMemoryError` guard around everything
 * after the bomb, and the headroom check at every case's start — live in [BreachHarness], which
 * the JavaScript suite shares (#8 lane 8a).
 */
class JsonataBreachTest {
    /** Body, the outcome §4.5's table predicts, and what "completed" would mean. */
    private data class Case(
        val name: String,
        val body: String,
        val predicted: BreachOutcome,
        val completedNote: String,
    )

    private val cases =
        listOf(
            Case(
                "range-bomb",
                // Tripled on purpose: ONE lazy range materialises ~240 MB and completes
                // inside 512m (measured). Three ranges (~600 MB) is the same operator
                // with its 1e7 cap as the only bound actually exhausting the JVM.
                "{\"a\": [1..10000000], \"b\": [1..10000000], \"c\": [1..10000000]}",
                BreachOutcome.UNBOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "pad-bomb",
                "\$pad(\"x\", 100000000)",
                BreachOutcome.UNBOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "join-bomb",
                "\$join([1..10000000], \",\")",
                BreachOutcome.REFUSED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "regex-bomb",
                "\$match(\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!\", /^(a+)+\$/)",
                BreachOutcome.BOUNDED,
                "resistant: java.util.regex fails fast on the record's pattern (no " +
                    "catastrophic backtracking measured at 32 chars); no bound fired",
            ),
            Case(
                "deep-recursion",
                "(\$f := function(\$x){ \$f(\$x + 1) }; \$f(0))",
                BreachOutcome.BOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "eval-nesting",
                "\$eval(\"\$pad(\\\"x\\\", 100000000)\")",
                BreachOutcome.UNBOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "now",
                "\$now()",
                BreachOutcome.REFUSED,
                "completed (no bound fired) - miscalibrated case",
            ),
            // #272: the hooks ARE frame variables the library looks up through the chain, so a
            // body that binds them to null runs every deeper step unbounded. Measured before the
            // compile refusal (2026-09-28): the tail loop below outlived the grace (UNBOUNDED).
            // Last in the corpus on purpose: were the refusal ever lost, the runaway thread it
            // leaves behind would burn a core under every case measured after it.
            Case(
                "hook-rebind",
                "( \$__evaluate_entry := \$nonexistent; \$__evaluate_exit := \$nonexistent; " +
                    "\$f := function(\$x){ \$f(\$x + 1) }; \$f(0) )",
                BreachOutcome.REFUSED,
                "completed (no bound fired) - miscalibrated case",
            ),
            // The same rebind through `$eval`, which parses at evaluate time and binds in the
            // CALLING frame: no compile-time walk can see it, so the engine's `$eval` shadow
            // checks the string before it runs.
            Case(
                "hook-rebind-eval",
                "( \$eval(\"\$__evaluate_entry := \$nonexistent\"); \$eval(\"\$__evaluate_exit := \$nonexistent\"); " +
                    "\$f := function(\$x){ \$f(\$x + 1) }; \$f(0) )",
                BreachOutcome.REFUSED,
                "completed (no bound fired) - miscalibrated case",
            ),
        )

    @Test
    fun `every bomb's measured outcome matches the prediction, and the report lands`() {
        BreachHarness.requireCalibratedHeap()

        val rows = cases.map { case -> BreachHarness.measure(case.name) { heapBefore, start -> classify(case, heapBefore, start) } }
        val report = BreachHarness.writeReport("jsonata-breach.md", "JSONata breach suite — measured outcomes (lane 7a)", rows)

        // The prediction check — a measured outcome that differs from the table
        // above FAILS the suite and forces the docs (and these predictions) to
        // reconcile before anything downstream codes against them.
        rows.zip(cases).forEach { (row, case) ->
            withClue("case '${case.name}': measured $row") {
                row.outcome shouldBe case.predicted
            }
        }

        // Non-vacuity: the corpus is the record's seven plus #272's two rebinds, every row materialised.
        rows.size shouldBe 9
        Files.readAllLines(report).size shouldBe (rows.size + BreachHarness.REPORT_HEADER_LINES)
    }

    /**
     * Runs one bomb on a fresh 1/1 pool (wall clock 2s, grace 5s, depth 100) and
     * classifies the outcome from OBSERVED events: the typed refusal, the caller's
     * timeout plus the abandoned thread's fate at the grace, or the heap blowout.
     * A result that COMPLETES stays referenced until the heap delta is read, so a
     * bomb that "succeeds" cannot hide its allocation from the measurement.
     *
     * The heap blowout is recorded ON THE EVALUATION THREAD (#218): whether the
     * `OutOfMemoryError` lands before the wall clock (a fast machine — the caller
     * catches it through the pool) or after it (a 2-vCPU runner — the caller has
     * already detached and the abandoned thread dies of it inside the grace), the
     * outcome is the same heap event and reads UNBOUNDED. Liveness at the grace alone
     * cannot tell "ended because the heap blew" from "ended because it finished".
     *
     * The heap blowout on the TEST thread (#289) is the guard around all of it: whatever the
     * classification or the bookkeeping allocates after the bomb — the evidence strings, the
     * heap delta, the row — an `OutOfMemoryError` there is the case's heap event, recorded
     * UNBOUNDED once [BreachHarness.measure] has drained the heap. No case starts with more than
     * [BreachHarness.MAX_HELD_AT_START_MB] held.
     */
    private fun classify(
        case: Case,
        heapBefore: Long,
        start: Long,
    ): BreachRow {
        val pool = ScriptEvaluationPool(1, 1, GRACE, ScriptEvaluationPool.SYSTEM)
        val limits = EvaluationLimits(Duration.ofSeconds(2), 100)
        var evidence: String
        var outcome: BreachOutcome
        var result: Any? = null
        val threadHeapBlowout = AtomicReference<OutOfMemoryError?>(null)

        try {
            // Inside the classified block: a refusal at COMPILE (the reserved-name walk, #272)
            // is a REFUSED row like any other typed refusal.
            val script = engine.compile(case.body)
            result =
                pool.run(limits, case.name) {
                    try {
                        engine.evaluate(script, null, limits)
                    } catch (err: OutOfMemoryError) {
                        threadHeapBlowout.set(err)
                        throw err
                    }
                }
            outcome = BreachOutcome.BOUNDED
            evidence = case.completedNote
        } catch (
            @Suppress("SwallowedException")
            err: ScriptTimeoutException,
        ) {
            // The caller is bounded. The THREAD may not be: alive at the grace is the
            // record's definition of unbounded abandonment. The exception's type is the
            // signal; the row's numbers come from the limits and the liveness check.
            Thread.sleep(GRACE.toMillis())
            val blowout = threadHeapBlowout.get()
            if (blowout != null) {
                outcome = BreachOutcome.UNBOUNDED
                evidence =
                    "ScriptTimeoutException at ${limits.wallClock.toMillis()} ms; the abandoned thread then " +
                    "died of OutOfMemoryError: ${blowout.message?.take(40)}"
            } else if (pool.abandonedThreadsAlive() > 0) {
                outcome = BreachOutcome.UNBOUNDED
                evidence =
                    "ScriptTimeoutException at ${limits.wallClock.toMillis()} ms; thread still alive at the grace"
            } else {
                outcome = BreachOutcome.BOUNDED
                evidence = "ScriptTimeoutException at ${limits.wallClock.toMillis()} ms; thread ended inside the grace"
            }
        } catch (
            @Suppress("SwallowedException") err: OutOfMemoryError,
        ) {
            // its message is the evidence
            outcome = BreachOutcome.UNBOUNDED
            evidence = "OutOfMemoryError: ${err.message?.take(60)}"
        } catch (err: ScriptingException) {
            outcome = BreachOutcome.REFUSED
            evidence = "${err.javaClass.simpleName}(${err.code}): ${err.message?.take(60)}"
        }

        val seconds = (System.nanoTime() - start) / BreachHarness.NANOS_PER_SECOND
        val heapDelta = BreachHarness.usedHeapMb() - heapBefore
        result = null
        return BreachRow(case.name, outcome, evidence, seconds, heapDelta)
    }

    private companion object {
        /** A.6's grace for the 1/1 pool: 5 seconds. */
        val GRACE: Duration = Duration.ofSeconds(5)
    }
}
