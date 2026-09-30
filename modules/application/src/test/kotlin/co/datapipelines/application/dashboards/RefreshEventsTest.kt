package co.datapipelines.application.dashboards

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The refresh stream's frames (the implementation spec's §8.3) are a wire contract: the `event:` name and the exact
 * `data:` object a client parses. Each frame's payload is pinned here whole, so a renamed key, a dropped field or a
 * value that stops being a plain string fails in this module rather than in a client. The rules the KDoc of
 * [RefreshEvent] states are pinned too: a failure names a CODE, optional fields are absent rather than null, and a
 * visualization's data carries its resolved bindings and nothing else.
 */
class RefreshEventsTest {
    @Test
    fun `refresh_started names the targets, the sources with their sharing, and the deadline`() {
        val event = RefreshEvent.Started(REFRESH, listOf("v_revenue", "v_count"), listOf(SourceRef("revenue", shared = true)), DEADLINE)

        event.eventName shouldBe "refresh_started"
        event.payload() shouldBe
            mapOf(
                "refresh_id" to REFRESH.toString(),
                "targets" to listOf("v_revenue", "v_count"),
                "sources" to listOf(mapOf("name" to "revenue", "shared" to true)),
                "deadline_at" to DEADLINE,
            )
    }

    @Test
    fun `source_started and source_completed carry the execution id as a string, and the completion its counts`() {
        val started = RefreshEvent.SourceStarted(REFRESH, "revenue", EXECUTION)
        val completed = RefreshEvent.SourceCompleted(REFRESH, "revenue", EXECUTION, rows = 12, bytes = 4096L)

        started.eventName shouldBe "source_started"
        started.payload() shouldBe mapOf("refresh_id" to REFRESH.toString(), "source" to "revenue", "execution_id" to EXECUTION.toString())
        completed.eventName shouldBe "source_completed"
        completed.payload() shouldBe
            mapOf(
                "refresh_id" to REFRESH.toString(),
                "source" to "revenue",
                "execution_id" to EXECUTION.toString(),
                "rows" to 12,
                "bytes" to 4096L,
            )
    }

    @Test
    fun `source_failed names a code and its message, and a source that never started has no execution id`() {
        val failed = RefreshEvent.SourceFailed(REFRESH, "revenue", executionId = null, code = "result_too_large", message = "over the cap")

        failed.eventName shouldBe "source_failed"
        failed.payload() shouldBe
            mapOf(
                "refresh_id" to REFRESH.toString(),
                "source" to "revenue",
                "execution_id" to null,
                "error" to mapOf("code" to "result_too_large", "message" to "over the cap"),
            )
    }

    @Test
    fun `visualization_status omits stage and reason when there is none, and falls back to the code for a missing message`() {
        val inProgress = RefreshEvent.VisualizationStatus(REFRESH, "v_revenue", state = "in-progress")
        val failed =
            RefreshEvent.VisualizationStatus(REFRESH, "v_revenue", state = "error", stage = "budget", reasonCode = "result_too_large")

        inProgress.eventName shouldBe "visualization_status"
        inProgress.payload() shouldBe
            mapOf("refresh_id" to REFRESH.toString(), "name" to "v_revenue", "type" to "visualization", "state" to "in-progress")
        failed.payload() shouldBe
            mapOf(
                "refresh_id" to REFRESH.toString(),
                "name" to "v_revenue",
                "type" to "visualization",
                "state" to "error",
                "stage" to "budget",
                "reason" to mapOf("code" to "result_too_large", "message" to "result_too_large"),
            )
    }

    @Test
    fun `visualization_data carries the resolved bindings and its counts, never the configuration`() {
        val bindings = mapOf("x" to listOf<Any?>("2026-09-01", "2026-09-02"), "y" to listOf<Any?>(10, null))
        val data = RefreshEvent.VisualizationData(REFRESH, "v_revenue", bindings, rows = 2, bytes = 64L)

        data.eventName shouldBe "visualization_data"
        data.payload() shouldBe
            mapOf(
                "refresh_id" to REFRESH.toString(),
                "name" to "v_revenue",
                "type" to "visualization",
                "bindings" to bindings,
                "rows" to 2,
                "bytes" to 64L,
            )
    }

    @Test
    fun `refresh_completed is the last frame and carries the status and each target's outcome`() {
        val targets = mapOf("v_revenue" to mapOf<String, Any?>("outcome" to "rendered"))
        val completed = RefreshEvent.Completed(REFRESH, "COMPLETED", targets)

        completed.eventName shouldBe "refresh_completed"
        completed.payload() shouldBe mapOf("refresh_id" to REFRESH.toString(), "status" to "COMPLETED", "targets" to targets)
    }

    private companion object {
        val REFRESH: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0de")
        val EXECUTION: UUID = UUID.fromString("00000000-0000-0000-0000-0000000e0001")
        const val DEADLINE = "2026-09-29T10:05:00Z"
    }
}
