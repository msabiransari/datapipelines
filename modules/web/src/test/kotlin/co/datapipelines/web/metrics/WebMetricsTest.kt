package co.datapipelines.web.metrics

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.web.CapturingSseEmitter
import co.datapipelines.web.sse.ExecutionStream
import co.datapipelines.web.sse.ExecutionStreamAuthority
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #263 — the duration timer's `close_reason` closed set at the cut boundary: an expired token
 * is its own value beside `revoked` (the same policy cut — the run keeps running), and never a
 * `client_disconnect`. Reads the REAL registry's recorded tag, so a deleted `isExpired` ask in
 * the stream guard turns this red (the mock's `hasExpired` answer would never be consulted and
 * the flag would stay false — the assertion is the recorded tag, not the mock).
 */
class WebMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = WebMetrics(registry)
    private val mapper = JsonMapper.builder().build()
    private val executionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val subscriber = AuthenticatedPrincipal(userId, "m@acme.test", "Member", AuthMethod.OIDC, workspaceName = "acme")

    @Test
    fun `a cut whose subscriber's token has expired records close_reason expired`() {
        val judge = mockk<ExecutionStreamAuthority>()
        every { judge.mayRead(any(), any()) } returns false
        every { judge.hasExpired(any()) } returns true
        val stream = ExecutionStream(executionId, userId, CapturingSseEmitter(), mapper, subscriber = subscriber, authority = judge)

        stream.heartbeat() shouldBe false
        metrics.streamClosed(stream)

        closeReason() shouldBe "expired"
    }

    @Test
    fun `a cut whose subscriber's standing was revoked records close_reason revoked`() {
        val judge = mockk<ExecutionStreamAuthority>()
        every { judge.mayRead(any(), any()) } returns false
        every { judge.hasExpired(any()) } returns false
        val stream = ExecutionStream(executionId, userId, CapturingSseEmitter(), mapper, subscriber = subscriber, authority = judge)

        stream.heartbeat() shouldBe false
        metrics.streamClosed(stream)

        closeReason() shouldBe "revoked"
    }

    @Test
    fun `a stream that reached its terminal event still records completed`() {
        val stream = ExecutionStream(executionId, userId, CapturingSseEmitter(), mapper)

        stream.send("pipeline_completed", 1, emptyMap())
        stream.markTerminal("pipeline_completed")
        metrics.streamClosed(stream)

        closeReason() shouldBe "completed"
    }

    /** The `close_reason` tag of the one `datapipelines.sse.stream.duration` observation. */
    private fun closeReason(): String? =
        registry
            .find(WebMetrics.SSE_STREAM_DURATION)
            .timer()
            ?.id
            ?.tags
            ?.firstOrNull { it.key == "close_reason" }
            ?.value
}
