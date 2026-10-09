package co.datapipelines.web.sse

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
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
 *
 * ## Subscriber authority (#230, P4)
 * Both call sites hand the stream the SUBSCRIBER — the principal the route authorized at open —
 * and every write (each replayed chunk, each followed event) re-asks their CURRENT authority
 * through [ExecutionStreamAuthority] before it goes out (ruling P4: an open stream is cut at
 * its next write after revocation). A refusal ends the stream at that write: nothing of the
 * event is served, a final `: revoked` comment (§6.6's comment form) names why.
 */
class SseLogStreamer(
    private val eventLog: SseEventLog,
    private val mapper: ObjectMapper,
    private val scheduler: ScheduledExecutorService,
    /**
     * #230 (P4): the subscriber re-judgement, shared by [replay] and [follow]. Kept BEFORE
     * [emitterFactory] on purpose — a function-typed parameter must stay LAST or every trailing-
     * lambda call site silently rebinds to the new hook. Null only in tests of the pre-#230
     * shape.
     */
    private val authority: ExecutionStreamAuthority? = null,
    /**
     * Test seam: builds the emitter to serve. Production wiring uses the default (a never-timing-
     * out emitter, because the execution's own timeout bounds the run); tests substitute a
     * capturing emitter, since Spring's `ResponseBodyEmitter.Handler` is package-private.
     */
    private val emitterFactory: () -> SseEmitter = { SseEmitter(NEVER_TIMEOUT) },
) {
    private val log = LoggerFactory.getLogger(SseLogStreamer::class.java)
    private val follows = ConcurrentHashMap<SseEmitter, FollowState>()

    /**
     * True when a (possibly still-growing) event log ANSWERED for [executionId] — the idempotent
     * attach's question (§3.5), whose answer for false is to consult the execution row.
     *
     * A read fault answers false, as before #487, on purpose: true would turn the fault into a
     * [follow], and for a terminal original whose log really expired that follow gives up with
     * the never-started 410 — "never started" about an execution that ran. The attach's own
     * fault answer (503, not `event_log_expired`) needs the launcher to read [ReplayRead] (#505).
     */
    fun hasLog(executionId: UUID): Boolean = eventLog.replay(executionId) is ReplayRead.Log

    /**
     * §10.3's one read of the log (#487): the route decides 410 / 503 / stream on it, then hands the
     * events to [replay] — the stream is served from this read, never from a second one.
     */
    fun load(executionId: UUID): ReplayRead = eventLog.replay(executionId)

    /**
     * §10.3: emits [events] — the stored stream [load] returned — once, in original order, then
     * completes.
     *
     * Each chunk re-asks the subscriber's authority first (#230, P4): a refusal ends the replay
     * at that chunk — a partially-consumed replay is the contract, not an error.
     */
    fun replay(
        executionId: UUID,
        events: List<LoggedSseEvent>,
        subscriber: AuthenticatedPrincipal? = null,
    ): SseEmitter {
        val emitter = emitterFactory()
        scheduler.execute {
            for (event in events) {
                if (!authorizedBeforeWrite(emitter, executionId, subscriber)) return@execute
                if (!send(emitter, event)) return@execute
            }
            completeQuietly(emitter, executionId)
        }
        return emitter
    }

    /**
     * Emits what the log already holds, then polls for more until the terminal sequence (§6.5
     * step 3) has been served and the stream closes.
     *
     * A log that never appears is given up on after [GIVE_UP_AFTER_POLLS] ticks — but only by a
     * follow that has served NOTHING (#324): that follow was attached to an original that never
     * started, its id does not resolve, and the give-up completes the stream with the id-free
     * `410 result.expired` (`reason: original_not_started`) instead of a silent end. A follow
     * that HAS served events and then loses its log keeps the old quiet completion (the client
     * already holds the original's events; the §6.8 "attach failed — re-execute" reading no
     * longer applies).
     *
     * The subscriber (#230, P4) rides in [FollowState]: every event the follow serves is
     * re-judged first, so an attach made under valid authority cannot outlive it.
     */
    fun follow(
        executionId: UUID,
        subscriber: AuthenticatedPrincipal? = null,
    ): SseEmitter {
        val emitter = emitterFactory()
        val state = FollowState(subscriber)
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
    private class FollowState(
        /** The subscriber this follow serves (#230, P4); re-judged before every served event. */
        val subscriber: AuthenticatedPrincipal?,
    ) {
        @Volatile var task: ScheduledFuture<*>? = null

        @Volatile var lastSentEventId: Int = 0

        @Volatile var emptyPolls: Int = 0

        /** Consecutive ticks whose read Redis did not answer (#487); any answered read resets it. */
        @Volatile var unavailableTicks: Int = 0

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
        val read = eventLog.replay(executionId)
        if (read !is ReplayRead.Log) {
            if (read is ReplayRead.Unavailable) onUnavailable(emitter, executionId, state, read) else onAbsent(emitter, executionId, state)
            return
        }
        state.unavailableTicks = 0
        val fresh = read.events.filter { it.eventId > state.lastSentEventId }
        for (event in fresh) {
            // The re-judge (#230, P4) runs before every served event; a refusal has already
            // cancelled and completed the stream, so the follow simply ends.
            if (!authorizedBeforeWrite(emitter, executionId, state.subscriber)) return
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

    /** No log yet (or any more) — an ANSWERED read, so it also ends a run of faults (#487). */
    private fun onAbsent(
        emitter: SseEmitter,
        executionId: UUID,
        state: FollowState,
    ) {
        state.unavailableTicks = 0
        // No log yet (or any more). Only a follow that has served nothing gives up on it.
        if (state.lastSentEventId == 0 && ++state.emptyPolls >= GIVE_UP_AFTER_POLLS) {
            cancel(emitter)
            // #324 — a follow that never served an event was attached to an original that
            // never started: the id does not resolve (there is no row to GET), so the give-up
            // is the ID-FREE 410 (`reason: original_not_started`), completed with an error
            // the way `ExecutionStreamLauncher.failBeforeStart` completes — nothing was sent,
            // so the response is uncommitted and the advice renders the envelope on the async
            // dispatch (wire-proven since #404, `IdempotentAttachRowOrderE2eTest`). A follow
            // that HAS served events never gives up on an absent log: it keeps polling.
            completeWithErrorQuietly(
                emitter,
                executionId,
                ApiException(
                    PipelineErrorCodes.Result.EXPIRED,
                    "The original execution of this idempotency key never started; its id does not " +
                        "resolve. Re-execute with a fresh Idempotency-Key.",
                    mapOf("reason" to "original_not_started"),
                ),
            )
        }
    }

    /**
     * A tick Redis did not answer (#487). Not "no log yet": it never counts toward
     * [GIVE_UP_AFTER_POLLS] and never ends a follow that served events on its own — the next tick
     * reads again. The first fault of a run logs once, with its cause; [UNAVAILABLE_TICKS_BEFORE_END]
     * consecutive faults end the follow, never quietly: before the first event the response is
     * still uncommitted, so it completes with `503 result.storage_unavailable` (the #324 error
     * completion, id-free — a fault cannot say whether the original's id resolves); after one, the
     * status is spent, so a final `event_log_unavailable` comment (§6.6's comment form, the revoked
     * cut's shape) names why the stream stops short of its terminal event.
     */
    private fun onUnavailable(
        emitter: SseEmitter,
        executionId: UUID,
        state: FollowState,
        read: ReplayRead.Unavailable,
    ) {
        val faults = ++state.unavailableTicks
        if (faults == 1) {
            log.warn("SSE follow of execution {}: the event log read failed; retrying next tick.", executionId, read.cause)
        }
        if (faults < UNAVAILABLE_TICKS_BEFORE_END) return
        log.warn(
            "SSE follow of execution {} ended: the event log read failed {} ticks in a row ({} event(s) served).",
            executionId,
            faults,
            state.lastSentEventId,
        )
        cancel(emitter)
        if (state.lastSentEventId == 0) {
            completeWithErrorQuietly(emitter, executionId, ApiErrors.eventLogUnavailable(null, read.cause))
        } else {
            runCatching { emitter.send(SseEmitter.event().comment(ApiErrors.EVENT_LOG_UNAVAILABLE)) }
            completeQuietly(emitter, executionId)
        }
    }

    /**
     * The #230 (P4) gate both loops pass before every write: re-ask [authority] for the
     * subscriber's CURRENT authority to read this execution.
     *
     * On a refusal the stream ends at THIS write: nothing of the event is served; a final
     * `: revoked` comment (§6.6's comment form — ignored by every SSE consumer) names why. Fail
     * closed: an answer that cannot be established is a refusal. A null subscriber or authority
     * — the pre-#230 shape, tests only — passes untouched.
     */
    private fun authorizedBeforeWrite(
        emitter: SseEmitter,
        executionId: UUID,
        subscriber: AuthenticatedPrincipal?,
    ): Boolean {
        val judge = authority ?: return true
        if (subscriber == null) return true
        // [ExecutionStreamAuthority.verdict] never throws — an unsettleable answer is its own
        // refusal (fail closed, in the log). #293: the cut is tagged with the SAME judgement's
        // reason — `expired` for a token past its `exp`, `revoked` otherwise (observability
        // §4.2's close reasons, which the live stream's metric already records) — never a second
        // read of the clock (#271). The final comment is the same static string either way.
        val verdict = judge.verdict(subscriber, executionId)
        if (verdict != StreamVerdict.ALLOWED) {
            val closeReason = if (verdict == StreamVerdict.EXPIRED) CLOSE_EXPIRED else CLOSE_REVOKED
            log.info(
                "SSE log stream of execution {} cut, close_reason={}: {}.",
                executionId,
                closeReason,
                if (verdict == StreamVerdict.EXPIRED) EXPIRED_WHY else REVOKED_WHY,
            )
            runCatching { emitter.send(SseEmitter.event().comment(REVOKED_COMMENT)) }
            cancel(emitter)
            completeQuietly(emitter, executionId)
        }
        return verdict == StreamVerdict.ALLOWED
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

    /**
     * [completeQuietly]'s error twin (#324): the completion carries [error] to the servlet
     * container while the response is still uncommitted — the never-started 410, which the
     * `@ControllerAdvice` renders as its envelope on the async dispatch (#404).
     */
    private fun completeWithErrorQuietly(
        emitter: SseEmitter,
        executionId: UUID,
        error: Exception,
    ) {
        follows.remove(emitter)
        runCatching { emitter.completeWithError(error) }
            .onFailure { log.debug("SSE log stream for {} did not complete cleanly", executionId, it) }
    }

    private companion object {
        const val NEVER_TIMEOUT = 0L

        /** observability §4.2's close reasons, as the live stream's duration metric records them. */
        const val CLOSE_EXPIRED = "expired"
        const val CLOSE_REVOKED = "revoked"

        const val EXPIRED_WHY = "the subscriber's session token passed its exp (#263)"
        const val REVOKED_WHY = "the subscriber's authority no longer holds (#230, P4)"

        /**
         * #230 (P4) — the final comment a cut stream carries. A comment, not an event: the
         * subscriber is no longer authorized to read EVENTS, and a comment is invisible to
         * every SSE consumer by construction (§6.6).
         */
        const val REVOKED_COMMENT = "revoked"

        /** Follow cadence. Sub-second, so a retry watches the original near-live. */
        const val FOLLOW_POLL_MILLIS = 250L

        /**
         * ~15s at the follow cadence: how long a follow waits for a log that never appears.
         * #324 — the wait doubles as the idempotent attach's start-window patience (above the
         * 10 s lifecycle write bound), and the give-up's answer differs by what was served:
         * nothing → the id-free never-started 410; events → the quiet completion.
         */
        const val GIVE_UP_AFTER_POLLS = 60

        /**
         * #487 — consecutive unanswered reads that end a follow. A read on the shipped client fails
         * at its 2 s command timeout (Lettuce queues commands while it reconnects), so eight ticks
         * are ~18 s with the 250 ms delay — past the give-up patience above, so a Redis restart
         * inside that window is ridden out; a read refused at once ends the follow in ~2 s.
         */
        const val UNAVAILABLE_TICKS_BEFORE_END = 8

        const val PIPELINE_COMPLETED = "pipeline_completed"

        /**
         * Events after which nothing further can arrive (§6.5). `pipeline_completed` is handled
         * separately: it may be followed by `data_ready`.
         */
        val HARD_TERMINAL_EVENTS = setOf("pipeline_failed", "execution_aborted", "data_ready")
    }
}
