package co.datapipelines.web.parameters.stream

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.web.config.SseProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Every live observed-evaluation stream on this instance, plus the two timers rest-api §6 requires —
 * [co.datapipelines.web.dashboards.runtime.RefreshStreamRegistry]'s twin, its OWN registry (an evaluation is neither an
 * execution nor a refresh; their graces would cancel things that do not exist).
 *
 * ## Heartbeat (§6.6)
 * One scheduled task ticks every `datapipelines.sse.heartbeat-interval-seconds` and writes the `: heartbeat` comment to
 * each stream that wrote nothing since the previous tick (the subscriber is re-judged first — [ParameterEvaluationStream]).
 *
 * ## Disconnect grace (§6.8) — "a disconnected client aborts its evaluation" (the owner's §11.7 ruling)
 * A stream whose client is gone before the evaluation ended is stamped when first noticed; once
 * `datapipelines.sse.disconnect-grace-seconds` has elapsed the evaluation's coroutine — the [Job] [attach]ed at launch —
 * is CANCELLED: the evaluator's deadline path runs (the awaiting coroutines stop, a running statement is abandoned —
 * cancel + discard), and the evaluation ends `ABORTED`. A disconnect after the end costs nothing. A stream CUT by the
 * authority guard is not a disconnect: the evaluation runs to its end. No abort route exists: the page supersedes an
 * attempt by closing its stream, and this grace does the rest.
 *
 * ## The one per-user stream cap (§12.1, #375 D7)
 * `datapipelines.sse.max-streams-per-user` counts execution, refresh AND evaluation streams together: [otherStreams]
 * answers the first two, and the refresh registry counts these in turn.
 *
 * ## Reuse (#375 D5)
 * An `evaluation_id` already registered here is refused `reused` — checked before the cap and again atomically at
 * [open]. Cross-instance reuse is S3's (#376): its history row's primary key refuses it at insert.
 */
class ParameterEvaluationStreamRegistry(
    private val properties: SseProperties,
    /** The user's OTHER open SSE streams — executions plus refreshes — for the one shared cap. */
    private val otherStreams: (UUID) -> Int,
    private val mapper: ObjectMapper,
    private val scheduler: ScheduledExecutorService = defaultScheduler(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(ParameterEvaluationStreamRegistry::class.java)
    private val streams = ConcurrentHashMap<UUID, ParameterEvaluationStream>()
    private val jobs = ConcurrentHashMap<UUID, Job>()
    private val disconnectedSince = ConcurrentHashMap<UUID, Long>()

    @Volatile private var lastTickAtMillis: Long = 0L

    init {
        val period = properties.heartbeatIntervalSeconds
        scheduler.scheduleAtFixedRate({ tick() }, period, period, TimeUnit.SECONDS)
    }

    /** Evaluation streams open on this instance. */
    val activeStreams: Int get() = streams.size

    fun activeStreamsFor(userId: UUID): Int = streams.values.count { it.userId == userId }

    /**
     * True when [userId] is at the shared per-user cap. Check-then-open is not atomic — the same bounded overshoot the
     * execution and refresh registries document.
     */
    fun atStreamLimit(userId: UUID): Boolean = otherStreams(userId) + activeStreamsFor(userId) >= properties.maxStreamsPerUser

    val maxStreamsPerUser: Int get() = properties.maxStreamsPerUser

    /** True when [evaluationId] is registered here — the D5 reuse check's question. */
    fun isOpen(evaluationId: UUID): Boolean = streams.containsKey(evaluationId)

    /**
     * Opens and registers the stream for [evaluationId]; the emitter never times out (the evaluate's deadline bounds the
     * run, and the grace ends one nobody reads). A second open of the same id is the `reused` refusal, atomically.
     */
    fun open(
        evaluationId: UUID,
        parameterSetId: UUID,
        version: Int,
        subscriber: AuthenticatedPrincipal,
        authority: ParameterEvaluationStreamAuthority,
    ): ParameterEvaluationStream {
        val stream =
            ParameterEvaluationStream(
                evaluationId,
                parameterSetId,
                version,
                subscriber.userId,
                SseEmitter(NEVER_TIMEOUT),
                mapper,
                subscriber,
                authority,
                nowMillis,
            )
        if (streams.putIfAbsent(evaluationId, stream) != null) {
            throw ParameterEvaluationRequests.bad("evaluation_id", ParameterEvaluationRequests.REUSED)
        }
        // Completion, timeout and error ALL mean "the client is not reading" and nothing more: the stream stays in the
        // map for the tick to decide (ended → drop it; not → the grace clock runs) — dropping it here would hide a
        // vanished client from the very timer that exists to abort its evaluation (the refresh registry's regression).
        stream.emitter.onCompletion { stream.markDisconnected() }
        stream.emitter.onTimeout { stream.markDisconnected() }
        stream.emitter.onError { stream.markDisconnected() }
        return stream
    }

    /** The evaluation's coroutine — what the grace cancels. */
    fun attach(
        evaluationId: UUID,
        job: Job,
    ) {
        if (streams.containsKey(evaluationId)) jobs[evaluationId] = job
    }

    fun find(evaluationId: UUID): ParameterEvaluationStream? = streams[evaluationId]

    /** Closes and deregisters the stream. Idempotent. */
    fun close(evaluationId: UUID) {
        val stream = streams.remove(evaluationId) ?: return
        jobs.remove(evaluationId)
        disconnectedSince.remove(evaluationId)
        stream.close()
    }

    @Suppress("TooGenericExceptionCaught") // a scheduled task that throws is cancelled silently — the timer must survive
    internal fun tick() {
        try {
            streams.values.forEach(::tickOne)
            lastTickAtMillis = nowMillis()
        } catch (e: RuntimeException) {
            log.warn("event=parameter.evaluation_tick_failed error={}", e.javaClass.simpleName)
        }
    }

    private fun tickOne(stream: ParameterEvaluationStream) {
        if (stream.isTerminal) {
            // The evaluation ended: a disconnect after it costs nothing (§6.8).
            if (!stream.isConnected) close(stream.evaluationId)
            return
        }
        if (stream.isConnected && stream.lastActivityAtMillis.get() <= lastTickAtMillis) stream.heartbeat()
        if (!stream.isConnected && !stream.isRevoked) noticeDisconnect(stream)
    }

    private fun noticeDisconnect(stream: ParameterEvaluationStream) {
        val firstNoticed = disconnectedSince.putIfAbsent(stream.evaluationId, nowMillis())
        if (firstNoticed == null) {
            log.info(
                "event=parameter.evaluation_client_gone evaluation_id={} grace_seconds={}",
                stream.evaluationId,
                properties.disconnectGraceSeconds,
            )
            return
        }
        if ((nowMillis() - firstNoticed) / MILLIS_PER_SECOND < properties.disconnectGraceSeconds) return
        log.info("event=parameter.evaluation_grace_elapsed evaluation_id={} action=abort", stream.evaluationId)
        jobs[stream.evaluationId]?.cancel()
        close(stream.evaluationId)
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        streams.keys.toList().forEach(::close)
    }

    private companion object {
        const val NEVER_TIMEOUT = 0L
        const val MILLIS_PER_SECOND = 1000L

        fun defaultScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "dp-parameter-heartbeat").apply { isDaemon = true } }
    }
}
