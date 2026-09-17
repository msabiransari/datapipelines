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

private const val NODE_PROGRESS = "node_progress"
