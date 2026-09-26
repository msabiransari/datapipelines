package co.datapipelines.web.sse

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.web.CapturingSseEmitter
import co.datapipelines.web.config.SseProperties
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * #230 (P4) — the LIVE stream's guard: a refusal ends the stream at that write, serves nothing
 * after it, and is never misread by the registry as a client disconnect (the execution keeps
 * its authority to completion — the run is not cancelled because its READER was).
 */
class ExecutionStreamAuthorityGuardTest {
    private val mapper = JsonMapper.builder().build()
    private val executionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private val subscriber =
        AuthenticatedPrincipal(userId, "m@acme.test", "Member", AuthMethod.OIDC, workspaceName = "acme")

    private fun authority(vararg verdicts: Boolean): ExecutionStreamAuthority {
        val judge = mockk<ExecutionStreamAuthority>()
        var call = 0
        every { judge.mayRead(any(), any()) } answers { verdicts[call++ % verdicts.size] }
        return judge
    }

    @Test
    fun `a refused write ends the stream with a revoked comment and serves nothing`() {
        val emitter = CapturingSseEmitter()
        val stream =
            ExecutionStream(executionId, userId, emitter, mapper, subscriber = subscriber, authority = authority(true, false))

        stream.send("execution_started", 1, mapOf("execution_id" to executionId.toString())) shouldBe true
        // The refusal hits at the next write — the event is NOT served, the stream is.
        stream.send("node_started", 2, emptyMap()) shouldBe false
        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        stream.isRevoked shouldBe true
        stream.isConnected shouldBe false
        emitter.eventNames() shouldBe listOf("execution_started")
        emitter.frames().any { it.contains("revoked") } shouldBe true
    }

    @Test
    fun `the heartbeat is judged like an event - a quiet stream cannot outlive a revocation`() {
        val emitter = CapturingSseEmitter()
        val stream =
            ExecutionStream(executionId, userId, emitter, mapper, subscriber = subscriber, authority = authority(false))

        stream.heartbeat() shouldBe false
        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        stream.isRevoked shouldBe true
        // The keepalive itself was never served: the only comment in the frames is the cut, and
        // no event name was ever written.
        emitter.frames().any { it.contains("revoked") } shouldBe true
        emitter.eventNames() shouldBe emptyList()
    }

    @Test
    fun `an unchanged authority reads through to the terminal event`() {
        val emitter = CapturingSseEmitter()
        val stream =
            ExecutionStream(executionId, userId, emitter, mapper, subscriber = subscriber, authority = authority(true))

        stream.send("execution_started", 1, mapOf("execution_id" to executionId.toString())) shouldBe true
        stream.heartbeat() shouldBe true
        stream.send("pipeline_completed", 2, emptyMap()) shouldBe true
        stream.markTerminal("pipeline_completed")

        stream.isRevoked shouldBe false
        emitter.eventNames() shouldBe listOf("execution_started", "pipeline_completed")
    }

    @Test
    fun `a stream without a subscriber or guard behaves exactly as before #230`() {
        val emitter = CapturingSseEmitter()
        val stream = ExecutionStream(executionId, userId, emitter, mapper)

        stream.send("execution_started", 1, emptyMap()) shouldBe true
        stream.isRevoked shouldBe false
        emitter.eventNames() shouldBe listOf("execution_started")
    }

    @Test
    fun `the registry never reads a revoked stream as a disconnect - the run is not cancelled for its reader`() {
        val cancellation = mockk<ExecutionCancellationService>()
        val clock = AtomicLong(0)
        val manual = CapturingScheduler()
        val registry =
            ExecutionStreamRegistry(
                properties = SseProperties(heartbeatIntervalSeconds = 15, disconnectGraceSeconds = 0),
                cancellationService = cancellation,
                mapper = mapper,
                scheduler = manual,
                nowMillis = clock::get,
            )
        val emitter = CapturingSseEmitter()
        val stream =
            ExecutionStream(executionId, userId, emitter, mapper, clock::get, subscriber = subscriber, authority = authority(false))
        registry.register(stream)

        // The cut happens on the first write the stream attempts; the ticks that follow — with
        // a grace of ZERO, so one misread would cancel immediately — must keep the revoked
        // stream out of §6.8's disconnect path entirely.
        stream.heartbeat() shouldBe false
        manual.runNow()
        manual.runNow()

        verify(exactly = 0) { cancellation.cancel(any(), any<AbortReason>()) }
        stream.isRevoked shouldBe true
    }

    /** A scheduler that captures the periodic task instead of running it on a real thread. */
    private class CapturingScheduler : ScheduledExecutorService by mockk(relaxed = true) {
        private lateinit var task: Runnable

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            task = command
            return mockk()
        }

        fun runNow() = task.run()
    }
}
