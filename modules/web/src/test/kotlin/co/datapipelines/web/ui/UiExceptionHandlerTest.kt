package co.datapipelines.web.ui

import co.datapipelines.web.api.CorrelationId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException

class UiExceptionHandlerTest {
    private val handler = UiExceptionHandler()

    private fun mockRequest() = mockk<HttpServletRequest>(relaxed = true)

    @Test
    fun `access denied maps to error 403 view with forbidden status`() {
        val request = mockRequest()
        val error = AccessDeniedException("denied")

        val result = handler.onAccessDenied(error, request)

        result.viewName shouldBe "error/403"
        result.status shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `unexpected error maps to error 500 view with correlation id`() {
        val request = mockRequest()
        val error = RuntimeException("boom")

        val result = handler.onUnexpected(error, request)

        result.viewName shouldBe "error/500"
        result.status shouldBe HttpStatus.INTERNAL_SERVER_ERROR
        result.model["correlationId"].shouldNotBeNull()
    }
}
