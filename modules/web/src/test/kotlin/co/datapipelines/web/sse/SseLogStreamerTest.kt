package co.datapipelines.web.sse

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
 * The log-served stream: §10.3 replay and the idempotent-retry follow, against a fake log and a
 * real scheduler. Event ids and names must survive verbatim — replay is byte-faithful by
 * construction, and the follow closes only after the terminal sequence.
 */
class SseLogStreamerTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val executionId = UUID.randomUUID()

    private fun event(
        id: Int,
        name: String,
    ) = LoggedSseEvent(id, name, mapOf("execution_id" to executionId.toString(), "n" to id))

    private fun streamer(
        log: SseEventLog,
        emitter: CapturingSseEmitter,
    ) = SseLogStreamer(log, JsonMapper.builder().build(), scheduler) { emitter }

    @Test
    fun `replay emits the stored events in order and completes`() {
        val stored = listOf(event(1, "execution_started"), event(2, "pipeline_completed"), event(3, "data_ready"))
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns stored
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).replay(executionId)

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "pipeline_completed", "data_ready")
        emitter.eventIds() shouldBe listOf("1", "2", "3")
    }

    @Test
    fun `follow serves new events as they land and closes after the terminal sequence`() {
        // A live execution: each read reveals one more scripted event, ending in pipeline_failed.
        val script = listOf(event(1, "execution_started"), event(2, "node_started"), event(3, "pipeline_failed"))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { script.take(reads.incrementAndGet()) }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        emitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "pipeline_failed")
    }

    @Test
    fun `follow gives up on a log that never appears`() {
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns null
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        // GIVE_UP_AFTER_POLLS (60) at the 250ms follow cadence ≈ 15s, plus slack.
        emitter.completed.await(30, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe emptyList()
    }
}
