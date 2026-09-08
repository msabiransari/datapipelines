package co.datapipelines.mcp

import co.datapipelines.auth.ApiKeyMissingException
import co.datapipelines.auth.AuthAttributes
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.AuthException
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Turns the authenticated request into an MCP calling context, or refuses it (mcp-server.md §4).
 *
 * ## There is no second validation path
 *
 * `/mcp` is API-key-only, and the key was already validated by `auth`'s `ApiKeyFilter` on the
 * Spring Security chain — the single [Auth §7.3](../../../../../../../docs/auth.md) path, with the
 * 60-second revocation/liveness re-check that makes a revoked key stop working within ~1 minute
 * on `/mcp` exactly as on REST. This filter **reads** the resulting principal; it never parses a
 * header or verifies a secret itself, so the §13 checklist item "no second, laxer code path for
 * the Bearer form" holds by construction (both carriers are `ApiKeyCredential.extract`'s job).
 *
 * ## Session JWTs are rejected
 *
 * A principal whose [AuthenticatedPrincipal.authMethod] is not [AuthMethod.API_KEY] is refused
 * with `auth.api_key.missing` — a valid browser session cannot call a tool (§4.1, §13). The code
 * is the *missing-credential* one deliberately: from `/mcp`'s point of view no API key was
 * presented, which is precisely §4.2's first case.
 *
 * ## Failure shape
 *
 * `401` with the REST §4.2 error envelope, written by auth's own [AuthErrorWriter] so an MCP
 * transport rejection is byte-identical to the REST one. When auth's filter stashed the specific
 * rejection ([AuthAttributes.AUTH_ERROR] — invalid, expired, deactivated owner) that exact code is
 * emitted rather than the generic missing-credential code.
 */
class McpAuthFilter(
    private val errorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(McpAuthFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = principal()
        if (principal == null || principal.authMethod != AuthMethod.API_KEY) {
            reject(request, response, principal)
            return
        }
        // §7.7 — a SCOPELESS kind (endpoint, server) authorises a surface `/mcp` is not on: an
        // endpoint key gets published endpoints and the cursor of executions it started, a server
        // key gets the promotion route family. `/mcp` is a SERVLET, so `ScopeInterceptor`'s
        // central confinement (which only sees MVC handlers) never runs here — the refusal has to
        // be made again, at this filter. Without it such a key could reach `tools/list` and
        // enumerate the surface: it could call nothing (a scopeless key fails every tool's scope
        // check) but it could READ the tool catalogue, which is more than either kind authorises.
        // Found by probing the live stack, not by any test (074); kept and widened in 091.
        if (principal.isEndpointKey || principal.isServerKey) {
            rejectConfinedKind(request, response, principal)
            return
        }
        val correlationId = correlationId(request)
        request.setAttribute(McpTransportKeys.PRINCIPAL, principal)
        request.setAttribute(McpTransportKeys.CORRELATION_ID, correlationId)
        // REST §3.4: the correlation id is echoed on every response, so an agent can always quote
        // an id to an operator — the same id the tool result carries in `_meta` (§6.3).
        response.setHeader(AuthErrorWriter.CORRELATION_HEADER, correlationId.toString())
        filterChain.doFilter(request, response)
    }

    /** §7.7 — `/mcp` is on no confined kind's surface; refused with the catalogued code. */
    private fun rejectConfinedKind(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedPrincipal,
    ) {
        val endpointKind = principal.isEndpointKey
        log.info(
            "Rejected a {}-kind key on /mcp (key={}): /mcp is not on its surface",
            if (endpointKind) "endpoint" else "server",
            principal.keyId,
        )
        errorWriter.write(
            request = request,
            response = response,
            status = HTTP_FORBIDDEN,
            code = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
            message =
                if (endpointKind) {
                    "An endpoint key may only call published endpoints and read the results of executions it started."
                } else {
                    "A server key may only be presented as DP-Promotion-Key on the promotion routes of a receiving deployment."
                },
            userMessage = "This kind of API key can't be used here.",
            details = mapOf("reason" to if (endpointKind) "endpoint_key_off_surface" else "server_key_off_surface"),
        )
    }

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedPrincipal?,
    ) {
        val stashed = request.getAttribute(AuthAttributes.AUTH_ERROR) as? AuthException
        val error = stashed ?: ApiKeyMissingException()
        if (principal != null) {
            log.info("Rejected {} on /mcp: session credentials are not accepted there", principal.authMethod)
        }
        errorWriter.write(request, response, error)
    }

    /**
     * The request's correlation id: the inbound `DP-Correlation-Id` when it is a UUID, otherwise a
     * fresh one. A malformed inbound value is replaced rather than propagated — the id is echoed
     * into every tool result and into logs, and unvalidated caller text does not belong in either.
     */
    private fun correlationId(request: HttpServletRequest): UUID {
        val header = request.getHeader(AuthErrorWriter.CORRELATION_HEADER)?.trim().orEmpty()
        return runCatching { UUID.fromString(header) }.getOrElse { UUID.randomUUID() }
    }

    private companion object {
        private const val HTTP_FORBIDDEN = 403
    }
}
