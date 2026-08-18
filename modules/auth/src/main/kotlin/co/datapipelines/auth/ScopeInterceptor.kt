package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Enforces the §7.6 scope matrix on controller handlers (auth.md §8.1, filter step 7)
 * via [RequiredScope]. The check is hierarchical ([Scope.satisfies]) — the annotated
 * operation's [ScopeMatrix.RestOperation.minScope] is the minimum.
 *
 * ## Default deny (AUTH-SEC-9)
 * A handler under the `/api` prefix or on `/mcp` that carries **no** `@RequiredScope` — on the
 * method or on its controller class — is **denied**, not served. Forgetting the
 * annotation is the realistic failure mode (a new endpoint, a hurried refactor), and
 * fail-open there means an unscoped endpoint ships silently. The denial is logged at
 * ERROR naming the handler, because it is a wiring bug an operator must see, not a
 * user error.
 *
 * Outside those prefixes an unannotated handler is allowed through: the login page,
 * static assets and health probes are gated by the filter chain's `permitAll`
 * list (§8.3), not by the scope matrix.
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
        val operation = declaredOperation(handler)
        if (operation == null) {
            if (!isScopeGoverned(request)) return true
            return denyUnannotated(request, response, handler)
        }

        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return when {
            // Authentication is enforced upstream; reaching a scoped handler without a
            // principal means no credentials were presented (§9 auth.api_key.missing).
            principal == null -> {
                errorWriter.write(request, response, ApiKeyMissingException())
                false
            }

            !Scope.satisfies(principal.scopes, operation.minScope) -> {
                denyScope(request, response, principal, operation)
            }

            else -> {
                true
            }
        }
    }

    /** Audits `auth.scope.denied` (§10.1) and writes 403 `auth.scope.insufficient`. */
    private fun denyScope(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedPrincipal,
        operation: ScopeMatrix.RestOperation,
    ): Boolean {
        val required = operation.minScope
        auditLogger.log(
            event = "auth.scope.denied",
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "operation" to operation.name,
                    "required" to required.wire,
                    "held" to principal.scopes.map { it.wire },
                ),
        )
        errorWriter.write(request, response, ScopeInsufficientException(required, principal.scopes))
        return false
    }

    /** The §7.6 operation this handler declares — method annotation first, class-level fallback. */
    private fun declaredOperation(handler: HandlerMethod): ScopeMatrix.RestOperation? =
        handler.getMethodAnnotation(RequiredScope::class.java)?.value
            ?: handler.beanType.getAnnotation(RequiredScope::class.java)?.value

    /** True on the surfaces the §7.6 matrix governs: the REST API and the MCP endpoint. */
    private fun isScopeGoverned(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith(API_PREFIX) || request.requestURI == ApiKeyCredential.MCP_PATH

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

    private companion object {
        const val API_PREFIX = "/api/"
        const val HTTP_FORBIDDEN = 403
    }
}
