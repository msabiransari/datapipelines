package co.datapipelines.scripting

import org.slf4j.MDC

/**
 * Copies the submitter's MDC onto the evaluation's own thread (#337, observability §3.3).
 *
 * A script evaluation runs on a `script-eval-N` thread that outlives the submitter's suspension
 * point; the run carries the submitter's MDC — the request's or the node's correlation id — so a
 * line from inside the pool is still attributable to the execution that asked for it, restored
 * afterwards. Module-local: `scripting` is a leaf module (module-structure §4.1) and propagation
 * is normative — there is no switch to inject or remove. The module's purity rule (ambient
 * clock/zone/random reads) is untouched: the MDC is a correlation slot, not a nondeterminism source.
 */
internal object MdcTask {
    /** The creating thread's MDC, copied — `null` when it carries none. */
    fun capture(): Map<String, String>? = MDC.getCopyOfContextMap()

    /** Runs [body] with [submitted] installed, restoring the thread's previous MDC afterwards. */
    fun wrap(
        submitted: Map<String, String>?,
        body: Runnable,
    ): Runnable =
        Runnable {
            val previous = capture()
            install(submitted)
            try {
                body.run()
            } finally {
                install(previous)
            }
        }

    private fun install(map: Map<String, String>?) {
        if (map == null) MDC.clear() else MDC.setContextMap(map)
    }
}
