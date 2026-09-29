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
 *    stops an overrunning builtin's caller — and the SANDBOX bound against a hostile
 *    body (owner ruling 2026-09-28, record §4.5); the hooks are a runaway guard.
 *  - **A body past the compile-time nesting ceiling never reaches the library (#314).**
 *    The library's parser AND evaluator recurse per level; 500 nested brackets overflowed
 *    a 256 KB stack inside the parse alone (measured 2026-09-29 — and 297's conformance
 *    run saw the same shape once on a default stack under load, the JIT deciding frame
 *    sizes). [compile] refuses past [JsonataNestingScan.CEILING] with the engine's
 *    catalogued DEPTH refusal — the same kind the evaluate-time counter throws, because
 *    the expression is grammatical and only its depth is over the line — and BOTH
 *    boundaries (compile and evaluate) turn a residual `StackOverflowError` into that
 *    same refusal, so an Error never escapes the seam even for a body that defeats the
 *    pre-scan's under-counting.
 *  - **The hooks cannot be unbound by the body (#272).** They are frame variables the
 *    library looks up by name, so a body that binds `$__evaluate_entry` switches them
 *    off. [compile] refuses every bind of a `__` name, read from the parsed AST
 *    ([JsonataReservedNames]); `$eval`, which parses at evaluate time and binds in the
 *    CALLING frame, is shadowed so its string meets the same check before it runs.
 *  - **The seam rethrows what escaped (#272).** [JsonataEvaluationGuard.escaped] reads
 *    the cause chain (a `$sort` comparator wraps), then a refusal still pending under a
 *    library replacement (the `and`/`or` short-circuit, `$eval`); a refusal the library
 *    swallowed and went on past never labels a later error.
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
 * AST's fields are not public: the reserved-name walk reads them reflectively, resolved
 * when it loads so a library that renames one fails every compile rather than the walk
 * going blind. The clock refusal stays a call-time shadow rather than a compile-time
 * scan — a body that never evaluates a clock call never sees it.
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
        // The nesting ceiling (#314) fires BEFORE the library parses: the library's
        // parser recurses per level, so a deeper body can overflow the compiling
        // thread's stack — an Error, not a refusal. The scan is a linear pass with
        // O(1) stack (JsonataNestingScan).
        if (JsonataNestingScan.exceeds(body)) {
            throw ScriptResourceLimitException(
                ScriptResourceLimitException.Kind.DEPTH,
                JsonataNestingScan.refusal(),
            )
        }
        val expr =
            try {
                Jsonata.jsonata(body)
            } catch (err: JException) {
                throw syntaxException(body, err)
            } catch (
                @Suppress("SwallowedException", "TooGenericExceptionCaught") err: StackOverflowError,
            ) {
                // A body that defeats the pre-scan (a miscounted construct) still
                // cannot surface an Error (#314) — the boundary guard. The Error
                // itself is deliberately not chained: the catalogued refusal is the
                // message, and walking an overflow's frames buys nothing.
                throw stackOverflowRefusal("at compile")
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
        refuseReservedBinds(body, expr)
        return JsonataCompiledScript(body, expr)
    }

    /**
     * A body that binds a `__` name is refused before it can run (#272, see the class KDoc).
     * The walk reads the tree the library just parsed — the one that will evaluate — so
     * compile parses once, as before the check existed.
     */
    private fun refuseReservedBinds(
        body: String,
        expr: Jsonata,
    ) {
        val bind = JsonataReservedNames.firstReservedBind(expr) ?: return
        val at = lineColumn(body, bind.position.coerceAtLeast(0))
        throw ScriptSyntaxException(at.first, at.second, JsonataReservedNames.refusal(bind))
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

        // One guard per evaluation — one depth counter, one clock start, one pending
        // refusal, thread-confined (the frame-per-evaluation rule the conformance suite's
        // falsification red-flags).
        val guard = JsonataEvaluationGuard(limits, clock)
        frame.setEvaluateEntryCallback { _, _, _ -> guard.onEntry() }
        frame.setEvaluateExitCallback { _, _, _, _ -> guard.onExit() }
        bindClock(frame, limits.now, guard)
        bindEval(frame, guard)
        return try {
            JsonataValues.fromEngineOutput(
                compiled.expr.evaluate(JsonataValues.toEngineInput(input), frame),
            )
        } catch (
            @Suppress("SwallowedException", "TooGenericExceptionCaught") err: StackOverflowError,
        ) {
            // The library's evaluator recurses too — a non-tail recursion past what
            // the thread's stack carries, with a maxDepth too high to refuse first,
            // arrives as an Error (#314). It is the same catalogued refusal, never
            // an Error escaping the seam; the Error is not chained (see compile).
            throw stackOverflowRefusal("at evaluate")
        } catch (
            @Suppress("TooGenericExceptionCaught") err: RuntimeException,
        ) {
            // Every failure — the engine's own refusal, a library script error, or a
            // library or engine defect surfacing mid-evaluation — is classified by
            // what escaped.
            throw guard.escaped(err)
        }
    }

    /**
     * Shadows `$eval` for this evaluation (#272): the string is parsed with the library's
     * own parser and a reserved bind is refused BEFORE the library evaluates it in the
     * calling frame. Anything else — including a string that does not parse, which keeps
     * the library's own D3120 — goes to the library's `$eval` unchanged.
     */
    private fun bindEval(
        frame: Jsonata.Frame,
        guard: JsonataEvaluationGuard,
    ) {
        frame.bind(
            "eval",
            Jsonata.JFunction(
                Jsonata.JFunctionCallable { _, args ->
                    val expr = args.getOrNull(0) as String?
                    reservedBindIn(expr)?.let { bind ->
                        throw guard.refuse(
                            ScriptEvaluationException("\$eval refused: ${JsonataReservedNames.refusal(bind)}"),
                        )
                    }
                    Functions.functionEval(expr, args.getOrNull(1))
                },
                SIGNATURE_EVAL,
            ),
        )
    }

    private fun reservedBindIn(expr: String?): JsonataReservedNames.Bind? =
        if (expr == null) {
            null
        } else {
            try {
                JsonataReservedNames.firstReservedBind(expr)
            } catch (
                @Suppress("SwallowedException") err: JException,
            ) {
                null // the library's own parse refuses it next, as D3120
            }
        }

    /**
     * Shadows the library's clock builtins for this evaluation — pinned to [now] when
     * the caller supplies it, refusing otherwise (see the class KDoc).
     */
    private fun bindClock(
        frame: Jsonata.Frame,
        now: Instant?,
        guard: JsonataEvaluationGuard,
    ) {
        if (now == null) {
            frame.bind("now", refusingClockFunction("now", guard))
            frame.bind("millis", refusingClockFunction("millis", guard))
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

    private fun refusingClockFunction(
        name: String,
        guard: JsonataEvaluationGuard,
    ): Jsonata.JFunction =
        clockFunction(SIGNATURE_NOW) { _, _ ->
            throw guard.refuse(
                ScriptEvaluationException(
                    "$name() is not available: a transform is a pure function of its inputs — " +
                        "pin EvaluationLimits.now so the clock is an input, or remove the clock call",
                ),
            )
        }

    private fun syntaxException(
        body: String,
        err: JException,
    ): ScriptSyntaxException {
        val at = lineColumn(body, err.location.coerceAtLeast(0))
        return ScriptSyntaxException(at.first, at.second, err.message ?: err.error)
    }

    /** The one catalogued refusal a stack overflow becomes, at either boundary (#314). */
    private fun stackOverflowRefusal(at: String): ScriptResourceLimitException =
        ScriptResourceLimitException(
            ScriptResourceLimitException.Kind.DEPTH,
            "the library's recursion overflowed the evaluation stack $at — refused as the " +
                "catalogued depth limit instead of surfacing an Error",
        )

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

        /** The library's own signature for `$eval` (`Jsonata.java` registers it so). */
        const val SIGNATURE_EVAL = "<sx?:x>"
    }
}
