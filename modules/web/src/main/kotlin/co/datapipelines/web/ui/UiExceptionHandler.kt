package co.datapipelines.web.ui

import co.datapipelines.web.api.CorrelationId
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView

@ControllerAdvice(basePackageClasses = [UiController::class])
class UiExceptionHandler {
    private val log = LoggerFactory.getLogger(UiExceptionHandler::class.java)

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun onAccessDenied(
        @Suppress("UNUSED_PARAMETER") error: org.springframework.security.access.AccessDeniedException,
        request: HttpServletRequest,
    ): ModelAndView {
        log.info("403 {} {}: access denied", request.method, request.requestURI)
        return errorModel("error/403", HttpStatus.FORBIDDEN)
    }

    @ExceptionHandler(Throwable::class)
    fun onUnexpected(
        error: Throwable,
        request: HttpServletRequest,
    ): ModelAndView {
        log.error("500 {} {}: unhandled {}", request.method, request.requestURI, error::class.java.name, error)
        val model = ModelAndView("error/500", HttpStatus.INTERNAL_SERVER_ERROR)
        model.addObject("correlationId", CorrelationId.current())
        return model
    }

    private fun errorModel(
        viewName: String,
        status: HttpStatus,
    ): ModelAndView {
        val model = ModelAndView(viewName, status)
        model.addObject("activeTheme", "saas")
        return model
    }
}
