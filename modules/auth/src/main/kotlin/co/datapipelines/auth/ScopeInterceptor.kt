package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.server.PathContainer
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Enforces the §7.6 scope matrix on controller handlers (auth.md §8.1, filter step 7)
 * via [RequiredScope]. The check is hierarchical ([Scope.satisfies]) — the annotated
 * operation's [ScopeMatrix.RestOperation.minScope] is the minimum.
 *
 * ## Default deny (AUTH-SEC-9)
 * A handler the §8.3 allowlist does **not** make public and that carries **no**
 * `@RequiredScope` — on the method or on its controller class — is **denied**, not served.
 * Forgetting the annotation is the realistic failure mode (a new endpoint, a hurried
 * refactor), and fail-open there means an unscoped endpoint ships silently. The denial is
 * logged at ERROR naming the handler, because it is a wiring bug an operator must see, not a
 * user error.
 *
 * The rule was three prefixes until 096 §C: `/api`, `/partials` (added in the 022b fix round,
 * review F6 — a mutating partial without the floor bypassed its REST twin's scope, so a
 * `read` key could register a datasource) and `/mcp`. That left THIRTEEN authenticated UI
 * pages in neither the allowlist nor the governed set: the chain authenticated them and
 * nothing scoped them, so any `user` key reached them at the implicit read floor, and a
 * future mutation on a page route would have been unguarded (review finding F3). The rule is
 * now stated the only way that cannot leave a gap: **public, or governed.** The three old
 * prefixes are a strict subset of it — none of them appears in the allowlist — so nothing
 * that was governed before is ungoverned now.
 *
 * The public handlers are still allowed through unannotated, which was the original reason
 * for stopping at the prefixes and is preserved exactly: the login page, the static assets,
 * the marketing site, the packaged docs and the health probes are gated by the filter chain's
 * `permitAll` list ([PublicPaths]), not by the scope matrix. An ANNOTATED handler is enforced
 * on any path, public included — the allowlist governs only the default-deny.
 */
