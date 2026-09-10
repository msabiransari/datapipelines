package co.datapipelines.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the request's active workspace (design §5) onto the authenticated principal.
 * Runs after [JwtAuthenticationFilter], so both credential paths are already resolved:
 *
 * - **API key / MCP:** the key's pinned workspace IS the context ([ApiKeyService.validate]
 *   already stamped it). A `DP-Workspace` header here is a catalogued refusal —
 *   `400 workspace.header_forbidden`, written immediately, chain stopped. It is never
 *   silently ignored: a header-switchable agent key would make every leaked key a
 *   skeleton key across the user's workspaces (D3), and a quietly dropped header would
 *   train agents on a lie.
 * - **Session (JWT):** `DP-Workspace: <name>` switches per request, membership-checked
 *   through the 60s liveness cache — a value the principal cannot reach is
 *   `404 workspace.not_found` (D-R5), chain stopped, indistinguishable from an unknown name
 *   or a deactivated one, so the header can probe nothing. Without the header the JWT's
 *   stamped `active_workspace` claim is re-checked (revocation within the D13 window),
 *   falling back to the first active membership, then to none — a principal with nothing
 *   selectable proceeds without a workspace and every workspace-scoped operation is refused
 *   downstream by [ScopeMatrix.allowed]'s null-context branch.
 *
 * The resolved [WorkspaceContext] carries the caller's CAPABILITY FLAGS for that workspace
 * (RBAC design §2). Resolving them here, once, is what lets `ScopeInterceptor` and the MCP
 * dispatcher answer authorization without a second membership read that could see a
 * different answer mid-request.
 *
 * Unlike the credential filters, the hard refusals here WRITE the response and stop the
 * chain: the request IS authenticated, so stashing an error for the entry point would
 * serve the request the header tried to redirect — the exact leak the refusal exists to
 * prevent.
 */
class WorkspaceResolutionFilter(
    private val workspaceService: WorkspaceService,
    private val lastUsedWorkspaceStore: LastUsedWorkspaceStore?,
    private val authErrorWriter: AuthErrorWriter,
    private val auditLogger: AuditLogger,
    private val clientAddressResolver: ClientAddressResolver,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(WorkspaceResolutionFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? AuthenticatedPrincipal
        if (principal == null) {
            filterChain.doFilter(request, response)
            return
        }
        val switch = request.getHeader(WORKSPACE_HEADER)?.trim()?.takeIf { it.isNotEmpty() }

        when (principal.authMethod) {
            AuthMethod.API_KEY -> {
                if (switch != null) {
                    val client = clientAddressResolver.clientAddressOf(request)
                    auditLogger.log(
                        event = "auth.workspace.header_rejected",
                        userId = principal.userId,
                        keyId = principal.keyId,
                        sourceIp = client,
                    )
                    log.info("DP-Workspace rejected on API-key request path={} client={}", request.requestURI, client)
                    authErrorWriter.write(request, response, WorkspaceHeaderForbiddenException())
                    return
                }
                // The pinned context was resolved at validation — nothing to do.
            }

            AuthMethod.OIDC -> {
                val resolved =
                    if (switch != null) {
                        val context =
                            try {
                                workspaceService.resolveSwitch(principal, switch)
                            } catch (e: WorkspaceNotFoundException) {
                                log.info(
                                    "DP-Workspace switch refused user_id={} path={} client={}",
                                    principal.userId,
                                    request.requestURI,
                                    clientAddressResolver.clientAddressOf(request),
                                )
                                authErrorWriter.write(request, response, e)
                                return
                            }
                        lastUsedWorkspaceStore?.recordUsed(principal.userId, context.name)
                        context
                    } else {
                        workspaceService.resolveForSession(principal, principal.workspaceName)
                    }
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(principal.copy(workspace = resolved), null, authentication.authorities)
            }

            AuthMethod.PROMOTION -> {
                // versioning §10.6: the promotion credential pins NO workspace. A promotion
                // payload names its own target workspace and the receiver resolves it there
                // (by name, globally unique), so there is nothing for this filter to stamp —
                // and a `DP-Workspace` header would be meaningless rather than dangerous. The
                // branch is explicit so the exhaustive `when` keeps forcing this decision.
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        /** The per-request workspace switch header (design §5.1, rest-api §3.6 `DP-` prefix). */
        const val WORKSPACE_HEADER = "DP-Workspace"
    }
}
