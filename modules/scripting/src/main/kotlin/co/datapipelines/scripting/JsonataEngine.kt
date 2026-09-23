package co.datapipelines.scripting

import com.dashjoin.jsonata.Functions
import com.dashjoin.jsonata.JException
import com.dashjoin.jsonata.Jsonata
import java.time.Duration
import java.time.Instant

/**
 * The round-one engine: JSONata via `com.dashjoin:jsonata`, in-process (transform-nodes
 * design §4.2).
 *
 * ## What this engine guarantees
 *
 *  - **Compile once, evaluate everywhere.** The parsed expression is shared across
 *    threads; the library evaluates each call on a per-thread clone of it. Proven by
 *    the conformance suite's determinism case (32 threads × 1000 evaluations with
 *    distinct inputs, every result equal to the single-threaded result).
 *  - **One `Frame` per evaluation.** The runtime bounds and the clock bindings live on
 *    a frame created for THIS evaluation and handed to the library as variable
 *    bindings, so no state crosses evaluations. Sharing one frame across threads is
 *    the exact defect the conformance suite's falsification red-flags: it would share
 *    one depth counter and one non-thread-safe binding map.
 *  - **Bounds between steps, honestly.** `setRuntimeBounds` checks wall clock and
 *    depth in the evaluate entry/exit callbacks — a single builtin call that overruns
 *    (`$pad`, `$join`, a backtracking regex) runs to completion first; depth is
 *    enforced at every step. Hence [capabilities]: heap and statements are NOT bounded
 *    in-process, and the engine is NOT interruptible. The evaluation pool's
 *    abandonment is the bound that actually stops an overrunning builtin's caller.
 *  - **No host access.** No Java function is registered anywhere: the body cannot
 *    reach the filesystem, network, environment or system properties. The conformance
 *    suite asserts the builtin catalogue contains no name from a deny-list.
 *
 * ## The clock (A.3 choice (a))
 *
 * The library reads its `$now()`/`$millis()` timestamp from a per-thread field set at
 * evaluation — a clock read this module's purity rule forbids making ambient. The
 * engine therefore always SHADOWS both builtins on the per-evaluation frame:
 * [EvaluationLimits.now] set → they return that instant (`$now()` twice a second apart
 * yields the same string — the conformance suite proves it); null → calling them is a
 * typed refusal ("a transform is a pure function of its inputs"). The refusal fires
 * when the body actually evaluates the call, so an unevaluated branch is legal.
 *
 * ## What it does not guarantee
 *
 * A malicious body can still exhaust the heap (see dag-executor.md's honest-bounds
 * table); input and output caps at the callers bound a well-formed evaluation. The
 * AST is not inspectable through the library's public API, so the clock refusal is a
 * call-time shadow rather than a compile-time scan — a body that never evaluates a
 * clock call never sees it.
 */
class JsonataEngine : ScriptEngine {
    override val type: ScriptLanguage = ScriptLanguage.JSONATA

    override val capabilities: EngineCapabilities =
        EngineCapabilities(
            boundsWallClockBetweenSteps = true,
            boundsDepth = true,
            boundsHeap = false,
            boundsStatements = false,
            interruptible = false,
        )

    override fun compile(body: String): CompiledScript {
        val expr =
            try {
                Jsonata.jsonata(body)
            } catch (err: JException) {
                throw syntaxException(body, err)
            }
        // The parser can finish with non-fatal errors collected on the AST; the
        // library would only refuse them at evaluate (S0500). Refuse here instead:
        // a body that cannot evaluate is not a compiled script.
        val deferred = expr.errors
        if (deferred != null && deferred.isNotEmpty()) {
            throw ScriptSyntaxException(
                line = 1,
                column = 1,
                message =
                    "the expression has deferred parse errors (S0500): " +
                        (deferred.firstOrNull()?.message ?: "unknown"),
            )
        }
        return JsonataCompiledScript(body, expr)
    }

