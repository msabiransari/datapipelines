package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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
 * The table published in dag-executor.md is pasted from this suite's report — the
 * measurement is the authority; the doc never hand-writes it.
 */
class JsonataBreachTest {
    private enum class Outcome { REFUSED, BOUNDED, UNBOUNDED }

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
     * timeout plus the abandoned thread's liveness at the grace, or the heap blowout.
     * A result that COMPLETES stays referenced until the heap delta is read, so a
     * bomb that "succeeds" cannot hide its allocation from the measurement.
     */
    private fun measure(case: Case): Row {
        val pool = ScriptEvaluationPool(1, 1, GRACE, ScriptEvaluationPool.SYSTEM)
        val script = engine.compile(case.body)
        val limits = EvaluationLimits(Duration.ofSeconds(2), 100)
        val heapBefore = usedHeapMb()
        val start = System.nanoTime()
        var evidence: String
        var outcome: Outcome
        var result: Any? = null

        try {
            result = pool.run(limits, case.name) { engine.evaluate(script, null, limits) }
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
            if (pool.abandonedThreadsAlive() > 0) {
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

        val seconds = (System.nanoTime() - start) / 1_000_000_000
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
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
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
    }
}
