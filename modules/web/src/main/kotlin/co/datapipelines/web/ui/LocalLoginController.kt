package co.datapipelines.web.ui

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.ClientAddressResolver
import co.datapipelines.auth.JwtService
import co.datapipelines.auth.LocalAuthService
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.sessionCookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/**
 * Local username/password login (auth.md §5A) — the form half of the login screen.
 *
 * Deliberately NOT under a scope-governed path: this endpoint is the authentication
 * ceremony itself, so there is no principal for [co.datapipelines.auth.ScopeInterceptor]
 * to check yet — exactly like the OIDC callback it mirrors. It is public (§8.3),
 * metered per IP by [co.datapipelines.auth.LoginRateLimitFilter] (`/login` prefix),
 * and protected by the same `dp_csrf` double-submit as every cookie-context POST.
 *
 * On success it mints the SAME session the OIDC success handler mints — same
 * [JwtService.issue], same workspace resolution (design §5.1/§7, `auto-per-user`
 * provisioning included), same `auth.login.success` audit event — so both flows
 * converge on one principal. Every mutating endpoint that comes AFTER login lives
 * on a governed path; this one only creates the session.
 */
@Controller
class LocalLoginController(
    private val localAuthService: LocalAuthService,
    private val jwtService: JwtService,
    private val auditLogger: AuditLogger,
    private val authProperties: AuthProperties,
    private val workspaceService: WorkspaceService,
    private val clientAddressResolver: ClientAddressResolver,
) {
    @PostMapping("/login")
    fun login(
        @RequestParam email: String,
        @RequestParam password: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        if (!authProperties.local.enabled) {
            // The method does not exist in this deployment — a 404, not a login error.
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Local password login is not enabled")
        }
        return when (
            val result =
                localAuthService.authenticate(
                    email = email,
                    password = password,
                    sourceIp = clientAddressResolver.clientAddressOf(request),
                    userAgent = request.getHeader("User-Agent"),
                )
        ) {
            is LocalAuthService.LocalLoginResult.Success -> {
                // §4.2 step 4 (design §5.1/§7): identical to the OIDC success handler.
                val activeWorkspace = workspaceService.workspaceForLogin(result.user, result.user.email)
                response.addCookie(sessionCookie(jwtService.issue(result.user, activeWorkspace?.name), authProperties))
                auditLogger.log(
                    event = "auth.login.success",
                    userId = result.user.id,
                    sourceIp = clientAddressResolver.clientAddressOf(request),
                    userAgent = request.getHeader("User-Agent"),
                    details =
                        mapOf(
                            "email" to result.user.email,
                            "provider" to UserService.LOCAL_PROVIDER,
                            "active_workspace" to activeWorkspace?.name,
                        ),
                )
                // 033: the signed-in landing page moved to /dashboard (`/` is the public site).
                "redirect:/dashboard"
            }

            // Unknown email, OIDC-only account and wrong password all land here — the
            // banner must not tell them apart (§5A.5).
            is LocalAuthService.LocalLoginResult.BadCredentials -> {
                "redirect:/login?error=credentials"
            }

            is LocalAuthService.LocalLoginResult.Locked -> {
                "redirect:/login?error=locked"
            }

            is LocalAuthService.LocalLoginResult.Inactive -> {
                "redirect:/login?error=inactive"
            }
        }
    }
}