class ScopeInterceptor(
    private val errorWriter: AuthErrorWriter,
    private val auditLogger: AuditLogger,
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(ScopeInterceptor::class.java)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

        // §7.7 — the KIND confinement is decided FIRST, before the annotation is even read.
        // A scopeless kind's reach is a route family, not an operation, so the question "may
        // this credential be here at all?" is not the annotation's to answer — and asking it
        // second would leave every UNannotated handler outside the governed prefixes (which is
        // every UI page: `/dashboard`, `/settings/api-keys`, `/api-console` before it declared
        // one) as a way around the confinement for a credential that authorises none of them.
        val confined = principal?.keyKind?.takeIf { it in ApiKeyKind.SCOPELESS }
        if (principal != null && confined != null && !reachableBy(confined, request.appPath())) {
            return denyKind(request, response, principal, confined)
        }

        val operation = declaredOperation(handler)
        return when {
            // No declared operation: allowed off the governed prefixes (the login page, static
            // assets, health probes are gated by the chain's `permitAll`), default-denied on them.
            operation == null -> {
                if (isScopeGoverned(request)) denyUnannotated(request, response, handler) else true
            }

            // Authentication is enforced upstream; reaching a scoped handler without a
            // principal means no credentials were presented (§9 auth.api_key.missing).
            principal == null -> {
                errorWriter.write(request, response, ApiKeyMissingException())
                false
            }

            // §7.7 — a scopeless key carries NO scopes by design, so the matrix cannot judge it,
            // and a floor would refuse it everywhere. Its real authorization on the routes it
            // DOES reach is the path bindings (`EndpointAuthorizer`), the execution's own audit
            // trail, or — for a server key — `PromotionServerKeyFilter`, which has already run.
            confined != null -> {
                true
            }

            else ->
                when (val decision = ScopeMatrix.allowed(principal, operation, principal.workspace)) {
                    is ScopeMatrix.Decision.Allowed -> {
                        auditSuperAdminAction(request, principal, operation)
                        true
                    }
                    is ScopeMatrix.Decision.Refused -> deny(request, response, principal, operation, decision)
                }
        }
    }

    /**
     * D-R8 — a super admin acting in a workspace they hold no explicit membership in leaves a
     * row saying so. Emitted on the ALLOW path, at the one choke point every governed handler
     * passes, so no route can be added that reaches a foreign workspace unaudited (the same
     * default-deny reasoning the unannotated branch exists for).
     *
     * Reads only (`VIEW`-capability operations) are deliberately included: D-R5's whole promise
     * is that a workspace's contents are invisible from outside, and the one principal exempted
     * from that promise is the one whose reads most need to be on the record.
     */
    private fun auditSuperAdminAction(
        request: HttpServletRequest,
        principal: AuthenticatedPrincipal,
        operation: ScopeMatrix.RestOperation,
    ) {
        val context = principal.workspace ?: return
        if (!context.actingViaSuperAdmin) return
        auditLogger.log(
            event = SUPER_ADMIN_ACTING,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "operation" to operation.name,
                    "workspace" to context.name,
                    "path" to request.requestURI,
                    "method" to request.method,
                ) + AuditLogger.actingVia(context),
        )
    }

    /**
     * §7.7 — the refusal a confined kind gets off its own surface.
     *
     * Refused HERE rather than by each handler, so a new route cannot become reachable to a
     * scopeless key by forgetting a check — the same default-deny reasoning the unannotated-
     * handler branch exists for. One error code for both kinds ([ENDPOINT_KEY_KIND_REFUSED],
     * catalogued as "the key's kind is wrong for what it is doing"); the `reason` detail and
     * the message say WHICH kind, because that is the part an operator can act on.
     */
    private fun denyKind(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedPrincipal,
        kind: ApiKeyKind,
    ): Boolean {
        val reason = OFF_SURFACE_REASON.getValue(kind)
        auditLogger.log(
            event = "auth.scope.denied",
            userId = principal.userId,
            keyId = principal.keyId,
            details = mapOf("reason" to reason, "path" to request.requestURI),
        )
        errorWriter.write(
            request = request,
            response = response,
            status = HTTP_FORBIDDEN,
            code = ENDPOINT_KEY_KIND_REFUSED,
            message = OFF_SURFACE_MESSAGE.getValue(kind),
            userMessage = "This kind of API key can't be used here.",
            details = mapOf("reason" to reason),
        )
        return false
    }

    /**
     * Audits `auth.scope.denied` (§10.1) and writes the refusal the matrix decided — its
     * catalogued code, its status and its details, unmodified.
     *
     * The interceptor does not re-derive WHY: [ScopeMatrix.allowed] already distinguished the
     * credential axis (`auth.scope.insufficient`), the role axis (`auth.role_required`), a
     * key whose issuer was demoted (`auth.key_issuer_role_lost`) and an unreachable workspace
     * (`workspace.not_found`), and a second judgement here is a second place for them to drift.
     */
    private fun deny(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedPrincipal,
        operation: ScopeMatrix.RestOperation,
        decision: ScopeMatrix.Decision.Refused,
    ): Boolean {
        auditLogger.log(
            event = "auth.scope.denied",
            userId = principal.userId,
            keyId = principal.keyId,
            details = mapOf("operation" to operation.name, "code" to decision.code) + decision.details,
        )
        errorWriter.write(
            request = request,
            response = response,
            status = statusFor(decision.code),
            code = decision.code,
            message = decision.message,
            userMessage = decision.userMessage,
            details = decision.details,
        )
        return false
    }

    /**
     * The HTTP status for a matrix refusal. Only `workspace.not_found` is a 404 — that is
     * D-R5's entire point, and it must not be a 403 by accident here after every repository
     * was taught to make it a 404.
     */
    private fun statusFor(code: String): Int = if (code == WorkspaceErrorCodes.NOT_FOUND) HTTP_NOT_FOUND else HTTP_FORBIDDEN

    /** The §7.6 operation this handler declares — method annotation first, class-level fallback. */
    private fun declaredOperation(handler: HandlerMethod): ScopeMatrix.RestOperation? =
        handler.getMethodAnnotation(RequiredScope::class.java)?.value
            ?: handler.beanType.getAnnotation(RequiredScope::class.java)?.value

    /**
     * True on every surface the §7.6 matrix governs — which, since 096 §C, is **everything
     * the §8.3 allowlist does not make public**.
     *
     * It used to be three prefixes: `/api/`, `/partials/` and `/mcp`. The KDoc's stated
     * reason for stopping there was that the handlers OUTSIDE them are the ones the filter
     * chain's `permitAll` list gates — the login page, the static assets, the health probes
     * — and that reason is honoured exactly, not overridden: those handlers are still
     * allowed through, because they are still on [PublicPaths.ENTRIES]. What the prefixes
     * got wrong was everything else in that "outside" — thirteen authenticated UI pages
     * that no allowlist and no matrix row had ever heard of, which the chain authenticated
     * and then nobody scoped (review finding F3). Stating the rule as "public, or governed"
     * closes that gap and removes the approximation: a future page route cannot land in a
     * fourth URL space and be ungoverned by accident.
     *
     * The three old prefixes are a strict subset of the new rule — none of them appears in
     * the allowlist — so no request that was governed before is ungoverned now.
     */
    private fun isScopeGoverned(request: HttpServletRequest): Boolean {
        val path = request.appPath()
        return PUBLIC_PATTERNS.none { it.matches(PathContainer.parsePath(path)) }
    }

    private fun denyUnannotated(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: HandlerMethod,
    ): Boolean {
        log.error(
            "DEFAULT DENY: handler {}#{} serves {} {} without @RequiredScope. Annotate it with the " +
                "auth.md §7.6 operation it implements (ScopeMatrix.RestOperation).",
            handler.beanType.name,
            handler.method.name,
            request.method,
            request.requestURI,
        )
        errorWriter.write(
            request = request,
            response = response,
            status = HTTP_FORBIDDEN,
            code = AuthErrorCodes.SCOPE_INSUFFICIENT,
            message = "Handler declares no §7.6 operation; denied by default",
            userMessage = "You do not have permission to perform this action.",
            details = mapOf("reason" to "handler_not_annotated"),
        )
        return false
    }

    companion object {
        /**
         * The governed-prefix constants, shared with the build-time guards in `web`
         * (RequiredScopeCoverageTest, 025 C6): the runtime deny and the compile-time
         * coverage check must own ONE spelling, or they drift — the interceptor denying
         * `/partials/` while the test scanned `/partials` (broader by one character) was
         * exactly the shape a drift would leave behind.
         */
        const val API_PREFIX = "/api/"
        const val PARTIALS_PREFIX = "/partials/"

        /**
         * The §8.3 allowlist, parsed once with the SAME parser Spring Security's chain
         * matches those patterns with — so "is this handler public?" gets the same answer
         * here as it got two filters earlier, rather than a second, drifting spelling of it.
         */
        private val PUBLIC_PATTERNS: List<PathPattern> =
            PathPatternParser.defaultInstance.let { parser -> PublicPaths.PATTERNS.map(parser::parse) }

        /**
         * The published-endpoint subtree (ruling R-EP1). Engineers own everything beneath it;
         * the `/api/v1` tree stays the product's.
         */
        const val PUBLISHED_ENDPOINT_PREFIX = "/api/x/"

        /**
         * `GET /api/v1/executions/{id}` and `.../result` — the cursor of an execution an endpoint
         * key started (§7.7). Whether THIS key started THAT execution is the handler's check, not
         * this one's: here the question is only "may this credential be on this route".
         */
        val EXECUTION_READ = Regex("^/api/v1/executions/[^/]+(/result)?$")

        /** §13.14's code, spelled here because `auth` does not depend on `pipeline-contract`. */
        const val ENDPOINT_KEY_KIND_REFUSED = "endpoint.key_kind_refused"

        /**
         * The whole reach of each scopeless kind (§7.7), in ONE expression: an `endpoint` key
         * gets the published-endpoint surface plus the two execution reads that let it collect
         * a result it started; a `server` key gets the promotion receiver's route family and
         * nothing else. A `user` key is not confined by kind at all — the §7.6 matrix judges it.
         *
         * A server key normally reaches the promotion routes through `DP-Promotion-Key`, whose
         * filter runs upstream and refuses the whole prefix without a valid one; the prefix is
         * named here so this table states the kind's authority in full rather than relying on
         * "something else would have stopped it".
         */
        fun reachableBy(
            kind: ApiKeyKind,
            uri: String,
        ): Boolean =
            when (kind) {
                ApiKeyKind.USER -> true
                ApiKeyKind.ENDPOINT -> uri.startsWith(PUBLISHED_ENDPOINT_PREFIX) || EXECUTION_READ.matches(uri)
                ApiKeyKind.SERVER -> uri.startsWith(PromotionServerKeyFilter.PROMOTION_PREFIX)
            }

        /** The audit `reason` for each confined kind, off its surface. */
        private val OFF_SURFACE_REASON: Map<ApiKeyKind, String> =
            mapOf(
                ApiKeyKind.ENDPOINT to "endpoint_key_off_surface",
                ApiKeyKind.SERVER to "server_key_off_surface",
            )

        /** What the refusal tells the caller — the operator-actionable half. */
        private val OFF_SURFACE_MESSAGE: Map<ApiKeyKind, String> =
            mapOf(
                ApiKeyKind.ENDPOINT to
                    "An endpoint key may only call published endpoints and read the results of executions it started.",
                ApiKeyKind.SERVER to
                    "A server key may only be presented as DP-Promotion-Key on the promotion routes of a receiving deployment.",
            )

        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404

        /** D-R8's audit event (auth.md §10.1) — a super admin acting outside their memberships. */
        const val SUPER_ADMIN_ACTING = "auth.super_admin_acting"
    }
}
