package co.datapipelines.scripting

/**
 * The evaluation itself failed — a JSONata runtime error (undefined variable, type
 * error inside the body, …), NOT a resource bound ([ScriptTimeoutException],
 * [ScriptResourceLimitException]) and not a syntax error ([ScriptSyntaxException]).
 *
 * [message] is bounded to 2000 characters so an engine message a malicious body
 * influenced cannot blow up a log line, an SSE event or an error payload; the record
 * §7 bounds the mapped code's detail the same way. Carries the
 * `pipeline.transform.evaluation_failed` code.
 */
class ScriptEvaluationException(
    message: String,
    cause: Throwable? = null,
) : ScriptingException(CODE, message.bounded(), details = emptyMap(), cause = cause) {
    companion object {
        /** pipeline-contract §13.18 — the script-threw row (record §7). */
        const val CODE = "pipeline.transform.evaluation_failed"

        /** The bound §7 puts on the mapped detail (`ErrorCodeMapper.MAX_MESSAGE_CHARS`). */
        const val MAX_MESSAGE_CHARS = 2000

        private fun String.bounded(): String = if (length <= MAX_MESSAGE_CHARS) this else take(MAX_MESSAGE_CHARS)
    }
}
