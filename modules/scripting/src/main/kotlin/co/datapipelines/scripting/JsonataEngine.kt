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
 *  - **Bounds between steps, honestly — counted by the engine (#260).** The engine
 *    installs its own `setEvaluateEntryCallback`/`setEvaluateExitCallback` hooks and
 *    counts every evaluate entry and exit on the per-evaluation frame: depth is
 *    enforced at every step — nested expressions and non-tail lambda recursion alike
 *    (a tail-recursive call is trampolined by the library and never nests; there the
 *    wall clock is the bound) — and the wall clock is checked at every entry and exit.
 *    The library's `Timebox` (what `setRuntimeBounds` installed) returned early from
 *    both callbacks on `isParallelCall` frames — the evaluator marks the second and
 *    later object pairs / arguments that way on the frame itself — so one depth unit
 *    leaked per item on set-level shapes (a 240-row reshape refused at the default
 *    depth of 100) and the clock check was skipped in exactly those frames. A single
 *    builtin call that overruns (`$pad`, `$join`, a backtracking regex) still runs to
 *    completion first — there is no step boundary inside it. Hence [capabilities]:
 *    heap and statements are NOT bounded in-process, and the engine is NOT
 *    interruptible. The evaluation pool's abandonment is the bound that actually
 *    stops an overrunning builtin's caller.
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
class JsonataEngine(
    /** The injected wall clock behind the between-steps bound (#260) — the module's
     * purity rule leaves the ambient read to [ScriptClock.SYSTEM] alone. */
    private val clock: ScriptClock = ScriptClock.SYSTEM,
) : ScriptEngine {
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

        // The depth counter and the clock live in THIS evaluation's closure — one
        // evaluation, one counter, thread-confined (the frame-per-evaluation rule the
        // conformance suite's falsification red-flags). `breach` records the typed
        // refusal the hooks threw: a library path may REPLACE a non-JException while
        // unwinding (evaluateBinary's and/or short-circuit turns one into
        // JException("Unexpected")), and the recorded breach — never the wrapper's
        // text — is what the seam rethrows.
        var depth = 0
        var breach: ScriptingException? = null
        val startedAt = clock.currentTimeMillis()

        fun checkDepth() {
            if (depth > limits.maxDepth) {
                val refusal =
                    ScriptResourceLimitException(
                        ScriptResourceLimitException.Kind.DEPTH,
                        "recursion depth exceeded the declared maximum of ${limits.maxDepth}",
                    )
                breach = refusal
                throw refusal
            }
        }

        fun checkWallClock() {
            if (clock.currentTimeMillis() - startedAt > limits.wallClock.toMillis()) {
                val timeout = ScriptTimeoutException(limits.wallClock, "")
                breach = timeout
                throw timeout
            }
        }

        frame.setEvaluateEntryCallback { _, _, _ ->
            depth++
            checkDepth()
            checkWallClock()
        }
        frame.setEvaluateExitCallback { _, _, _, _ ->
            depth--
            checkWallClock()
        }
        bindClock(frame, limits.now)
        return try {
            JsonataValues.fromEngineOutput(
                compiled.expr.evaluate(JsonataValues.toEngineInput(input), frame),
            )
        } catch (err: ScriptingException) {
            throw err
        } catch (err: JException) {
            // The hooks' own typed refusal arrives wrapped when a library path replaced
            // it mid-unwind (evaluateBinary's and/or short-circuit turns a non-JException
            // into JException("Unexpected")); the recorded breach — never the wrapper's
            // text — is what gets rethrown. Every remaining JException is a script error.
            throw breach ?: ScriptEvaluationException(err.message ?: err.error, err)
        } catch (
            @Suppress("TooGenericExceptionCaught") err: RuntimeException,
        ) {
            // The library rethrows builtin failures verbatim after message population;
            // anything else here is an engine or library defect surfacing mid-evaluation.
            throw breach ?: ScriptEvaluationException(
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
    }
}
