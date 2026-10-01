package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.RefreshEvent
import co.datapipelines.application.dashboards.RefreshEvents
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
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
 * One client's live refresh stream (the implementation spec's §8.3) — [co.datapipelines.web.sse.ExecutionStream]'s
 * twin, with its own identity: the refresh id, NEVER an execution id (the execution registry's disconnect grace would
 * cancel an execution that does not exist).
 *
 * ## Framing
 * `event:` name, `id:` monotonic PER REFRESH (starting at 1, assigned under the send lock so the id order is the write
 * order), `data:` JSON — and a `: heartbeat` comment while the stream is quiet ([RefreshStreamRegistry] ticks it).
 *
 * ## The subscriber is re-judged before every write (P4)
 * As an execution stream re-asks the reader's standing, this asks [RefreshStreamAuthority]: the session's expiry, the
 * user's liveness, the workspace membership and `dashboard.execute` — NOW, not at open. A refusal ends the stream at
 * that write (a final `: revoked` comment, nothing of the write served); the REFRESH keeps running to its end, because
 * a revocation cuts the reading, never the running (P4's first half) — the terminal row is still written.
 *
 * ## A dropped client
 * A servlet container reports a vanished client only on a failed write, so [emit] treating `IOException` as "gone"
 * IS the detection. The engine never sees it: [emit] answers false and the refresh carries on until the registry's
 * grace elapses.
 */
class RefreshStream(
    val refreshId: UUID,
    val userId: UUID,
    val emitter: SseEmitter,
    private val mapper: ObjectMapper,
    private val subscriber: AuthenticatedPrincipal,
    private val authority: RefreshStreamAuthority,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RefreshEvents {
    private val log = LoggerFactory.getLogger(RefreshStream::class.java)
    private val nextId = AtomicInteger(1)
    private val connected = AtomicBoolean(true)
    private val terminal = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)

    val isConnected: Boolean get() = connected.get()

    /** True once `refresh_completed` has been written — a disconnect after it costs nothing. */
    val isTerminal: Boolean get() = terminal.get()

    /** True once the authority guard cut the stream. The refresh is NOT aborted for it. */
    val isRevoked: Boolean get() = revoked.get()

    /** The last instant anything — frame or heartbeat — was written. */
    val lastActivityAtMillis = AtomicLong(nowMillis())

    @Synchronized
    override fun emit(event: RefreshEvent): Boolean {
        val access = if (connected.get()) currentAccess() else null
        val sent = access != null && write(event, access.executionRead)
        // The last frame ends the stream WHETHER OR NOT it was delivered: a cut or vanished subscriber still leaves a
        // terminal stream for the registry to drop, and a refresh that already ended is never "aborted" by a late grace.
        if (event is RefreshEvent.Completed) {
            terminal.set(true)
            close()
        }
        return sent
    }

    private fun write(
        event: RefreshEvent,
        executionRead: Boolean,
    ): Boolean =
        try {
            emitter.send(
                SseEmitter
                    .event()
                    .name(event.eventName)
                    .id(nextId.getAndIncrement().toString())
                    .data(mapper.writeValueAsString(project(event, executionRead)), MediaType.APPLICATION_JSON),
            )
            lastActivityAtMillis.set(nowMillis())
            true
        } catch (e: IOException) {
            log.debug("event=dashboard.refresh_stream_gone refresh_id={} while={}", refreshId, event.eventName, e)
            connected.set(false)
            false
        } catch (e: IllegalStateException) {
            log.debug("event=dashboard.refresh_stream_closed refresh_id={} while={}", refreshId, event.eventName, e)
            connected.set(false)
            false
        }

    /** The `: heartbeat` keepalive comment; false when the client is gone or the authority refused. */
    @Synchronized
    fun heartbeat(): Boolean {
        if (!connected.get() || currentAccess() == null) return false
        return try {
            emitter.send(SseEmitter.event().comment(HEARTBEAT))
            lastActivityAtMillis.set(nowMillis())
            true
        } catch (e: IOException) {
            connected.set(false)
            log.debug("event=dashboard.refresh_stream_gone refresh_id={} while=heartbeat", refreshId, e)
            false
        } catch (e: IllegalStateException) {
            connected.set(false)
            log.debug("event=dashboard.refresh_stream_gone refresh_id={} while=heartbeat state=completed", refreshId, e)
            false
        }
    }

    fun markDisconnected() {
        connected.set(false)
    }

    private fun currentAccess(): RefreshStreamAccess? {
        if (revoked.get()) return null
        val access = authority.access(subscriber)
        if (access.verdict == StreamVerdict.ALLOWED) return access
        revoked.set(true)
        log.info("event=dashboard.refresh_stream_cut refresh_id={} verdict={}", refreshId, access.verdict)
        runCatching { emitter.send(SseEmitter.event().comment(REVOKED)) }
        close()
        return null
    }

    private fun project(
        event: RefreshEvent,
        executionRead: Boolean,
    ): Map<String, Any?> = projectRefreshPayload(event, executionRead)

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

/** One current-principal verdict shared by stream admission and its execution-link projection. */
data class RefreshStreamAccess(
    val verdict: StreamVerdict,
    val executionRead: Boolean,
)

/** Project execution links from a freshly resolved principal decision without changing the engine event. */
internal fun projectRefreshPayload(
    event: RefreshEvent,
    executionRead: Boolean,
): Map<String, Any?> {
    val payload = event.payload()
    val sourceEvent = event is RefreshEvent.SourceStarted || event is RefreshEvent.SourceCompleted || event is RefreshEvent.SourceFailed
    return if (!executionRead && sourceEvent) payload - "execution_id" else payload
}

/**
 * The re-judgement a refresh stream makes of its subscriber before every write (P4, #230's twin for the runtime):
 * the same predicate a NEW runtime request would meet, asked of the subscriber's CURRENT standing, in this order and
 * failing closed:
 *
 * 0. the validated session token's expiry (#263) — before any store read;
 * 1. liveness ([PrincipalLiveness], through the auth cache's TTL);
 * 2. the live identity (`is_admin`), and the workspace the stream OPENED in, strictly re-resolved through the
 *    membership cache and matched by immutable workspace id;
 * 3. `dashboard.execute` — the route's own declared permission, asked of the refreshed principal.
 *
 * It does not re-run the promoter lens: the dashboard was served at open and the refresh runs to its end; what a
 * revocation cuts is the READING. An answer that cannot be established (a store error behind an expired cache entry)
 * is a refusal, never a reason to keep serving.
 */
class RefreshStreamAuthority(
    private val liveness: PrincipalLiveness,
    private val workspaces: WorkspaceService,
    private val users: UserService,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(RefreshStreamAuthority::class.java)

    /** Never throws: an unsettleable answer is [StreamVerdict.REVOKED] with no execution link. */
    fun access(subscriber: AuthenticatedPrincipal): RefreshStreamAccess =
        try {
            when {
                subscriber.sessionExpiresAtMillis?.let { nowMillis() >= it } == true -> denied(StreamVerdict.EXPIRED)
                else -> judge(subscriber)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("event=dashboard.refresh_stream_authority_failed error={}", e.javaClass.simpleName)
            denied(StreamVerdict.REVOKED)
        }

    /** Kept as a verdict-only view for callers that do not project event payloads. */
    fun verdict(subscriber: AuthenticatedPrincipal): StreamVerdict = access(subscriber).verdict

    private fun judge(subscriber: AuthenticatedPrincipal): RefreshStreamAccess {
        if (liveness.check(subscriber.userId, pin = null) != null) return denied(StreamVerdict.REVOKED)
        val user = users.snapshot(subscriber.userId) ?: return denied(StreamVerdict.REVOKED)
        val live = subscriber.copy(superAdmin = user.isAdmin)
        val context =
            subscriber.workspace?.let { openingWorkspace ->
                workspaces.contextFor(live, openingWorkspace.name)?.takeIf { it.id == openingWorkspace.id }
            } ?: return denied(StreamVerdict.REVOKED)
        val current = live.copy(workspace = context)
        return if (current.holds(Permission.DASHBOARD_EXECUTE)) {
            RefreshStreamAccess(StreamVerdict.ALLOWED, current.holds(Permission.EXECUTION_READ))
        } else {
            denied(StreamVerdict.REVOKED)
        }
    }

    private fun denied(verdict: StreamVerdict) = RefreshStreamAccess(verdict, executionRead = false)
}
