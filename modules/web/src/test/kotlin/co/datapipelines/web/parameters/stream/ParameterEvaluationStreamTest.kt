package co.datapipelines.web.parameters.stream

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.parameters.EvaluateResponse
import co.datapipelines.parameters.EvaluationOutcome
import co.datapipelines.parameters.OrgEcho
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterEvaluationEvent
import co.datapipelines.web.CapturingSseEmitter
import co.datapipelines.web.sse.SseJson
import co.datapipelines.web.sse.StreamVerdict
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One observed-evaluation stream — `ExecutionStreamAuthorityGuardTest`'s cases on this stream (a refused write ends the
 * stream and serves nothing; the heartbeat is judged like an event; an unchanged authority reads through to the
 * terminal frame) plus its own framing: monotonic ids from 1, the terminal frame ends the stream whether or not it was
 * delivered, and an ABORTED end writes nothing and still ends it.
 */
class ParameterEvaluationStreamTest {
    private val evaluationId = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val subscriber =
        AuthenticatedPrincipal(
            userId,
            "v@acme.test",
            "Viewer",
            AuthMethod.OIDC,
            workspace = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.VIEWER),
        )
    private val response =
        EvaluateResponse(id = setId, name = "acme/sales/region_filters", version = 4, org = OrgEcho(null, null), parameters = emptyList())

    private fun authority(vararg verdicts: Boolean): ParameterEvaluationStreamAuthority {
        val judge = mockk<ParameterEvaluationStreamAuthority>()
        var call = 0
        every { judge.verdict(any()) } answers { if (verdicts[call++ % verdicts.size]) StreamVerdict.ALLOWED else StreamVerdict.REVOKED }
        return judge
    }

    private fun stream(
        emitter: CapturingSseEmitter,
        authority: ParameterEvaluationStreamAuthority,
    ) = ParameterEvaluationStream(evaluationId, setId, 4, userId, emitter, SseJson.mapper, subscriber, authority)

    private val started = ParameterEvaluationEvent.Started(listOf("country"), Instant.parse("2026-10-02T00:00:30Z"))

    @Test
    fun `a refused write ends the stream with a revoked comment and serves nothing`() {
        val emitter = CapturingSseEmitter()
        val stream = stream(emitter, authority(true, false))

        stream.emit(started) shouldBe true
        stream.emit(ParameterEvaluationEvent.ParameterWaiting("state", listOf("country"))) shouldBe false

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        stream.isRevoked shouldBe true
        stream.isConnected shouldBe false
        emitter.eventNames() shouldBe listOf("evaluation_started")
        emitter.frames().any { it.contains("revoked") } shouldBe true
    }

    @Test
    fun `the heartbeat is judged like an event - a quiet stream cannot outlive a revocation`() {
        val emitter = CapturingSseEmitter()
        val stream = stream(emitter, authority(false))

        stream.heartbeat() shouldBe false

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        stream.isRevoked shouldBe true
        emitter.frames().any { it.contains("revoked") } shouldBe true
        emitter.frames().any { it.contains("heartbeat") } shouldBe false
        emitter.eventNames() shouldBe emptyList()
    }

    @Test
    fun `an unchanged authority reads through to the terminal frame - and every write asked it`() {
        val emitter = CapturingSseEmitter()
        val judge = authority(true)
        val stream = stream(emitter, judge)

        stream.emit(started) shouldBe true
        stream.heartbeat() shouldBe true
        stream.emit(ParameterEvaluationEvent.ParameterResolved("country", "default", reset = false, rows = null)) shouldBe true
        stream.emit(ParameterEvaluationEvent.Ended(EvaluationOutcome.COMPLETED, null, response)) shouldBe true

        emitter.eventNames() shouldBe listOf("evaluation_started", "parameter_resolved", "evaluation_completed")
        emitter.eventIds() shouldBe listOf("1", "2", "3")
        stream.isTerminal shouldBe true
        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        verify(exactly = 4) { judge.verdict(any()) }
    }

    @Test
    fun `the terminal frame ends the stream even when it could not be delivered`() {
        val emitter = CapturingSseEmitter()
        val stream = stream(emitter, authority(true))
        stream.emit(started) shouldBe true
        emitter.failNextSendWith = IOException("client gone")

        stream.emit(ParameterEvaluationEvent.Ended(EvaluationOutcome.TIMEOUT, ParameterErrorCodes.EVALUATE_TIMEOUT, null)) shouldBe false

        stream.isTerminal shouldBe true
        stream.isConnected shouldBe false
        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `an ABORTED end writes no frame and still ends the stream`() {
        val emitter = CapturingSseEmitter()
        val judge = authority(true)
        val stream = stream(emitter, judge)
        stream.emit(started) shouldBe true

        stream.emit(ParameterEvaluationEvent.Ended(EvaluationOutcome.ABORTED, null, null)) shouldBe false

        emitter.eventNames() shouldBe listOf("evaluation_started")
        stream.isTerminal shouldBe true
        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        verify(exactly = 1) { judge.verdict(any()) }
    }

    @Test
    fun `a dropped client is detected on the failed write and the stream stops writing`() {
        val emitter = CapturingSseEmitter()
        val stream = stream(emitter, authority(true))
        emitter.failNextSendWith = IOException("broken pipe")

        stream.emit(started) shouldBe false
        emitter.failNextSendWith = null
        stream.emit(ParameterEvaluationEvent.ParameterAdmitted("state")) shouldBe false

        stream.isConnected shouldBe false
        stream.isRevoked shouldBe false
        emitter.eventNames() shouldBe emptyList()
    }
}
