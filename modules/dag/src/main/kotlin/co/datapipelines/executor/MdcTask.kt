package co.datapipelines.executor

import org.slf4j.MDC
import java.util.concurrent.Callable

/**
 * Copies the submitter's MDC onto a task that runs on another thread (#337, observability §3.3).
 *
 * The executor's own coroutine context carries the ids ([LogContext]); the raw threads this module
 * spawns from inside a request or an execution — the cancel re-issue daemon — are outside coroutine
 * land, so their work captures the creating thread's MDC and installs it for the task's duration,
 * restoring the thread's own previous map afterwards. Capture is a plain `Map` copy at the creation
 * site and [wrap] is what runs: two steps, so a caller that captures early can hand the map to a
 * thread created later (the selector abandon shape in `parameters` does exactly that).
 *
 * Deliberately module-local (`dag`): the same few lines live beside each pool that owns threads,
 * because no module below `web` may depend on `dag` for a logging helper (module-structure §4.2),
 * and propagation is normative — there is no switch to inject or remove.
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

    /** As [wrap], for callables (a render submitted to a `ThreadPoolExecutor`). */
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
