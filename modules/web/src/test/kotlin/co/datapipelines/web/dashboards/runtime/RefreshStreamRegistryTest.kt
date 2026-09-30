package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.RefreshEvent
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.RefreshAbortFlags
import co.datapipelines.web.CapturingSseEmitter
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseJson
import co.datapipelines.web.sse.StreamVerdict
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * [RefreshStreamRegistry], [RefreshStream] and [RefreshAbortSignal] — the disconnect grace (rest-api §6.8) for a REFRESH
 * (#10 L2), on a controllable clock and a scheduler that never fires: the test calls `tick()` itself.
 *
 * The regression at the heart of it, found by the runtime E2E: the container's completion callback, fired when a client
 * vanishes, used to REMOVE the stream from the registry — and a stream no longer in the registry is invisible to the very
 * timer that exists to abort its refresh. A refresh whose client vanished then ran on, holding a workspace place, to its
 * deadline. Falsified: restoring `onCompletion { close(refreshId) }` turns the grace case red (the refresh is never
 * aborted, the abort signal stays false).
 */
class RefreshStreamRegistryTest {
    private val clock = AtomicLong(1_000_000L)
    private val flags = RecordingFlags()
    private val abort = RefreshAbortSignal(flags, remotePollMillis = 15_000L, nowMillis = clock::get)
    private val properties = SseProperties(heartbeatIntervalSeconds = 1, disconnectGraceSeconds = 2, maxStreamsPerUser = 3)
    private val executionStreams =
        ExecutionStreamRegistry(
            properties,
            mockk<ExecutionCancellationService>(relaxed = true),
            SseJson.mapper,
            mockk<ScheduledExecutorService>(relaxed = true),
        )
    private val registry =
        RefreshStreamRegistry(
            properties,
            executionStreams,
            abort,
            SseJson.mapper,
            mockk<ScheduledExecutorService>(relaxed = true),
            clock::get,
        )
    private val authority = mockk<RefreshStreamAuthority>()
    private val user = UUID.randomUUID()

    private fun principal() =
        AuthenticatedPrincipal(
            userId = user,
            email = "v@e2e.test",
            displayName = "V",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.VIEWER),
        )

    private fun open(refreshId: UUID = UUID.randomUUID()): RefreshStream {
        every { authority.access(any()) } returns RefreshStreamAccess(StreamVerdict.ALLOWED, executionRead = false)
        return registry.open(refreshId, principal(), authority)
    }

    private fun completed(stream: RefreshStream) = stream.emit(RefreshEvent.Completed(stream.refreshId, "COMPLETED", emptyMap()))

    @Test
    fun `a client that vanished before refresh_completed is aborted once the grace has elapsed - and the stream is dropped`() {
        val stream = open()
        abort.register(stream.refreshId)

        stream.markDisconnected() // the container's completion / error callback: the client is gone
        registry.tick() // noticed: the clock starts
        abort.requested(stream.refreshId).shouldBeFalse()
        clock.addAndGet(1_500)
        registry.tick() // inside the 2 s grace
        abort.requested(stream.refreshId).shouldBeFalse()
        registry.find(stream.refreshId).shouldNotBeNull()

        clock.addAndGet(700) // 2.2 s since noticed
        registry.tick()

        abort.requested(stream.refreshId).shouldBeTrue()
        registry.find(stream.refreshId).shouldBeNull()
    }

    @Test
    fun `a client that comes back within the grace is not aborted - the stream had never gone if it still writes`() {
        val stream = open()
        abort.register(stream.refreshId)

        registry.tick()
        clock.addAndGet(5_000)
        registry.tick()

        stream.isConnected.shouldBeTrue()
        abort.requested(stream.refreshId).shouldBeFalse()
    }

    @Test
    fun `a disconnect after refresh_completed costs nothing - the stream is dropped and nothing is aborted`() {
        val stream = open()
        abort.register(stream.refreshId)
        completed(stream)
        stream.isTerminal.shouldBeTrue()
        stream.markDisconnected()

        registry.tick()

        registry.find(stream.refreshId).shouldBeNull()
        abort.requested(stream.refreshId).shouldBeFalse()
    }

    @Test
    fun `a stream cut because its reader lost authority is not a disconnect - the refresh is NOT aborted and the stream goes at its end`() {
        val stream = open()
        abort.register(stream.refreshId)
        every { authority.access(any()) } returns RefreshStreamAccess(StreamVerdict.REVOKED, executionRead = false)

        stream.emit(RefreshEvent.SourceStarted(stream.refreshId, "s", UUID.randomUUID())).shouldBeFalse() // cut at this write
        stream.isRevoked.shouldBeTrue()
        clock.addAndGet(60_000)
        registry.tick()

        abort.requested(stream.refreshId).shouldBeFalse() // P4's first half: a revocation cuts the reading, never the running
        registry.find(stream.refreshId).shouldNotBeNull()
        completed(stream) // the refresh ends; its last frame is attempted and refused, but the stream is terminal
        registry.tick()
        registry.find(stream.refreshId).shouldBeNull()
    }

    @Test
    fun `every write re-asks the reader's authority - the event and the heartbeat alike`() {
        val stream = open()

        stream.emit(RefreshEvent.SourceStarted(stream.refreshId, "s", UUID.randomUUID())).shouldBeTrue()
        stream.emit(RefreshEvent.SourceStarted(stream.refreshId, "t", UUID.randomUUID())).shouldBeTrue()
        stream.heartbeat().shouldBeTrue()

        verify(exactly = 3) { authority.access(any()) }
    }

    @Test
    fun `source frame serialization removes every execution link for a current non-reader including null failure ids`() {
        val emitter = CapturingSseEmitter()
        val id = UUID.randomUUID()
        val stream = RefreshStream(id, user, emitter, SseJson.mapper, principal(), authority)
        val execution = UUID.randomUUID()
        every { authority.access(any()) } returns RefreshStreamAccess(StreamVerdict.ALLOWED, executionRead = false)
        val events =
            listOf(
                RefreshEvent.SourceStarted(id, "a", execution),
                RefreshEvent.SourceCompleted(id, "a", execution, 1, 16),
                RefreshEvent.SourceFailed(id, "a", null, "source.failed", "safe"),
            )

        events.forEach { stream.emit(it).shouldBeTrue() }

        emitter.eventNames() shouldBe listOf("source_started", "source_completed", "source_failed")
        events.forEach { event ->
            val payload = SseJson.mapper.readTree(SseJson.mapper.writeValueAsString(projectRefreshPayload(event, executionRead = false)))
            payload.has("execution_id") shouldBe false
        }
        (events[0] as RefreshEvent.SourceStarted).executionId shouldBe execution
        (events[1] as RefreshEvent.SourceCompleted).executionId shouldBe execution
        verify(exactly = 3) { authority.access(any()) }
    }

    @Test
    fun `a current execution reader keeps the exact id in every source frame`() {
        val emitter = CapturingSseEmitter()
        val id = UUID.randomUUID()
        val stream = RefreshStream(id, user, emitter, SseJson.mapper, principal(), authority)
        val execution = UUID.randomUUID()
        every { authority.access(any()) } returns RefreshStreamAccess(StreamVerdict.ALLOWED, executionRead = true)
        val events =
            listOf(
                RefreshEvent.SourceStarted(id, "a", execution),
                RefreshEvent.SourceCompleted(id, "a", execution, 1, 16),
                RefreshEvent.SourceFailed(id, "a", execution, "source.failed", "safe"),
            )

        events.forEach { stream.emit(it).shouldBeTrue() }

        events.forEach { event ->
            val payload = SseJson.mapper.readTree(SseJson.mapper.writeValueAsString(projectRefreshPayload(event, executionRead = true)))
            payload.path("execution_id").asText() shouldBe execution.toString()
        }
        verify(exactly = 3) { authority.access(any()) }
    }

    @Test
    fun `the per-user stream cap counts execution AND refresh streams together`() {
        registry.atStreamLimit(user).shouldBeFalse()
        open()
        open()
        registry.atStreamLimit(user).shouldBeFalse() // 2 of 3
        executionStreams.open(UUID.randomUUID(), user)
        registry.atStreamLimit(user).shouldBeTrue() // 2 refresh + 1 execution = 3
        registry.activeStreamsFor(user) shouldBe 2
    }

    @Test
    fun `a quiet stream is kept alive - the tick writes a heartbeat only when nothing was written since the last one`() {
        val stream = open()
        registry.tick() // first tick: everything is older than "the last tick" (0), so it beats
        val afterFirst = stream.lastActivityAtMillis.get()

        clock.addAndGet(1_000)
        stream.emit(RefreshEvent.SourceStarted(stream.refreshId, "s", UUID.randomUUID())) // activity since the last tick
        val afterEvent = stream.lastActivityAtMillis.get()
        registry.tick()

        (afterEvent > afterFirst).shouldBeTrue()
        stream.lastActivityAtMillis.get() shouldBe afterEvent // busy: no heartbeat this tick
    }

    @Test
    fun `the abort signal - a local trigger is immediate, a remote flag is read at most once per poll interval`() {
        val id = UUID.randomUUID()
        abort.register(id)
        abort.owns(id).shouldBeTrue()

        abort.requested(id).shouldBeFalse() // first read goes to Redis
        abort.requested(id).shouldBeFalse() // inside the interval: no second read
        flags.reads shouldBe 1
        clock.addAndGet(15_001)
        flags.set = true
        abort.requested(id).shouldBeTrue()
        flags.reads shouldBe 2

        abort.forget(id)
        abort.owns(id).shouldBeFalse()
        flags.cleared shouldBe listOf(id)

        val local = UUID.randomUUID()
        abort.triggerLocal(local)
        abort.requested(local).shouldBeTrue() // no Redis read needed
    }

    private class RecordingFlags : RefreshAbortFlags {
        var set = false
        var reads = 0
        val cleared = mutableListOf<UUID>()

        override fun request(
            refreshId: UUID,
            ttlSeconds: Long,
        ) {
            set = true
        }

        override fun isRequested(refreshId: UUID): Boolean {
            reads++
            return set
        }

        override fun clear(refreshId: UUID) {
            cleared += refreshId
            set = false
        }
    }
}
