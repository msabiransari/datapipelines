package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.csrf.MissingCsrfTokenException
import org.springframework.stereotype.Component

/**
 * Maps Spring Security access-denied outcomes to the §13.7 envelope.
 *
 * A CSRF failure is reported as `auth.csrf.invalid` (403) with `details.reason` =
 * `missing` | `mismatch` (auth.md §9) — **never** as `auth.scope.insufficient`
 * (AUTH-SEC-1): the whole [CsrfException] hierarchy is matched, not just the two
 * concrete subclasses, so a new Spring CSRF exception type cannot silently fall
 * through to the scope branch.
 *
 * `missing` vs `mismatch` is decided by what the request actually presented rather
 * than by which exception type Spring chose: Spring raises
 * [MissingCsrfTokenException] only when the *repository* held no token, so a request
 * that sent the `dp_csrf` cookie but no `DP-CSRF-Token` header arrives as an
 * `InvalidCsrfTokenException` even though the caller sent no token at all.
 *
 * Anything else is [AccessDeniedWithoutScopeException] — the §13.7 code with honest,
 * empty details. Scope denials that DO know their requirement come from
 * [ScopeInterceptor], which raises [ScopeInsufficientException] with the real
 * `required`/`held` pair; this handler never invents one (security NEW-7).
 */
@Component
class AuthAccessDeniedHandler(
    private val errorWriter: AuthErrorWriter,
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
                "CSRF rejected reason={} method={} path={} remote={}",
                reason,
                request.method,
                request.requestURI,
                request.remoteAddr,
            )
            errorWriter.write(request, response, CsrfInvalidException(reason))
            return
        }
        log.info(
            "Authorization denied method={} path={} remote={} cause={}",
            request.method,
            request.requestURI,
            request.remoteAddr,
            accessDeniedException.javaClass.simpleName,
        )
        errorWriter.write(request, response, AccessDeniedWithoutScopeException())
    }

    private fun csrfReason(
        request: HttpServletRequest,
        exception: CsrfException,
    ): String = if (exception is MissingCsrfTokenException || !presentedToken(request)) MISSING else MISMATCH

    /** True when the request carried a CSRF token at all (header per D10, or form field). */
    private fun presentedToken(request: HttpServletRequest): Boolean =
        !request.getHeader(SecurityConfig.CSRF_HEADER).isNullOrBlank() ||
            !request.getParameter(CSRF_PARAMETER).isNullOrBlank()

    private companion object {
        const val MISSING = "missing"
        const val MISMATCH = "mismatch"
        const val CSRF_PARAMETER = "_csrf"
    }
}
