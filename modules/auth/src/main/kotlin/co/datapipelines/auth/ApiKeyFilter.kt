package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ThreadLocalRandom

/**
 * Header → key → principal (auth.md §7.3, §8.2). Runs BEFORE [JwtAuthenticationFilter]
 * so a valid key wins over a session cookie (§8.4).
 *
 * Credential carriers ([ApiKeyCredential.extract]):
 * - `DP-API-Key: dpk_<id>.<secret>` on any API surface (D10).
 * - `Authorization: Bearer dpk_<id>.<secret>` **only on `/mcp`** (D11, §8.5) — the
 *   `dpk_` prefix routes it through the identical validation path.
 *
 * On a hard rejection the specific [AuthException] is stashed on the request so
 * [AuthEntryPoint] emits the exact §13.7 code; the chain then continues (the
 * authorization filter returns 401 for protected paths). Nothing is thrown from
 * here — the failure is a defined, logged boundary (rules/02), not a swallowed one.
 *
 * ## What gets audited (§10.1, security NEW-2)
 * `auth.api_key.rejected` records a *validation failure* — a well-formed credential
 * that was checked against the store and refused. A credential failing
 * [ApiKeyCredential.hasValidShape] never reached validation, so it is not that event;
 * auditing it would also make an unauthenticated `INSERT INTO audit_log` the cheapest
 * way to attack the database, since a garbage header costs the attacker nothing and
 * costs us a durable write. Malformed credentials are logged at DEBUG and dropped.
 * The rejection the *caller* sees is unchanged: [AuthEntryPoint] still answers
 * `auth.api_key.invalid`.
 *
 * ## The MCP key is MCP-only (#215 B2, owner ruling 2026-09-24)
 * A valid `mcp`-kind key presented anywhere but `/mcp` is REFUSED here, before any permission
 * is asked — the mirror image of `McpAuthFilter`, which refuses the other two kinds ON `/mcp`: the
 * same catalogued `endpoint.key_kind_refused` (403), `details.reason = mcp_key_off_surface`, and
 * the chain stops. An agent's key therefore reaches no REST route and no page; the web UI's own
 * calls carry the session and never meet this branch. `ScopeInterceptor`'s kind table says the
 * same for every MVC route, as the second line.
 */
class ApiKeyFilter(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
    private val auditLogger: AuditLogger,
    private val clientAddressResolver: ClientAddressResolver,
    private val errorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(ApiKeyFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val credential = ApiKeyCredential.extract(request)
        if (credential != null && SecurityContextHolder.getContext().authentication == null) {
            val principal = authenticate(credential, request)
            if (principal != null && principal.keyKind == ApiKeyKind.MCP && !onMcp(request)) {
                refuseOffSurface(request, response, principal)
                return
            }
        }
        filterChain.doFilter(request, response)
    }

    /** `/mcp` — the MCP key's whole surface (B2). */
    private fun onMcp(request: HttpServletRequest): Boolean = ScopeInterceptor.reachableBy(ApiKeyKind.MCP, request.appPath())

    /**
     * B2's refusal: 403 `endpoint.key_kind_refused`, reason `mcp_key_off_surface`, audited as
     * every kind refusal is (`auth.scope.denied`, the §10.1 event's historical name). The chain
     * stops — no later filter or handler may answer a request this credential is not allowed to
     * make at all.
     */
    private fun refuseOffSurface(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedPrincipal,
    ) {
        SecurityContextHolder.clearContext()
        val reason = ScopeInterceptor.OFF_SURFACE_REASON.getValue(ApiKeyKind.MCP)
        auditLogger.log(
            event = "auth.scope.denied",
            userId = principal.userId,
            keyId = principal.keyId,
            details = mapOf("reason" to reason, "path" to request.requestURI),
        )
        log.info("Refused an MCP key off /mcp key={} path={}", principal.keyId, request.requestURI)
        errorWriter.write(
            request = request,
            response = response,
            status = HTTP_FORBIDDEN,
            code = ScopeInterceptor.ENDPOINT_KEY_KIND_REFUSED,
            message = ScopeInterceptor.OFF_SURFACE_MESSAGE.getValue(ApiKeyKind.MCP),
            userMessage = "This kind of API key can't be used here.",
            details = mapOf("reason" to reason),
        )
    }

    /** Validates [credential] into the security context; null (and the error stashed) when it is refused. */
    private fun authenticate(
        credential: String,
        request: HttpServletRequest,
    ): AuthenticatedPrincipal? {
        try {
            val principal = apiKeyService.validate(credential)
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
            touchUsage(principal.keyId, request)
            if (ThreadLocalRandom.current().nextInt(USED_AUDIT_SAMPLE) == 0) {
                auditLogger.log(event = "auth.api_key.used", userId = principal.userId, keyId = principal.keyId)
            }
            return principal
        } catch (e: AuthException) {
            request.setAttribute(AuthAttributes.AUTH_ERROR, e)
            val client = clientAddressResolver.clientAddressOf(request)
            if (ApiKeyCredential.hasValidShape(credential)) {
                auditLogger.log(
                    event = "auth.api_key.rejected",
                    sourceIp = client,
                    details = mapOf("code" to e.code),
                )
                log.info("DP-API-Key rejected code={} path={} client={}", e.code, request.requestURI, client)
            } else {
                // Shape-rejected: not an `auth.api_key.rejected` event, and not a DB write.
                log.debug("Malformed DP-API-Key discarded path={} client={}", request.requestURI, client)
            }
            return null
        }
    }

    private fun touchUsage(
        keyId: String?,
        request: HttpServletRequest,
    ) {
        if (keyId == null) return
        try {
            apiKeyRepository.touchUsage(keyId, clientAddressResolver.clientAddressOf(request), request.getHeader("User-Agent"))
        } catch (e: DataAccessException) {
            log.warn("api_keys usage stamp failed key_id={} cause={}", keyId, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val USED_AUDIT_SAMPLE = 100
        const val HTTP_FORBIDDEN = 403
    }
}
