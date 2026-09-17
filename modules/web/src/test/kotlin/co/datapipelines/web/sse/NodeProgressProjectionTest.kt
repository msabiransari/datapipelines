package co.datapipelines.web.sse

import co.datapipelines.events.NodeProgress
import co.datapipelines.executor.OperationDestination
import co.datapipelines.executor.OperationKind
import co.datapipelines.executor.OperationPhase
import co.datapipelines.executor.OperationSnapshot
import co.datapipelines.executor.OperationState
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The `node_progress` wire payload (rest-api §6.4.9): snake_case keys, wire enum values,
 * counts absent when unobserved, `committed` absent while running, correlation id stamped,
 * and the serialized JSON carrying identifiers and metrics only.
 */
class NodeProgressProjectionTest {
    private val correlationId = UUID.randomUUID()
    private val projection = SseEventProjection(correlationId)
    private val executionId = UUID.randomUUID()
    private val started = Instant.parse("2026-09-16T10:00:00.100Z")
    private val observed = Instant.parse("2026-09-16T10:00:05.401Z")

    private fun snapshot(
        state: OperationState,
        committed: Boolean? = null,
        rolledBack: Boolean? = null,
        rowsFetched: Long? = 12000,
        rowsWritten: Long? = 11000,
        child: UUID? = null,
    ) = OperationSnapshot(
        nodeId = "stage_trips",
        attempt = 1,
        sequence = 3,
        kind = OperationKind.STAGE,
        destination = OperationDestination.tempdb("trips"),
        state = state,
        startedAt = started,
        observedAt = observed,
        elapsedMs = 5301,
        timingsMs = mapOf(OperationPhase.EXECUTING to 410L, OperationPhase.FETCHING to 3400L, OperationPhase.WAITING_OUTPUT to 22L),
        rowsFetched = rowsFetched,
        rowsWritten = rowsWritten,
        batchesWritten = 11,
        committed = committed,
        rolledBack = rolledBack,
        childExecutionId = child,
    )

    @Test
    fun `a running sample projects every documented key and no committed label`() {
        val event = NodeProgress(executionId, snapshot(OperationState.WRITING))
        projection.eventName(event) shouldBe "node_progress"
        val payload = projection.payload(event)
        payload["execution_id"] shouldBe executionId
        payload["node_id"] shouldBe "stage_trips"
        payload["attempt"] shouldBe 1
        payload["sequence"] shouldBe 3
        payload["operation"] shouldBe "stage"
        payload["destination"] shouldBe mapOf("kind" to "tempdb", "table" to "trips")
        payload["state"] shouldBe "writing"
        payload["started_at"] shouldBe started
        payload["observed_at"] shouldBe observed
        payload["elapsed_ms"] shouldBe 5301L
        payload["rows_fetched"] shouldBe 12000L
        payload["rows_written"] shouldBe 11000L
        payload["batches_written"] shouldBe 11L
        @Suppress("UNCHECKED_CAST")
        (payload["timings_ms"] as Map<String, Long>) shouldContainExactly
            mapOf("executing" to 410L, "fetching" to 3400L, "waiting_output" to 22L)
        payload.containsKey("committed") shouldBe false
        payload.containsKey("rolled_back") shouldBe false
        payload.containsKey("child_execution_id") shouldBe false
        payload["correlation_id"] shouldBe correlationId
    }

    @Test
    fun `unobserved counts are absent, not minus one`() {
        val payload = projection.payload(NodeProgress(executionId, snapshot(OperationState.EXECUTING, rowsFetched = null, rowsWritten = null)))
        payload.containsKey("rows_fetched") shouldBe false
        payload.containsKey("rows_written") shouldBe false
    }

    @Test
    fun `a terminal sample carries committed and rolled_back and the child id when known`() {
        val child = UUID.randomUUID()
        val payload =
            projection.payload(NodeProgress(executionId, snapshot(OperationState.FAILED, committed = false, rolledBack = true, child = child)))
        payload["state"] shouldBe "failed"
        payload["committed"] shouldBe false
        payload["rolled_back"] shouldBe true
        payload["child_execution_id"] shouldBe child
    }

    @Test
    fun `datasource destinations name the datasource and optional table only`() {
        val event =
            NodeProgress(
                executionId,
                snapshot(OperationState.CONNECTING).copy(kind = OperationKind.STATEMENT, destination = OperationDestination.datasource("pg")),
            )
        projection.payload(event)["destination"] shouldBe mapOf("kind" to "datasource", "datasource" to "pg")
        projection.payload(event)["operation"] shouldBe "statement"
    }

    @Test
    fun `the serialized payload carries identifiers and metrics only`() {
        val json = SseJson.mapper.writeValueAsString(projection.payload(NodeProgress(executionId, snapshot(OperationState.WRITING))))
        json shouldContain "\"observed_at\":\"2026-09-16T10:00:05.401Z\""
        json shouldNotContain "jdbc:"
        json shouldNotContain "SELECT"
        json shouldNotContain "password"
    }
}
