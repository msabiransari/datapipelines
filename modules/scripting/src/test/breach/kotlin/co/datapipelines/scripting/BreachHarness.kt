package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** A breach case's outcome (transform-nodes design §4.5): the three the record names, plus the environment's verdict. */
internal enum class BreachOutcome { REFUSED, BOUNDED, UNBOUNDED, SKIPPED }

/** One measured row of a breach report. */
internal data class BreachRow(
    val case: String,
    val outcome: BreachOutcome,
    val evidence: String,
    val secondsLived: Long,
    val heapDeltaMb: Long,
)

/**
 * The mechanics every breach suite shares (#289, extracted for the JavaScript suite in #8 lane 8a):
 * the 512m JVM check, the per-case headroom check, the `OutOfMemoryError` guard around everything
 * after the bomb, and the report. A suite supplies only its corpus and how one case is classified.
 *
 * **Why the guard** (CI run 36365034218, 2026-09-28, a 2-vCPU runner): an `OutOfMemoryError`
 * reached the test thread outside the old guard — at the evidence string of the OOM branch
 * itself — JUnit rethrew it, the Gradle test worker died, and the module's other tests lost their
 * verdict with it. So everything after the bomb — the classification, the evidence strings, the
 * heap delta, the row — runs inside [measure]'s guard; an OOM there is recorded UNBOUNDED once the
 * heap has drained, and nothing is allocated before it has. Every case starts from a `System.gc()`
 * and a headroom check: a case that starts with more than [MAX_HELD_AT_START_MB] still held is
 * recorded SKIPPED with the numbers and fails the prediction check by name — an environment
 * verdict, never a silent pass.
 */
internal object BreachHarness {
    const val MB = 1024L * 1024L

    /** The heap the corpora are calibrated for — breachSuite's `maxHeapSize` (the module's build file). */
    const val HEAP_MB = 512L

    /** `-Xmx512m` reads as 512 MB on G1 and a survivor space less on the serial/parallel collectors. */
    const val HEAP_FLOOR_MB = HEAP_MB * 7 / 8

    /**
     * The most a case may start with still held (after gc) and be measured. The corpus is
     * calibrated on a JVM that starts every case with nearly all of its 512m free: measured
     * 2026-09-28, 8–9 MB in use at every case's start (503–504 MB free). Past this a bomb's
     * outcome says more about what an earlier case left behind than about the bomb. An
     * ABSOLUTE bound on the heap still held, not a share of the max: the check in [measure]
     * compares the used heap with this constant and never reads `maxMemory` (that is read only
     * for the logged free figure), so a collector that reports a smaller `maxMemory` cannot trip
     * it. The max itself is pinned separately, by [requireCalibratedHeap].
     */
    const val MAX_HELD_AT_START_MB = 128L

    /** How long [awaitHeadroom] waits for a blown heap to drain before the row is written anyway (30 s). */
    private const val DRAIN_NANOS = 30_000_000_000L
    private const val DRAIN_POLL_MS = 100L
    const val NANOS_PER_SECOND = 1_000_000_000L

    /** A constant, so recording the test thread's blowout allocates no evidence string. */
    private const val TEST_THREAD_BLOWOUT =
        "OutOfMemoryError on the test thread after the bomb (classification or bookkeeping); recorded once the heap drained"

    /**
     * The corpus is calibrated for a 512m JVM: a larger heap lets the big cases complete, a
     * smaller one blows the small ones — either way the table would lie. Refuse to measure.
     */
    fun requireCalibratedHeap() {
        val maxHeapMb = Runtime.getRuntime().maxMemory() / MB
        withClue("the breach suite runs in breachSuite's own ${HEAP_MB}m JVM; this JVM's max heap is $maxHeapMb MB") {
            (maxHeapMb in HEAP_FLOOR_MB..HEAP_MB) shouldBe true
        }
    }

    /**
     * Measures one case: the headroom check, then [classify] (the bomb and everything after it,
     * given the heap in use before it and the start instant) inside the `OutOfMemoryError` guard.
     */
    fun measure(
        case: String,
        classify: (heapBeforeMb: Long, startNanos: Long) -> BreachRow,
    ): BreachRow {
        val heapBefore = usedHeapMb()
        val headroom = Runtime.getRuntime().maxMemory() / MB - heapBefore
        // The start state, in the XML's system-out: a surprising row on a runner is read against it.
        println("breach case $case: starts with $headroom MB free after gc ($heapBefore MB in use)")
        if (heapBefore > MAX_HELD_AT_START_MB) {
            return BreachRow(
                case,
                BreachOutcome.SKIPPED,
                "no headroom: $heapBefore MB still held after gc at the case's start (at most $MAX_HELD_AT_START_MB; " +
                    "$headroom MB free) - not measured (an environment verdict; re-run before reading it as a bound)",
                0,
                0,
            )
        }
        val start = System.nanoTime()
        return try {
            classify(heapBefore, start)
        } catch (
            @Suppress("SwallowedException") err: OutOfMemoryError,
        ) {
            // The heap blew on THIS thread after the bomb: the heap event is the outcome. Nothing
            // is allocated until the heap has drained; the row's numbers are read after it has.
            awaitHeadroom()
            BreachRow(
                case,
                BreachOutcome.UNBOUNDED,
                TEST_THREAD_BLOWOUT,
                (System.nanoTime() - start) / NANOS_PER_SECOND,
                usedHeapMb() - heapBefore,
            )
        }
    }

    /** Writes `modules/scripting/build/reports/[fileName]`: a title, the five columns, one line per row. */
    fun writeReport(
        fileName: String,
        title: String,
        rows: List<BreachRow>,
    ): Path {
        val report = repoBuildReports().resolve(fileName)
        val lines =
            mutableListOf(
                "# $title",
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

    /** The lines [writeReport] adds above the rows. */
    const val REPORT_HEADER_LINES = 4

    @Suppress("ExplicitGarbageCollectionCall") // the heap delta IS the measurement; gc is the only way to read it
    fun usedHeapMb(): Long {
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
    fun repoBuildReports(): Path {
        var dir = java.io.File(".").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${java.io.File(".").absolutePath}")
        }
        return java.io.File(dir, "modules/scripting/build/reports").toPath()
    }
}
