package co.datapipelines.web.ui

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.api.CorrelationId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins BOTH halves of the htmx branch (034 B4): the toast contract is what makes an
 * htmx failure visible at all, and the page branch is what stops a later round
 * "simplifying" the ordinary-request pages away.
 */
class UiExceptionHandlerTest {
    private val handler = UiExceptionHandler()

    private fun mockRequest(
        htmx: Boolean = false,
        method: String = "GET",
    ) = mockk<HttpServletRequest>(relaxed = true) {
        every { getHeader("HX-Request") } returns if (htmx) "true" else null
        every { this@mockk.method } returns method
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

    /**
     * #408 — the typed bindings of the `web.ui` package, one probe route per binding shape the
     * package declares (33 typed `@PathVariable`s — UUID and Int — and 45 typed `@RequestParam`s —
     * Int, Long, Boolean, UUID — on the lane's base; no enum-typed one). [byId] and [byNumber]
     * answer EVERY well-formed value as absent, with `DashboardUiController.board`'s own refusal,
     * so a malformed value is diffed against an absent one at the same route. [unconvertible]
     * declares a type no converter reads: that is OUR defect, not the caller's, and it stays the
     * 500 (owner ruling at dispatch).
     */
    @Controller
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    class UiTypedProbeController {
        @GetMapping("/probe/ui-id/{id}")
        fun byId(
            @PathVariable id: UUID,
        ): String = throw ResponseStatusException(HttpStatus.NOT_FOUND, ABSENT_REASON)

        @GetMapping("/probe/ui-number/{n}")
        fun byNumber(
            @PathVariable n: Int,
        ): String = throw ResponseStatusException(HttpStatus.NOT_FOUND, ABSENT_REASON)

        @GetMapping("/probe/ui-page")
        fun page(
            @RequestParam version: Int,
        ): String = "probe/page"

        @GetMapping("/probe/ui-filter")
        fun filter(
            @RequestParam("pipeline_id", required = false) pipelineId: UUID?,
            @RequestParam(defaultValue = "false") archived: Boolean,
            @RequestParam(defaultValue = "0") offset: Long,
        ): String = "probe/filter"

        @PostMapping("/probe/ui-form")
        fun form(
            @RequestParam readonly: Boolean,
        ): String = "probe/form"

        @GetMapping("/probe/ui-unconvertible")
        fun unconvertible(
            @RequestParam thing: Unconvertible,
        ): String = "probe/unconvertible"
    }

    /** No `String` constructor and no `valueOf`/`of`/`from`: no converter can produce one. */
    class Unconvertible private constructor()

    private val typedMvc =
        MockMvcBuilders
            .standaloneSetup(UiTypedProbeController())
            .setControllerAdvice(UiExceptionHandler(), ApiExceptionHandler())
            .build()

    private fun MockHttpServletRequestBuilder.htmx() = header("HX-Request", "true")

    /**
     * Resolves [error] to this advice's handler method the way Spring does (the most specific
     * `@ExceptionHandler` type wins) and invokes it. A unit case written this way is RED when the
     * #408 arm is reverted — the `Throwable` backstop answers — rather than a compile error.
     */
    private fun dispatch(
        error: Exception,
        request: HttpServletRequest,
    ): Any? =
        ExceptionHandlerMethodResolver(UiExceptionHandler::class.java)
            .resolveMethod(error)
            .shouldNotBeNull()
            .invoke(handler, error, request)

    private fun typedParameter(
        method: String,
        type: Class<*>,
    ) = MethodParameter(UiTypedProbeController::class.java.getMethod(method, type), 0)

    /** What Spring raises for `GET /dashboards/not-a-uuid`: the path variable's conversion failed. */
    private fun malformedId(value: String = "not-a-uuid") =
        MethodArgumentTypeMismatchException(
            value,
            UUID::class.java,
            "id",
            typedParameter("byId", UUID::class.java),
            IllegalArgumentException("Invalid UUID string: $value"),
        )

    /** What Spring raises for `GET /dashboards/{id}/preview?version=abc`: the query value's conversion failed. */
    private fun malformedVersion(value: String = "abc") =
        MethodArgumentTypeMismatchException(
            value,
            Int::class.java,
            "version",
            typedParameter("page", Int::class.javaPrimitiveType!!),
            NumberFormatException("For input string: \"$value\""),
        )

    /**
     * Runs [block] with this advice's logger forced to DEBUG and returns what it logged. Forced,
     * so "nothing at ERROR" can never pass because nothing was logged at all.
     */
    private fun <T> logged(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(UiExceptionHandler::class.java) as Logger
        val level = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val result = block()
            return result to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
    }

    /** The caller-error log shape (the REST advice's): ONE DEBUG line carrying the throwable, nothing at INFO or above. */
    private fun List<ILoggingEvent>.shouldBeOneCallerErrorLine() {
        withClue("log events ${map { "${it.level} ${it.formattedMessage}" }}") {
            map { it.level } shouldBe listOf(Level.DEBUG)
            single().throwableProxy.shouldNotBeNull()
        }
    }

    /** The code a toast's small text carries (`<code> · correlation <id>`). */
    private fun toastCode(body: String): String? = Regex("""ds-toast-body">([a-z_.]+) · correlation """).find(body)?.groupValues?.get(1)

    /** Everything a page answer is made of in this harness: status, view, model, headers, body. */
    private fun MvcResult.pageShape(): Map<String, Any?> =
        mapOf(
            "status" to response.status,
            "view" to modelAndView?.viewName,
            "model" to modelAndView?.model?.toMap(),
            "headers" to response.headerNames.associateWith { response.getHeaders(it) },
            "body" to response.contentAsString,
        )

    /**
     * A toast answer minus its per-request correlation id and its headline (see the "absent"
     * case's note) — and so minus `Content-Length`, which is the headline's length.
     */
    private fun MvcResult.toastShape(): Map<String, Any?> =
        mapOf(
            "status" to response.status,
            "headers" to (response.headerNames - "Content-Length").associateWith { response.getHeaders(it) },
            "code" to toastCode(response.contentAsString),
        )

    @Test
    fun `a malformed path id is the 404 page an absent id gets, logged at DEBUG - never the 500 backstop (#408)`() {
        val (page, events) = logged { dispatch(malformedId(), mockRequest()) }

        page.shouldBeInstanceOf<ModelAndView>().run {
            viewName shouldBe "error/404"
            status shouldBe HttpStatus.NOT_FOUND
            model["activeTheme"] shouldBe UiProperties().theme
        }
        events.shouldBeOneCallerErrorLine()
    }

    @Test
    fun `a malformed path id on an htmx request is the 404 toast with the stand-in code, echoing nothing (#408)`() {
        val (toast, events) = logged { dispatch(malformedId(), mockRequest(htmx = true)) }

        toast.shouldBeInstanceOf<ResponseEntity<*>>().run {
            statusCode shouldBe HttpStatus.NOT_FOUND
            headers.getFirst("HX-Retarget") shouldBe "#toast"
            headers.getFirst("HX-Reswap") shouldBe "beforeend"
            val body = body as String
            toastCode(body) shouldBe PipelineErrorCodes.Execution.ABORTED
            body shouldNotContain "not-a-uuid"
        }
        events.shouldBeOneCallerErrorLine()
    }

    @Test
    fun `a malformed query value on a GET is the 400 page saying the ADDRESS is unreadable (#408)`() {
        val (page, events) = logged { dispatch(malformedVersion(), mockRequest()) }

        page.shouldBeInstanceOf<ModelAndView>().run {
            viewName shouldBe "error/400"
            status shouldBe HttpStatus.BAD_REQUEST
            model["detail"] shouldBe ADDRESS_DETAIL
            model["activeTheme"] shouldBe UiProperties().theme
        }
        events.shouldBeOneCallerErrorLine()
    }

    @Test
    fun `a malformed form value on a POST is the 400 page with the template's own FORM sentence (#408)`() {
        val page = dispatch(malformedVersion(), mockRequest(method = "POST")).shouldBeInstanceOf<ModelAndView>()

        page.viewName shouldBe "error/400"
        page.status shouldBe HttpStatus.BAD_REQUEST
        page.model shouldNotContainKey "detail"
    }

    @Test
    fun `a malformed query value on an htmx request is the 400 toast naming the parameter - REST's code, never the value (#408)`() {
        val (toast, events) = logged { dispatch(malformedVersion("abc-canary"), mockRequest(htmx = true)) }

        toast.shouldBeInstanceOf<ResponseEntity<*>>().run {
            statusCode shouldBe HttpStatus.BAD_REQUEST
            headers.getFirst("HX-Retarget") shouldBe "#toast"
            val body = body as String
            toastCode(body) shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
            body shouldContain "Parameter 'version' is missing or not of the expected type."
            body shouldNotContain "abc-canary"
        }
        events.shouldBeOneCallerErrorLine()
    }

    /**
     * The Roles proof (#408): a caller cannot tell a malformed id from a well-formed absent one —
     * the page answer is identical in every part this harness sees (status, view, model, headers,
     * body); the view and model ARE the page, so the rendered bytes are identical too. The htmx
     * answer matches in status, headers and code; its headline differs only because the absent
     * route supplies its OWN reason ("Dashboard not found") and the malformed value never reached
     * a route — it carries the status phrase, which names nothing about the value.
     */
    @Test
    fun `a malformed UUID path id answers exactly what a well-formed absent id answers, page and toast (#408)`() {
        val absent = typedMvc.perform(get("/probe/ui-id/{id}", UUID.randomUUID())).andReturn()
        val (malformed, events) = logged { typedMvc.perform(get("/probe/ui-id/not-a-uuid")).andReturn() }

        malformed.response.status shouldBe HttpStatus.NOT_FOUND.value()
        malformed.modelAndView?.viewName shouldBe "error/404"
        malformed.pageShape() shouldBe absent.pageShape()
        events.shouldBeOneCallerErrorLine()

        val absentToast = typedMvc.perform(get("/probe/ui-id/{id}", UUID.randomUUID()).htmx()).andReturn()
        val malformedToast = typedMvc.perform(get("/probe/ui-id/not-a-uuid").htmx()).andReturn()
        malformedToast.toastShape() shouldBe absentToast.toastShape()
        malformedToast.response.status shouldBe HttpStatus.NOT_FOUND.value()
        absentToast.response.contentAsString shouldContain ABSENT_REASON
        malformedToast.response.contentAsString shouldContain HttpStatus.NOT_FOUND.reasonPhrase
    }

    @Test
    fun `a malformed Int path variable answers what an absent one answers (#408)`() {
        val absent = typedMvc.perform(get("/probe/ui-number/7")).andReturn()
        val (malformed, events) = logged { typedMvc.perform(get("/probe/ui-number/seven")).andReturn() }

        malformed.response.status shouldBe HttpStatus.NOT_FOUND.value()
        malformed.pageShape() shouldBe absent.pageShape()
        events.shouldBeOneCallerErrorLine()
    }

    @Test
    fun `a malformed typed query value is the 400 page and the 400 toast at the wire, for every typed query shape (#408)`() {
        val (page, events) = logged { typedMvc.perform(get("/probe/ui-page").param("version", "abc")).andReturn() }
        page.response.status shouldBe HttpStatus.BAD_REQUEST.value()
        page.modelAndView?.viewName shouldBe "error/400"
        page.modelAndView?.model?.get("detail") shouldBe ADDRESS_DETAIL
        events.shouldBeOneCallerErrorLine()

        // Int, UUID, Boolean, Long — each refusal names ITS parameter (the binding's name, not the Kotlin one).
        listOf(
            "/probe/ui-page" to ("version" to "abc"),
            "/probe/ui-filter" to ("pipeline_id" to "nope"),
            "/probe/ui-filter" to ("archived" to "maybe"),
            "/probe/ui-filter" to ("offset" to "x"),
        ).forEach { (path, param) ->
            val (name, value) = param
            val toast = typedMvc.perform(get(path).param(name, value).htmx()).andReturn()
            withClue("$path?$name=$value") {
                toast.response.status shouldBe HttpStatus.BAD_REQUEST.value()
                toast.response.getHeader("HX-Retarget") shouldBe "#toast"
                toastCode(toast.response.contentAsString) shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
                toast.response.contentAsString shouldContain "Parameter '$name' is missing or not of the expected type."
            }
        }

        // Unchanged: a well-formed value still reaches the handler.
        typedMvc
            .perform(get("/probe/ui-page").param("version", "3"))
            .andReturn()
            .modelAndView
            ?.viewName shouldBe "probe/page"
    }

    @Test
    fun `a malformed form value on a POST is the 400 page with the form sentence, and the htmx toast names it (#408)`() {
        val page = typedMvc.perform(post("/probe/ui-form").param("readonly", "maybe")).andReturn()
        page.response.status shouldBe HttpStatus.BAD_REQUEST.value()
        page.modelAndView?.viewName shouldBe "error/400"
        page.modelAndView?.model.shouldNotBeNull() shouldNotContainKey "detail"

        val toast = typedMvc.perform(post("/probe/ui-form").param("readonly", "maybe").htmx()).andReturn()
        toast.response.status shouldBe HttpStatus.BAD_REQUEST.value()
        toast.response.contentAsString shouldContain "Parameter 'readonly' is missing or not of the expected type."
    }

    /**
     * #408 Security: a markup payload and an encoded newline (log forging) as the id AND as a query
     * value. Neither reaches a response body, a header or the model, and nothing is logged at INFO
     * or above. The DEBUG line's message carries the request URI as received — still
     * percent-encoded, so no decoded payload and no forged line — and its attached throwable is
     * Spring's (the REST advice's shape; DEBUG is off at the shipped root level INFO).
     */
    @Test
    fun `an injection payload as the id or a query value is echoed nowhere - body, headers, model, log message (#408)`() {
        val requests =
            listOf(
                "path <script>" to get(URI.create("/probe/ui-id/%3Cscript%3Ealert(1)%3C%2Fscript%3E")),
                "path newline" to get(URI.create("/probe/ui-id/%0AFAKE-LOG-LINE")),
                "query <script>" to get("/probe/ui-page").param("version", SCRIPT_PAYLOAD),
                "query newline" to get("/probe/ui-page").param("version", NEWLINE_PAYLOAD),
            )
        requests.forEach { (label, request) ->
            listOf(false, true).forEach { htmx ->
                val (result, events) = logged { typedMvc.perform(if (htmx) request.htmx() else request).andReturn() }
                withClue("$label, htmx=$htmx") {
                    result.response.status shouldBe if (label.startsWith("path")) 404 else 400
                    // What reaches the caller: not the payload in ANY form, escaped or not.
                    val served =
                        listOf(result.response.contentAsString) +
                            result.response.headerNames.flatMap { result.response.getHeaders(it) } +
                            result.modelAndView
                                ?.model
                                ?.values
                                .orEmpty()
                                .map { it.toString() }
                    served.forEach { surface ->
                        surface shouldNotContain "script"
                        surface shouldNotContain "FAKE-LOG-LINE"
                    }
                    // What reaches the log message: never the decoded payload, never a line break.
                    events.map { it.formattedMessage }.forEach { message ->
                        message shouldNotContain "<script>"
                        message shouldNotContain "\n"
                        message shouldNotContain "\r"
                    }
                    // Non-vacuity: the advice's arm ran (a no-handler 404 would log nothing here).
                    events.shouldBeOneCallerErrorLine()
                }
            }
        }
    }

    /**
     * A parameter type NO converter reads is a defect in our code whatever the caller sent
     * (Spring raises [MethodArgumentConversionNotSupportedException] only on
     * `ConverterNotFoundException`/`IllegalStateException`), so it keeps the backstop's 500 and
     * its ERROR line with the stack — owner ruling at #408's dispatch. A future widening of the
     * #408 arm to this type turns this red.
     */
    @Test
    fun `a parameter type no converter reads stays the 500 with an ERROR stack - our defect, not the caller's (#408)`() {
        val (result, events) = logged { typedMvc.perform(get("/probe/ui-unconvertible").param("thing", "x")).andReturn() }

        result.resolvedException.shouldBeInstanceOf<MethodArgumentConversionNotSupportedException>()
        result.response.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR.value()
        result.modelAndView?.viewName shouldBe "error/500"
        events.map { it.level } shouldBe listOf(Level.ERROR)
        events.single().throwableProxy.shouldNotBeNull()
    }

    /**
     * `error/400` rendered for real: absent a `detail`, today's FORM sentence (098 §C's copy, the
     * `@Valid` and missing-field arm's page); with one, the detail INSTEAD of it (#408's address
     * copy). Red on a template that ignores the attribute.
     */
    @Test
    fun `error 400 renders its form sentence by default and the detail instead when one is passed (#408)`() {
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }

        fun render(detail: String?): String =
            engine.process(
                "error/400",
                WebContext(
                    JakartaServletWebApplication
                        .buildApplication(MockServletContext())
                        .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
                ).withRoles().apply {
                    setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
                    setVariable("workspaceHeaderFragment", "")
                    setVariable("workspaceOptions", emptyList<Any>())
                    setVariable("activeWorkspace", "acme")
                    setVariable("activeTheme", "dark")
                    setVariable("authenticated", true)
                    setVariable("currentPath", "/dashboards/x")
                    setVariable("reportProblemUrl", co.datapipelines.web.ui.site.REPORT_PROBLEM_URL)
                    if (detail != null) setVariable("detail", detail)
                },
            )

        val plain = render(null)
        plain shouldContain FORM_SENTENCE
        plain shouldNotContain ADDRESS_DETAIL

        val addressed = render(ADDRESS_DETAIL)
        addressed shouldContain ADDRESS_DETAIL
        addressed shouldNotContain FORM_SENTENCE
    }

    private companion object {
        /** A value no real client sends; a 406 body carrying it would be echoing the Accept header. */
        const val ACCEPT_CANARY = "application/x-accept-canary-222"

        /** What [UiProbeController] would return; it must never appear in a refusal. */
        const val UI_SECRET = "dpk_UIPROBESECRET.never-in-a-refusal"

        /** `DashboardUiController.board`'s refusal for an absent, foreign or lens-hidden id. */
        const val ABSENT_REASON = "Dashboard not found"

        /** #408's `detail` for a malformed value in the ADDRESS (a GET's query) — names no value. */
        const val ADDRESS_DETAIL = "The address names a value this page cannot read. Check the link and try again."

        /** `error/400`'s default sentence (098 §C), kept for a form that does not bind. */
        const val FORM_SENTENCE = "Something in the form that was sent is missing or malformed. Go back and try again."

        const val SCRIPT_PAYLOAD = "<script>alert(1)</script>"

        const val NEWLINE_PAYLOAD = "\nFAKE-LOG-LINE"
    }
}
