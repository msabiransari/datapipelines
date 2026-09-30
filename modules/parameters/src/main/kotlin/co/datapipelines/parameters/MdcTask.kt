package co.datapipelines.parameters

import org.slf4j.MDC

/**
 * Copies the submitter's MDC onto a task that runs on another thread (#337, observability §3.3).
 *
 * A selector statement (`selector-N`) and the detached abandon thread (`selector-abandon-N`) leave
 * the submitting evaluate's coroutine; both carry the submitter's MDC — the node's correlation id —
 * so the one abandonment error line and any worker-side line stay attributable to the execution
 * that issued the statement, restored afterwards. Module-local: `parameters` may not depend on
 * `dag` or `scripting` for a logging helper (module-structure §4.1) and propagation is normative —
 * there is no switch to inject or remove.
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
