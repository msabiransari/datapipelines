package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiExceptionHandler
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
        body shouldContain "auth.permission.undeclared"
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

    /**
     * #222's two shapes inside the `web.ui` package, through a real MVC pipeline with BOTH
     * advices in production order: [plain] is the mapping-time refusal (a `produces` mismatch;
     * no handler matched, so the global `ApiExceptionHandler` answers it), and [json] is the
     * return-value refusal (the handler ran, then no converter writes its answer in an accepted
     * type, which is this advice's). [calls] counts the handler runs.
     */
    @Controller
    class UiProbeController {
        val calls = AtomicInteger()

        @GetMapping("/probe/ui-plain", produces = [MediaType.TEXT_PLAIN_VALUE])
        @ResponseBody
        fun plain(): String {
            calls.incrementAndGet()
            return UI_SECRET
        }

        @GetMapping("/probe/ui-json")
        @ResponseBody
        fun json(): Map<String, String> {
            calls.incrementAndGet()
            return mapOf("key" to UI_SECRET)
        }
    }

    @Test
    fun `an Accept a web ui route cannot satisfy is 406 not_acceptable on both paths, echoing nothing - never the 500`() {
        val probe = UiProbeController()
        val mvc =
            MockMvcBuilders
                .standaloneSetup(probe)
                .setControllerAdvice(UiExceptionHandler(), ApiExceptionHandler())
                .build()

        // The mapping-time refusal: the handler never runs.
        val mapped =
            mvc
                .perform(get("/probe/ui-plain").header("Accept", "application/json, $ACCEPT_CANARY"))
                .andExpect(status().isNotAcceptable)
                .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE))
                .andExpect(jsonPath("$.error.details.produces[0]").value(MediaType.TEXT_PLAIN_VALUE))
                .andReturn()
        probe.calls.get() shouldBe 0

        // The return-value refusal: the handler ran; its answer still never reaches the body.
        val written =
            mvc
                .perform(get("/probe/ui-json").header("Accept", "text/csv, $ACCEPT_CANARY"))
                .andExpect(status().isNotAcceptable)
                .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE))
                .andExpect(jsonPath("$.error.details.produces").isArray)
                .andReturn()
        probe.calls.get() shouldBe 1

        listOf(mapped, written).forEach { result ->
            result.response.contentType shouldBe MediaType.APPLICATION_JSON_VALUE
            result.response.contentAsString shouldNotContain ACCEPT_CANARY
            result.response.contentAsString shouldNotContain UI_SECRET
        }

        // Unchanged: an acceptable request still reaches the handler.
        mvc.perform(get("/probe/ui-plain").header("Accept", "*/*")).andExpect(status().isOk)
        probe.calls.get() shouldBe 2
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

    private companion object {
        /** A value no real client sends; a 406 body carrying it would be echoing the Accept header. */
        const val ACCEPT_CANARY = "application/x-accept-canary-222"

        /** What [UiProbeController] would return; it must never appear in a refusal. */
        const val UI_SECRET = "dpk_UIPROBESECRET.never-in-a-refusal"
    }
}
