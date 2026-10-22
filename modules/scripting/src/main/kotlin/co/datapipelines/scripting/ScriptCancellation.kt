package co.datapipelines.scripting

/**
 * The per-slot cancellation hook between [ScriptEvaluationPool] and an engine that CAN be stopped
 * from outside (#8 A.2b).
 *
 * The pool creates one per admitted evaluation and runs the work with it installed on the
 * evaluation's own thread. An engine whose running body can be stopped only by a call from another
 * thread — the JavaScript engine: `Context.close(true)` is the one thing that stops a running
 * Context; `Thread.interrupt()` reaches nothing inside the isolate (measured in lane 8a: a
 * `while (true) {}` was still running 2 s after the interrupt, and `close(true)` returned in 3–4 ms)
 * — registers that call at the start of `evaluate` and clears it at the end. When the pool abandons
 * the evaluation it calls [cancel] BEFORE `Future.cancel(true)`, so the slot's thread ends and the
 * slot comes back instead of staying held by a runaway.
 *
 * Off the pool (a direct `evaluate` call) [register] finds no slot and returns a no-op handle; the
 * engine's own wall-clock watchdog is then the only outside stop.
 */
internal class ScriptCancellation {
    private val lock = Any()
    private var canceller: Runnable? = null
    private var cancelled = false

    /**
     * Stops the registered evaluation, now or as soon as one registers: a cancel that arrives before
     * the engine registered is remembered, and [register] runs the canceller at once.
     */
    fun cancel() {
        val registered =
            synchronized(lock) {
                cancelled = true
                canceller
            }
        registered?.run()
    }

    private fun install(stop: Runnable): AutoCloseable {
        val alreadyCancelled =
            synchronized(lock) {
                canceller = stop
                cancelled
            }
        if (alreadyCancelled) stop.run()
        return AutoCloseable {
            synchronized(lock) {
                if (canceller === stop) canceller = null
            }
        }
    }

    companion object {
        private val current = ThreadLocal<ScriptCancellation?>()

        /** Runs [body] with [slot] installed on this thread — the pool's side. */
        fun <T> runWith(
            slot: ScriptCancellation,
            body: () -> T,
        ): T {
            current.set(slot)
            try {
                return body()
            } finally {
                current.remove()
            }
        }

        /**
         * Registers [stop] on the slot this thread runs for — the engine's side. Close the returned
         * handle when the evaluation ends; off the pool it is a no-op.
         */
        fun register(stop: Runnable): AutoCloseable = current.get()?.install(stop) ?: AutoCloseable {}
    }
}
