package co.datapipelines.web.sse

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One client's live execution stream (rest-api.md §6).
 *
 * Owns the `SseEmitter` and the connected/terminal state the disconnect-grace rule (§6.8) is
 * driven from. The monotonic `event_id` counter (§6.3/§6.7) is deliberately NOT here: it is
 * monotonic **per execution**, events keep flowing while no consumer is attached, and the durable
 * record's UNIQUE (execution_id, event_id) constraint agrees — so the counter lives on
 * [WebEventEmitter], the one object whose lifetime is the execution's.
 *
 * ## Disconnect detection
 * A servlet container reports a dropped client only when a write fails, so `send` treating an
 * `IOException` as "client gone" **is** the detection: the first event or heartbeat after the drop
 * discovers it. The heartbeat therefore has a second job beyond keeping proxies open — it bounds
 * how long a disconnect can go unnoticed on a stream with no event flow, which is exactly the
 * long-running-query case §6.6 is written for.
 *
 * ## Terminal
 * [markTerminal] is set by the emitter when a terminal event is projected. §6.8 cancels only an
 * execution that has **not** reached a terminal event: "a disconnect after the terminal event
 * costs nothing".
 */
class ExecutionStream(
    val executionId: UUID,
    val userId: UUID,
    val emitter: SseEmitter,
    private val mapper: ObjectMapper,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(ExecutionStream::class.java)
    private val connected = AtomicBoolean(true)
    private val terminal = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** False once a write has failed or the container reported completion/timeout. */
    val isConnected: Boolean get() = connected.get()

    /** True once a terminal event (§6.5 step 3) has been written. */
    val isTerminal: Boolean get() = terminal.get()

    /**
     * The `close_reason` of the `datapipelines.sse.stream.duration` timer (observability §4):
     * `completed` / `failed` / `aborted` from the terminal event, `client_disconnect` when the
     * client left first. Null while nothing terminal has been written.
     */
    @Volatile var terminalKind: String? = null
        private set

    /**
     * The last instant anything — event or heartbeat — was written. §6.6 sends heartbeats only
     * "when no events have been emitted": the registry's tick reads this to stay quiet on a busy
     * stream.
     */
    val lastActivityAtMillis = AtomicLong(nowMillis())

    /**
     * Writes one SSE event.
     *
     * @return false when the client is gone. Never throws for a dropped client: the emitter must
     *   not propagate "nobody is listening" into the executor (dag-executor §10).
     */
    fun send(
        eventName: String,
        eventId: Int,
        payload: Map<String, Any?>,
    ): Boolean {
        if (!connected.get()) return false
        return try {
            emitter.send(
                SseEmitter
                    .event()
                    .name(eventName)
                    .id(eventId.toString())
                    .data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON),
            )
            lastActivityAtMillis.set(nowMillis())
            true
        } catch (e: IOException) {
            // The one honest signal a servlet container gives for a vanished client.
            log.debug("SSE client for execution {} disconnected while sending {}", executionId, eventName, e)
            connected.set(false)
            false
        } catch (e: IllegalStateException) {
            // The emitter was already completed (terminal event raced a container timeout).
            log.debug("SSE emitter for execution {} already closed when sending {}", executionId, eventName, e)
            connected.set(false)
            false
        }
    }

    /**
     * Writes the §6.6 keepalive comment.
     *
     * An SSE comment (`: heartbeat`) is ignored by `EventSource` and by a fetch-based consumer; it
     * exists solely to keep the TCP connection alive through a load balancer's idle timeout.
     */
    fun heartbeat(): Boolean {
        if (!connected.get()) return false
        return try {
            emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT))
            lastActivityAtMillis.set(nowMillis())
            true
        } catch (e: IOException) {
            log.debug("SSE client for execution {} disconnected on heartbeat", executionId, e)
            connected.set(false)
            false
        } catch (e: IllegalStateException) {
            // The emitter was already completed — the stream is as good as disconnected.
            log.debug("SSE emitter for execution {} already closed on heartbeat", executionId, e)
            connected.set(false)
            false
        }
    }

    /** Records that the terminal event has been written (§6.5), with its close-reason kind. */
    fun markTerminal(kind: String) {
        terminal.set(true)
        // The first terminal kind wins: `data_ready` trailing `pipeline_completed` must not
        // overwrite `completed`, and nothing follows the other two terminal events.
        if (terminalKind == null && kind in TERMINAL_KINDS) terminalKind = kind
    }

    /** Records the stream's open instant and kind for the duration metric. */
    val openedAtMillis: Long = nowMillis()

    /** Records that the container reported the connection gone (completion / timeout / error). */
    fun markDisconnected() {
        connected.set(false)
    }

    /** Completes the emitter exactly once; further calls are no-ops. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected.set(false)
        runCatching { emitter.complete() }
            .onFailure { log.debug("SSE emitter for execution {} failed to complete cleanly", executionId, it) }
    }

    private companion object {
        const val HEARTBEAT_COMMENT = "heartbeat"

        /** The terminal event names that map to a metric close-reason (observability §4). */
        val TERMINAL_KINDS = setOf("pipeline_completed", "pipeline_failed", "execution_aborted")
    }
}
