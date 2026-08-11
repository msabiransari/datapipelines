package co.datapipelines.web

import co.datapipelines.web.api.CorrelationId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** The request correlation filter (rest-api §3.4): adopt-or-mint, echo, and MDC cleanup. */
class CorrelationIdFilterTest {
    private val filter = CorrelationIdFilter()

    @Test
    fun `a UUID inbound header is adopted and echoed`() {
        val inbound = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        request.addHeader(CorrelationId.HEADER, inbound)
        val response = MockHttpServletResponse()
        var mdcDuring: String? = null

        filter.doFilter(
            request,
            response,
            jakarta.servlet.FilterChain { _, _ -> mdcDuring = MDC.get(CorrelationId.MDC_KEY) },
        )

        response.getHeader(CorrelationId.HEADER) shouldBe inbound
        mdcDuring shouldBe inbound
        MDC.get(CorrelationId.MDC_KEY) shouldBe null
    }

    @Test
    fun `a garbage inbound header is replaced, not propagated`() {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        request.addHeader(CorrelationId.HEADER, "garbage<script>")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val echoed = response.getHeader(CorrelationId.HEADER)
        echoed shouldNotBe "garbage<script>"
        UUID.fromString(echoed) // parses — a minted UUID
    }
}
