package co.datapipelines.web.ui

import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutorJson
import com.fasterxml.jackson.databind.JsonNode

/**
 * One node's operation as the execution detail page shows it (149) — derived from the
 * DURABLE `node_progress` records (`execution_events`, rest-api §6.4.9), the last sample per
 * node. Everything is a fact the sample carried: an operation whose last sample is not
 * terminal (the execution ended around it) reports `terminal = false` and NO commit — the
 * page never upgrades an unobserved commit to "committed".
 */
data class NodeOperationRow(
    val nodeId: String,
    val operation: String,
    val destination: String,
    val state: String,
    val terminal: Boolean,
    val rowsFetched: Long?,
    val rowsWritten: Long?,
    val elapsedMs: Long?,
    val committed: Boolean?,
    val rolledBack: Boolean,
    /** The per-state time share as text, in measurement order; null when nothing was measured. */
    val timings: String?,
    val childExecutionId: String?,
)

object NodeOperationHistory {
    private const val NODE_PROGRESS = "node_progress"
    private val PHASES =
        listOf(
            "connecting" to "connect",
            "executing" to "query",
            "fetching" to "fetch",
            "waiting_output" to "wait",
            "writing" to "write",
            "finalizing" to "finalize",
        )
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_PER_MINUTE = 60_000L

    /** The rows, in first-appearance order of the node; nodes without a sample are absent. */
    fun from(records: List<ExecutionEventRecord>): List<NodeOperationRow> {
        val latest = LinkedHashMap<String, JsonNode>()
        records
            .asSequence()
            .filter { it.eventType == NODE_PROGRESS }
            .mapNotNull { runCatching { ExecutorJson.mapper.readTree(it.payloadJson) }.getOrNull() }
            .filter { it.hasNonNull("node_id") }
            .forEach { sample ->
                val nodeId = sample["node_id"].asText()
                val previous = latest[nodeId]
                if (previous == null || sample.path("sequence").asInt() >= previous.path("sequence").asInt()) latest[nodeId] = sample
            }
        return latest.map { (nodeId, s) -> row(nodeId, s) }
    }

    private fun row(
        nodeId: String,
        s: JsonNode,
    ): NodeOperationRow {
        val state = s.path("state").asText("")
        return NodeOperationRow(
            nodeId = nodeId,
            operation = s.path("operation").asText("—"),
            destination = destinationText(s.path("destination")),
            state = state,
            terminal = state in TERMINAL,
            rowsFetched = s.longOrNull("rows_fetched"),
            rowsWritten = s.longOrNull("rows_written"),
            elapsedMs = s.longOrNull("elapsed_ms"),
            committed = s.get("committed")?.takeIf { it.isBoolean }?.asBoolean(),
            rolledBack = s.path("rolled_back").asBoolean(false),
            timings = timingsText(s.path("timings_ms")),
            childExecutionId = s.get("child_execution_id")?.takeIf { it.isTextual }?.asText(),
        )
    }

    private fun JsonNode.longOrNull(field: String): Long? = get(field)?.takeIf { it.isNumber }?.asLong()

    fun destinationText(d: JsonNode): String =
        when (d.path("kind").asText("")) {
            "tempdb" -> d.get("table")?.asText()?.let { "tempdb.$it" } ?: "tempdb"
            "datasource" -> listOfNotNull(d.get("datasource")?.asText(), d.get("table")?.asText()).joinToString(".")
            "caller" -> "caller"
            "parent" -> "parent pipeline"
            "none" -> "no output"
            else -> "—"
        }

    private fun timingsText(t: JsonNode): String? {
        if (!t.isObject) return null
        val parts = PHASES.mapNotNull { (key, label) -> t.get(key)?.takeIf { it.isNumber }?.let { "$label ${millis(it.asLong())}" } }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun millis(ms: Long): String =
        when {
            ms < MILLIS_PER_SECOND -> "$ms ms"
            ms < MILLIS_PER_MINUTE -> String.format(java.util.Locale.ROOT, "%.1f s", ms / MILLIS_PER_SECOND.toDouble())
            else -> "${ms / MILLIS_PER_MINUTE}m ${(ms % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND}s"
        }

    private val TERMINAL = setOf("completed", "failed", "aborted")
}
