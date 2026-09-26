package co.datapipelines.web.sse

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.web.CapturingSseEmitter
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * #230 (P4) — the LOG-served streams' guard: both the §10.3 replay (per chunk) and the
 * idempotent-retry follow (per served event) re-ask the subscriber's authority before every
 * write; a refusal ends the stream at that write, carrying `: revoked` and nothing after. The
 * control case reads through to the terminal sequence exactly as before.
 *
 * The authority is a counting double: the N-th re-judgement refuses — the deterministic stand-in
 * for "the revocation took effect between two writes", with the scheduler real and every wait
 * on an event (the [SseLogStreamerTest] shape).
 */
class SseLogStreamerAuthorityTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val executionId = UUID.randomUUID()

    private val subscriber =
        AuthenticatedPrincipal(UUID.randomUUID(), "m@acme.test", "Member", AuthMethod.OIDC, workspaceName = "acme")

    /** Refuses from the [refuseFrom]-th re-judgement on — writes before that pass. */
    private fun authorityRefusingFrom(refuseFrom: Int): ExecutionStreamAuthority {
        val judge = mockk<ExecutionStreamAuthority>()
        val calls = AtomicInteger(0)
        every { judge.mayRead(any(), any()) } answers { calls.incrementAndGet() < refuseFrom }
        return judge
    }

    private fun authorityAlwaysAllowed(): ExecutionStreamAuthority {
        val judge = mockk<ExecutionStreamAuthority>()
        every { judge.mayRead(any(), any()) } returns true
        return judge
    }

    private fun event(
        id: Int,
        name: String,
    ) = LoggedSseEvent(id, name, mapOf("execution_id" to executionId.toString(), "n" to id))

    private fun streamer(
        log: SseEventLog,
        emitter: CapturingSseEmitter,
        authority: ExecutionStreamAuthority,
    ) = SseLogStreamer(log, JsonMapper.builder().build(), scheduler, authority) { emitter }

    @Test
    fun `replay cuts at the first chunk after the authority is refused`() {
        val stored =
            listOf(event(1, "execution_started"), event(2, "node_started"), event(3, "pipeline_completed"), event(4, "data_ready"))
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns stored
        val emitter = CapturingSseEmitter()

        streamer(log, emitter, authorityRefusingFrom(2)).replay(executionId, subscriber)

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        // Exactly one chunk served; nothing of the second — and never the terminal sequence.
        emitter.eventNames() shouldBe listOf("execution_started")
        emitter.frames().any { it.contains("revoked") } shouldBe true
    }

    @Test
    fun `an unchanged authority replays everything`() {
        val stored = listOf(event(1, "execution_started"), event(2, "pipeline_completed"), event(3, "data_ready"))
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns stored
        val emitter = CapturingSseEmitter()

        streamer(log, emitter, authorityAlwaysAllowed()).replay(executionId, subscriber)

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "pipeline_completed", "data_ready")
        emitter.frames().any { it.contains("revoked") } shouldBe false
    }

    @Test
    fun `follow cuts at the first event after the authority is refused`() {
        // A live execution: one event is in the log when the follow attaches; a second (the
        // terminal) persists later — after the revocation has taken effect.
        val script = listOf(event(1, "execution_started"), event(2, "pipeline_failed"))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { script.take(reads.incrementAndGet()) }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter, authorityRefusingFrom(2)).follow(executionId, subscriber)

        emitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started")
        emitter.frames().any { it.contains("revoked") } shouldBe true
    }

    @Test
    fun `an unchanged authority follows to the terminal sequence`() {
        val script = listOf(event(1, "execution_started"), event(2, "node_started"), event(3, "pipeline_failed"))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { script.take(reads.incrementAndGet()) }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter, authorityAlwaysAllowed()).follow(executionId, subscriber)

        emitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "pipeline_failed")
        emitter.frames().any { it.contains("revoked") } shouldBe false
    }
}
