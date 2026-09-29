package co.datapipelines.scripting

import com.dashjoin.jsonata.JException

/**
 * One evaluation's between-steps bounds and its answer to "what escaped?" (#260, #272).
 *
 * Created per evaluation and confined to its thread: the depth counter, the start of the
 * wall clock and the pending refusal are this evaluation's alone (the frame-per-evaluation
 * rule the conformance suite red-flags).
 *
 * **The hooks.** [onEntry] and [onExit] are installed as the library's evaluate callbacks:
 * depth is counted at every entry and exit, the wall clock checked at both. They are a
 * runaway guard — a single builtin that overruns never reaches a step boundary, and the
 * evaluation pool's abandonment is the sandbox bound (owner ruling 2026-09-28, record §4.5).
 *
 * **What escaped.** The library delivers a failure to the seam three ways: verbatim; WRAPPED
 * (`$sort`'s comparator rethrows everything inside a plain `RuntimeException`, jsonata 0.9.10
 * `Functions.java:1968`), where the cause chain still holds it; and REPLACED with no cause
 * (the `and`/`or` short-circuit's `JException("Unexpected")`, `$eval`'s `D3121`). [escaped]
 * reads the chain first; only when the chain holds neither an engine refusal nor a library
 * error does the refusal this guard RECORDED ([refuse]) answer. A recorded refusal is PENDING
 * only until the library runs another step: a hook firing after it means the evaluation went
 * on (`$replace` with a function swallows a throwable, `Functions.java:858`), so it did not
 * escape and can never label a later, genuine error.
 */
internal class JsonataEvaluationGuard(
    private val limits: EvaluationLimits,
    private val clock: ScriptClock,
) {
    private var depth = 0
    private var pending: ScriptingException? = null
    private val startedAt = clock.currentTimeMillis()

    /** The evaluate-entry hook: one step deeper; depth and clock checked. */
    fun onEntry() {
        pending = null
        depth++
        if (depth > limits.maxDepth) {
            throw refuse(
                ScriptResourceLimitException(
                    ScriptResourceLimitException.Kind.DEPTH,
                    "recursion depth exceeded the declared maximum of ${limits.maxDepth}",
                ),
            )
        }
        checkWallClock()
    }

    /** The evaluate-exit hook: one step shallower; clock checked. */
    fun onExit() {
        pending = null
        depth--
        checkWallClock()
    }

    /** Records [refusal] as pending and returns it for the caller to throw. */
    fun refuse(refusal: ScriptingException): ScriptingException {
        pending = refusal
        return refusal
    }

    /** The typed failure the seam rethrows for [err], which escaped the library. */
    fun escaped(err: Throwable): ScriptingException {
        val chain = generateSequence(err) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val scriptError = chain.firstNotNullOfOrNull { it as? JException }
        // Order: the engine's own refusal where the chain still holds it; else a stack
        // overflow the library WRAPPED (#314) — the same catalogued refusal the direct
        // boundary catch gives, wherever the library buried it; else a refusal still
        // pending, which escaped under a library REPLACEMENT (that carries no cause);
        // else the library's script error, wherever it was wrapped.
        return chain.firstNotNullOfOrNull { it as? ScriptingException }
            ?: chain.filterIsInstance<StackOverflowError>().firstOrNull()?.let {
                ScriptResourceLimitException(
                    ScriptResourceLimitException.Kind.DEPTH,
                    "the library's recursion overflowed the evaluation stack mid-evaluation — " +
                        "refused as the catalogued depth limit instead of surfacing an Error",
                )
            }
            ?: pending
            ?: scriptError?.let { ScriptEvaluationException(it.message ?: it.error, it) }
            ?: ScriptEvaluationException("evaluation failed unexpectedly: ${err.message ?: err.javaClass.name}", err)
    }

    private fun checkWallClock() {
        if (clock.currentTimeMillis() - startedAt > limits.wallClock.toMillis()) {
            throw refuse(ScriptTimeoutException(limits.wallClock, ""))
        }
    }

    private companion object {
        /** Wrappers the library stacks are one or two deep; the bound only stops a cyclic chain. */
        const val MAX_CAUSE_DEPTH = 16
    }
}
