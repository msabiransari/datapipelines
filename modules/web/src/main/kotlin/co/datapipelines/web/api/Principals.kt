package co.datapipelines.web.api

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.ApiKeyMissingException
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionTrigger
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
 * The current principal's promoter-lens view (roles design §3.1, 178) — what this request may
 * SEE of the workspace's pipelines and templates, resolved ONCE per handler and passed to
 * every `PipelineService` / `TemplateService` read it makes. For every role but the promoter
 * this is [LensedView.EVERYTHING] and costs nothing. Read the principal first when the
 * handler needs it too, and pass it: `lens.viewFor(principal)`.
 */
fun PromoterLens.current(): LensedView = viewFor(currentPrincipal())

/**
 * Execution **ownership** (D11, 2026-09-20; rest-api §7.2, §10.2, §10.4).
 *
 * A record is visible to the person whose OWN run it is and to any workspace admin. `dag`'s
 * [co.datapipelines.executor.ExecutionCancellationService] deliberately performs no owner check —
 * authorization is the surface's job — so every execution-scoped handler must apply this before
 * acting. A non-visible execution is then reported as *not found* (never 403), so a caller cannot
 * distinguish "another user's execution" from "no such execution" — `mcp-server`'s
 * `ExecutionRecord.visibleTo` applies the same rule and this mirrors it.
 */
fun ExecutionRecord.visibleTo(principal: AuthenticatedPrincipal): Boolean =
    when {
        // §7.7 / #215 A.5 — an endpoint key acts as its OWN identity, so the runs it started carry
        // that identity in `executed_by` and are its own — no other key and no person shares it.
        // Runs a key served BEFORE V34 carry its creator instead; `ExecutionVisibility` reads the
        // serve audit for those, which needs a repository and cannot live in this pure extension.
        principal.isEndpointKey -> {
            isOwnRunOf(principal.userId, byKeyIdentity = true)
        }

        // D11: "own" is `ExecutionRecord.isOwnRunOf` — executed by this user AND not through an
        // endpoint key (an endpoint's run belongs to the endpoint, and lists for admins only).
        // `execution.read_all` (#215 — the workspace admin's, the super admin's) sees every run
        // in the workspace. The promoter is refused `execution.read` by the matrix before this.
        // #9 R3: a SCHEDULED run is attributed to its schedule and visible to every member whose
        // role reaches the read the route declared (`execution.read`, or `execution.result.read`
        // on the result) — the record was already read in the caller's workspace, so the
        // workspace is the boundary. Visibility only: cancelling one still needs `cancel_all`.
        else -> {
            isOwnRunOf(principal.userId) ||
                principal.holds(Permission.EXECUTION_READ_ALL) ||
                (triggeredVia == ExecutionTrigger.SCHEDULE && principal.holds(Permission.EXECUTION_READ))
        }
    }

/**
 * [visibleTo]'s twin for CANCEL (#215): the caller's own run, or any run with
 * `execution.cancel_all`. An endpoint key cancels nothing — it is confined to the serve surface
 * and its own results. A run the caller may not cancel answers not-found, like a read.
 */
fun ExecutionRecord.cancellableBy(principal: AuthenticatedPrincipal): Boolean =
    !principal.isEndpointKey && (isOwnRunOf(principal.userId) || principal.holds(Permission.EXECUTION_CANCEL_ALL))
