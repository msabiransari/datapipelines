package co.datapipelines.web.parameters.stream

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.web.sse.StreamVerdict
import org.slf4j.LoggerFactory

/**
 * The re-judgement an observed-evaluation stream makes of its subscriber before EVERY write, the heartbeat included
 * (the parameter-set workspace spec §4.4, #343's rule) — [co.datapipelines.web.dashboards.runtime.RefreshStreamAuthority]'s
 * twin: the same predicate a NEW observed request would meet, asked of the subscriber's CURRENT standing, in this order
 * and failing closed:
 *
 * 0. the validated session token's expiry (#263) — before any store read;
 * 1. liveness ([PrincipalLiveness], through the auth cache's TTL);
 * 2. the live identity (`is_admin`), and the workspace the stream OPENED in, strictly re-resolved through the
 *    membership cache and matched by immutable workspace id — membership elsewhere preserves nothing;
 * 3. `parameter_set.evaluate` — the route's own declared permission, asked of the refreshed principal.
 *
 * The route is session-only: no key kind reaches it (an MCP key reaches no REST route, and the `dashboard` key only its
 * own runtime routes), so a key principal is refused here outright rather than judged on a path it never takes. It
 * does not re-run the promoter lens (the set was resolved at open; what a revocation cuts is the READING — the
 * evaluation runs to its end). An answer that cannot be established is a refusal, never a reason to keep serving.
 */
class ParameterEvaluationStreamAuthority(
    private val liveness: PrincipalLiveness,
    private val workspaces: WorkspaceService,
    private val users: UserService,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(ParameterEvaluationStreamAuthority::class.java)

    /** Never throws: an unsettleable answer is [StreamVerdict.REVOKED]. */
    fun verdict(subscriber: AuthenticatedPrincipal): StreamVerdict =
        try {
            when {
                subscriber.sessionExpiresAtMillis?.let { nowMillis() >= it } == true -> StreamVerdict.EXPIRED
                subscriber.keyId != null -> StreamVerdict.REVOKED
                else -> judge(subscriber)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("event=parameter.evaluation_stream_authority_failed error={}", e.javaClass.simpleName)
            StreamVerdict.REVOKED
        }

    @Suppress("ReturnCount") // fail-closed: every early exit is a refusal; the returns ARE the order
    private fun judge(subscriber: AuthenticatedPrincipal): StreamVerdict {
        if (liveness.check(subscriber.userId, pin = null) != null) return StreamVerdict.REVOKED
        val user = users.snapshot(subscriber.userId) ?: return StreamVerdict.REVOKED
        val live = subscriber.copy(superAdmin = user.isAdmin)
        val context =
            subscriber.workspace?.let { openingWorkspace ->
                workspaces.contextFor(live, openingWorkspace.name)?.takeIf { it.id == openingWorkspace.id }
            } ?: return StreamVerdict.REVOKED
        return if (live.copy(workspace = context).holds(Permission.PARAMETER_SET_EVALUATE)) StreamVerdict.ALLOWED else StreamVerdict.REVOKED
    }
}
