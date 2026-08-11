package co.datapipelines.web.sse

import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.web.config.SseProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every live execution stream on this instance, plus the two timers §6 requires.
 *
 * ## Heartbeat (§6.6)
 * One scheduled task ticks every `datapipelines.sse.heartbeat-interval-seconds` and writes the
 * `: heartbeat` comment to each stream that has emitted nothing since the previous tick. One timer
 * for all streams rather than one per stream: a thread per open SSE connection is the resource
 * shape SSE exists to avoid, and 50 streams per user (§12.1) times the user count would be a lot
 * of threads doing nothing.
 *
 * ## Disconnect grace (§6.8) — "a disconnected client cancels its execution"
 * The same tick notices a stream whose client is gone and whose execution has **not** reached a
 * terminal event, and stamps the moment it first noticed. When
 * `datapipelines.sse.disconnect-grace-seconds` has elapsed since that stamp and the execution is
 * still not terminal, the execution is cancelled with [AbortReason.CLIENT_DISCONNECT] — statements
 * interrupted, connections released, `execution_aborted` emitted. dag-executor §15.2 is explicit
 * that this timer is the SSE layer's to own, not the executor's.
 *
 * A disconnect **after** the terminal event costs nothing and cancels nothing (§6.8).
 *
 * ## Per-user stream cap (§12.1)
 * `datapipelines.sse.max-streams-per-user` concurrent streams. Counted here because this is the
 * only object that knows what is open.
 */
