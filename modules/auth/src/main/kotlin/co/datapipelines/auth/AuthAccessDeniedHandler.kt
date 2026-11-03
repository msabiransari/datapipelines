package co.datapipelines.auth

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.csrf.MissingCsrfTokenException

/**
 * Maps Spring Security access-denied outcomes to the §13.7 envelope.
 *
 * A CSRF failure is reported as `auth.csrf.invalid` (403) with `details.reason` =
 * `missing` | `mismatch` (auth.md §9) — **never** as the generic authorization refusal
 * (AUTH-SEC-1): the whole [CsrfException] hierarchy is matched, not just the two
 * concrete subclasses, so a new Spring CSRF exception type cannot silently fall
 * through to the other branch.
 *
 * `missing` vs `mismatch` is decided by what the request actually presented rather
 * than by which exception type Spring chose: Spring raises
 * [MissingCsrfTokenException] only when the *repository* held no token, so a request
 * that sent the `dp_csrf` cookie but no `DP-CSRF-Token` header arrives as an
 * `InvalidCsrfTokenException` even though the caller sent no token at all.
 *
 * ## The browser shape gets the error PAGE, not the envelope (#782)
 * A refusal of a plain browser form navigation (Accept admits text/html, no htmx
 * partial swap, not a machine surface) forwards to Boot's error rendering with the
 * refusal stashed in [AuthAttributes.CSRF_REFUSAL], so the person sees the error/403
 * page — the fixed user message, a Reload action, the correlation id — instead of raw
 * JSON. The synchronous forward renders while the correlation filter's MDC is still
 * active, so the page quotes the same id the log line carries. The refusal and the
 * error status are request attributes; the page uses only the fixed user message.
 *
 * Boot explicitly configures the security filter's dispatcher types, including ERROR
 * and FORWARD by default. Each filter also has its own dispatch rules. The error
 * render targets the fixed `/error` path; the HTTP boundary and integration guards
 * independently assert that a refused logout clears no session and sends no redirect.
 *
 * Anything else is [AccessDeniedUndeclaredException] — `auth.permission.undeclared` with honest,
 * empty details. Refusals that DO know their requirement come from [ScopeInterceptor] through
 * [ScopeMatrix.allowed], with the real `required`/`held` pair; this handler never invents one
 * (security NEW-7).
 */
class AuthAccessDeniedHandler(
    private val errorWriter: AuthErrorWriter,
    private val clientAddressResolver: ClientAddressResolver,
) : AccessDeniedHandler {
    private val log = LoggerFactory.getLogger(AuthAccessDeniedHandler::class.java)

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        if (accessDeniedException is CsrfException) {
            val reason = csrfReason(request, accessDeniedException)
            log.info(
                "CSRF rejected reason={} method={} path={} client={}",
                reason,
                request.method,
                request.requestURI,
                clientAddressResolver.clientAddressOf(request),
            )
            val refusal = CsrfInvalidException(reason)
            if (rendersErrorPage(request)) {
                forwardToErrorPage(request, response, refusal)
                return
            }
            errorWriter.write(request, response, refusal)
            return
        }
        log.info(
            "Authorization denied method={} path={} client={} cause={}",
            request.method,
            request.requestURI,
            clientAddressResolver.clientAddressOf(request),
            accessDeniedException.javaClass.simpleName,
        )
        errorWriter.write(request, response, AccessDeniedUndeclaredException())
    }

    private fun csrfReason(
        request: HttpServletRequest,
        exception: CsrfException,
    ): String = if (exception is MissingCsrfTokenException || !presentedToken(request)) MISSING else MISMATCH

    /** True when the request carried a CSRF token at all (header per D10, or form field). */
    private fun presentedToken(request: HttpServletRequest): Boolean =
        !request.getHeader(SecurityConfig.CSRF_HEADER).isNullOrBlank() ||
            !request.getParameter(CSRF_PARAMETER).isNullOrBlank()

    /**
     * A page a person reads (the [ScopeInterceptor] rule): not an API/partials/MCP machine
     * surface, not an htmx partial swap, and an Accept that admits text/html. Machine surfaces
     * keep the §13.7 JSON envelope byte-for-byte; htmx keeps its own refusal semantics.
     */
    private fun rendersErrorPage(request: HttpServletRequest): Boolean =
        !isMachineSurface(request.appPath()) &&
            request.getHeader(HX_REQUEST_HEADER) == null &&
            (request.getHeader("Accept") ?: "").contains(TEXT_HTML)

    private fun isMachineSurface(path: String): Boolean =
        path.startsWith(ScopeInterceptor.API_PREFIX) ||
            path.startsWith(ScopeInterceptor.PARTIALS_PREFIX) ||
            path.startsWith(ScopeInterceptor.MCP_PREFIX)

    private fun forwardToErrorPage(
        request: HttpServletRequest,
        response: HttpServletResponse,
        refusal: CsrfInvalidException,
    ) {
        request.setAttribute(AuthAttributes.CSRF_REFUSAL, refusal)
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpServletResponse.SC_FORBIDDEN)
        response.status = HttpServletResponse.SC_FORBIDDEN
        request.getRequestDispatcher(AuthAttributes.ERROR_PAGE_PATH).forward(request, response)
    }

    private companion object {
        const val MISSING = "missing"
        const val MISMATCH = "mismatch"
        const val CSRF_PARAMETER = "_csrf"
        const val HX_REQUEST_HEADER = "HX-Request"
        const val TEXT_HTML = "text/html"
    }
}
