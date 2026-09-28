package co.datapipelines.web.requestlimits

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.api.ApiErrorCatalog.userMessageFor
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.config.RequestLimitsProperties
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * The platform's request-body cap (#279, pipeline-contract §13.21): a declared `Content-Length`
 * over the cap is refused unread; a chunked body is refused by the counting wrapper at the byte
 * past the cap, without the underlying stream ever being pulled past it; a body at the cap
 * passes; and the refusal is the §4.2 envelope carrying the correlation id — written by the
 * filter when the marker escapes the chain, and by [ApiExceptionHandler.onBodyTooLarge] on REST
 * when the advice answers it first.
 */
class RequestBodyCapFilterTest {
    /** The binder's own floor (§3.31) — the properties class enforces the window, so a unit test uses it. */
    private val cap = RequestLimits.MIN_REQUEST_BYTES
    private val response = MockHttpServletResponse()
    private val filter = RequestBodyCapFilter(RequestLimitsProperties(maxRequestBytes = cap), errorWriter())

    @AfterEach
    fun clearMdc() = MDC.remove(AuthErrorWriter.MDC_KEY)

    @Test
    fun `a Content-Length one byte over the cap is refused unread`() {
        val request = postRequest(ByteArray(cap.toInt() + 1))
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        response.status shouldBe 413
        response.contentAsString shouldContain """"code":"${PipelineErrorCodes.Request.BODY_TOO_LARGE}""""
        response.contentAsString shouldContain """"limit_bytes":$cap"""
        response.contentAsString shouldContain userMessageFor(PipelineErrorCodes.Request.BODY_TOO_LARGE)
        chain.request.shouldBeNull() // the handler and every parser downstream never ran
    }

    @Test
    fun `a body at the cap passes the filter and reaches the handler`() {
        val request = postRequest(ByteArray(cap.toInt()))
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        chain.request.shouldNotBeNull()
        response.contentAsString shouldNotContain PipelineErrorCodes.Request.BODY_TOO_LARGE
    }

    @Test
    fun `a chunked body is refused at the byte past the cap and the container stream is never pulled past it`() {
        val recording = RecordingStream(ByteArray(cap.toInt() + 1))
        val request = chunkedRequest(recording)
        val chain =
            FilterChain { wrapped, _ ->
                // The "parser": read the whole body, like Jackson or the MCP transport would.
                wrapped.getInputStream().readAllBytes()
            }

        filter.doFilter(request, response, chain)

        response.status shouldBe 413
        response.contentAsString shouldContain """"code":"${PipelineErrorCodes.Request.BODY_TOO_LARGE}""""
        // The cap held: the counting wrapper never asked the container for more than cap bytes.
        recording.maxPulled shouldBe cap
    }

    @Test
    fun `a GET is not wrapped and not counted`() {
        val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        chain.request.shouldNotBeNull()
        response.contentAsString shouldNotContain PipelineErrorCodes.Request.BODY_TOO_LARGE
    }

    @Test
    fun `the refusal carries the correlation id the request was stamped with`() {
        MDC.put(AuthErrorWriter.MDC_KEY, "00000000-0000-0000-0000-000000000042")
        val request = postRequest(ByteArray(cap.toInt() + 1))
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        response.getHeader(AuthErrorWriter.CORRELATION_HEADER) shouldBe "00000000-0000-0000-0000-000000000042"
        response.contentAsString shouldContain """"correlation_id":"00000000-0000-0000-0000-000000000042""""
    }

    @Test
    fun `the advice answers the escaped marker with the same envelope the filter writes`() {
        val marker = RequestBodyCapFilter.RequestBodyTooLargeException(limitBytes = cap)

        val entity = ApiExceptionHandler().onBodyTooLarge(marker, MockHttpServletRequest("POST", "/mcp"))

        entity.statusCode.value() shouldBe 413
        val body = entity.body.shouldNotBeNull()
        body.error.code shouldBe PipelineErrorCodes.Request.BODY_TOO_LARGE
        body.error.details shouldBe mapOf("limit_bytes" to cap)
        body.error.userMessage shouldBe userMessageFor(PipelineErrorCodes.Request.BODY_TOO_LARGE)
    }

    /** A `POST /api/v1/pipelines` whose declared length is the body's size (MockHttpServletRequest derives it). */
    private fun postRequest(body: ByteArray): HttpServletRequest =
        MockHttpServletRequest("POST", "/api/v1/pipelines").apply { setContent(body) }

    /** The chunked shape: no length declared, and the container's stream is the recording one. */
    private fun chunkedRequest(stream: RecordingStream): HttpServletRequest =
        object : HttpServletRequestWrapper(MockHttpServletRequest("POST", "/api/v1/pipelines")) {
            override fun getInputStream(): ServletInputStream = stream

            override fun getContentLengthLong(): Long = -1
        }

    private fun errorWriter() = AuthErrorWriter(JsonMapper.builder().build())

    /** Counts how far the container's stream was ever pulled — the cap's "never read past" proof. */
    private class RecordingStream(
        private val data: ByteArray,
    ) : ServletInputStream() {
        var maxPulled = 0L
            private set

        private var position = 0

        override fun read(): Int {
            if (position >= data.size) return -1
            val b = data[position].toInt() and 0xff
            position++
            maxPulled = maxOf(maxPulled, position.toLong())
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (position >= data.size) return -1
            val n = minOf(len, data.size - position)
            System.arraycopy(data, position, b, off, n)
            position += n
            maxPulled = maxOf(maxPulled, position.toLong())
            return n
        }

        override fun available(): Int = data.size - position

        override fun isFinished(): Boolean = position >= data.size

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
    }
}
