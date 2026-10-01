package co.datapipelines.web.requestlimits

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.web.api.ApiErrorCatalog.userMessageFor
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.config.RequestLimitsProperties
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
    fun `a body at the cap passes the filter and an EOF-reading handler gets every byte`() {
        // The handler reads to EOF, like `@RequestBody String` and the MCP transport do — the
        // 279 pass's observation 2: a chain that never reads made this case vacuous, and a body
        // of EXACTLY the cap was refused on those routes by the read that only wanted its -1.
        val request = postRequest(ByteArray(cap.toInt()))
        var readBytes = -1
        val chain = FilterChain { wrapped, _ -> readBytes = wrapped.getInputStream().readAllBytes().size }

        filter.doFilter(request, response, chain)

        readBytes shouldBe cap.toInt()
        response.contentAsString shouldNotContain PipelineErrorCodes.Request.BODY_TOO_LARGE
    }

    @Test
    fun `a chunked body of exactly the cap reads to EOF and passes - the probe finds no byte`() {
        val recording = RecordingStream(ByteArray(cap.toInt()))
        val request = chunkedRequest(recording)
        var readBytes = -1
        val chain = FilterChain { wrapped, _ -> readBytes = wrapped.getInputStream().readAllBytes().size }

        filter.doFilter(request, response, chain)

        readBytes shouldBe cap.toInt()
        response.contentAsString shouldNotContain PipelineErrorCodes.Request.BODY_TOO_LARGE
        recording.maxPulled shouldBe cap
    }

    @Test
    fun `the container stream is not opened until a handler asks for the body - a form post keeps its parameters`() {
        // The 279 pass's finding 1: the first wrapper opened the stream in its constructor, which
        // makes Tomcat skip form-body parsing — every form-encoded POST (local login included)
        // reached its handler with no fields. A handler that never asks must never trigger the open.
        val request = OpenCountingRequest()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        chain.request.shouldNotBeNull()
        request.opened shouldBe 0
    }

    @Test
    fun `the filter is registered on the two JSON surfaces only - never on every path`() {
        val registration = RequestLimitsConfiguration().requestBodyCapFilterRegistration(filter)

        registration.urlPatterns.toList() shouldContainExactlyInAnyOrder listOf("/api/v1/*", "/mcp", "/mcp/*")
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
        // The cap held: at the cap the wrapper pulls ONE probe byte to tell EOF from excess, and
        // that byte is what refuses the body — never a second one.
        recording.maxPulled shouldBe cap + 1
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

    // ---- #353: the screenshot route's ONE exemption — its own 4 MiB cap, its own refusal ---------------------

    @Test
    fun `the screenshot route admits a body over the platform cap and under its own 4 MiB`() {
        val size = 3 * 1024 * 1024
        val request = screenshotRequest(ByteArray(size))
        var readBytes = -1
        val chain = FilterChain { wrapped, _ -> readBytes = wrapped.getInputStream().readAllBytes().size }

        filter.doFilter(request, response, chain)

        (size > cap) shouldBe true // the platform cap would refuse this very body anywhere else
        readBytes shouldBe size
        response.contentAsString shouldNotContain PipelineErrorCodes.Request.BODY_TOO_LARGE
    }

    @Test
    fun `the screenshot route refuses 4 MiB + 1 declared unread - with its own code, never the platform's`() {
        val request = screenshotRequest(ByteArray(SCREENSHOT_CAP + 1))
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        response.status shouldBe 413
        response.contentAsString shouldContain """"code":"${VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE}""""
        response.contentAsString shouldContain """"cap_bytes":$SCREENSHOT_CAP"""
        response.contentAsString shouldNotContain PipelineErrorCodes.Request.BODY_TOO_LARGE
        chain.request.shouldBeNull()
    }

    @Test
    fun `a chunked screenshot past 4 MiB is refused by the counting stream - never pulled past the cap`() {
        val recording = RecordingStream(ByteArray(SCREENSHOT_CAP + 1024))
        val request =
            object : HttpServletRequestWrapper(MockHttpServletRequest("POST", SCREENSHOT_PATH)) {
                override fun getInputStream(): ServletInputStream = recording

                override fun getContentLengthLong(): Long = -1
            }
        val chain = FilterChain { wrapped, _ -> wrapped.getInputStream().readAllBytes() }

        filter.doFilter(request, response, chain)

        response.status shouldBe 413
        response.contentAsString shouldContain VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE
        (recording.maxPulled <= SCREENSHOT_CAP + 1L) shouldBe true // the one probe byte, never the rest
    }

    @Test
    fun `the exemption is the one route - a near spelling and another visualization route keep the platform cap`() {
        listOf(
            "/api/v1/visualizations/$ID",
            "/api/v1/visualizations/$ID/tests/sessions/$ID/screenshot/extra",
            "/api/v1/visualizations/$ID/tests/sessions/$ID/results",
            "/api/v1/visualizations/$ID/tests/runs/$ID/screenshot",
        ).forEach { path ->
            val refused = MockHttpServletResponse()
            filter.doFilter(
                MockHttpServletRequest("POST", path).apply { setContent(ByteArray(cap.toInt() + 1)) },
                refused,
                MockFilterChain(),
            )
            refused.status shouldBe 413
            refused.contentAsString shouldContain PipelineErrorCodes.Request.BODY_TOO_LARGE
        }
        // PUT on the screenshot path is not the route either.
        val put = MockHttpServletResponse()
        filter.doFilter(
            MockHttpServletRequest("PUT", SCREENSHOT_PATH).apply { setContent(ByteArray(cap.toInt() + 1)) },
            put,
            MockFilterChain(),
        )
        put.contentAsString shouldContain PipelineErrorCodes.Request.BODY_TOO_LARGE
    }

    private fun screenshotRequest(body: ByteArray): HttpServletRequest =
        MockHttpServletRequest("POST", SCREENSHOT_PATH).apply {
            contentType = "image/png"
            setContent(body)
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

    /** A POST whose container stream counts how often it was opened — zero when no handler reads the body. */
    private class OpenCountingRequest :
        HttpServletRequestWrapper(
            MockHttpServletRequest("POST", "/api/v1/pipelines").apply { setContent(ByteArray(8)) },
        ) {
        var opened = 0
            private set

        override fun getInputStream(): ServletInputStream {
            opened++
            return super.getInputStream()
        }
    }

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

    private companion object {
        /** The spec's §6.1/§17 number, written out — the oracle is never the production constant under test. */
        const val SCREENSHOT_CAP = 4 * 1024 * 1024
        const val ID = "6f1c2e7a-0000-4000-8000-000000000001"
        const val SCREENSHOT_PATH = "/api/v1/visualizations/$ID/tests/sessions/$ID/screenshot"
    }
}
