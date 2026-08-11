package co.datapipelines.web.sse

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serves an SSE stream **from the Redis event log** rather than from a live execution.
 *
 * Two call sites (rest-api.md):
 *
 *  - **§10.3 replay** — `GET /executions/{id}/events`. The log holds the original events with
 *    their original ids and payloads; re-emitting them is the whole endpoint.
 *  - **Idempotent execute retry** (§3.5, dag-executor §11.2) — a retried `POST
 *    /pipelines/{id}/execute` with the same `Idempotency-Key` attaches to the original execution.
 *    There is no stream resumption (§6.8), so the retry is served the original's events from the
 *    log, *following* it live (a short poll) while the original is still running.
 *
 * A follower's disconnect cancels **nothing**: the disconnect-grace rule (§6.8) belongs to the
 * owning stream in [ExecutionStreamRegistry], and an execution must not be aborted because a
 * replay consumer went away.
 */
class SseLogStreamer(
    private val eventLog: SseEventLog,
    private val mapper: ObjectMapper,
    private val scheduler: ScheduledExecutorService,
    /**
     * Test seam: builds the emitter to serve. Production wiring uses the default (a never-timing-
     * out emitter, because the execution's own timeout bounds the run); tests substitute a
     * capturing emitter, since Spring's `ResponseBodyEmitter.Handler` is package-private.
     */
    private val emitterFactory: () -> SseEmitter = { SseEmitter(NEVER_TIMEOUT) },
) {
    private val log = LoggerFactory.getLogger(SseLogStreamer::class.java)
    private val follows = ConcurrentHashMap<SseEmitter, FollowState>()

    /** True when a (possibly still-growing) event log exists for [executionId]. */
    fun hasLog(executionId: UUID): Boolean = eventLog.replay(executionId) != null

    /** §10.3: emits the stored stream once, in original order, then completes. */
    fun replay(executionId: UUID): SseEmitter {
        val emitter = emitterFactory()
        val events = eventLog.replay(executionId).orEmpty()
        scheduler.execute {
            events.forEach { event -> if (!send(emitter, event)) return@execute }
            completeQuietly(emitter, executionId)
        }
        return emitter
    }

    /**
     * Emits what the log already holds, then polls for more until the terminal sequence (§6.5
     * step 3) has been served and the stream closes.
     *
     * A log that never appears (the original died before its first event could be persisted) is
     * given up on after [GIVE_UP_AFTER_POLLS] ticks; the stream completes without events, which
     * the client reads as "attach failed — re-execute", exactly the §6.8 guidance.
     */
    fun follow(executionId: UUID): SseEmitter {
        val emitter = emitterFactory()
        val state = FollowState()
        follows[emitter] = state
        state.task =
            scheduler.scheduleWithFixedDelay(
                { followTick(emitter, executionId) },
                0,
                FOLLOW_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        emitter.onCompletion { cancel(emitter) }
        emitter.onTimeout { cancel(emitter) }
        emitter.onError { cancel(emitter) }
        return emitter
    }

    /** Mutable per-follow bookkeeping; one instance per [follow] call, dropped at close. */
    private class FollowState {
        @Volatile var task: ScheduledFuture<*>? = null

        @Volatile var lastSentEventId: Int = 0

        @Volatile var emptyPolls: Int = 0

        /** Ticks since `pipeline_completed` was served without a `data_ready` appearing. */
        @Volatile var closePendingTicks: Int = -1

        val cancelled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
    }

    private fun followTick(
        emitter: SseEmitter,
        executionId: UUID,
    ) {
        val state = follows[emitter] ?: return
        if (state.cancelled.get()) return
        try {
            tick(emitter, executionId, state)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // A scheduled task that throws is silently unscheduled; the stream would hang open.
            log.warn("SSE follow of execution {} failed; closing the stream.", executionId, e)
            cancel(emitter)
            completeQuietly(emitter, executionId)
        }
    }

    private fun tick(
        emitter: SseEmitter,
        executionId: UUID,
        state: FollowState,
    ) {
        val events = eventLog.replay(executionId)
        if (events == null) {
            // No log yet (or any more). Only a follow that has served nothing gives up on it.
            if (state.lastSentEventId == 0 && ++state.emptyPolls >= GIVE_UP_AFTER_POLLS) {
                cancel(emitter)
                completeQuietly(emitter, executionId)
            }
            return
        }
        val fresh = events.filter { it.eventId > state.lastSentEventId }
        for (event in fresh) {
            if (!send(emitter, event)) {
                cancel(emitter)
                return
            }
            state.lastSentEventId = event.eventId
            if (event.eventName in HARD_TERMINAL_EVENTS) {
                cancel(emitter)
                completeQuietly(emitter, executionId)
                return
            }
            if (event.eventName == PIPELINE_COMPLETED) state.closePendingTicks = 0
        }
        // `pipeline_completed` may be trailed by `data_ready` in the next persist cycle; give it
        // one extra tick before closing, so the follow serves the same terminal sequence the
        // live stream did (§6.5 step 3).
        if (state.closePendingTicks >= 0) {
            if (fresh.isEmpty() && ++state.closePendingTicks > 1) {
                cancel(emitter)
                completeQuietly(emitter, executionId)
            }
        }
    }

    private fun cancel(emitter: SseEmitter) {
        val state = follows.remove(emitter) ?: return
        if (state.cancelled.compareAndSet(false, true)) state.task?.cancel(false)
    }

    private fun send(
        emitter: SseEmitter,
        event: LoggedSseEvent,
    ): Boolean =
        try {
            emitter.send(
                SseEmitter
                    .event()
                    .name(event.eventName)
                    .id(event.eventId.toString())
                    .data(mapper.writeValueAsString(event.payload), MediaType.APPLICATION_JSON),
            )
            true
        } catch (e: IOException) {
            // The one honest signal a servlet container gives for a vanished client.
            log.debug("SSE log client disconnected while sending {}: {}", event.eventName, e.message)
            false
        } catch (e: IllegalStateException) {
            log.debug("SSE log emitter already closed: {}", e.message)
            false
        }

    private fun completeQuietly(
        emitter: SseEmitter,
        executionId: UUID,
    ) {
        follows.remove(emitter)
        runCatching { emitter.complete() }
            .onFailure { log.debug("SSE log stream for {} did not complete cleanly", executionId, it) }
    }

    private companion object {
        const val NEVER_TIMEOUT = 0L

        /** Follow cadence. Sub-second, so a retry watches the original near-live. */
        const val FOLLOW_POLL_MILLIS = 250L

        /** ~15s at the follow cadence: how long a follow waits for a log that never appears. */
        const val GIVE_UP_AFTER_POLLS = 60

        const val PIPELINE_COMPLETED = "pipeline_completed"

        /**
         * Events after which nothing further can arrive (§6.5). `pipeline_completed` is handled
         * separately: it may be followed by `data_ready`.
         */
        val HARD_TERMINAL_EVENTS = setOf("pipeline_failed", "execution_aborted", "data_ready")
    }
}
