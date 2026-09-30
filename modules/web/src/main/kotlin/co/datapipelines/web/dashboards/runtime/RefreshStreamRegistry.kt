package co.datapipelines.web.dashboards.runtime

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.executor.RefreshAbortFlags
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.sse.ExecutionStreamRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Every live REFRESH stream on this instance, plus the two timers rest-api §6 requires (the implementation spec's
 * §8.3) — its OWN registry, never [ExecutionStreamRegistry], whose grace would cancel an execution that has no id.
 *
 * ## Heartbeat (§6.6)
 * One scheduled task ticks every `datapipelines.sse.heartbeat-interval-seconds` and writes the `: heartbeat`
 * comment to each stream that wrote nothing since the previous tick.
 *
 * ## Disconnect grace (§6.8) — "a disconnected client aborts its refresh"
 * A stream whose client is gone before `refresh_completed` is stamped when first noticed; once
 * `datapipelines.sse.disconnect-grace-seconds` has elapsed the REFRESH is aborted on this instance — through
 * [RefreshAbortSignal.triggerLocal], the same path an explicit abort takes, so the refresh ends ABORTED and its
 * sources are cancelled. A disconnect after `refresh_completed` costs nothing. A stream CUT by the authority guard is
 * not a disconnect: the refresh runs to its end (P4's first half).
 *
 * ## Per-user stream cap (§12.1)
 * `datapipelines.sse.max-streams-per-user` counts execution AND refresh streams together — a refresh stream is an SSE
 * stream like an execution's, so the one cap holds them both.
 */
class RefreshStreamRegistry(
    private val properties: SseProperties,
    private val executionStreams: ExecutionStreamRegistry,
    private val abort: RefreshAbortSignal,
    private val mapper: ObjectMapper,
    private val scheduler: ScheduledExecutorService = defaultScheduler(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(RefreshStreamRegistry::class.java)
    private val streams = ConcurrentHashMap<UUID, RefreshStream>()
    private val disconnectedSince = ConcurrentHashMap<UUID, Long>()

    @Volatile private var lastTickAtMillis: Long = 0L

    init {
        val period = properties.heartbeatIntervalSeconds
        scheduler.scheduleAtFixedRate({ tick() }, period, period, TimeUnit.SECONDS)
    }

    /** Refresh streams open on this instance. */
    val activeStreams: Int get() = streams.size

    fun activeStreamsFor(userId: UUID): Int = streams.values.count { it.userId == userId }

    /**
     * True when [userId] is at the shared per-user cap. Check-then-open is not atomic — the same bounded overshoot
     * [ExecutionStreamRegistry.atStreamLimit] documents.
     */
    fun atStreamLimit(userId: UUID): Boolean =
        executionStreams.activeStreamsFor(userId) + activeStreamsFor(userId) >= properties.maxStreamsPerUser

    val maxStreamsPerUser: Int get() = properties.maxStreamsPerUser

    /** Opens and registers the stream for [refreshId]; the emitter never times out (the refresh's deadline bounds the run). */
    fun open(
        refreshId: UUID,
        subscriber: AuthenticatedPrincipal,
        authority: RefreshStreamAuthority,
    ): RefreshStream {
        val stream = RefreshStream(refreshId, subscriber.userId, SseEmitter(NEVER_TIMEOUT), mapper, subscriber, authority, nowMillis)
        streams[refreshId] = stream
        // Completion, timeout and error ALL mean "the client is not reading" and nothing more: the stream stays in the map
        // for the tick to decide (the refresh is terminal → drop it; not → the grace clock runs). Dropping the stream at
        // completion would hide a vanished client from the very timer that exists to abort its refresh.
        stream.emitter.onCompletion { stream.markDisconnected() }
        stream.emitter.onTimeout { stream.markDisconnected() }
        stream.emitter.onError { stream.markDisconnected() }
        return stream
    }

    fun find(refreshId: UUID): RefreshStream? = streams[refreshId]

    /** Closes and deregisters the stream. Idempotent. */
    fun close(refreshId: UUID) {
        val stream = streams.remove(refreshId) ?: return
        disconnectedSince.remove(refreshId)
        stream.close()
    }

    @Suppress("TooGenericExceptionCaught") // a scheduled task that throws is cancelled silently — the timer must survive
    internal fun tick() {
        try {
            streams.values.forEach(::tickOne)
            lastTickAtMillis = nowMillis()
        } catch (e: RuntimeException) {
            log.warn("event=dashboard.refresh_tick_failed error={}", e.javaClass.simpleName)
        }
    }

    private fun tickOne(stream: RefreshStream) {
        if (stream.isTerminal) {
            // `refresh_completed` was written (or attempted): a disconnect after it costs nothing (§6.8).
            if (!stream.isConnected) close(stream.refreshId)
            return
        }
        if (stream.isConnected && stream.lastActivityAtMillis.get() <= lastTickAtMillis) stream.heartbeat()
        if (!stream.isConnected && !stream.isRevoked) noticeDisconnect(stream)
    }

    private fun noticeDisconnect(stream: RefreshStream) {
        val firstNoticed = disconnectedSince.putIfAbsent(stream.refreshId, nowMillis())
        if (firstNoticed == null) {
            log.info(
                "event=dashboard.refresh_client_gone refresh_id={} grace_seconds={}",
                stream.refreshId,
                properties.disconnectGraceSeconds,
            )
            return
        }
        if ((nowMillis() - firstNoticed) / MILLIS_PER_SECOND < properties.disconnectGraceSeconds) return
        log.info("event=dashboard.refresh_grace_elapsed refresh_id={} action=abort", stream.refreshId)
        abort.triggerLocal(stream.refreshId)
        close(stream.refreshId)
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
            Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "dp-refresh-heartbeat").apply { isDaemon = true } }
    }
}

