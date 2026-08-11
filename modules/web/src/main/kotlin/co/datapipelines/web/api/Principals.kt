package co.datapipelines.web.api

import co.datapipelines.auth.ApiKeyMissingException
import co.datapipelines.auth.AuthenticatedPrincipal
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
    triggeredBy == principal.userId || Scope.satisfies(principal.scopes, Scope.ADMIN)
