package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
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
 * died, and the module's 62 other tests lost their verdict with it. Two rules keep a heap event
 * a ROW rather than a dead worker:
 *
 *  - everything after the bomb — the classification, the evidence strings, the heap delta, the
 *    row — runs inside one `OutOfMemoryError` guard ([measure]); an OOM that reaches the test
 *    thread there is recorded UNBOUNDED once the heap has drained, and nothing is allocated
 *    before it has;
 *  - every case starts from a `System.gc()` and a headroom check: a case that starts with more
 *    than [MAX_HELD_AT_START_MB] of the heap still held is recorded SKIPPED with the numbers and
 *    fails the prediction check by name — an environment verdict, never a silent pass.
 */
class JsonataBreachTest {
    private enum class Outcome { REFUSED, BOUNDED, UNBOUNDED, SKIPPED }

    private data class Row(
        val case: String,
        val outcome: Outcome,
        val evidence: String,
        val secondsLived: Long,
        val heapDeltaMb: Long,
    )

    /** Body, the outcome §4.5's table predicts, and what "completed" would mean. */
    private data class Case(
        val name: String,
        val body: String,
        val predicted: Outcome,
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
                Outcome.UNBOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "pad-bomb",
                "\$pad(\"x\", 100000000)",
                Outcome.UNBOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "join-bomb",
                "\$join([1..10000000], \",\")",
                Outcome.REFUSED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "regex-bomb",
                "\$match(\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!\", /^(a+)+\$/)",
                Outcome.BOUNDED,
                "resistant: java.util.regex fails fast on the record's pattern (no " +
                    "catastrophic backtracking measured at 32 chars); no bound fired",
            ),
            Case(
                "deep-recursion",
                "(\$f := function(\$x){ \$f(\$x + 1) }; \$f(0))",
                Outcome.BOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "eval-nesting",
                "\$eval(\"\$pad(\\\"x\\\", 100000000)\")",
                Outcome.UNBOUNDED,
                "completed (no bound fired) - miscalibrated case",
            ),
            Case(
                "now",
                "\$now()",
                Outcome.REFUSED,
                "completed (no bound fired) - miscalibrated case",
            ),
        )

    @Test
    fun `every bomb's measured outcome matches the prediction, and the report lands`() {
        // The corpus is calibrated for a 512m JVM: a larger heap lets the big cases complete,
        // a smaller one blows the small ones — either way the table would lie. Refuse to measure.
        val maxHeapMb = Runtime.getRuntime().maxMemory() / MB
        withClue("the breach suite runs in breachSuite's own ${HEAP_MB}m JVM; this JVM's max heap is $maxHeapMb MB") {
            (maxHeapMb in HEAP_FLOOR_MB..HEAP_MB) shouldBe true
        }

        val rows = cases.map { measure(it) }
        val report = writeReport(rows)

        // The prediction check — a measured outcome that differs from the table
        // above FAILS the suite and forces the docs (and these predictions) to
        // reconcile before anything downstream codes against them.
        rows.zip(cases).forEach { (row, case) ->
            withClue("case '${case.name}': measured $row") {
                row.outcome shouldBe case.predicted
            }
        }

        // Non-vacuity: the corpus is the record's seven, every row materialised.
        rows.size shouldBe 7
        Files.readAllLines(report).size shouldBe (rows.size + 4)
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
     * UNBOUNDED after [awaitHeadroom]. No case starts with more than [MAX_HELD_AT_START_MB] held.
     */
    private fun measure(case: Case): Row {
        val heapBefore = usedHeapMb()
        val headroom = Runtime.getRuntime().maxMemory() / MB - heapBefore
        // The start state, in the XML's system-out: a surprising row on a runner is read against it.
        println("breach case ${case.name}: starts with $headroom MB free after gc ($heapBefore MB in use)")
        if (heapBefore > MAX_HELD_AT_START_MB) {
            return Row(
                case.name,
                Outcome.SKIPPED,
                "no headroom: $heapBefore MB still held after gc at the case's start (at most $MAX_HELD_AT_START_MB; " +
                    "$headroom MB free) - not measured (an environment verdict; re-run before reading it as a bound)",
                0,
                0,
            )
        }
        val start = System.nanoTime()
        return try {
            classify(case, heapBefore, start)
        } catch (
            @Suppress("SwallowedException") err: OutOfMemoryError,
        ) {
            // The heap blew on THIS thread after the bomb: the heap event is the outcome. Nothing
            // is allocated until the heap has drained (the old measure died at exactly this point,
            // building its evidence string); the row's numbers are read after it has.
            awaitHeadroom()
            Row(
                case.name,
                Outcome.UNBOUNDED,
                TEST_THREAD_BLOWOUT,
                (System.nanoTime() - start) / NANOS_PER_SECOND,
                usedHeapMb() - heapBefore,
            )
        }
    }

    /** The bomb and everything after it — [measure]'s guarded body. */
    private fun classify(
        case: Case,
        heapBefore: Long,
        start: Long,
    ): Row {
        val pool = ScriptEvaluationPool(1, 1, GRACE, ScriptEvaluationPool.SYSTEM)
        val script = engine.compile(case.body)
        val limits = EvaluationLimits(Duration.ofSeconds(2), 100)
        var evidence: String
        var outcome: Outcome
        var result: Any? = null
        val threadHeapBlowout = AtomicReference<OutOfMemoryError?>(null)

        try {
            result =
                pool.run(limits, case.name) {
                    try {
                        engine.evaluate(script, null, limits)
                    } catch (err: OutOfMemoryError) {
                        threadHeapBlowout.set(err)
                        throw err
                    }
                }
            outcome = Outcome.BOUNDED
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
                outcome = Outcome.UNBOUNDED
                evidence =
                    "ScriptTimeoutException at ${limits.wallClock.toMillis()} ms; the abandoned thread then " +
                    "died of OutOfMemoryError: ${blowout.message?.take(40)}"
            } else if (pool.abandonedThreadsAlive() > 0) {
                outcome = Outcome.UNBOUNDED
                evidence =
                    "ScriptTimeoutException at ${limits.wallClock.toMillis()} ms; thread still alive at the grace"
            } else {
                outcome = Outcome.BOUNDED
                evidence = "ScriptTimeoutException at ${limits.wallClock.toMillis()} ms; thread ended inside the grace"
            }
        } catch (
            @Suppress("SwallowedException") err: OutOfMemoryError,
        ) {
            // its message is the evidence
            outcome = Outcome.UNBOUNDED
            evidence = "OutOfMemoryError: ${err.message?.take(60)}"
        } catch (err: ScriptingException) {
            outcome = Outcome.REFUSED
            evidence = "${err.javaClass.simpleName}(${err.code}): ${err.message?.take(60)}"
        }

        val seconds = (System.nanoTime() - start) / NANOS_PER_SECOND
        val heapDelta = usedHeapMb() - heapBefore
        result = null
        return Row(case.name, outcome, evidence, seconds, heapDelta)
    }

    private fun writeReport(rows: List<Row>): Path {
        val report = repoBuildReports().resolve("jsonata-breach.md")
        val lines =
            mutableListOf(
                "# JSONata breach suite — measured outcomes (lane 7a)",
                "",
                "| case | outcome | code-or-exception | seconds-lived | heap-delta-mb |",
                "|---|---|---|---|---|",
            )
        rows.forEach { row ->
            lines +=
                "| ${row.case} | ${row.outcome} | ${row.evidence.replace('|', '/')} | ${row.secondsLived} | ${row.heapDeltaMb} |"
        }
        Files.createDirectories(report.parent)
        Files.write(report, lines)
        return report
    }

    @Suppress("ExplicitGarbageCollectionCall") // the heap delta IS the measurement; gc is the only way to read it
    private fun usedHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(50)
        System.gc()
        return (runtime.totalMemory() - runtime.freeMemory()) / MB
    }

    /**
     * Waits until no more than [MAX_HELD_AT_START_MB] is held again — at most [DRAIN_NANOS], since
     * a runaway thread may still hold the heap — WITHOUT allocating: it runs right after an
     * `OutOfMemoryError` on this thread, where one more allocation is one more OOM. Only natives
     * and arithmetic here.
     */
    @Suppress("ExplicitGarbageCollectionCall") // draining the heap is the point; gc is the only lever
    private fun awaitHeadroom() {
        val runtime = Runtime.getRuntime()
        val deadline = System.nanoTime() + DRAIN_NANOS
        while (System.nanoTime() < deadline) {
            System.gc()
            if (runtime.totalMemory() - runtime.freeMemory() <= MAX_HELD_AT_START_MB * MB) return
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoBuildReports(): Path {
        var dir = java.io.File(".").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${java.io.File(".").absolutePath}")
        }
        return java.io.File(dir, "modules/scripting/build/reports").toPath()
    }

    private companion object {
        /** A.6's grace for the 1/1 pool: 5 seconds. */
        val GRACE: Duration = Duration.ofSeconds(5)

        const val MB = 1024L * 1024L

        /** The heap the corpus is calibrated for — breachSuite's `maxHeapSize` (the module's build file). */
        const val HEAP_MB = 512L

        /** `-Xmx512m` reads as 512 MB on G1 and a survivor space less on the serial/parallel collectors. */
        const val HEAP_FLOOR_MB = HEAP_MB * 7 / 8

        /**
         * The most a case may start with still held (after gc) and be measured. The corpus is
         * calibrated on a JVM that starts every case with nearly all of its 512m free: measured
         * 2026-09-28, 8–9 MB in use at every case's start (503–504 MB free). Past this a bomb's
         * outcome says more about what an earlier case left behind than about the bomb. Relative
         * to the JVM's own max, so a collector that reports a smaller `maxMemory` cannot trip it.
         */
        const val MAX_HELD_AT_START_MB = 128L

        /** How long [awaitHeadroom] waits for a blown heap to drain before the row is written anyway (30 s). */
        const val DRAIN_NANOS = 30_000_000_000L
        const val DRAIN_POLL_MS = 100L
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** A constant, so recording the test thread's blowout allocates no evidence string. */
        const val TEST_THREAD_BLOWOUT =
            "OutOfMemoryError on the test thread after the bomb (classification or bookkeeping); recorded once the heap drained"
    }
}
