package co.datapipelines.web.api

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.ApiKeyMissingException
import co.datapipelines.auth.AuthenticatedPrincipal
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
        // §7.7 — an endpoint key is NOT its owner. `executedBy` is a user id, so owner-equality
        // would let a key bound to /lending read a /payroll key's results merely by sharing an
        // owner. An endpoint key's visibility is decided by [visibleToEndpointKey] instead, which
        // needs a repository and therefore cannot live in this pure extension.
        principal.isEndpointKey -> false

        // D11: "own" is `ExecutionRecord.isOwnRunOf` — executed by this user AND not through an
        // endpoint key (an endpoint's run belongs to the endpoint, and lists for admins only). A
        // WORKSPACE ADMIN sees every run in their workspace, a super admin any. The promoter is
        // refused the execution reads by the matrix before this is asked.
        else -> isOwnRunOf(principal.userId) || principal.isWorkspaceAdmin
    }
