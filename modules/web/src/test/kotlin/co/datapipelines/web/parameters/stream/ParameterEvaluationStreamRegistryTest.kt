package co.datapipelines.web.parameters.stream

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.parameters.EvaluateResponse
import co.datapipelines.parameters.EvaluationOutcome
import co.datapipelines.parameters.OrgEcho
import co.datapipelines.parameters.ParameterEvaluationEvent
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.sse.SseJson
import co.datapipelines.web.sse.StreamVerdict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [ParameterEvaluationStreamRegistry] — `RefreshStreamRegistryTest`'s twin on a controllable clock and a scheduler that
 * never fires (the test calls `tick()` itself): the owner's §11.7 ruling — a client gone past the disconnect grace
 * ABORTS its evaluation (the attached job is cancelled, asserted on a [CompletableDeferred] standing in for it) — and
 * its three siblings, the one per-user cap over all three stream kinds (D7), and the reuse refusal (D5).
 */
class ParameterEvaluationStreamRegistryTest {
    private val clock = AtomicLong(1_000_000L)
    private val properties = SseProperties(heartbeatIntervalSeconds = 1, disconnectGraceSeconds = 2, maxStreamsPerUser = 3)
    private val others = AtomicInteger(0)
    private val registry =
        ParameterEvaluationStreamRegistry(
            properties,
            otherStreams = { others.get() },
            mapper = SseJson.mapper,
            scheduler = mockk<ScheduledExecutorService>(relaxed = true),
            nowMillis = clock::get,
        )
    private val authority = mockk<ParameterEvaluationStreamAuthority>()
    private val user = UUID.randomUUID()
    private val setId = UUID.randomUUID()

    private fun principal() =
        AuthenticatedPrincipal(
            userId = user,
            email = "v@e2e.test",
            displayName = "V",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.VIEWER),
        )

    /** Opens a stream with a stand-in job attached — the deferred is what the grace must cancel. */
    private fun open(evaluationId: UUID = UUID.randomUUID()): Pair<ParameterEvaluationStream, CompletableDeferred<Unit>> {
        every { authority.verdict(any()) } returns StreamVerdict.ALLOWED
        val stream = registry.open(evaluationId, setId, 4, principal(), authority)
        val job = CompletableDeferred<Unit>()
        registry.attach(evaluationId, job)
        return stream to job
    }

    private fun completed(stream: ParameterEvaluationStream) =
        stream.emit(
            ParameterEvaluationEvent.Ended(
                EvaluationOutcome.COMPLETED,
                null,
                EvaluateResponse(id = setId, name = "a/b", version = 4, org = OrgEcho(null, null), parameters = emptyList()),
            ),
        )

    @Test
    fun `a client that vanished before the end is aborted once the grace has elapsed - the job is cancelled and the stream dropped`() {
        val (stream, job) = open()

        stream.markDisconnected() // the container's completion / error callback: the client is gone
        registry.tick() // noticed: the clock starts
        job.isCancelled.shouldBeFalse()
        clock.addAndGet(1_500)
        registry.tick() // inside the 2 s grace
        job.isCancelled.shouldBeFalse()
        registry.find(stream.evaluationId).shouldNotBeNull()

        clock.addAndGet(700) // 2.2 s since noticed
        registry.tick()

        job.isCancelled.shouldBeTrue()
        registry.find(stream.evaluationId).shouldBeNull()
    }

    @Test
    fun `a client that is still reading is never aborted - a stream that writes had never gone`() {
        val (stream, job) = open()

        registry.tick()
        clock.addAndGet(5_000)
        registry.tick()

        stream.isConnected.shouldBeTrue()
        job.isCancelled.shouldBeFalse()
    }

    @Test
    fun `a disconnect after the end costs nothing - the stream is dropped and nothing is aborted`() {
        val (stream, job) = open()
        completed(stream)
        stream.isTerminal.shouldBeTrue()
        stream.markDisconnected()

        registry.tick()

        registry.find(stream.evaluationId).shouldBeNull()
        job.isCancelled.shouldBeFalse()
    }

    @Test
    fun `a stream cut because its reader lost authority is not a disconnect - the evaluation runs on and the stream goes at its end`() {
        val (stream, job) = open()
        every { authority.verdict(any()) } returns StreamVerdict.REVOKED

        stream.emit(ParameterEvaluationEvent.ParameterAdmitted("state")).shouldBeFalse() // cut at this write
        stream.isRevoked.shouldBeTrue()
        clock.addAndGet(60_000)
        registry.tick()

        job.isCancelled.shouldBeFalse() // a revocation cuts the reading, never the running
        registry.find(stream.evaluationId).shouldNotBeNull()
        completed(stream) // the evaluation ends; its last frame is refused, but the stream is terminal
        registry.tick()
        registry.find(stream.evaluationId).shouldBeNull()
    }

    @Test
    fun `every write re-asks the reader's authority - the event and the heartbeat alike`() {
        val (stream, _) = open()

        stream.emit(ParameterEvaluationEvent.Started(listOf("a"), Instant.EPOCH)).shouldBeTrue()
        stream.emit(ParameterEvaluationEvent.ParameterAdmitted("a")).shouldBeTrue()
        stream.heartbeat().shouldBeTrue()

        verify(exactly = 3) { authority.verdict(any()) }
    }

    @Test
    fun `the per-user stream cap counts execution, refresh AND evaluation streams together`() {
        registry.atStreamLimit(user).shouldBeFalse()
        open()
        registry.atStreamLimit(user).shouldBeFalse() // 1 of 3
        others.set(1)
        registry.atStreamLimit(user).shouldBeFalse() // 1 + 1 of 3
        others.set(2)
        registry.atStreamLimit(user).shouldBeTrue() // 1 evaluation + 2 others = 3
        registry.activeStreamsFor(user) shouldBe 1
    }

    @Test
    fun `a quiet stream is kept alive - the tick writes a heartbeat only when nothing was written since the last one`() {
        val (stream, _) = open()
        registry.tick()
        val afterFirst = stream.lastActivityAtMillis.get()

        clock.addAndGet(1_000)
        stream.emit(ParameterEvaluationEvent.ParameterAdmitted("a"))
        val afterEvent = stream.lastActivityAtMillis.get()
        registry.tick()

        (afterEvent > afterFirst).shouldBeTrue()
        stream.lastActivityAtMillis.get() shouldBe afterEvent
    }

    @Test
    fun `an evaluation id already open here is refused reused - atomically at open`() {
        val id = UUID.randomUUID()
        open(id)

        registry.isOpen(id).shouldBeTrue()
        val error = shouldThrow<ApiException> { registry.open(id, setId, 4, principal(), authority) }

        error.code shouldBe "parameter.validation.body_invalid"
        error.details shouldBe mapOf("path" to "evaluation_id", "reason" to "reused")
        registry.activeStreamsFor(user) shouldBe 1
    }
}
