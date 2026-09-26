package co.datapipelines.web.sse

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.web.api.visibleTo
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * #230 (security-assurance record P4, owner ruling 2026-09-24) — the ONE re-judgement an open
 * execution stream makes of its subscriber before every write (event or heartbeat): an open
 * stream is cut at its next write after revocation, while the EXECUTION itself keeps its
 * authority to completion (P4's first half — a revocation cuts the reading, never the running).
 *
 * The predicate is the one a NEW `GET /api/v1/executions/{id}/events` request would run, asked
 * of the subscriber's CURRENT standing rather than the snapshot that opened the stream:
 *
 *  1. **Liveness** — [PrincipalLiveness], through [co.datapipelines.auth.AuthCache]'s TTL, the
 *     same predicate the credential filter runs before any request is served (D15). A session
 *     pins no workspace, so the judged half is the user half — exactly as
 *     `JwtAuthenticationFilter` judges it (a stream subscriber is always a session: keys reach
 *     no SSE route, §7.7; should that ever change, the pin must join this check).
 *  2. **The live identity** — `users.is_admin` is read per request by the filter, never from a
 *     frozen flag, so the re-judgement reads the same snapshot through the same cache.
 *  3. **The context a new request would resolve now** — [WorkspaceService.resolveForSession],
 *     the request path's own resolution (claim, fallback, D-R8's super-admin branch), through
 *     the membership cache. A removal, a deactivation or a demotion is therefore seen on the
 *     instance that performed it at once, and elsewhere within one TTL (§11.4) — the bound P4
 *     accepts.
 *  4. **The route's own two checks** — the declared permission (`execution.read`, the role
 *     matrix) and [visibleTo] (own run / `execution.read_all`), asked of the refreshed
 *     principal. The record is looked up in the workspace the CURRENT context resolves: a
 *     removed member's new request resolves elsewhere and finds nothing — the stream answers
 *     the same way. The one false-miss is the live stream's first moments (the `execution_started`
 *     event precedes the RUNNING row's durable write), covered in [judge].
 *
 * Every read goes through the existing caches; a tick costs no database read beyond the TTL's.
 * An answer that cannot be established (a store error behind an expired cache entry) is NO:
 * fail closed — an open stream never keeps serving on an unknown answer.
 */
class ExecutionStreamAuthority(
    private val executions: ExecutionRepository,
    private val liveness: PrincipalLiveness,
    private val workspaces: WorkspaceService,
    private val users: UserService,
) {
    private val log = LoggerFactory.getLogger(ExecutionStreamAuthority::class.java)

    /**
     * True while [subscriber] may still read [executionId] — the same verdict a fresh request
     * carrying their credential would get. Never throws: an answer that cannot be established
     * (a store failure behind an expired cache entry) IS a refusal, and says so in the log.
     */
    fun mayRead(
        subscriber: AuthenticatedPrincipal,
        executionId: UUID,
    ): Boolean =
        try {
            judge(subscriber, executionId)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("Authority re-judge for execution {} failed; answering REFUSED (fail closed).", executionId, e)
            false
        }

    private fun judge(
        subscriber: AuthenticatedPrincipal,
        executionId: UUID,
    ): Boolean {
        if (liveness.check(subscriber.userId, pin = null) != null) return false
        val user = users.snapshot(subscriber.userId) ?: return false
        // The identity refresh FIRST: `is_admin` is a per-request read on the request path
        // (D-R1), and the resolution below branches on it (D-R8), so a demoted super admin must
        // not resolve a super-admin context their next request could not get.
        val liveIdentity = subscriber.copy(superAdmin = user.isAdmin)
        val context = workspaces.resolveForSession(liveIdentity, liveIdentity.workspaceName) ?: return false
        val current = liveIdentity.copy(workspace = context)
        if (!current.holds(Permission.EXECUTION_READ)) return false
        val record = executions.findById(context.id, executionId)
        if (record != null) return record.visibleTo(current)
        // A missing record is a refusal UNLESS this is the live stream's first moments: the
        // `execution_started` event is written BEFORE the RUNNING row is durably created
        // (dag-executor §10's emitter policy persists after the send), so the row a new request
        // would read does not exist yet. The revocation-relevant halves have already passed —
        // liveness, the permission, and the context still resolving to the SAME workspace the
        // route judged at open (a removed member resolves elsewhere or nowhere, and never gets
        // here) — so the open-time visibility verdict stands for this window.
        return context.id == subscriber.workspace?.id
    }
}
