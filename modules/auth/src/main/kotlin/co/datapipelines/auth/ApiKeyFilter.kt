package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
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
 */
@Component
class ApiKeyFilter(
    private val apiKeyService: ApiKeyService,
    private val apiKeyRepository: ApiKeyRepository,
    private val auditLogger: AuditLogger,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(ApiKeyFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val credential = ApiKeyCredential.extract(request)
        if (credential != null && SecurityContextHolder.getContext().authentication == null) {
            authenticate(credential, request)
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(
        credential: String,
        request: HttpServletRequest,
    ) {
        try {
            val principal = apiKeyService.validate(credential)
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.scopes.map { SimpleGrantedAuthority("SCOPE_${it.wire}") },
                )
            touchUsage(principal.keyId, request)
            if (ThreadLocalRandom.current().nextInt(USED_AUDIT_SAMPLE) == 0) {
                auditLogger.log(event = "auth.api_key.used", userId = principal.userId, keyId = principal.keyId)
            }
        } catch (e: AuthException) {
            request.setAttribute(AuthAttributes.AUTH_ERROR, e)
            if (ApiKeyCredential.hasValidShape(credential)) {
                auditLogger.log(
                    event = "auth.api_key.rejected",
                    sourceIp = request.remoteAddr,
                    details = mapOf("code" to e.code),
                )
                log.info("DP-API-Key rejected code={} path={} remote={}", e.code, request.requestURI, request.remoteAddr)
            } else {
                // Shape-rejected: not an `auth.api_key.rejected` event, and not a DB write.
                log.debug("Malformed DP-API-Key discarded path={} remote={}", request.requestURI, request.remoteAddr)
            }
        }
    }

    private fun touchUsage(
        keyId: String?,
        request: HttpServletRequest,
    ) {
        if (keyId == null) return
        try {
            apiKeyRepository.touchUsage(keyId, request.remoteAddr, request.getHeader("User-Agent"))
        } catch (e: DataAccessException) {
            log.warn("api_keys usage stamp failed key_id={} cause={}", keyId, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val USED_AUDIT_SAMPLE = 100
    }
}
