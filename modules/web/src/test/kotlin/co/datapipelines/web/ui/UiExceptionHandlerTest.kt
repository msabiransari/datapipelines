package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.CorrelationId
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
}