/**
 * The [co.datapipelines.application.dashboards.AbortSignal] of this instance: an abort asked for HERE (the disconnect
 * grace, the abort route landing on the owning instance) is a set membership; an abort asked for on ANOTHER instance
 * is the Redis flag ([RefreshAbortFlags]), read at most once per [remotePollMillis] per refresh so the engine's cheap
 * poll never becomes a Redis read per tick.
 *
 * It also knows which refreshes THIS instance runs ([register] / [forget]): the abort route uses [owns] to abort
 * locally and immediately when it can, and to leave the flag for the owner when it cannot.
 */
class RefreshAbortSignal(
    private val flags: RefreshAbortFlags,
    private val remotePollMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : co.datapipelines.application.dashboards.AbortSignal {
    private val local = ConcurrentHashMap.newKeySet<UUID>()
    private val running = ConcurrentHashMap.newKeySet<UUID>()
    private val lastRemoteRead = ConcurrentHashMap<UUID, Long>()

    fun register(refreshId: UUID) {
        running.add(refreshId)
    }

    /** Owner's cleanup at the refresh's end: the local mark, the read cache and the Redis flag. */
    fun forget(refreshId: UUID) {
        running.remove(refreshId)
        local.remove(refreshId)
        lastRemoteRead.remove(refreshId)
        flags.clear(refreshId)
    }

    /** True while this instance runs [refreshId]. */
    fun owns(refreshId: UUID): Boolean = refreshId in running

    /** Aborts [refreshId] on this instance, immediately. */
    fun triggerLocal(refreshId: UUID) {
        local.add(refreshId)
    }

    override fun requested(refreshId: UUID): Boolean {
        if (refreshId in local) return true
        val now = nowMillis()
        val last = lastRemoteRead[refreshId]
        if (last != null && now - last < remotePollMillis) return false
        lastRemoteRead[refreshId] = now
        return flags.isRequested(refreshId)
    }
}
