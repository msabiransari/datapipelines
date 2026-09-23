package co.datapipelines.scripting

/**
 * The evaluation exceeded its wall clock (transform-nodes design §4.3: the engine's own
 * bound fires first; the node deadline is the outer backstop).
 *
 * Raised by the engine when the library's between-steps time check fires AND by the
 * evaluation pool when the budget plus abandonment grace is overrun. Note the honesty
 * rule the capabilities table states: a single builtin call that overruns is NOT
 * interruptible, so this exception can arrive while the evaluation thread is still
 * running — the pool counts and replaces such threads, it never joins them. Carries
 * the `pipeline.transform.timeout` code.
 */
class ScriptTimeoutException(
    wallClock: java.time.Duration,
    label: String,
) : ScriptingException(
        CODE,
        "evaluation exceeded its wall clock budget of ${wallClock.toMillis()} ms" +
            (if (label.isBlank()) "" else " (script: $label)"),
        mapOf("wall_clock_ms" to wallClock.toMillis()),
    ) {
    companion object {
        /** pipeline-contract §13.18 — the engine-timeout row (record §7). */
        const val CODE = "pipeline.transform.timeout"
    }
}