    override fun evaluate(
        script: CompiledScript,
        input: Any?,
        limits: EvaluationLimits,
    ): Any? {
        val compiled = script as? JsonataCompiledScript
        require(compiled != null) {
            "the CompiledScript was not produced by this engine (${ScriptLanguage.JSONATA})"
        }
        val frame = Jsonata.Frame(null)
        frame.setRuntimeBounds(limits.wallClock.toMillis(), limits.maxDepth)
        bindClock(frame, limits.now)
        return try {
            JsonataValues.fromEngineOutput(
                compiled.expr.evaluate(JsonataValues.toEngineInput(input), frame),
            )
        } catch (err: ScriptingException) {
            throw err
        } catch (err: JException) {
            throw engineException(limits, err)
        } catch (
            @Suppress("TooGenericExceptionCaught") err: RuntimeException,
        ) {
            // The library rethrows builtin failures verbatim after message population;
            // anything else here is an engine or library defect surfacing mid-evaluation.
            throw ScriptEvaluationException(
                "evaluation failed unexpectedly: ${err.message ?: err.javaClass.name}",
                err,
            )
        }
    }

    /**
     * Shadows the library's clock builtins for this evaluation — pinned to [now] when
     * the caller supplies it, refusing otherwise (see the class KDoc).
     */
    private fun bindClock(
        frame: Jsonata.Frame,
        now: Instant?,
    ) {
        if (now == null) {
            frame.bind("now", refusingClockFunction("now"))
            frame.bind("millis", refusingClockFunction("millis"))
        } else {
            val millis = now.toEpochMilli()
            frame.bind(
                "now",
                clockFunction(SIGNATURE_NOW) { picture, timezone ->
                    Functions.dateTimeFromMillis(millis, picture, timezone)
                },
            )
            frame.bind("millis", clockFunction(SIGNATURE_MILLIS) { _, _ -> millis })
        }
    }

    /** A variable-arity builtin backed by [body]; arity is the caller's concern. */
    private fun clockFunction(
        signature: String,
        body: (picture: String?, timezone: String?) -> Any?,
    ): Jsonata.JFunction =
        Jsonata.JFunction(
            Jsonata.JFunctionCallable { _, args ->
                body(
                    args.getOrNull(0) as String?,
                    args.getOrNull(1) as String?,
                )
            },
            signature,
        )

    private fun refusingClockFunction(name: String): Jsonata.JFunction =
        clockFunction(SIGNATURE_NOW) { _, _ ->
            throw ScriptEvaluationException(
                "$name() is not available: a transform is a pure function of its inputs — " +
                    "pin EvaluationLimits.now so the clock is an input, or remove the clock call",
            )
        }

    private fun syntaxException(
        body: String,
        err: JException,
    ): ScriptSyntaxException {
        val at = lineColumn(body, err.location.coerceAtLeast(0))
        return ScriptSyntaxException(at.first, at.second, err.message ?: err.error)
    }

    private fun engineException(
        limits: EvaluationLimits,
        err: JException,
    ): ScriptingException {
        val text = err.message ?: err.error
        // Timebox.java throws its raw sentence as the JException "code"; the engine's
        // message rendering prefixes it ("JSonataException …"), so match on the raw
        // error text, never on the rendered message.
        val raw = err.error
        return when {
            raw.contains(DEPTH_PREFIX) -> {
                ScriptResourceLimitException(
                    ScriptResourceLimitException.Kind.DEPTH,
                    "recursion depth exceeded the declared maximum of ${limits.maxDepth}",
                )
            }

            raw.contains(TIMEOUT_PREFIX) -> {
                ScriptTimeoutException(limits.wallClock, "")
            }

            else -> {
                ScriptEvaluationException(text, err)
            }
        }
    }

    /** 1-based line/column for a 0-based character offset (the library's position). */
    private fun lineColumn(
        body: String,
        offset: Int,
    ): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, body.length)
        val prefix = body.substring(0, clamped)
        val line = prefix.count { it == '\n' } + 1
        val lastNewline = prefix.lastIndexOf('\n')
        val column = clamped - (lastNewline + 1) + 1
        return line to column
    }

    private companion object {
        /** The library's own signature strings for the two clock builtins. */
        const val SIGNATURE_NOW = "<s?s?:s>"
        const val SIGNATURE_MILLIS = "<:n>"

        /** Timebox.java's two refusal prefixes (checked as `message`, source 0.9.10). */
        const val DEPTH_PREFIX = "Stack overflow error"
        const val TIMEOUT_PREFIX = "Expression evaluation timeout"
    }
}
