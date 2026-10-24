package co.datapipelines.scripting

import org.graalvm.polyglot.PolyglotException

/**
 * Maps what the isolate threw to the seam's typed refusals (#8 A.3). Classified by the polyglot
 * flags and the sandbox's own limit messages (measured in lane 8a, GraalVM 25.4.4.1.1 — each pinned
 * by a test, so an upgrade that rewords one goes red rather than silently re-labelling it):
 *
 * | message (start)                              | flags                  | refusal                  |
 * |----------------------------------------------|------------------------|--------------------------|
 * | `Maximum CPU time limit of …`                | exhausted, cancelled   | [ScriptTimeoutException] |
 * | `Context execution was cancelled.`           | cancelled              | [ScriptTimeoutException] (the watchdog or the pool) |
 * | `Maximum heap memory limit of …`             | exhausted, cancelled   | HEAP                     |
 * | `Garbage-collected heap size exceeded…`      | exhausted              | HEAP (the isolate's own cap) |
 * | `Array allocation too large.`                | exhausted              | HEAP (the isolate's allocator) |
 * | `Maximum statement limit of …`               | exhausted, cancelled   | STATEMENTS               |
 * | `Maximum frame limit of …`                   | exhausted, cancelled   | DEPTH                    |
 * | `Maximum output/error stream size of …`      | exhausted, cancelled   | OUTPUT                   |
 * | `Resource exhausted: Stack overflow`         | exhausted              | DEPTH (parse nesting)    |
 * | `RangeError: Maximum call stack size exceeded` | guest                | DEPTH (the native stack, past the frame cap's reach) |
 *
 * `Array allocation too large.` came from the breach suite's `new Array(1e9).fill(0)` on one run in
 * two; the other run, and three more under the module's tests, met the sandbox's heap cap instead.
 *
 * A guest error is [ScriptEvaluationException] with the guest's message and the author-relative
 * line of the innermost frame in the author's source; the prelude's tagged errors carry their own
 * text (a refused clock read, a result that is not JSON-shaped, a result past its budget).
 */
internal object JavaScriptErrors {
    const val REFUSED_TAG = "__dp_refused:"
    const val SHAPE_TAG = "__dp_shape:"
    const val RESULT_BYTES_TAG = "__dp_result_bytes:"
    const val DEPTH_TAG = "__dp_depth:"

    private const val CPU = "Maximum CPU time limit"
    private const val CANCELLED = "Context execution was cancelled"
    private const val STACK_OVERFLOW = "Stack overflow"
    private const val CALL_STACK_ERROR = "RangeError: Maximum call stack size exceeded"
    private const val MAX_ERR_CHARS = 500

    private val limitKinds: List<Pair<String, ScriptResourceLimitException.Kind>> =
        listOf(
            "Maximum heap memory limit" to ScriptResourceLimitException.Kind.HEAP,
            "Garbage-collected heap size exceeded" to ScriptResourceLimitException.Kind.HEAP,
            "Array allocation too large" to ScriptResourceLimitException.Kind.HEAP,
            "Maximum statement limit" to ScriptResourceLimitException.Kind.STATEMENTS,
            "Maximum frame limit" to ScriptResourceLimitException.Kind.DEPTH,
            "Maximum output stream size" to ScriptResourceLimitException.Kind.OUTPUT,
            "Maximum error stream size" to ScriptResourceLimitException.Kind.OUTPUT,
            STACK_OVERFLOW to ScriptResourceLimitException.Kind.DEPTH,
        )

    /**
     * The refusal for [err]. [timedOut] is true when the engine's watchdog or the pool closed the
     * Context; [authorLine] maps a location in the author's source to the author's own line;
     * [errText] is the captured error stream (appended, bounded, to a guest error).
     */
    fun map(
        err: PolyglotException,
        timedOut: Boolean,
        wallClock: java.time.Duration,
        authorLine: (PolyglotException) -> Int?,
        errText: String,
    ): ScriptingException {
        val message = err.message.orEmpty()
        return when {
            message.startsWith(CPU) || (err.isCancelled && (timedOut || message.startsWith(CANCELLED)) && !err.isResourceExhausted) -> {
                ScriptTimeoutException(wallClock, "")
            }

            err.isResourceExhausted -> {
                limit(message)
            }

            // Uncaught, it is the native stack's own bound — the same limit as the frame cap; a body's
            // own error that merely mentions it is not (the prefix is the engine's).
            message.startsWith(CALL_STACK_ERROR) -> {
                ScriptResourceLimitException(ScriptResourceLimitException.Kind.DEPTH, message)
            }

            err.isInternalError -> {
                ScriptEvaluationException("the JavaScript engine failed internally: $message", err)
            }

            else -> {
                guest(message, authorLine(err), errText, err)
            }
        }
    }

    /** The limit a resource-exhausted [message] names, or null for one the table does not know. */
    fun limitKind(message: String): ScriptResourceLimitException.Kind? = limitKinds.firstOrNull { message.contains(it.first) }?.second

    private fun limit(message: String): ScriptingException {
        val kind = limitKind(message)
        return if (kind == null) {
            ScriptEvaluationException("the JavaScript sandbox refused a resource: $message")
        } else {
            ScriptResourceLimitException(kind, message)
        }
    }

    private fun guest(
        message: String,
        line: Int?,
        errText: String,
        err: PolyglotException,
    ): ScriptingException {
        preludeRefusal(message)?.let { return it }
        val located = if (line == null) message else "$message (line $line)"
        val withErr = if (errText.isBlank()) located else "$located; stderr: ${errText.trim().take(MAX_ERR_CHARS)}"
        return ScriptEvaluationException(withErr, err)
    }

    /** A refusal the prelude raised, by the tag it carries; null for the author's own error. */
    private fun preludeRefusal(message: String): ScriptingException? =
        tagged(message, RESULT_BYTES_TAG)?.let { ScriptResourceLimitException(ScriptResourceLimitException.Kind.RESULT_BYTES, it) }
            ?: tagged(message, DEPTH_TAG)?.let { ScriptResourceLimitException(ScriptResourceLimitException.Kind.DEPTH, it) }
            ?: tagged(message, REFUSED_TAG)?.let { ScriptEvaluationException(it) }
            ?: tagged(message, SHAPE_TAG)?.let { ScriptEvaluationException(it) }

    private fun tagged(
        message: String,
        tag: String,
    ): String? {
        val at = message.indexOf(tag)
        return if (at < 0) null else message.substring(at + tag.length)
    }
}
