package co.datapipelines.web.api

import co.datapipelines.auth.ApiKeyMissingException
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Capability
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRecord
import org.springframework.security.core.context.SecurityContextHolder

/**
 * The authenticated principal for the request being served.
 *
 * The security chain and [co.datapipelines.auth.ScopeInterceptor] have already run by the time a
 * controller executes, so a scoped handler without a principal is unreachable through the real
 * pipeline — but "unreachable" is not a type, so the absent case throws the same
 * missing-credential error the filter chain would have raised rather than an NPE.
 */
fun currentPrincipal(): AuthenticatedPrincipal =
    SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        ?: throw ApiKeyMissingException()

/**
 * Execution **ownership** (rest-api §7.2, §10.2, §10.4 — carry-forward #2).
 *
 * A record is visible to its owner and to any `admin`. `dag`'s
 * [co.datapipelines.executor.ExecutionCancellationService] deliberately performs no owner check —
 * authorization is the surface's job — so every execution-scoped handler must apply this before
 * acting. A non-visible execution is then reported as *not found* (never 403), so a caller cannot
 * distinguish "another user's execution" from "no such execution" — `mcp-server`'s
 * `ExecutionRecord.visibleTo` applies the same rule and this mirrors it.
 */
fun ExecutionRecord.visibleTo(principal: AuthenticatedPrincipal): Boolean =
    when {
        // §7.7 — an endpoint key is NOT its owner. `triggeredBy` is a user id, so owner-equality
        // would let a key bound to /lending read a /payroll key's results merely by sharing an
        // owner. An endpoint key's visibility is decided by [visibleToEndpointKey] instead, which
        // needs a repository and therefore cannot live in this pure extension.
        principal.isEndpointKey -> false

        // RBAC round 1: the bypass was `Scope.satisfies(scopes, ADMIN)` — a scope no key may
        // hold any more (O-2) and no session ever has (D-R1), so it had become dead code and
        // "an admin sees the workspace's runs" quietly stopped being true. The authority moved
        // to the capability axis, where it now lives: a WORKSPACE ADMIN sees every run in
        // their workspace, a super admin sees any. Everyone else sees their own.
        //
        // Deliberately NOT widened to every member, though the design's §1 table would allow
        // it ("read … executions ✓" for a viewer): that is a behaviour change this round was
        // not asked to make, and the conservative reading keeps the rule the deployment has.
        else -> triggeredBy == principal.userId || principal.isWorkspaceAdmin
    }
