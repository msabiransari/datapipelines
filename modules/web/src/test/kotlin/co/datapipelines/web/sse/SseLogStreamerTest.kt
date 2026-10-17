package co.datapipelines.web.sse

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.CapturingSseEmitter
import co.datapipelines.web.TickScheduler
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.dao.QueryTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The log-served stream: §10.3 replay and the idempotent-retry follow, against a fake log.
 * Every state-transition case drives the cadence on [TickScheduler] (#564): the test advances
 * the polls tick by tick, so each bound is asserted at its exact boundary — open at N−1, closed
 * at N — with no wall-clock wait. Exactly ONE case keeps a real single-thread executor to pin
 * the scheduling contract itself: a real 250 ms cadence serving the terminal sequence, the
 * close cancelling the task, and a throwing read ending the stream quietly — semantics a
 * hand-advanced double can model but only a real scheduler proves end to end. A further case
 * pins the double's own contract directly, because every other case stands on it.
 *
 * Event ids and names must survive verbatim — replay is byte-faithful by construction, and the
 * follow closes only after the terminal sequence.
 */
class SseLogStreamerTest {
    private val scheduler = TickScheduler()
    private val realScheduler = lazy { Executors.newSingleThreadScheduledExecutor() }
    private val executionId = UUID.randomUUID()

    @AfterEach
    fun stopRealScheduler() {
        if (realScheduler.isInitialized()) {
            realScheduler.value.shutdownNow()
            realScheduler.value.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
        }
    }

    private fun event(
        id: Int,
        name: String,
    ) = LoggedSseEvent(id, name, mapOf("execution_id" to executionId.toString(), "n" to id))

    private fun streamer(
        log: SseEventLog,
        emitter: CapturingSseEmitter,
    ) = SseLogStreamer(log, JsonMapper.builder().build(), scheduler) { emitter }

    private fun streamerOnRealScheduler(
        log: SseEventLog,
        emitter: CapturingSseEmitter,
    ) = SseLogStreamer(log, JsonMapper.builder().build(), realScheduler.value) { emitter }

    @Test
    fun `replay emits the stored events in order and completes`() {
        val stored = listOf(event(1, "execution_started"), event(2, "pipeline_completed"), event(3, "data_ready"))
        val log = mockk<SseEventLog>()
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).replay(executionId, stored)
        // The replay task runs once, to completion, synchronously: the loop inside it is the
        // only order there is, so the captured frames prove the stored order.
        scheduler.runPending()

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
        scheduler.runPending()

        emitter.completed.await(5, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "node_progress", "node_completed", "pipeline_completed")
        emitter.eventIds() shouldBe listOf("1", "2", "3", "4", "5")
        emitter.frames().any { it.contains("\"observed_at\":\"2026-09-16T10:00:00.400Z\"") } shouldBe true
    }

    /**
     * #564 — the ONE real-scheduler case. It pins the scheduling contract itself, which a
     * hand-advanced double cannot prove: a real cadence serves the three-event script and
     * closes; after the close the scheduled task is cancelled (the mock's invocation count
     * freezes across a real wait spanning several polls — the close leaves no task polling);
     * and a read that throws a non-DataAccessException RuntimeException ends the stream
     * quietly (SseLogStreamer's tick-level catch, the "silently unscheduled" path) instead of
     * hanging open.
     */
    @Test
    fun `follow on a real scheduler serves the terminal sequence, the close cancels the task, and a throwing read ends quietly`() {
        // A live execution: each read reveals one more scripted event, ending in pipeline_failed.
        val script = listOf(event(1, "execution_started"), event(2, "node_started"), event(3, "pipeline_failed"))
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers { ReplayRead.Log(script.take(reads.incrementAndGet())) }
        val emitter = CapturingSseEmitter()

        streamerOnRealScheduler(log, emitter).follow(executionId)

        emitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started", "node_started", "pipeline_failed")

        // 1 s spans ~4 polls at the 250 ms cadence: if the close had left the task scheduled,
        // the count would grow; cancelled, it is frozen.
        val readsAtClose = reads.get()
        Thread.sleep(1000)
        reads.get() shouldBe readsAtClose

        // The quiet end: a plain RuntimeException (not a DataAccessException) is the tick-level
        // catch — completed, never error-completed, nothing served, no hang.
        val throwingLog = mockk<SseEventLog>()
        every { throwingLog.replay(executionId) } throws IllegalStateException("the log read blew up")
        val failingEmitter = CapturingSseEmitter()

        streamerOnRealScheduler(throwingLog, failingEmitter).follow(executionId)

        failingEmitter.completed.await(10, TimeUnit.SECONDS) shouldBe true
        failingEmitter.errorCompleted.count shouldBe 1L
        failingEmitter.eventNames() shouldBe emptyList()
    }

    @Test
    fun `follow gives up on a log that never appears with the id-free never-started 410`() {
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } returns ReplayRead.Absent
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        // GIVE_UP_AFTER_POLLS polls, advanced by hand: still open at 59, the give-up lands
        // exactly on the 60th.
        repeat(GIVE_UP_AFTER_POLLS - 1) { scheduler.tick() }
        emitter.errorCompleted.count shouldBe 1L
        emitter.completed.count shouldBe 1L

        scheduler.tick()
        emitter.errorCompleted.count shouldBe 0L
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

        streamer(log, emitter).follow(executionId)

        // One event served, then absents ten polls past the give-up threshold: the stream stays
        // open — no error completion (the never-started 410 would be a lie; the client already
        // holds the original's events) and no quiet completion either (today's kept shape).
        repeat(GIVE_UP_AFTER_POLLS + 10) { scheduler.tick() }
        emitter.errorCompleted.count shouldBe 1L
        emitter.completed.count shouldBe 1L
        emitter.eventNames() shouldBe listOf("execution_started")
        // The close that cancels a follow lives in the completion callback, which fires only
        // once the servlet container initializes the emitter (see the real-scheduler case); an
        // uninitialized test emitter keeps polling, so nothing past the bound is asserted here.
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

        // Open at seven consecutive faults — one short of the bound; the 503 lands on the 8th.
        repeat(UNAVAILABLE_TICKS_BEFORE_END - 1) { scheduler.tick() }
        emitter.errorCompleted.count shouldBe 1L
        emitter.completed.count shouldBe 1L

        scheduler.tick()
        emitter.errorCompleted.count shouldBe 0L
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

        // 59 absents then 7 faults — 66 polls, and neither bound has fired: the give-up counter
        // sits at 59 and the fault run at 7, so the follow is still open.
        repeat(GIVE_UP_AFTER_POLLS - 1 + UNAVAILABLE_TICKS_BEFORE_END - 1) { scheduler.tick() }
        emitter.errorCompleted.count shouldBe 1L
        emitter.completed.count shouldBe 1L

        // The 67th poll reads the log and serves the terminal sequence.
        scheduler.tick()
        emitter.completed.count shouldBe 0L
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

        // The serve, then the first run of 7 faults — one short of the bound: still open.
        repeat(UNAVAILABLE_TICKS_BEFORE_END) { scheduler.tick() }
        emitter.completed.count shouldBe 1L

        // The serving read resets the consecutive count, so the second run of 7 also rides out
        // and the terminal event lands on the 17th poll.
        repeat(UNAVAILABLE_TICKS_BEFORE_END + 1) { scheduler.tick() }
        emitter.completed.count shouldBe 0L
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

        // The serve, then seven consecutive faults — one short of the bound: still open.
        repeat(UNAVAILABLE_TICKS_BEFORE_END) { scheduler.tick() }
        emitter.completed.count shouldBe 1L

        // The eighth consecutive fault ends it — never quietly: a final
        // `event_log_unavailable` comment after the served frame.
        scheduler.tick()
        emitter.completed.count shouldBe 0L
        emitter.errorCompleted.count shouldBe 1L
        emitter.eventNames() shouldBe listOf("execution_started")
        emitter.frames().last() shouldContain "event_log_unavailable"
    }

    /**
     * #564 — the double's own contract, pinned directly because every case above stands on it:
     * one run per tick; `cancel` stops the ticks; a run that threw is never re-run — the
     * ScheduledThreadPoolExecutor suppression the log streamer's tick-level catch is written
     * around.
     */
    @Test
    fun `the test scheduler runs a fixed-delay task once per tick, honours cancel, and never re-runs a task that threw`() {
        var runs = 0
        val increment = Runnable { runs++ }
        val task = scheduler.scheduleWithFixedDelay(increment, 0, 250L, TimeUnit.MILLISECONDS)

        scheduler.tick()
        scheduler.tick()
        runs shouldBe 2

        task.cancel(false) shouldBe true
        scheduler.tick()
        runs shouldBe 2

        var throwing = 0
        val blowUp =
            Runnable {
                throwing++
                error("the tick blew up")
            }
        scheduler.scheduleWithFixedDelay(blowUp, 0, 250L, TimeUnit.MILLISECONDS)
        scheduler.tick()
        scheduler.tick()
        throwing shouldBe 1
    }

    /**
     * #564 — the tick-level catch's twin on the double: a read that throws a plain
     * RuntimeException (not a DataAccessException) ends the follow quietly — completed, never
     * error-completed, nothing served — and the follow never reads again.
     */
    @Test
    fun `a follow whose read throws a plain RuntimeException ends quietly and never reads again`() {
        val reads = AtomicInteger(0)
        val log = mockk<SseEventLog>()
        every { log.replay(executionId) } answers {
            reads.incrementAndGet()
            error("the log read blew up")
        }
        val emitter = CapturingSseEmitter()

        streamer(log, emitter).follow(executionId)

        scheduler.tick()
        emitter.completed.count shouldBe 0L
        emitter.errorCompleted.count shouldBe 1L
        emitter.eventNames() shouldBe emptyList()

        scheduler.tick()
        reads.get() shouldBe 1
    }

    /** The shape a Redis command timeout arrives in through Spring Data Redis (Lettuce's, translated). */
    private fun unavailable() = ReplayRead.Unavailable(QueryTimeoutException("Redis command timed out"))

    private companion object {
        /** SseLogStreamer's private bounds, restated: a change there must be read here. */
        const val GIVE_UP_AFTER_POLLS = 60
        const val UNAVAILABLE_TICKS_BEFORE_END = 8
    }
}
