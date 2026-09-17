package co.datapipelines.integration

import com.fasterxml.jackson.databind.JsonNode

/**
 * The lifecycle names of a streamed execution: every event name EXCEPT `node_progress`.
 *
 * `node_progress` (REST API §6.4.9, 149) is additive — zero or more samples per node,
 * interleaved between that node's `node_started` and its terminal event, their count a
 * function of wall time and cadence rather than of the pipeline's shape. An exact-sequence
 * assertion over the LIFECYCLE therefore compares this list; what it pins is the compatibility
 * promise that the lifecycle events keep their exact order under the samples.
 * [NodeProgressE2eTest] is where the samples themselves are asserted.
 */
fun List<Pair<String, JsonNode>>.lifecycleNames(): List<String> = map { it.first }.filterNot { it == NODE_PROGRESS }

/**
 * The failure record of every `node_failed` / `pipeline_failed` in the stream, one line each
 * (154, #137): the clue an exact-sequence assertion prints when the sequence it expected is
 * not the one that arrived.
 *
 * A lifecycle-only assertion compares NAMES, so a `node_failed` where a `node_completed` was
 * expected reads as "wrong sequence" and says nothing about WHY the node failed — and on a CI
 * runner the application log that would name the code is gone with the runner. The failure
 * record (rest-api §6.4.4) is already on the wire, unchanged across `node_failed`,
 * `pipeline_failed` and `error_json`; this reads `error.code`, `error.message` and the root
 * cause of `error.exception` (the LAST `caused_by` entry, or the exception itself) off it.
 * `details` are appended because the staging codes carry their measurement there
 * (`memory_used_bytes`, `max_memory_mb`). Empty when nothing failed.
 */
fun List<Pair<String, JsonNode>>.failureClue(): String {
    val failures = filter { (name, _) -> name == NODE_FAILED || name == PIPELINE_FAILED }
    if (failures.isEmpty()) return ""
    return failures.joinToString(separator = "\n", prefix = "failure records on the stream:\n") { (name, payload) ->
        val error = payload.path("error")
        val exception = error.path("exception")
        val rootCause = exception.path("caused_by").lastOrNull() ?: exception
        val details = error.path("details")
        buildString {
            append("  ").append(name)
            // `node_failed` names its node as `node_id`; the terminal `pipeline_failed` as `failed_node_id`.
            val nodeId = payload.path("node_id").takeUnless { it.isMissingNode } ?: payload.path("failed_node_id")
            append(" node=").append(nodeId.asText("-"))
            append(" code=").append(error.path("code").asText("-"))
            append(" message=").append(error.path("message").asText("-"))
            if (!rootCause.isMissingNode && !rootCause.isEmpty) {
                append(" root_cause=").append(rootCause.path("class").asText("-"))
                append(": ").append(rootCause.path("message").asText("-"))
            }
            if (!details.isMissingNode && !details.isEmpty) append(" details=").append(details)
        }
    }
}

private const val NODE_PROGRESS = "node_progress"
private const val NODE_FAILED = "node_failed"
private const val PIPELINE_FAILED = "pipeline_failed"
