package co.datapipelines.web.parameters.stream

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.parameters.ParameterEvaluationEvent
import co.datapipelines.parameters.ParameterEvaluationObserver
import co.datapipelines.web.sse.StreamVerdict
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One client's observed-evaluation stream (the parameter-set workspace spec §4.2) —
 * [co.datapipelines.web.dashboards.runtime.RefreshStream]'s twin, with its own identity: the client-minted
 * `evaluation_id`. It IS the evaluator's [ParameterEvaluationObserver]: every engine event is projected
 * ([ParameterEvaluationEventProjection]) and written.
 *
 * ## Framing
 * `event:` name, `id:` monotonic PER STREAM (starting at 1, assigned under the send lock so the id order is the write
 * order), `data:` JSON — and a `: heartbeat` comment while the stream is quiet ([ParameterEvaluationStreamRegistry]
 * ticks it). The terminal frame (`evaluation_completed` / `evaluation_failed`) ends the stream WHETHER OR NOT it was
 * delivered; an `ABORTED` end writes no frame and ends it too.
 *
 * ## The subscriber is re-judged before every write (spec §4.4)
 * [ParameterEvaluationStreamAuthority] answers NOW, not at open: the session's expiry, the user's liveness, the opening
 * workspace by id and `parameter_set.evaluate`. A refusal ends the stream at that write (a final `: revoked` comment,
 * nothing of the write served); the EVALUATION keeps running to its end — a revocation cuts the reading, never the
 * running.
 *
 * ## A dropped client
 * A servlet container reports a vanished client only on a failed write, so [emit] treating `IOException` as "gone" IS
 * the detection; the registry's grace then decides (the owner's §11.7 ruling: past the grace, the evaluation is aborted).
 */
class ParameterEvaluationStream(
    val evaluationId: UUID,
    val parameterSetId: UUID,
    val version: Int,
    val userId: UUID,
    val emitter: SseEmitter,
    private val mapper: ObjectMapper,
    private val subscriber: AuthenticatedPrincipal,
    private val authority: ParameterEvaluationStreamAuthority,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ParameterEvaluationObserver {
    private val log = LoggerFactory.getLogger(ParameterEvaluationStream::class.java)
    private val nextId = AtomicInteger(1)
    private val connected = AtomicBoolean(true)
    private val terminal = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)

    val isConnected: Boolean get() = connected.get()

    /** True once the evaluation ended (its terminal frame written or attempted) — a disconnect after it costs nothing. */
    val isTerminal: Boolean get() = terminal.get()

    /** True once the authority guard cut the stream. The evaluation is NOT aborted for it. */
    val isRevoked: Boolean get() = revoked.get()

    /** The last instant anything — frame or heartbeat — was written. */
    val lastActivityAtMillis = AtomicLong(nowMillis())

    override fun on(event: ParameterEvaluationEvent) {
        emit(event)
    }

    /** Projects and writes [event]; false when nothing was delivered (no frame, client gone, or the authority refused). */
    @Synchronized
    fun emit(event: ParameterEvaluationEvent): Boolean {
        val frame = ParameterEvaluationEventProjection.frame(event, evaluationId, parameterSetId, version)
        val sent = frame != null && connected.get() && currentlyAllowed() && write(frame)
        if (event is ParameterEvaluationEvent.Ended) {
            terminal.set(true)
            log.info("event=parameter.evaluation_ended evaluation_id={} outcome={} delivered={}", evaluationId, event.outcome, sent)
            close()
        }
        return sent
    }

    private fun write(frame: EvaluationFrame): Boolean =
        try {
            emitter.send(
                SseEmitter
                    .event()
                    .name(frame.name)
                    .id(nextId.getAndIncrement().toString())
                    .data(mapper.writeValueAsString(frame.data), MediaType.APPLICATION_JSON),
            )
            lastActivityAtMillis.set(nowMillis())
            true
        } catch (e: IOException) {
            log.debug("event=parameter.evaluation_stream_gone evaluation_id={} while={}", evaluationId, frame.name, e)
            connected.set(false)
            false
        } catch (e: IllegalStateException) {
            log.debug("event=parameter.evaluation_stream_closed evaluation_id={} while={}", evaluationId, frame.name, e)
            connected.set(false)
            false
        }

    /** The `: heartbeat` keepalive comment, judged like an event; false when the client is gone or the authority refused. */
    @Synchronized
    fun heartbeat(): Boolean {
        if (!connected.get() || !currentlyAllowed()) return false
        return try {
            emitter.send(SseEmitter.event().comment(HEARTBEAT))
            lastActivityAtMillis.set(nowMillis())
            true
        } catch (e: IOException) {
            connected.set(false)
            log.debug("event=parameter.evaluation_stream_gone evaluation_id={} while=heartbeat", evaluationId, e)
            false
        } catch (e: IllegalStateException) {
            connected.set(false)
            log.debug("event=parameter.evaluation_stream_gone evaluation_id={} while=heartbeat state=completed", evaluationId, e)
            false
        }
    }

    fun markDisconnected() {
        connected.set(false)
    }

    private fun currentlyAllowed(): Boolean {
        if (revoked.get()) return false
        val verdict = authority.verdict(subscriber)
        if (verdict == StreamVerdict.ALLOWED) return true
        revoked.set(true)
        log.info("event=parameter.evaluation_stream_cut evaluation_id={} verdict={}", evaluationId, verdict)
        runCatching { emitter.send(SseEmitter.event().comment(REVOKED)) }
        close()
        return false
    }

    /** Completes the emitter exactly once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected.set(false)
        runCatching { emitter.complete() }
    }

    private companion object {
        const val HEARTBEAT = "heartbeat"
        const val REVOKED = "revoked"
    }
}
