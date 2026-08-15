package co.datapipelines.web.api

import co.datapipelines.auth.AuthException
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * The one place a thrown failure becomes an HTTP response (rest-api.md §4.2).
 *
 * Every handler returns the same envelope `auth`'s filter-chain writer emits, so a client cannot
 * tell from the shape whether it was rejected at the filter, the interceptor or the controller.
 * The status always comes from [ApiErrorCatalog] (or, for [AuthException], from the exception's
 * own `status`) — never from the throw site, so one code cannot mean 400 here and 500 there.
 *
 * ## Logging discipline (rules/02, observability §3.2)
 * 5xx logs at ERROR with the stack trace; 4xx logs at DEBUG without one — a caller's malformed
 * request is not an operator's incident, and logging it at WARN with a trace makes the real
 * incidents unfindable. The one 5xx exception is **502 Bad Gateway**: the downstream the CALLER
 * pointed us at is broken (their own database, typically — `pipeline.execution.datasource_unreachable`),
 * which logs at WARN without a stack: not silent, not an incident either. Nothing is swallowed
 * in any branch.
 *
 * ## Spec gaps this handler stands in for
 * §13 catalogues no transport-level code ("body is not JSON", "method not allowed") and no
 * generic internal-error code. Both are reported to the orchestrator. Until they exist the status
 * is always correct and the code is the nearest catalogued one, with `details.reason` naming what
 * actually happened, so nothing is silently mislabelled.
 *
 * ## Errors are always `application/json` (gate C, B6)
 * Every response below pins `Content-Type: application/json` explicitly. That bypasses content
 * negotiation — deliberately: an SSE client sends `Accept: text/event-stream`, and a pre-stream
 * failure must still render the §4.2 envelope rather than dying as a 406.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    /** `auth` exceptions carry their own status and user message (auth.md §9). */
    @ExceptionHandler(AuthException::class)
    fun onAuth(
        error: AuthException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val status = HttpStatus.resolve(error.status) ?: HttpStatus.INTERNAL_SERVER_ERROR
        logAt(status, request, error)
        return ResponseEntity.status(status).contentType(JSON).body(
            ApiErrorResponse.of(
                code = error.code,
                message = error.message ?: error.code,
                details = error.details,
                userMessage = error.userMessage,
            ),
        )
    }

    /**
     * Everything raised by a domain module or by this one: validation failures, staging failures,
     * executor failures, [ApiException]. All carry a §13 code; the catalog supplies the status.
     */
    @ExceptionHandler(DatapipelinesException::class)
    fun onDomain(
        error: DatapipelinesException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val status = ApiErrorCatalog.statusFor(error.code)
        logAt(status, request, error)
        return ResponseEntity.status(status).contentType(JSON).body(
            ApiErrorResponse.of(
                code = error.code,
                message = error.message ?: error.code,
                details = error.details,
            ),
        )
    }

    /** A body Jackson could not read, or the wrong content type (rest-api §3.3). */
    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun onUnreadableBody(
        error: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.debug("400 {} {}: unreadable body", request.method, request.requestURI, error)
        val code = malformedBodyCodeFor(request.requestURI)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(JSON).body(
            ApiErrorResponse.of(
                code = code,
                message = "Request body could not be read: ${error.message?.take(MAX_MESSAGE_CHARS)}",
                details = mapOf(ApiErrors.REASON to ApiErrors.MALFORMED_JSON),
            ),
        )
    }

    /** A query/path parameter that is missing or the wrong type (rest-api §4.3, §7.2). */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun onBadParameter(
        error: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.debug("400 {} {}: bad parameter", request.method, request.requestURI, error)
        val name =
            when (error) {
                is MethodArgumentTypeMismatchException -> error.name
                is MissingServletRequestParameterException -> error.parameterName
                else -> null
            }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(JSON).body(
            ApiErrorResponse.of(
                code = PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                message = "Parameter '${name ?: "?"}' is missing or not of the expected type.",
                details = mapOf("parameter" to name),
            ),
        )
    }

    /** Wrong verb on a real path — 405 with the same envelope rather than the container's page. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun onMethodNotAllowed(
        error: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.debug("405 {} {}", request.method, request.requestURI)
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .contentType(JSON)
            .header("Allow", error.supportedMethods?.joinToString(", ").orEmpty())
            .body(
                ApiErrorResponse.of(
                    code = INTERNAL_STAND_IN_CODE,
                    message = "${error.method} is not supported on this endpoint.",
                    details =
                        mapOf(
                            ApiErrors.REASON to "method_not_allowed",
                            "allowed" to error.supportedMethods?.toList().orEmpty(),
                        ),
                    userMessage = "That action isn't available on this address.",
                ),
            )
    }

    /**
     * No handler for the path — 404, not the 500 backstop. §13 catalogues no "no such endpoint"
     * code (reported); the catalogued not-found code plus `details.reason` keeps it unambiguous.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun onNoResource(
        @Suppress("UNUSED_PARAMETER") error: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.debug("404 {} {}", request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(JSON).body(
            ApiErrorResponse.of(
                code = PipelineErrorCodes.Execution.NOT_FOUND,
                message = "No endpoint serves ${request.method} ${request.requestURI}.",
                details = mapOf(ApiErrors.REASON to "endpoint_not_found"),
                userMessage = "We couldn't find that address.",
            ),
        )
    }

    /**
     * The backstop. Nothing reaches a client as a container error page.
     *
     * `Throwable` rather than `Exception`: an `Error` escaping a handler would otherwise bypass
     * the envelope entirely, and a stack-trace HTML page is exactly the topology disclosure
     * observability §6.4 exists to prevent.
     */
    @ExceptionHandler(Throwable::class)
    fun onUnexpected(
        error: Throwable,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.error("500 {} {}: unhandled {}", request.method, request.requestURI, error::class.java.name, error)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(JSON).body(
            ApiErrorResponse.of(
                code = INTERNAL_STAND_IN_CODE,
                message = "Unexpected server error.",
                details = mapOf(ApiErrors.REASON to "internal_error"),
                userMessage = "Something went wrong on our side. Quote the correlation id when reporting this.",
            ),
        )
    }

    private fun logAt(
        status: HttpStatus,
        request: HttpServletRequest,
        error: Throwable,
    ) {
        when {
            // 502 is the caller's downstream being down, not this server breaking — WARN, no stack.
            status == HttpStatus.BAD_GATEWAY ->
                log.warn("{} {} {}: {}", status.value(), request.method, request.requestURI, error.message)
            status.is5xxServerError ->
                log.error("{} {} {}", status.value(), request.method, request.requestURI, error)
            else ->
                log.debug("{} {} {}: {}", status.value(), request.method, request.requestURI, error.message)
        }
    }

    private fun malformedBodyCodeFor(uri: String): String =
        when {
            uri.startsWith("$API_PREFIX/templates") -> PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED
            uri.startsWith("$API_PREFIX/datasources") -> PipelineErrorCodes.Datasource.PROPERTIES_INVALID
            else -> PipelineErrorCodes.Validation.SCHEMA_VERSION_UNSUPPORTED
        }

    private companion object {
        val JSON: MediaType = MediaType.APPLICATION_JSON
        const val API_PREFIX = "/api/v1"
        const val MAX_MESSAGE_CHARS = 200

        /**
         * The stand-in for failures §13 catalogues no code for (405, unexpected internal errors).
         *
         * `pipeline.execution.aborted` is §13.4's "aborted unexpectedly (executor error)" at HTTP
         * 500 — the catalog's only "we broke, not you" entry. Using it keeps this module at zero
         * codes of its own; `details.reason` states what really happened. Reported as a spec gap.
         */
        const val INTERNAL_STAND_IN_CODE = PipelineErrorCodes.Execution.ABORTED
    }
}
