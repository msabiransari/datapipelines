package co.datapipelines.web.ui

import co.datapipelines.auth.AuthErrorCodes
import co.datapipelines.auth.AuthException
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.api.CorrelationId
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView

/**
 * UI error rendering (ui-screens.md §5.1). Two delivery shapes for the same failure:
 *
 * - an ordinary request gets the whole error PAGE (`error/403`, `error/500`);
 * - an htmx request gets the refusal as a TOAST: htmx never swaps a 4xx/5xx body, so a
 *   whole document would reach the user as nothing at all. The response keeps its real
 *   status, carries the toast markup retargeted at the #toast stack
 *   (`HX-Retarget: #toast` + `HX-Reswap: beforeend`), and `toast.js`'s `bridgeErrors`
 *   is what lets htmx swap it. The branch keys on the `HX-Request` header, not the path:
 *   partial routes are not the only htmx callers.
 *
 * The toast follows §5.1's error rendering: the user message as the headline, the error
 * `code` and the `correlation_id` in the small text — a 500 the user cannot quote is
 * barely better than silence. The exception message never leaves the log.
 *
 * ## Why the `@Order` is load-bearing — and why the status-carrying handlers exist
 * Spring evaluates `@ControllerAdvice` beans in `@Order` then registration order, and the
 * FIRST matching handler wins. `ApiExceptionHandler` is global and unordered and sorts
 * ahead of this advice by package name (`...web.api` before `...web.ui`), so without an
 * explicit order EVERY exception from a UI controller was answered with the REST JSON
 * envelope — this advice was dead code and the `error/403` / `error/500` pages never
 * rendered (verified against the running demo stack, 034). The scope restriction
 * (`basePackageClasses` — the `web.ui` package holds no `@RestController`) keeps the
 * REST surface on `ApiExceptionHandler` exactly as before.
 *
 * Winning first means the `Throwable` backstop would also steal exceptions that carry
 * their OWN status — `ResponseStatusException` (the disabled-local-login 404 the
 * OIDC-only regression suite pins), `AuthException`, and `DatapipelinesException`'s
 * catalog codes. Those three are handled explicitly here with the SAME status rules the
 * REST advice applies; only a genuinely unexpected failure reaches `onUnexpected`.
 */
@ControllerAdvice(basePackageClasses = [UiController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class UiExceptionHandler {
    private val log = LoggerFactory.getLogger(UiExceptionHandler::class.java)

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun onAccessDenied(
        @Suppress("UNUSED_PARAMETER") error: org.springframework.security.access.AccessDeniedException,
        request: HttpServletRequest,
    ): Any {
        log.info("403 {} {}: access denied", request.method, request.requestURI)
        if (isHtmx(request)) {
            return toast(
                HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action.",
                AuthErrorCodes.SCOPE_INSUFFICIENT,
            )
        }
        return errorModel("error/403", HttpStatus.FORBIDDEN)
    }

    /**
     * `auth` exceptions carry their own status and a user-safe message (auth.md §9) —
     * declared before [onDomain] because [AuthException] IS a [DatapipelinesException]
     * and within one advice the most specific handler wins.
     */
    @ExceptionHandler(AuthException::class)
    fun onAuth(
        error: AuthException,
        request: HttpServletRequest,
    ): Any {
        val status = HttpStatus.resolve(error.status) ?: HttpStatus.INTERNAL_SERVER_ERROR
        logAt(status, request, error)
        return refusal(status, error.userMessage, error.code, request)
    }

    /** Domain exceptions carry a §13 code; the catalog supplies the status — the REST advice's rule. */
    @ExceptionHandler(DatapipelinesException::class)
    fun onDomain(
        error: DatapipelinesException,
        request: HttpServletRequest,
    ): Any {
        val status = ApiErrorCatalog.statusFor(error.code)
        logAt(status, request, error)
        // The headline is deliberately generic: a domain exception's `message` is written
        // for the log and the REST envelope, not proven safe for a toast (B3).
        return refusal(status, "That action couldn't be completed.", error.code, request)
    }

    /** A controller's deliberate refusal carrying its own status (e.g. the 404 a disabled local login answers). */
    @ExceptionHandler(ResponseStatusException::class)
    fun onResponseStatus(
        error: ResponseStatusException,
        request: HttpServletRequest,
    ): Any {
        val status = HttpStatus.resolve(error.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        logAt(status, request, error)
        // `reason` is controller-authored refusal text, never a stack frame.
        return refusal(status, error.reason ?: status.reasonPhrase, INTERNAL_STAND_IN_CODE, request)
    }

    @ExceptionHandler(Throwable::class)
    fun onUnexpected(
        error: Throwable,
        request: HttpServletRequest,
    ): Any {
        log.error("500 {} {}: unhandled {}", request.method, request.requestURI, error::class.java.name, error)
        if (isHtmx(request)) {
            return toast(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Quote the correlation id when reporting this.",
                INTERNAL_STAND_IN_CODE,
            )
        }
        val model = ModelAndView("error/500", HttpStatus.INTERNAL_SERVER_ERROR)
        model.addObject("correlationId", CorrelationId.current())
        return model
    }

    /** One refusal, both delivery shapes: toast for htmx, the matching error page otherwise. */
    private fun refusal(
        status: HttpStatus,
        userMessage: String,
        code: String,
        request: HttpServletRequest,
    ): Any {
        if (isHtmx(request)) return toast(status, userMessage, code)
        return when (status) {
            HttpStatus.NOT_FOUND -> {
                errorModel("error/404", status)
            }

            HttpStatus.FORBIDDEN -> {
                errorModel("error/403", status)
            }

            else -> {
                ModelAndView("error/500", status).apply {
                    addObject("correlationId", CorrelationId.current())
                }
            }
        }
    }

    private fun logAt(
        status: HttpStatus,
        request: HttpServletRequest,
        error: Throwable?,
    ) {
        if (status.is5xxServerError) {
            log.error("{} {} {}: {}", status.value(), request.method, request.requestURI, error?.message, error)
        } else {
            log.debug("{} {} {}: {}", status.value(), request.method, request.requestURI, error?.message)
        }
    }

    private fun isHtmx(request: HttpServletRequest): Boolean = request.getHeader(HX_REQUEST_HEADER) != null

    /** Shape C (§5.1), same delivery as the partial controllers' refusal toasts. */
    private fun toast(
        status: HttpStatus,
        userMessage: String,
        code: String,
    ): ResponseEntity<String> {
        val correlationId = CorrelationId.current()
        return ResponseEntity
            .status(status)
            .header("HX-Retarget", "#toast")
            .header("HX-Reswap", "beforeend")
            .body(
                ToastHtml.oob(
                    "danger",
                    ToastHtml.esc(userMessage),
                    ToastHtml.esc("$code · correlation $correlationId"),
                ),
            )
    }

    private fun errorModel(
        viewName: String,
        status: HttpStatus,
    ): ModelAndView {
        val model = ModelAndView(viewName, status)
        model.addObject("activeTheme", "saas")
        return model
    }

    private companion object {
        const val HX_REQUEST_HEADER = "HX-Request"

        /**
         * Mirrors `ApiExceptionHandler.INTERNAL_STAND_IN_CODE` (private there): §13 catalogues
         * no generic internal-error code, and `pipeline.execution.aborted` is the catalog's
         * only "we broke, not you" entry. Keep the two in sync until the catalog grows one.
         */
        val INTERNAL_STAND_IN_CODE = PipelineErrorCodes.Execution.ABORTED
    }
}
