package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.CorrelationId
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import java.util.UUID

/**
 * Pins BOTH halves of the htmx branch (034 B4): the toast contract is what makes an
 * htmx failure visible at all, and the page branch is what stops a later round
 * "simplifying" the ordinary-request pages away.
 */
class UiExceptionHandlerTest {
    private val handler = UiExceptionHandler()

    private fun mockRequest(htmx: Boolean = false) =
        mockk<HttpServletRequest>(relaxed = true) {
            every { getHeader("HX-Request") } returns if (htmx) "true" else null
        }

    @Test
    fun `access denied maps to error 403 view with forbidden status`() {
        val request = mockRequest()
        val error = AccessDeniedException("denied")

        val result = handler.onAccessDenied(error, request).shouldBeInstanceOf<ModelAndView>()

        result.viewName shouldBe "error/403"
        result.status shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `unexpected error maps to error 500 view with correlation id`() {
        val request = mockRequest()
        val error = RuntimeException("boom")

        val result = handler.onUnexpected(error, request).shouldBeInstanceOf<ModelAndView>()

        result.viewName shouldBe "error/500"
        result.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
        result.model["correlationId"].shouldNotBeNull()
    }

    @Test
    fun `unexpected error on an htmx request is a toast carrying code and correlation id`() {
        val request = mockRequest(htmx = true)
        val correlationId = UUID.randomUUID()

        val result =
            CorrelationId
                .withId(correlationId) {
                    handler.onUnexpected(RuntimeException("boom — secret detail"), request)
                }.shouldBeInstanceOf<ResponseEntity<*>>()

        result.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
        result.headers.getFirst("HX-Retarget") shouldBe "#toast"
        result.headers.getFirst("HX-Reswap") shouldBe "beforeend"
        val body = result.body as String
        body shouldContain "ds-toast-danger"
        body shouldContain "hx-swap-oob=\"beforeend:#toast\""
        body shouldContain "pipeline.execution.aborted"
        body shouldContain correlationId.toString()
        // The exception's own message never leaves the log (034 B3).
        body shouldNotContain "secret detail"
    }

    @Test
    fun `access denied on an htmx request is a toast carrying code and correlation id`() {
        val request = mockRequest(htmx = true)
        val correlationId = UUID.randomUUID()

        val result =
            CorrelationId
                .withId(correlationId) {
                    handler.onAccessDenied(AccessDeniedException("denied"), request)
                }.shouldBeInstanceOf<ResponseEntity<*>>()

        result.statusCode shouldBe HttpStatus.FORBIDDEN
        result.headers.getFirst("HX-Retarget") shouldBe "#toast"
        val body = result.body as String
        body shouldContain "auth.scope.insufficient"
        body shouldContain correlationId.toString()
    }

    @Test
    fun `a deliberate ResponseStatusException keeps ITS status - never the 500 backstop`() {
        // The OIDC-only regression suite pins this at the wire: the disabled local
        // login's deliberate 404 must survive the UI advice winning over the REST one.
        val page =
            handler
                .onResponseStatus(ResponseStatusException(HttpStatus.NOT_FOUND, "Local login is disabled"), mockRequest())
                .shouldBeInstanceOf<ModelAndView>()
        page.viewName shouldBe "error/404"
        page.status shouldBe HttpStatus.NOT_FOUND

        val toast =
            handler
                .onResponseStatus(ResponseStatusException(HttpStatus.NOT_FOUND, "Local login is disabled"), mockRequest(htmx = true))
                .shouldBeInstanceOf<ResponseEntity<*>>()
        toast.statusCode shouldBe HttpStatus.NOT_FOUND
        (toast.body as String) shouldContain "Local login is disabled"
    }

    @Test
    fun `a domain exception takes the catalog status, and its message never reaches the toast`() {
        val error =
            DatapipelinesException(
                code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                message = "jdbc:postgres://internal-host:5432/prod refused",
            )

        val toast =
            handler
                .onDomain(error, mockRequest(htmx = true))
                .shouldBeInstanceOf<ResponseEntity<*>>()

        toast.statusCode shouldBe HttpStatus.BAD_GATEWAY
        val body = toast.body as String
        body shouldContain "pipeline.execution.datasource_unreachable"
        body shouldNotContain "internal-host"
    }

    /**
     * 098 §C — a form that does not bind is the CALLER's error: 400, `error/400`, and a DEBUG
     * line without a trace. It used to reach [UiExceptionHandler.onUnexpected]: 500,
     * `error/500`'s "Something went wrong on our side", and an ERROR line with a stack trace.
     * (`POST /login`'s own answer is [LocalLoginController]'s redirect — a handler method on the
     * controller wins over an advice, and `LocalLoginControllerTest` pins that at the wire.)
     */
    @Test
    fun `an unbindable request is a 400 page, not the 500 backstop`() {
        val error = MissingServletRequestParameterException("email", "String")

        val page = handler.onUnbindableRequest(error, mockRequest()).shouldBeInstanceOf<ModelAndView>()

        page.viewName shouldBe "error/400"
        page.status shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `an unbindable htmx request is a 400 toast, and neither logs ERROR or a stack`() {
        val logger = org.slf4j.LoggerFactory.getLogger(UiExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val appender =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val toast =
                handler
                    .onUnbindableRequest(MissingServletRequestParameterException("email", "String"), mockRequest(htmx = true))
                    .shouldBeInstanceOf<ResponseEntity<*>>()

            toast.statusCode shouldBe HttpStatus.BAD_REQUEST
            toast.headers.getFirst("HX-Retarget") shouldBe "#toast"

            appender.list.map { it.level.toString() } shouldNotContain "ERROR"
            appender.list.none { it.throwableProxy != null } shouldBe true
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** A BindException from an `@Valid` form takes the same road. */
    @Test
    fun `a bind failure takes the same 400`() {
        val binding = org.springframework.validation.BeanPropertyBindingResult(Any(), "form")
        binding.reject("bad")

        handler
            .onUnbindableRequest(org.springframework.validation.BindException(binding), mockRequest())
            .shouldBeInstanceOf<ModelAndView>()
            .status shouldBe HttpStatus.BAD_REQUEST
    }
}
