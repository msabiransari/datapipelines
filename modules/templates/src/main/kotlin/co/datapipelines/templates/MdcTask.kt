package co.datapipelines.templates

import org.slf4j.MDC
import java.util.concurrent.Callable

/**
 * Copies the submitter's MDC onto a task that runs on another thread (#337, observability §3.3).
 *
 * A render (`template-render-N`) and a save-time parse (`template-parse`) leave the caller's thread;
 * both carry the caller's MDC — the request's or the node's correlation id — for the task's
 * duration, restored afterwards, so a line logged from inside a render is still attributable to the
 * request that asked for it. Module-local: `templates` sits below `dag` and `web`
 * (module-structure §4.1) and propagation is normative — there is no switch to inject or remove.
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

    /** As [wrap], for callables (a render submitted to the render pool). */
    fun <T> wrap(
        submitted: Map<String, String>?,
        body: Callable<T>,
    ): Callable<T> =
        Callable {
            val previous = capture()
            install(submitted)
            try {
                body.call()
            } finally {
                install(previous)
            }
        }

    private fun install(map: Map<String, String>?) {
        if (map == null) MDC.clear() else MDC.setContextMap(map)
    }
}
