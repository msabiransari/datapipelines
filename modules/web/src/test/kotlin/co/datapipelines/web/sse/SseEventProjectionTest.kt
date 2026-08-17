package co.datapipelines.web.sse

import co.datapipelines.events.DataReady
import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.NodeFailed
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.MappedError
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.NodeStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The wire projection (rest-api.md §6.4) as a pure function: no servlet, no Redis, no executor.
 * The three carry-forwards this class owns — correlation id on **every** event,
 * `node_failed.failed_at` ← `NodeStats.completedAt`, terminal `status` derived from the event
 * type — each get a direct assertion.
 */
class SseEventProjectionTest {
    private val correlationId = UUID.randomUUID()
    private val projection = SseEventProjection(correlationId)
    private val executionId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val started = Instant.parse("2026-08-05T14:30:00.123Z")
    private val completed = Instant.parse("2026-08-05T14:30:01.500Z")

    @Test
    fun `execution_started carries the documented fields and the correlation id`() {
        val event =
            ExecutionStarted(
                executionId = executionId,
                pipelineId = pipelineId,
                pipelineVersion = 3,
                parameters = mapOf("start_date" to "2026-01-01"),
                startedAt = started,
            )
        projection.eventName(event) shouldBe "execution_started"
        val payload = projection.payload(event)
        payload["execution_id"] shouldBe executionId
        payload["pipeline_version"] shouldBe 3
        payload["parameters"] shouldBe mapOf("start_date" to "2026-01-01")
        payload["correlation_id"] shouldBe correlationId
    }

    @Test
    fun `node_failed derives failed_at from the stats completedAt`() {
        val stats = stats(NodeStatus.FAILED)
        val event = NodeFailed(executionId, "fetch_orders", MappedError("pipeline.node.query_execution_failed", "boom"), stats)
        val payload = projection.payload(event)
        payload["failed_at"] shouldBe completed
        payload["duration_ms"] shouldBe 1377L
        payload["correlation_id"] shouldBe correlationId
        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any?>
        error["code"] shouldBe "pipeline.node.query_execution_failed"
        error["user_message"] shouldBe "A step in the pipeline failed while it was running."
        error["doc_url"] shouldBe "https://docs.datapipelines.co/errors/pipeline-node-query-execution-failed"
    }

    @Test
    fun `terminal events derive status from the event type`() {
        val completedEvent = PipelineCompleted(executionId, pipelineId, 1, started, completed, 1377, listOf(stats(NodeStatus.SUCCESS)))
        projection.payload(completedEvent)["status"] shouldBe "SUCCESS"

        val failedEvent =
            PipelineFailed(executionId, pipelineId, 1, started, completed, 1377, "n1", MappedError("c", "m"), emptyList())
        projection.payload(failedEvent)["status"] shouldBe "FAILED"
        projection.payload(failedEvent)["failed_node_id"] shouldBe "n1"

        val aborted = ExecutionAborted(executionId, pipelineId, AbortReason.CLIENT_DISCONNECT, completed, emptyList())
        projection.payload(aborted)["status"] shouldBe "ABORTED"
        projection.payload(aborted)["reason"] shouldBe "client_disconnect"
        projection.payload(aborted)["correlation_id"] shouldBe correlationId
    }

    @Test
    fun `data_ready carries the stored-result fields`() {
        val expires = Instant.parse("2026-08-05T14:35:02Z")
        val event =
            DataReady(
                executionId = executionId,
                pipelineId = pipelineId,
                schema = emptyList(),
                rows = listOf(listOf(1, "12345.67")),
                totalRows = 2,
                hasMore = true,
                resultUrl = "https://dp.example.com/api/v1/executions/$executionId/result",
                expiresAt = expires,
                ttlSeconds = 300,
            )
        val payload = projection.payload(event)
        payload["row_count"] shouldBe 1
        payload["total_rows"] shouldBe 2L
        payload["has_more"] shouldBe true
        payload["result_url"] shouldBe "https://dp.example.com/api/v1/executions/$executionId/result"
        payload["ttl_seconds"] shouldBe 300L
        payload["correlation_id"] shouldBe correlationId
    }

    @Test
    fun `node events carry attempt and out-stats`() {
        val nodeStarted = NodeStarted(executionId, "n", started, attempt = 1)
        projection.payload(nodeStarted)["attempt"] shouldBe 1

        val nodeCompleted = NodeCompleted(executionId, "n", stats(NodeStatus.SUCCESS))
        val payload = projection.payload(nodeCompleted)
        payload["rows_out"] shouldBe 10L
        payload["bytes_out"] shouldBe 100L
        payload["correlation_id"] shouldBe correlationId
        // A non-PIPELINE node never carries the composition link (design §7).
        payload.containsKey("child_execution_id") shouldBe false
    }

    @Test
    fun `a PIPELINE node's completion links to the child execution it spawned`() {
        val childExecutionId = UUID.randomUUID()
        val event = NodeCompleted(executionId, "revenue", stats(NodeStatus.SUCCESS).copy(childExecutionId = childExecutionId))

        projection.payload(event)["child_execution_id"] shouldBe childExecutionId
    }

    private fun stats(status: NodeStatus) =
        NodeStats(
            nodeId = "n",
            status = status,
            startedAt = started,
            completedAt = completed,
            durationMs = 1377,
            rowsOut = 10,
            bytesOut = 100,
        )
}
