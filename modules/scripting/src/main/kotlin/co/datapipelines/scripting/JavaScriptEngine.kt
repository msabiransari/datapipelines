package co.datapipelines.scripting

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JavaScript engine: a body runs in a GraalJS polyglot ISOLATE (transform-nodes design §4.4,
 * D-T16; #8 lane 8a). Same seam as [JsonataEngine] — compile once, evaluate many, JSON in, JSON out.
 *
 * ## The body
 *
 * A stored body is the STATEMENTS of `main(input)` (D4): the engine wraps it as
 * `function main(input) {` + body + `}` and calls `main` with the parsed input. The frame is a
 * contract convention, not a fence: a body that closes `main`'s brace early and writes top-level
 * code runs that code under the same sandbox (or fails to parse) — the sandbox is the boundary.
 * Line numbers in every refusal are the body's own (line 1 is the body's first line).
 *
 * ## What each capability means here (measured in lane 8a; the breach suite's report is the record)
 *
 *  - **Wall clock** — the engine's own watchdog runs `Context.close(true)` at
 *    [EvaluationLimits.wallClock]; `sandbox.MaxCPUTime` is the same budget in CPU time beside it
 *    (checked every ~10 ms, waits excluded). Whichever trips first wins; both are
 *    [ScriptTimeoutException]. On [ScriptEvaluationPool] the pool's abandonment calls the same
 *    `close(true)` ([ScriptCancellation]), so [EngineCapabilities.interruptible] means exactly
 *    "closed within ~50 ms of the deadline, the thread freed".
 *  - **Depth** — two different things: `sandbox.MaxStackFrames` bounds the guest's call depth at
 *    runtime ([EvaluationLimits.maxDepth] frames of the body's own, plus [PRELUDE_FRAMES] for the
 *    engine's), and `sandbox.MaxASTDepth` bounds nesting at parse — at compile and for any source
 *    the body parses at runtime. Past the native stack (~1 000–2 000 frames) the guest sees a
 *    `RangeError` first; uncaught, it is the same DEPTH refusal.
 *  - **Heap** — `sandbox.MaxHeapMemory`, SOFT: the sandbox measures retained heap, so an allocation
 *    can overshoot before the Context is cancelled; `engine.MaxIsolateMemory` is the hard cap behind
 *    it, and the isolate's heap is not the JVM's.
 *  - **Statements** — `sandbox.MaxStatements`, hard; the prelude is an internal source and not counted.
 *
 * ## Determinism (cooperative)
 *
 * The prelude pins `Date.now()`, a no-argument `new Date()`/`Date()` and `Intl.DateTimeFormat`'s
 * "now" to [EvaluationLimits.now], or refuses them when it is null; `Math.random()` is always
 * refused (no seed exists this round). A refusal the body catches still fails the evaluation. The
 * time zone is UTC and the locale `en-US`. This is an aid, not a boundary: the isolate has no other
 * clock or entropy source a body could reach (no `performance`, `crypto`, timers or
 * `queueMicrotask` — the globals list is in the lane's evidence).
 *
 * ## Numbers and the result's shape
 *
 * See [JavaScriptValues]: integers past ±2^53, every BIGINTEGER and any DECIMAL whose double loses
 * digits cross as decimal STRINGS — `Number(x)` loses digits beyond 15; for exact money use integer
 * minor units. `main` returns a synchronous JSON-shaped value (null, boolean, finite number, string,
 * BigInt, array, plain object — recursively); an object property that is `undefined` is DROPPED, and
 * everything else stringify would silently repair (`NaN`, a `Map`, a `Date`, a Promise, a cycle, an
 * array hole) is refused with its path. The host's strict decode, not the prelude, is the boundary.
 *
 * ## The sandbox
 *
 * [JavaScriptRuntime] builds every Engine and Context; the isolate has no host, file, network,
 * process, thread or environment access. A runtime whose isolate cannot start refuses every
 * evaluation with "the JavaScript engine is unavailable: …".
 */
class JavaScriptEngine internal constructor(
    private val runtime: JavaScriptRuntime,
    /**
     * The `sandbox.MaxCPUTime` budget for a wall clock: the same duration in production. A test
     * passes a longer one to show the watchdog, not the CPU budget, stops a body at its wall clock.
     */
    private val cpuBudget: (java.time.Duration) -> java.time.Duration,
) : ScriptEngine {
    constructor(runtime: JavaScriptRuntime) : this(runtime, { it })

    override val type: ScriptLanguage = ScriptLanguage.JAVASCRIPT

    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            boundsWallClockBetweenSteps = true,
            boundsDepth = true,
            boundsHeap = true,
            boundsStatements = true,
            interruptible = true,
        )

    override fun compile(body: String): CompiledScript {
        val source = authorSource(body)
        val bodyLines = body.count { it == '\n' } + 1
        val limits = SandboxLimits(COMPILE_MILLIS, runtime.options.maxHeapBytes, 1, PRELUDE_FRAMES)
        // Parse only: Context.parse builds the AST and runs nothing (no guest statement executes).
        runtime.withContext(limits, OutputSink.capture(SMALL), OutputSink.capture(SMALL)) { context ->
            try {
                context.parse(source)
            } catch (err: PolyglotException) {
                throw compileRefusal(err, bodyLines)
            }
        }
        return JavaScriptCompiledScript(body, source, bodyLines, sha256(body))
    }

    private fun compileRefusal(
        err: PolyglotException,
        bodyLines: Int,
    ): ScriptingException {
        if (err.isSyntaxError) {
            val location = err.sourceLocation
            val line = authorLine(location?.startLine, bodyLines) ?: 1
            val column = if (location == null || location.startLine - WRAPPER_LINES != line) 1 else location.startColumn
            return ScriptSyntaxException(line, column, syntaxMessage(err.message.orEmpty()))
        }
        if (err.isResourceExhausted) {
            // The parser's own cap reads "Resource exhausted: Stack overflow" (measured: arrays and
            // parentheses trip between 900 and 1 100 levels at MaxASTDepth 1 000); a message naming
            // another limit keeps that limit's kind rather than being called nesting.
            val other = JavaScriptErrors.limitKind(err.message.orEmpty())?.takeIf { it != ScriptResourceLimitException.Kind.DEPTH }
            return ScriptResourceLimitException(
                other ?: ScriptResourceLimitException.Kind.DEPTH,
                if (other != null) {
                    err.message.orEmpty()
                } else {
                    "the body nests deeper than the parser allows (sandbox.MaxASTDepth ${runtime.options.maxAstDepth}): ${err.message}"
                },
            )
        }
        return ScriptEvaluationException("the JavaScript engine could not parse the body: ${err.message}", err)
    }

    override fun evaluate(
        script: CompiledScript,
        input: Any?,
        limits: EvaluationLimits,
    ): Any? {
        val compiled = script as? JavaScriptCompiledScript
        require(compiled != null) { "the CompiledScript was not produced by this engine (${ScriptLanguage.JAVASCRIPT})" }
        val options = runtime.options
        val maxInput = minOf(limits.maxInputBytes ?: options.maxInputBytes, options.maxInputBytes)
        val maxResult = minOf(limits.maxResultBytes ?: options.maxResultBytes, options.maxResultBytes)
        val json = JavaScriptValues.encodeInput(input, maxInput)
        val out = OutputSink.capture(options.maxOutputBytes)
        val err = OutputSink.capture(options.maxOutputBytes)
        val text =
            try {
                runtime.withContext(sandboxLimits(limits), out, err) { context ->
                    run(context, compiled, json, limits, maxResult, err)
                }
            } finally {
                limits.console?.let { sink -> JavaScriptConsole.deliver(out.utf8(), sink) }
            }
        val bytes = utf8Length(text)
        if (bytes > maxResult) {
            throw ScriptResourceLimitException(
                ScriptResourceLimitException.Kind.RESULT_BYTES,
                "the result is $bytes bytes, past the budget of $maxResult",
            )
        }
        return JavaScriptValues.decodeResult(text)
    }

    @Suppress("ThrowsCount") // each failure kind maps to its own refusal of the engine contract
    private fun run(
        context: Context,
        compiled: JavaScriptCompiledScript,
        json: String,
        limits: EvaluationLimits,
        maxResult: Long,
        err: BoundedBuffer,
    ): String {
        val stopped = AtomicBoolean(false)
        val stop =
            Runnable {
                stopped.set(true)
                context.close(true)
            }
        try {
            JavaScriptWatchdog.arm(limits.wallClock, stop).use {
                ScriptCancellation.register(stop).use { return enter(context, compiled, json, limits, maxResult) }
            }
        } catch (failure: PolyglotException) {
            throw JavaScriptErrors.map(failure, stopped.get(), limits.wallClock, { e -> guestLine(e, compiled.bodyLines) }, err.utf8())
        } catch (failure: IllegalStateException) {
            // A Context closed by the watchdog before the guest entered it.
            if (stopped.get()) throw ScriptTimeoutException(limits.wallClock, "")
            throw ScriptEvaluationException("the JavaScript engine failed: ${failure.message}", failure)
        }
    }

    /** The prelude's entry over the author's `main`: the result as the JSON text the prelude wrote. */
    private fun enter(
        context: Context,
        compiled: JavaScriptCompiledScript,
        json: String,
        limits: EvaluationLimits,
        maxResult: Long,
    ): String {
        val entry = context.eval(PRELUDE)
        val main = context.eval(compiled.source)
        val result: Value = entry.execute(main, json, limits.now?.toEpochMilli(), maxResult, JavaScriptValues.MAX_NESTING)
        if (!result.isString) throw ScriptEvaluationException("the engine's entry returned a non-string result")
        return result.asString()
    }

    private fun sandboxLimits(limits: EvaluationLimits): SandboxLimits {
        val options = runtime.options
        return SandboxLimits(
            cpuMillis = cpuBudget(limits.wallClock).toMillis().coerceAtLeast(1),
            heapBytes = minOf(limits.maxHeapBytes ?: options.maxHeapBytes, options.maxHeapBytes),
            statements = minOf(limits.maxStatements ?: options.maxStatements, options.maxStatements),
            stackFrames = minOf(limits.maxDepth.toLong() + PRELUDE_FRAMES, options.maxStackFrames.toLong()).toInt(),
        )
    }

    /** The innermost frame in the author's source, as the body's own line. */
    private fun guestLine(
        err: PolyglotException,
        bodyLines: Int,
    ): Int? {
        val frame =
            err.polyglotStackTrace.firstOrNull { it.isGuestFrame && it.sourceLocation?.source?.name == AUTHOR_SOURCE_NAME }
        return authorLine(
            frame?.sourceLocation?.startLine ?: err.sourceLocation
                ?.takeIf {
                    it.source.name == AUTHOR_SOURCE_NAME
                }?.startLine,
            bodyLines,
        )
    }

    private fun authorLine(
        sourceLine: Int?,
        bodyLines: Int,
    ): Int? = sourceLine?.let { (it - WRAPPER_LINES).coerceIn(1, bodyLines) }

    /** GraalJS repeats the source name and line in its message; the refusal carries its own. */
    private fun syntaxMessage(message: String): String =
        message
            .removePrefix("SyntaxError: ")
            .replace(Regex("""^$AUTHOR_SOURCE_NAME:\d+:\d+ """), "")
            .lineSequence()
            .first()

    internal companion object {
        /** The name every author Source carries; the line mapper finds the body's frames by it. */
        const val AUTHOR_SOURCE_NAME = "main.js"

        /** Lines the wrapper adds above the body (`function main(input) {`). */
        const val WRAPPER_LINES = 1

        /**
         * Guest frames the engine's own code may hold above the body's deepest frame (the prelude's
         * entry, and the console writer and its formatter when the deepest frame logs).
         */
        const val PRELUDE_FRAMES = 4

        private const val COMPILE_MILLIS = 10_000L

        /** The streams of a Context that runs no guest statement (compile's parse). */
        private const val SMALL = 1024L

        /**
         * The prelude, an internal source: its statements are not counted (measured in lane 8a). Cached,
         * unlike an author's source: it is the engine's own code, the same in every Context, so the shared
         * Engine parses it once (measured: `return 1;` p50 3.2 → 1.5 ms over 1 000 evaluations).
         */
        val PRELUDE: Source =
            Source
                .newBuilder(
                    JavaScriptRuntime.LANGUAGE,
                    requireNotNull(JavaScriptEngine::class.java.getResource("javascript-prelude.js")) {
                        "javascript-prelude.js is missing from the scripting jar"
                    }.readText(),
                    "dp-prelude.js",
                ).internal(true)
                .buildLiteral()

        /** An author's body, wrapped; never cached in the shared Engine (#8 A.2: each evaluation parses its own). */
        fun authorSource(body: String): Source =
            Source
                .newBuilder(JavaScriptRuntime.LANGUAGE, "function main(input) {\n$body\n}\nmain\n", AUTHOR_SOURCE_NAME)
                .cached(false)
                .buildLiteral()

        /** UTF-8 widths: below U+0080 one byte, below U+0800 two, a surrogate pair four, the rest of the BMP three. */
        private const val ONE_BYTE_LIMIT = 0x80
        private const val TWO_BYTE_LIMIT = 0x800
        private const val SURROGATE_PAIR_BYTES = 4
        private const val BMP_BYTES = 3

        /** The result's UTF-8 size without encoding it (a result may be the whole budget, 64 MiB). */
        private fun utf8Length(text: String): Long {
            var bytes = 0L
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes +=
                    when {
                        c.code < ONE_BYTE_LIMIT -> {
                            1
                        }

                        c.code < TWO_BYTE_LIMIT -> {
                            2
                        }

                        Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                            i++
                            SURROGATE_PAIR_BYTES
                        }

                        else -> {
                            BMP_BYTES
                        }
                    }
                i++
            }
            return bytes
        }

        private fun sha256(body: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** The two kinds of guest stream the engine hands the runtime. */
internal object OutputSink {
    fun capture(limit: Long): BoundedBuffer =
        BoundedBuffer(limit + SLACK) { attempted ->
            ScriptResourceLimitException(ScriptResourceLimitException.Kind.OUTPUT, "the console passed $limit bytes (at least $attempted)")
        }

    /** The sandbox's own cap trips first; the host cap sits just above it. */
    private const val SLACK = 64L * 1024L
}
