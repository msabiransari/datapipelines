package co.datapipelines.web.sse

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.CapturingSseEmitter
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.dao.QueryTimeoutException
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
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).replay(executionId, stored)

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "pipeline_completed", "data_ready")
        emitter.eventIds() shouldBe listOf("1", "2", "3")
    }

    /** 149: replay is byte-faithful for `node_progress` too — original ids, original payloads, original order. */
    @Test
    fun `replay serves node_progress samples with their original ids between the node events`() {
        val progress =
            LoggedSseEvent(
                3,
                "node_progress",
                mapOf(
                    "node_id" to "n",
                    "state" to "writing",
                    "observed_at" to "2026-09-16T10:00:00.400Z",
                ),
            )
        val stored =
            listOf(
                event(1, "execution_started"),
                event(2, "node_started"),
                progress,
                event(4, "node_completed"),
                event(5, "pipeline_completed"),
            )
        val log = mockk<SseEventLog>()
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).replay(executionId, stored)

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "node_progress", "node_completed", "pipeline_completed")
        emitter.eventIds() shouldBe listOf("1", "2", "3", "4", "5")
        emitter.frames().any { it.contains("\"observed_at\":\"2026-09-16T10:00:00.400Z\"") } shouldBe true
    }

    @Test
    fun `follow serves new events as they land and closes after the terminal sequence`() {
        // A live execution: each read reveals one more scripted event, ending in pipeline_failed.
        val script = listOf(event(1, "execution_started"), event(2, "node_started"), event(3, "pipeline_failed"))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { ReplayRead.Log(script.take(reads.incrementAndGet())) }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        emitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "pipeline_failed")
    }

    @Test
    fun `follow gives up on a log that never appears with the id-free never-started 410`() {
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns ReplayRead.Absent
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        // GIVE_UP_AFTER_POLLS (60) at the 250ms follow cadence ≈ 15s, plus slack.
        emitter.errorCompleted.await(30, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe emptyList()
        // #324 — the completion is the never-started 410 as an error: code `result.expired`,
        // `reason: original_not_started`, and NO execution_id (the id does not resolve —
        // there is no row to GET). The exact map pins the absence.
        val error = emitter.error()
        (error is ApiException) shouldBe true
        error as ApiException
        error.code shouldBe PipelineErrorCodes.Result.EXPIRED
        error.details shouldBe mapOf("reason" to "original_not_started")
    }

    /** #324 — the `lastSentEventId == 0` guard: a follow that SERVED an event never gives up. */
    @Test
    fun `a follow that served an event and then lost its log never gives up`() {
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers {
            if (reads.incrementAndGet() == 1) ReplayRead.Log(listOf(event(1, "execution_started"))) else ReplayRead.Absent
        }
        val emitter = CapturingSseEmitter()
        try {
            streamer(log, emitter).follow(executionId)

            // One event served, then nulls past the give-up threshold: the stream stays open —
            // no error completion (the never-started 410 would be a lie; the client already
            // holds the original's events) and no quiet completion either (today's kept shape).
            emitter.errorCompleted.await(20, TimeUnit.SECONDS) shouldBe false
            emitter.completed.await(1, TimeUnit.SECONDS) shouldBe false
            emitter.eventNames() shouldBe listOf("execution_started")
        } finally {
            // Releases the polling task through the completion callback.
            emitter.complete()
        }
    }

    /**
     * #487 — a fault is not "no log yet": a follow that served nothing and only ever meets an
     * unanswered read ends after UNAVAILABLE_TICKS_BEFORE_END (8) ticks with the 503 code, never the
     * never-started 410 (which an expiry-shaped fault reached after 60 ticks). Id-free like #324's:
     * a fault cannot say whether the original's id resolves.
     */
    @Test
    fun `a follow that served nothing and meets only faults ends with the id-free storage-unavailable error`() {
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns unavailable()
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        // 8 ticks at the 250 ms cadence ≈ 2 s; far inside the never-started give-up's ~15 s.
        emitter.errorCompleted.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe emptyList()
        val error = emitter.error()
        (error is ApiException) shouldBe true
        error as ApiException
        error.code shouldBe PipelineErrorCodes.Result.STORAGE_UNAVAILABLE
        error.details shouldBe mapOf("reason" to "event_log_unavailable")
        (error.cause is DataAccessException) shouldBe true
    }

    /**
     * #487 — unanswered reads never count toward GIVE_UP_AFTER_POLLS (60): 59 absent reads, then
     * 7 faults (one short of the fault bound), then the log. Counted as absent polls, the faults
     * would have given up at the 60th tick with the never-started 410 instead of serving the log.
     */
    @Test
    fun `faults between absent reads do not count toward the never-started give-up`() {
        val script =
            List(GIVE_UP_AFTER_POLLS - 1) { ReplayRead.Absent } +
                List(UNAVAILABLE_TICKS_BEFORE_END - 1) { unavailable() } +
                ReplayRead.Log(listOf(event(1, "execution_started"), event(2, "pipeline_failed")))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { script[minOf(reads.getAndIncrement(), script.lastIndex)] }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        // 67 ticks at 250 ms ≈ 17 s, plus slack.
        emitter.completed.await(40, TimeUnit.SECONDS) shouldBe true
        emitter.errorCompleted.count shouldBe 1L
        emitter.eventNames() shouldBe listOf("execution_started", "pipeline_failed")
    }

    /**
     * #487 — a follow that served events rides out faults: the bound counts CONSECUTIVE faults, and
     * any answered read resets it. Two runs of 7 faults (each one short of the bound) around a read
     * that serves event 2 — never ending the stream early — then the terminal event. A regression
     * pin as well as the reset's falsification: without the reset the 8th fault overall ends it.
     */
    @Test
    fun `a follow that served events rides out fault runs shorter than the bound and serves what follows`() {
        val first = listOf(event(1, "execution_started"))
        val second = first + event(2, "node_started")
        val script =
            listOf<ReplayRead>(ReplayRead.Log(first)) +
                List(UNAVAILABLE_TICKS_BEFORE_END - 1) { unavailable() } +
                ReplayRead.Log(second) +
                List(UNAVAILABLE_TICKS_BEFORE_END - 1) { unavailable() } +
                ReplayRead.Log(second + event(3, "pipeline_failed"))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { script[minOf(reads.getAndIncrement(), script.lastIndex)] }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        emitter.completed.await(20, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "pipeline_failed")
        emitter.frames().any { it.contains("event_log_unavailable") } shouldBe false
    }

    /**
     * #487 — never quietly: a follow that served events and then meets only faults ends after the
     * bound with a final `event_log_unavailable` comment (the status is spent once a frame went out).
     * Before #487 the fault read as a lost log and that follow polled on, open, with no end.
     */
    @Test
    fun `a follow that served events ends after the fault bound with the event_log_unavailable comment`() {
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers {
            if (reads.incrementAndGet() == 1) ReplayRead.Log(listOf(event(1, "execution_started"))) else unavailable()
        }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        emitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.errorCompleted.count shouldBe 1L
        emitter.eventNames() shouldBe listOf("execution_started")
        emitter.frames().last() shouldContain "event_log_unavailable"
    }

    /** The shape a Redis command timeout arrives in through Spring Data Redis (Lettuce's, translated). */
    private fun unavailable() = ReplayRead.Unavailable(QueryTimeoutException("Redis command timed out"))

    private companion object {
        /** SseLogStreamer's private bounds, restated: a change there must be read here. */
        const val GIVE_UP_AFTER_POLLS = 60
        const val UNAVAILABLE_TICKS_BEFORE_END = 8
    }
}
