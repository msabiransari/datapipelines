package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Matches the published-endpoint subtree, for the CSRF exemption (#1037; auth.md §8.4) — the one
 * PATH-scoped exception to "CSRF follows the credential, not the path", and the reason that rule
 * says "follows the credential": on this subtree NO cookie-authenticated request exists for the
 * double-submit to protect.
 *
 * The published subtree is a machine surface by its own §5.2 first rule: the serve handler
 * refuses a browser session (`401 auth.session.required`) before anything is resolved, so a
 * cookie never authorizes anything there — the only credential that can serve is an API key,
 * which [ApiKeyCredentialMatcher] already exempts on credential grounds. Without this matcher a
 * session-cookie POST that the handler is about to refuse anyway would first meet the CSRF
 * filter, answering `403 auth.csrf.invalid` where the method era's contract (#1037
 * published-mutation-apis §4.4) requires the `401` key gate to come BEFORE CSRF processing: the
 * caller must learn "present a key", not "present a token you were never given a way to use".
 *
 * Scope is [ScopeInterceptor.isPublishedEndpointPath] — `/api/<category>/…` with the reserved
 * categories (`v[0-9]+`, `api`) excluded — the same predicate that confines an endpoint key's
 * reach, pinned to the handler mapping's own pattern by `EndpointCategoryRuleTest` in `web`. The
 * product's own `/api/v1/…` routes keep the double-submit everywhere (§8.4's rule unchanged
 * there); `CsrfFilter` only consults this exemption for its guarded verbs, so a published GET
 * was never in scope to begin with.
 */
class PublishedEndpointRouteMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean = ScopeInterceptor.isPublishedEndpointPath(request.appPath())
}