class ExecutionStreamRegistry(
    private val properties: SseProperties,
    private val cancellationService: ExecutionCancellationService,
    private val mapper: ObjectMapper,
    private val scheduler: ScheduledExecutorService = defaultScheduler(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(ExecutionStreamRegistry::class.java)
    private val streams = ConcurrentHashMap<UUID, ExecutionStream>()
    private val perUser = ConcurrentHashMap<UUID, AtomicInteger>()
    private val disconnectedSince = ConcurrentHashMap<UUID, Long>()

    init {
        val period = properties.heartbeatIntervalSeconds
        scheduler.scheduleAtFixedRate({ tick() }, period, period, TimeUnit.SECONDS)
    }

    /** Streams currently open on this instance — the `datapipelines.sse.streams.active` gauge. */
    val activeStreams: Int get() = streams.size

    /** Open streams for [userId]. */
    fun activeStreamsFor(userId: UUID): Int = perUser[userId]?.get() ?: 0

    /** The configured `datapipelines.sse.max-streams-per-user` (§12.1), exposed for error messages. */
    val maxStreamsPerUser: Int get() = properties.maxStreamsPerUser

    /**
     * True when [userId] is already at `datapipelines.sse.max-streams-per-user` (§12.1).
     *
     * The check and the later [register] are not atomic (gate C, F11): two concurrent execute
     * calls can both pass the check, so the cap is a **bounded overshoot**, not a hard guarantee
     * — at most a handful of extra streams per user under a concurrent burst, each still bounded
     * by the execution timeout. Closing the race would need a lock around check+register for a
     * limit that is a round number by design; not worth it in v1.
     */
    fun atStreamLimit(userId: UUID): Boolean = activeStreamsFor(userId) >= properties.maxStreamsPerUser

    /**
     * Registers a stream.
     *
     * The emitter timeout is `0` — never time out. The execution's own
     * `datapipelines.executor.execution-timeout-seconds` bounds the run, and a container timeout
     * firing first would close the stream *without* a terminal event, which §6.5 forbids.
     */
    fun open(
        executionId: UUID,
        userId: UUID,
    ): ExecutionStream {
        val stream = ExecutionStream(executionId, userId, SseEmitter(NEVER_TIMEOUT), mapper, nowMillis)
        register(stream)
        return stream
    }

    /**
     * Registers an already-constructed stream — the execute path, where the `SseEmitter` must
     * exist before the executor mints the execution id it is keyed by.
     */
    fun register(stream: ExecutionStream) {
        streams[stream.executionId] = stream
        perUser.computeIfAbsent(stream.userId) { AtomicInteger() }.incrementAndGet()
        stream.emitter.onCompletion { close(stream.executionId) }
        stream.emitter.onTimeout { stream.markDisconnected() }
        stream.emitter.onError { stream.markDisconnected() }
    }

    /**
     * Post-construction hook invoked with each stream at [close] — the
     * `datapipelines.sse.stream.duration` observation (observability §4). Set once by the metrics
     * wiring; a no-op until then so the registry is testable without a meter registry.
     */
    @Volatile var onStreamClosed: (ExecutionStream) -> Unit = {}

    /** The live stream for [executionId], or null when nothing on this instance is watching. */
    fun find(executionId: UUID): ExecutionStream? = streams[executionId]

    /** Closes and deregisters the stream for [executionId]. Idempotent. */
    fun close(executionId: UUID) {
        val stream = streams.remove(executionId) ?: return
        disconnectedSince.remove(executionId)
        perUser[stream.userId]?.decrementAndGet()
        runCatching { onStreamClosed(stream) }
            .onFailure { log.warn("stream-close metrics hook failed for {}", executionId, it) }
        stream.close()
    }

    /**
     * One heartbeat/grace tick.
     *
     * Never allowed to throw: a scheduled task that throws is silently cancelled by
     * `ScheduledExecutorService`, which would stop every heartbeat and every grace timer on the
     * instance without a single log line.
     *
     * Heartbeats go only to **quiet** streams: §6.6's keepalive exists "when no events have been
     * emitted", so a stream that wrote anything since the previous tick is skipped.
     */
    @Suppress("TooGenericExceptionCaught") // any escape must not kill the only timer — see KDoc
    internal fun tick() {
        try {
            streams.values.forEach { stream ->
                if (stream.isConnected && stream.lastActivityAtMillis.get() <= lastTickAtMillis) stream.heartbeat()
                if (!stream.isConnected) noticeDisconnect(stream)
            }
            lastTickAtMillis = nowMillis()
        } catch (e: RuntimeException) {
            log.warn("SSE heartbeat tick failed; the timer survives so streams keep their keepalive.", e)
        }
    }

    /** The previous tick's instant; the quiet-stream cutoff for §6.6. */
    @Volatile private var lastTickAtMillis: Long = 0L

    private fun noticeDisconnect(stream: ExecutionStream) {
        if (stream.isTerminal) {
            // §6.8 — a disconnect after the terminal event costs nothing.
            close(stream.executionId)
            return
        }
        val firstNoticed = disconnectedSince.putIfAbsent(stream.executionId, nowMillis())
        if (firstNoticed == null) {
            log.info(
                "SSE client for execution {} disconnected before a terminal event; cancelling in {}s unless it finishes.",
                stream.executionId,
                properties.disconnectGraceSeconds,
            )
            return
        }
        val elapsedSeconds = (nowMillis() - firstNoticed) / MILLIS_PER_SECOND
        if (elapsedSeconds < properties.disconnectGraceSeconds) return

        log.info("Disconnect grace elapsed for execution {}; cancelling (client_disconnect).", stream.executionId)
        // The flag is written even when this instance is not the one running the execution, so a
        // multi-instance deployment still reaches the owning instance (dag-executor §8.3.1).
        cancellationService.cancel(stream.executionId, AbortReason.CLIENT_DISCONNECT)
        close(stream.executionId)
    }

    /** Shutdown drain: stop the timer and complete every open stream. */
    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        streams.keys.toList().forEach(::close)
    }

    private companion object {
        const val NEVER_TIMEOUT = 0L
        const val MILLIS_PER_SECOND = 1000L

        fun defaultScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "dp-sse-heartbeat").apply { isDaemon = true }
            }
    }
}
