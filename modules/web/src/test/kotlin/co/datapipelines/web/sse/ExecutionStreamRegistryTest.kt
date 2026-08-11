package co.datapipelines.web.sse

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
 * The registry's two timers (rest-api §6.6 heartbeat, §6.8 disconnect-grace) and the per-user
 * stream cap (§12.1), driven by a manual scheduler and a fake clock — no real time passes.
 */
class ExecutionStreamRegistryTest {
    private val cancellation = mockk<ExecutionCancellationService>()
    private val clock = AtomicLong(0)
    private val tick = CapturingScheduler()
    private val mapper = JsonMapper.builder().build()
    private val registry =
        ExecutionStreamRegistry(
            properties = SseProperties(heartbeatIntervalSeconds = 15, disconnectGraceSeconds = 30, maxStreamsPerUser = 2),
            cancellationService = cancellation,
            mapper = mapper,
            scheduler = tick,
            nowMillis = clock::get,
        )

    private fun openCapturing(
        executionId: UUID = UUID.randomUUID(),
        userId: UUID = UUID.randomUUID(),
    ): Pair<ExecutionStream, CapturingSseEmitter> {
        val emitter = CapturingSseEmitter()
        val stream = ExecutionStream(executionId, userId, emitter, mapper, clock::get)
        registry.register(stream)
        return stream to emitter
    }

    @Test
    fun `a busy stream gets no heartbeat while a quiet one does`() {
        val (stream, emitter) = openCapturing()

        fun heartbeats() = emitter.frames().count { it.contains("heartbeat") }

        tick.runNow() // t=0, stream created at t=0 and quiet → heartbeat
        heartbeats() shouldBe 1

        clock.set(10)
        stream.send("node_started", 1, mapOf("x" to 1)) // activity at t=10
        tick.runNow() // t=10: emitted since the last tick → NO heartbeat
        heartbeats() shouldBe 1

        clock.set(20)
        tick.runNow() // t=20: quiet since t=10 → heartbeat
        heartbeats() shouldBe 2
    }

    @Test
    fun `the cap counts open streams per user`() {
        val user = UUID.randomUUID()
        registry.atStreamLimit(user) shouldBe false
        val (first, _) = openCapturing(userId = user)
        openCapturing(userId = user)
        registry.atStreamLimit(user) shouldBe true
        registry.activeStreams shouldBe 2

        registry.close(first.executionId)
        registry.atStreamLimit(user) shouldBe false
        registry.activeStreamsFor(user) shouldBe 1
    }

    @Test
    fun `a connected stream gets the heartbeat comment on the tick`() {
        val (_, emitter) = openCapturing()

        tick.runNow()

        emitter.frames().any { it.contains("heartbeat") } shouldBe true
    }

    @Test
    fun `a failed heartbeat write marks the stream disconnected`() {
        val (stream, emitter) = openCapturing()
        emitter.failNextSendWith = java.io.IOException("client gone")

        tick.runNow()

        stream.isConnected shouldBe false
    }

    @Test
    fun `disconnect before a terminal event cancels after the grace elapses`() {
        every { cancellation.cancel(any(), any()) } returns true
        val (stream, _) = openCapturing()
        stream.markDisconnected()

        tick.runNow() // noticed; grace starts
        verify(exactly = 0) { cancellation.cancel(any(), any()) }

        clock.set(29_000)
        tick.runNow() // 29s < 30s grace
        verify(exactly = 0) { cancellation.cancel(any(), any()) }

        clock.set(30_000)
        tick.runNow() // grace elapsed
        verify(exactly = 1) { cancellation.cancel(stream.executionId, AbortReason.CLIENT_DISCONNECT) }
        registry.activeStreams shouldBe 0
    }

    @Test
    fun `a disconnect after the terminal event cancels nothing`() {
        val (stream, _) = openCapturing()
        stream.markTerminal("pipeline_completed")
        stream.markDisconnected()

        tick.runNow()

        verify(exactly = 0) { cancellation.cancel(any(), any()) }
        registry.activeStreams shouldBe 0
    }

    /** A scheduler that captures the periodic task instead of running it on a real thread. */
    private class CapturingScheduler : ScheduledExecutorService by stub() {
        private lateinit var task: Runnable

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            task = command
            return mockk<ScheduledFuture<*>>()
        }

        fun runNow() = task.run()

        companion object {
            fun stub(): ScheduledExecutorService = mockk(relaxed = true)
        }
    }
}
