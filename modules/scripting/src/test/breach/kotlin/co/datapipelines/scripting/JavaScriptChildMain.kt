package co.datapipelines.scripting

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.SandboxPolicy
import java.time.Duration

/**
 * What [JavaScriptMeasurementsTest] runs in a CHILD JVM, where the isolate has not been loaded yet.
 *
 *  - `first-engine`: prints `FIRST_ENGINE_MS=<n>`, the time of the process's first
 *    [JavaScriptRuntime.availability] (the isolate library loaded, one Engine and Context built);
 *  - `trace <rows>`: the row workload's guest half (parse the input JSON, map, stringify) over
 *    `rows` rows in a spawned isolate with `sandbox.TraceLimits`, the trace on stdout. NOT through
 *    [JavaScriptRuntime]: the UNTRUSTED policy refuses `sandbox.TraceLimits` ("can only be used up to
 *    the ISOLATED sandbox policy", measured), so this one Context is ISOLATED with the runtime's
 *    heap cap — a measuring instrument in test code, never a second production builder.
 */
object JavaScriptChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "first-engine" -> {
                val start = System.nanoTime()
                val availability = JavaScriptRuntime().availability()
                println("FIRST_ENGINE_MS=${(System.nanoTime() - start) / NANOS_PER_MS} $availability")
            }

            "trace" -> {
                trace(args[1].toInt())
            }

            "rss" -> {
                rss(args[1].toInt(), args[2].toInt())
            }

            else -> {
                error("usage: first-engine | trace <rows> | rss <evaluations> <rows>")
            }
        }
    }

    private fun trace(rows: Int) {
        val options = JavaScriptRuntimeOptions()
        // The same JSON the engine's encoder writes for rowsInput (the encoder is internal to main).
        val json = (0 until rows).joinToString(",", "{\"rows\":[", "]}") { "{\"id\":$it,\"name\":\"row $it\",\"amount\":${it * 3L}}" }
        val log = java.io.ByteArrayOutputStream()
        Engine
            .newBuilder("js")
            .sandbox(SandboxPolicy.ISOLATED)
            .spawnIsolate(true)
            .option("engine.MaxIsolateMemory", "${options.isolateMemoryBytes}B")
            .out(java.io.OutputStream.nullOutputStream())
            .err(log)
            .logHandler(log)
            .build()
            .use { engine ->
                Context
                    .newBuilder("js")
                    .engine(engine)
                    .sandbox(SandboxPolicy.ISOLATED)
                    .option("sandbox.MaxHeapMemory", "${options.maxHeapBytes}B")
                    .option("sandbox.MaxCPUTime", "60s")
                    .option("sandbox.TraceLimits", "true")
                    .build()
                    .use { context ->
                        val run = context.eval("js", TRACE_BODY)
                        println("TRACE_CHARS=${run.execute(json).asString().length}")
                    }
            }
        // The trace is written when the Context closes; printed once the Engine has closed too.
        print(log.toString(Charsets.UTF_8))
    }

    /**
     * Prints `RSS <evaluations-done> <status fields>` before the first evaluation and after every
     * tenth of [evaluations], plus the process's mapping count: resident memory split by kind,
     * because an isolate maps its image heap per isolate and `VmRSS` counts an aliased page once
     * per mapping.
     */
    private fun rss(
        evaluations: Int,
        rows: Int,
    ) {
        val engine = JavaScriptEngine(JavaScriptRuntime())
        val script = engine.compile(ROW_BODY)
        val input = rowsInput(rows)
        engine.evaluate(script, input, LIMITS)
        println("RSS 0 ${memory()}")
        val step = (evaluations / STEPS).coerceAtLeast(1)
        for (done in 1..evaluations) {
            engine.evaluate(script, input, LIMITS)
            if (done % step == 0) println("RSS $done ${memory()}")
        }
    }

    private fun memory(): String {
        val status = java.io.File("/proc/self/status").readLines()
        val fields = listOf("VmRSS", "RssAnon", "RssFile", "RssShmem", "VmSize")
        val values = fields.map { f -> "$f=" + (status.firstOrNull { it.startsWith("$f:") }?.substringAfter(':')?.trim() ?: "?") }
        val maps =
            java.io
                .File("/proc/self/maps")
                .readLines()
                .size
        return (values + "maps=$maps").joinToString(" ")
    }

    private const val STEPS = 10

    private const val NANOS_PER_MS = 1_000_000L

    /** [ROW_BODY]'s guest half as one function over the input's JSON text: parse, map, stringify. */
    private const val TRACE_BODY =
        "(json) => JSON.stringify(JSON.parse(json).rows.map(r => ({ id: r.id, name: r.name.toUpperCase(), amount: r.amount * 2 })))"

    /** The row workload: a per-row map, the shape a row-mode SCRIPT node runs. */
    const val ROW_BODY = "return input.rows.map(r => ({ id: r.id, name: r.name.toUpperCase(), amount: r.amount * 2 }));"

    val LIMITS = EvaluationLimits(wallClock = Duration.ofSeconds(60), maxDepth = 100)

    /** `rows` rows of three columns: the measurements' input shape. */
    fun rowsInput(rows: Int): Map<String, Any?> =
        mapOf("rows" to List(rows) { mapOf("id" to it.toLong(), "name" to "row $it", "amount" to it * 3L) })
}
