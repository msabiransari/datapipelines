package co.datapipelines.scripting

import java.time.Duration

/**
 * The engine seam (transform-nodes design §4.1): compile once, evaluate many, JSON in,
 * JSON out. Knows nothing of modes, contracts, invariants, Context or tempdb.
 *
 * Input and output are JSON-shaped values — [Map] (keys are [String]), [List],
 * [String], [Number], [Boolean] or null, nested arbitrarily. The engine treats the
 * input as read-only for the duration of the call; a caller that mutates it
 * concurrently owns the race.
 *
 * ## Threading — read before calling
 *
 * [compile] is a one-thread operation (parse); the returned [CompiledScript] is then
 * safe to evaluate concurrently from any number of threads. But [evaluate] BLOCKS a
 * thread for up to the whole wall-clock budget and — because the JSONata engine is not
 * interruptible inside a builtin — an overrun evaluation keeps its thread alive past
 * the budget. **Never call evaluate on an executor thread you cannot afford to lose;
 * use [ScriptEvaluationPool]**, which bounds concurrency, abandons overruns and
 * replaces their threads. This is the same discipline the DAG executor applies to
 * blocking JDBC calls.
 *
 * Refusals are typed; callers map them to catalog codes (the exceptions carry the
 * record §7 mapping in their `code`): [ScriptSyntaxException],
 * [ScriptEvaluationException], [ScriptTimeoutException],
 * [ScriptResourceLimitException], [ScriptPoolExhaustedException].
 */
interface ScriptEngine {
    /** Which language this engine evaluates. */
    val type: ScriptLanguage

    /** Which limits this engine can actually enforce — measured, not intended. */
    val capabilities: EngineCapabilities

    /**
     * Parses the body. A syntax error is [ScriptSyntaxException] with 1-based
     * [ScriptSyntaxException.line] and [ScriptSyntaxException.column]; nothing else
     * fails here — semantic errors surface at [evaluate].
     */
    fun compile(body: String): CompiledScript

    /**
     * Evaluates one JSON-shaped input against the compiled body under [limits].
     * Returns a JSON-shaped value (including null — a JSONata expression legitimately
     * matches nothing). Never call on an executor thread; use [ScriptEvaluationPool].
     */
    fun evaluate(
        script: CompiledScript,
        input: Any?,
        limits: EvaluationLimits,
    ): Any?

    companion object {
        /** The wall clock a body may legally run before the pool declares abandonment. */
        fun defaultLimits(wallClock: Duration): EvaluationLimits =
            EvaluationLimits(
                wallClock = wallClock,
                maxDepth = DEFAULT_MAX_DEPTH,
            )

        /** The library's own default recursion bound (Timebox maxDepth = 100). */
        const val DEFAULT_MAX_DEPTH = 100
    }
}
