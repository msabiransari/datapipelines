package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The JavaScript engine's costs (transform-nodes design §4.6; #8 lane 8a A.5), written to
 * `build/reports/javascript-measurements.md` in the breach report's pattern. The numbers are this
 * machine's, not a contract: the report is evidence for the record's sizing, and its assertions
 * are only that every number was measured (non-vacuity) and the two parity FACTS hold.
 *
 * Measured: the isolate's load at the first Engine of a fresh JVM (two child processes: a cold
 * resource cache, then the same cache warm); a whole evaluation's p50/p95 over 1 000 (a Context per
 * evaluation on the runtime's one Engine, the D1 arm built); the parse of a 200-line body; 100 000 rows through the
 * engine alone at 10 000 / 2 000 / 500 rows per batch, with the encode/decode share from the same
 * run with an identity body; the limits one 10 000-row parse/map/stringify reached
 * (`sandbox.TraceLimits`, in a child JVM under the ISOLATED policy — UNTRUSTED refuses the option —
 * and, since it traces 0 B of heap, the smallest `sandbox.MaxHeapMemory` the same evaluation
 * completes under); a fresh JVM's RSS across 1 000 evaluations (the isolate's heap is not the JVM's,
 * so sizing is by RSS); the JSONata/JavaScript parity of 2^53 + 1; and the globals a body sees.
 */
class JavaScriptMeasurementsTest {
    private val runtime = ScriptingTestSupport.javaScriptRuntime
    private val engine = JavaScriptEngine(runtime)
    private val report = mutableListOf<String>()

    @Test
    fun `the engine's costs are measured and the report lands`() {
        withClue("the isolate must start here") { runtime.availability() shouldBe Availability.Available }

        firstEngine()
        evaluationLatency()
        parse()
        rows()
        traceLimits()
        heapFloor()
        rss()
        parity()
        globals()

        val path = BreachHarness.repoBuildReports().resolve("javascript-measurements.md")
        Files.createDirectories(path.parent)
        Files.write(path, listOf("# JavaScript engine measurements (#8 lane 8a)", "", "| measurement | value |", "|---|---|") + report)
        Files.readAllLines(path).size shouldBe (report.size + BreachHarness.REPORT_HEADER_LINES)
    }

    private fun row(
        name: String,
        value: String,
    ) {
        report += "| $name | ${value.replace('|', '/').replace(Regex("\\s+"), " ")} |"
    }

    private fun firstEngine() {
        val cache = Files.createTempDirectory(buildTmp(), "graaljs-resources-")
        listOf("cold resource cache", "warm resource cache").forEach { label ->
            val out = child(listOf("-Dpolyglot.engine.userResourceCache=$cache"), "first-engine")
            val ms = Regex("FIRST_ENGINE_MS=(\\d+) Available").find(out)?.groupValues?.get(1)
            withClue("the child's output: $out") { (ms != null) shouldBe true }
            row("isolate load at the first Engine, fresh JVM ($label)", "$ms ms")
        }
    }

    private fun evaluationLatency() {
        val script = engine.compile("return 1;")
        repeat(WARMUP) { engine.evaluate(script, null, LIMITS) }
        val samples =
            LongArray(SAMPLES) {
                val start = System.nanoTime()
                engine.evaluate(script, null, LIMITS)
                System.nanoTime() - start
            }.sorted()
        row("Context + evaluate `return 1;` on the shared Engine, p50 over $SAMPLES", ms(samples[SAMPLES / 2]))
        row("Context + evaluate `return 1;` on the shared Engine, p95 over $SAMPLES", ms(samples[SAMPLES * P95 / PERCENT]))
    }

    private fun parse() {
        val body =
            (1..PARSE_LINES).joinToString("\n") { "const v$it = (input && input.k$it) ? String(input.k$it).trim() : 'v$it';" } +
                "\nreturn 1;"
        repeat(PARSE_WARMUP) { engine.compile(body) }
        val samples =
            LongArray(PARSE_SAMPLES) {
                val start = System.nanoTime()
                engine.compile(body)
                System.nanoTime() - start
            }.sorted()
        row("compile (parse) of a ${PARSE_LINES + 1}-line body, median of $PARSE_SAMPLES", ms(samples[PARSE_SAMPLES / 2]))
    }

    private fun rows() {
        val work = engine.compile(JavaScriptChildMain.ROW_BODY)
        val identity = engine.compile("return input.rows;")
        BATCHES.forEach { batch ->
            val batches = TOTAL_ROWS / batch
            val input = JavaScriptChildMain.rowsInput(batch)
            engine.evaluate(work, input, JavaScriptChildMain.LIMITS)
            val workNanos = timeBatches(batches) { engine.evaluate(work, input, JavaScriptChildMain.LIMITS) }
            val identityNanos = timeBatches(batches) { engine.evaluate(identity, input, JavaScriptChildMain.LIMITS) }
            workNanos shouldBeGreaterThan 0L
            row(
                "$TOTAL_ROWS rows at $batch per batch ($batches batches)",
                "total ${ms(workNanos)}, per batch ${ms(workNanos / batches)}; identity body total ${ms(identityNanos)} " +
                    "(identity ÷ work ≈ ${identityNanos * PERCENT / workNanos}%: the encode, Context and decode share)",
            )
        }
    }

    private fun timeBatches(
        batches: Int,
        evaluate: () -> Any?,
    ): Long {
        val start = System.nanoTime()
        repeat(batches) { evaluate() }
        return System.nanoTime() - start
    }

