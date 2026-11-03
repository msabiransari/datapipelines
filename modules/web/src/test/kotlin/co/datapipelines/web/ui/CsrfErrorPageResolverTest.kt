package co.datapipelines.web.ui

import co.datapipelines.auth.AuthAttributes
import co.datapipelines.auth.CsrfInvalidException
import co.datapipelines.web.api.CorrelationId
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.ModelAndView

/**
 * The #782 page branch's web side: a resolver that claims ONLY the forwarded CSRF refusal
 * and renders `error/403` with the refusal's fixed user message, the request's correlation
 * id and the deployment theme. Any other 403 returns null so Boot's default resolution —
 * the plain error/403 for a permission refusal — keeps today's behaviour.
 */
class CsrfErrorPageResolverTest {
    private val resolver = CsrfErrorPageResolver()

    @Test
    fun `a forwarded CSRF refusal renders the error page with detail, correlation id and theme`() {
        val request = MockHttpServletRequest("POST", "/logout")
        request.setAttribute(AuthAttributes.CSRF_REFUSAL, CsrfInvalidException("mismatch"))
        CorrelationId.withId(CORRELATION_ID) {
            val page = resolver.resolveErrorView(request, HttpStatus.FORBIDDEN, emptyMap())

            checkNotNull(page)
            page.viewName shouldBe "error/403"
            page.status shouldBe HttpStatus.FORBIDDEN
            page.model["detail"] shouldBe "Your page is out of date. Reload it and try again."
            page.model["correlationId"] shouldBe CORRELATION_ID.toString()
            page.model["activeTheme"] shouldBe UiProperties().theme
        }
    }

    @Test
    fun `a plain 403 without the stash is not claimed`() {
        val request = MockHttpServletRequest("GET", "/admin")

        val page = resolver.resolveErrorView(request, HttpStatus.FORBIDDEN, emptyMap())

        page shouldBe null
    }

    @Test
    fun `a stash of the wrong type is not claimed`() {
        val request = MockHttpServletRequest("POST", "/workspace/switch")
        request.setAttribute(AuthAttributes.CSRF_REFUSAL, "not-an-exception")

        val page = resolver.resolveErrorView(request, HttpStatus.FORBIDDEN, emptyMap())

        page shouldBe null
    }

    private companion object {
        val CORRELATION_ID = java.util.UUID.randomUUID()
    }
}