    private fun traceLimits() {
        val out = child(emptyList(), "trace", TRACE_ROWS.toString())
        withClue("the child's output: $out") { out.contains("TRACE_CHARS=") shouldBe true }
        val traced = out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("TRACE_CHARS=") }
        println("TraceLimits, one $TRACE_ROWS-row evaluation:\n" + traced.joinToString("\n"))
        withClue("the child traced nothing: $out") { traced.isEmpty() shouldBe false }
        row("sandbox.TraceLimits, one $TRACE_ROWS-row parse/map/stringify (ISOLATED policy, same heap cap)", traced.joinToString("; "))
    }

    /**
     * The smallest `sandbox.MaxHeapMemory` (powers of two) under which one 10 000-row evaluation
     * completes — the heap number TraceLimits did not give (it traced 0 B for the same workload).
     */
    private fun heapFloor() {
        val script = engine.compile(JavaScriptChildMain.ROW_BODY)
        val input = JavaScriptChildMain.rowsInput(TRACE_ROWS)
        val refused = mutableListOf<String>()
        val floor =
            HEAP_STEPS_MIB.firstOrNull { mib ->
                val limits = JavaScriptChildMain.LIMITS.copy(maxHeapBytes = mib * KIB * KIB)
                runCatching { engine.evaluate(script, input, limits) }
                    .onFailure { refused += "$mib MiB: ${(it as? ScriptResourceLimitException)?.kind ?: it.javaClass.simpleName}" }
                    .isSuccess
            }
        withClue("no cap up to ${HEAP_STEPS_MIB.last()} MiB passed: $refused") { (floor != null) shouldBe true }
        row(
            "smallest sandbox.MaxHeapMemory for one $TRACE_ROWS-row evaluation",
            "$floor MiB (refused below: ${refused.joinToString("; ").ifEmpty { "none" }})",
        )
    }

    private fun rss() {
        val out = child(emptyList(), "rss", SAMPLES.toString(), RSS_ROWS.toString())
        val lines = out.lines().filter { it.startsWith("RSS ") }
        println("RSS, a fresh JVM, $SAMPLES evaluations of $RSS_ROWS rows:\n" + lines.joinToString("\n"))
        withClue("the child measured nothing: $out") { lines.size shouldBe RSS_LINES }
        val series =
            lines.joinToString(", ") { line ->
                Regex("VmRSS=(\\d+) kB")
                    .find(line)!!
                    .groupValues[1]
                    .toLong()
                    .div(KIB)
                    .toString()
            }
        row("process VmRSS (MB) in a fresh JVM, every ${SAMPLES / (RSS_LINES - 1)} of $SAMPLES evaluations of $RSS_ROWS rows", series)
        row("process memory before the 1st and after the ${SAMPLES}th of those evaluations", "${lines.first()}; ${lines.last()}")
    }

    private fun parity() {
        val big = TWO_TO_53 + 1
        val jsonata = JsonataEngine().let { it.evaluate(it.compile("$"), big, LIMITS) }
        val javaScript = engine.evaluate(engine.compile("return input;"), big, LIMITS)
        row("2^53 + 1 as a Long through an identity body: JSONata", "$jsonata (${jsonata?.javaClass?.simpleName})")
        row("2^53 + 1 as a Long through an identity body: JavaScript", "\"$javaScript\" (${javaScript?.javaClass?.simpleName})")
        // The facts the record's §12 Q7 decision reads: JSONata hands the Long back untouched (it never
        // leaves the JVM); JavaScript returns the decimal string the number rule sends past ±2^53.
        jsonata shouldBe big
        javaScript shouldBe big.toString()
    }

    private fun globals() {
        val names = engine.evaluate(engine.compile("return Object.getOwnPropertyNames(globalThis).sort();"), null, LIMITS) as List<*>
        row("globals a body sees (${names.size})", names.joinToString(", "))
        names shouldContain "console"
        listOf("Java", "Packages", "Polyglot", "print", "printErr", "load", "WebAssembly", "fetch").forEach { names shouldNotContain it }
    }

    /** Runs [JavaScriptChildMain] in a fresh JVM on this test's class path; returns stdout and stderr. */
    private fun child(
        jvmArgs: List<String>,
        vararg args: String,
    ): String {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-Xmx256m",
            ) + jvmArgs + listOf("-cp", System.getProperty("java.class.path"), JavaScriptChildMain::class.java.name) +
                args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        withClue("the child JVM did not finish: $out") { process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS) shouldBe true }
        withClue("the child JVM failed: $out") { process.exitValue() shouldBe 0 }
        return out
    }

    private fun buildTmp(): Path = Files.createDirectories(BreachHarness.repoBuildReports().parent.resolve("tmp/javascript-measurements"))

    private fun ms(nanos: Long): String = "%.1f ms".format(nanos / NANOS_PER_MS)

    private companion object {
        val LIMITS = EvaluationLimits(wallClock = Duration.ofSeconds(10), maxDepth = 100)
        const val WARMUP = 50
        const val SAMPLES = 1000
        const val P95 = 95
        const val PERCENT = 100
        const val PARSE_LINES = 199
        const val PARSE_WARMUP = 5
        const val PARSE_SAMPLES = 21
        const val TOTAL_ROWS = 100_000
        val BATCHES = listOf(10_000, 2_000, 500)
        const val TRACE_ROWS = 10_000
        const val RSS_ROWS = 100
        const val RSS_LINES = 11
        const val KIB = 1024L
        const val TWO_TO_53 = 9_007_199_254_740_992L
        const val NANOS_PER_MS = 1_000_000.0
        const val CHILD_SECONDS = 120L
        val HEAP_STEPS_MIB = listOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L)
    }
}
