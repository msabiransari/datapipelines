package co.datapipelines.web.requestlimits

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.auth.appPath
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.config.RequestLimitsProperties
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * The platform-wide request-body cap (#279, pipeline-contract §13.21, rest-api §4.2): a body
 * over `datapipelines.web.max-request-bytes` is refused with `413 request.body_too_large` on
 * BOTH JSON surfaces — REST `/api/v1` and MCP `/mcp` — before any handler and before any
 * parser reads the body.
 *
 * ## The two refusal paths
 *
 *  - **A declared `Content-Length` over the cap** is refused here, before the chain runs: no
 *    parser is reached; the refused body may then be drained under the fixed bounds. This is the path every standard client takes — HTTP
 *    clients that send a body almost always declare its length.
 *  - **A `Transfer-Encoding: chunked` body declares no length**, so the filter wraps the
 *    request's input stream with a counting delegate that serves at most [RequestLimits]-bounded
 *    bytes and throws [RequestBodyTooLargeException] at the first byte past the cap. The
 *    wrapper never pulls a byte beyond the cap from the underlying stream, so the body is
 *    refused without ever being buffered ([ContentCachingRequestWrapper] was deliberately NOT
 *    used: it reads the whole body into memory first, which is exactly the allocation this cap
 *    exists to bound). On REST the marker is answered by `ApiExceptionHandler`'s dedicated
 *    handler — the same envelope, the same code; on `/mcp` the SDK's transport servlet wraps
 *    its read loop in a broad catch and answers its own JSON-RPC error — the body still never
 *    parses, which is the cap's job; the 413 envelope is the Content-Length path's guarantee.
 *
 * ## Ordering and scope
 *
 * Registered just after [co.datapipelines.web.CorrelationIdFilter] and the CORS filter, well
 * before the Spring Security chain, so a refusal needs no principal — the envelope carries the
 * correlation id and the catalogued code, nothing else. Body-less methods (GET/HEAD/OPTIONS)
 * are skipped: there is nothing to count.
 *
 * `server.tomcat.max-swallow-size` (configuration.md §3.13) bounds how much of a refused body
 * Tomcat drains afterwards. Both routes first drain small declared refusals under fixed byte,
 * time and concurrency bounds (#467 / #559 / #561): screenshots up to 6 MiB, platform bodies up
 * to their effective cap + 2 MiB. A completed drain preserves the 413 for a write-then-read client.
 * The filter budget is 10 s plus one connector timeout (70 s default), with eight permits
 * (560 thread-seconds). Tomcat's later swallow has no total time budget for a trickling sender.
 */
class RequestBodyCapFilter(
    private val limits: RequestLimitsProperties,
    private val errorWriter: AuthErrorWriter,
    meterRegistry: MeterRegistry,
    private val nanoTime: () -> Long = System::nanoTime,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestBodyCapFilter::class.java)
    private val drainPermits = Semaphore(ScreenshotUploadRoute.MAX_CONCURRENT_DRAINS)

    /** Closed dimensions, eagerly registered: eight series, including quiet zeroes (observability §4.1 / §4.3). */
    private val drains =
        DrainRoute.entries.associateWith { route ->
            DrainOutcome.entries.associateWith { outcome ->
                meterRegistry.counter(DRAIN_METRIC, "outcome", outcome.tag, "route", route.tag)
            }
        }

    private enum class DrainRoute(
        val tag: String,
    ) {
        SCREENSHOT("screenshot"),
        PLATFORM("platform"),
    }

    private enum class DrainOutcome(
        val tag: String,
    ) {
        COMPLETED("completed"),
        EXPIRED("expired"),
        SATURATED("saturated"),
        CLIENT_GONE("client_gone"),
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val screenshot = ScreenshotUploadRoute.matches(request)
        val cap = if (screenshot) ScreenshotUploadRoute.CAP_BYTES else limits.maxRequestBytes
        val declared = request.contentLengthLong
        if (declared > cap) {
            if (screenshot) {
                refuseScreenshot(request, response)
                drainRefused(request, response, declared, ScreenshotUploadRoute.DRAIN_BYTES, DrainRoute.SCREENSHOT)
            } else {
                refuse(request, response, cap)
                drainRefused(request, response, declared, cap + ScreenshotUploadRoute.DRAIN_MARGIN_BYTES, DrainRoute.PLATFORM)
            }
            return
        }
        try {
            filterChain.doFilter(CountingRequestWrapper(request, cap), response)
        } catch (e: RequestBodyTooLargeException) {
            // Surfaces whose servlet does not intercept the marker reach here; where an advice
            // (REST) or the SDK (MCP) answered first, the writer's committed check makes this a no-op.
            if (screenshot) refuseScreenshot(request, response) else refuse(request, response, e.limitBytes)
        }
    }

    /**
     * The screenshot route's own refusal (#353): `413 visualization.test.screenshot_too_large`, the code, message and
     * details the session service answers for the same bytes — never the platform's `request.body_too_large`, whose
     * cap is not this route's.
     */
    private fun refuseScreenshot(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val code = VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE
        errorWriter.write(
            request = request,
            response = response,
            status = ApiErrorCatalog.statusFor(code).value(),
            code = code,
            message = "The screenshot exceeds the 4 MiB cap.",
            userMessage = ApiErrorCatalog.userMessageFor(code),
            details = mapOf("cap_bytes" to VisualizationTestSessionService.MAX_SCREENSHOT_BYTES),
        )
    }

    /**
     * #467 / #559 / #561: flush the 413, then discard a small declared body so a write-then-read
     * client can receive it. Both routes share a nonblocking permit and a monotonic deadline.
     * A read already in progress still waits for the connector timeout; the deadline is checked
     * after every returned read. On expiry/saturation the container's own swallow remains in force.
     */
    private fun drainRefused(
        request: HttpServletRequest,
        response: HttpServletResponse,
        declared: Long,
        bound: Long,
        route: DrainRoute,
    ) {
        if (declared > bound) return
        val started = nanoTime()
        if (!drainPermits.tryAcquire()) {
            recordDrain(DrainOutcome.SATURATED, route, declared, 0, started)
            return
        }
        var readBytes = 0L
        try {
            val deadline = started + ScreenshotUploadRoute.DRAIN_BUDGET.toNanos()
            response.flushBuffer()
            val input = request.inputStream
            val buffer = ByteArray(DRAIN_BUFFER_BYTES)
            var read = 0
            while (read >= 0 && nanoTime() < deadline && readBytes < declared) {
                read = input.read(buffer, 0, minOf(buffer.size.toLong(), declared - readBytes).toInt())
                if (read > 0) readBytes += read
            }
            val outcome = if (read < 0 || readBytes == declared) DrainOutcome.COMPLETED else DrainOutcome.EXPIRED
            recordDrain(outcome, route, declared, readBytes, started)
        } catch (_: IOException) {
            // Disconnect or connector timeout: the response was already refused; never log exception text.
            recordDrain(DrainOutcome.CLIENT_GONE, route, declared, readBytes, started)
        } finally {
            drainPermits.release()
        }
    }

    private fun recordDrain(
        outcome: DrainOutcome,
        route: DrainRoute,
        declared: Long,
        readBytes: Long,
        started: Long,
    ) {
        drains.getValue(route).getValue(outcome).increment()
        val message =
            when (outcome) {
                DrainOutcome.EXPIRED -> "event=request.body_drain_expired declared_bytes={} read_bytes={} elapsed_ms={} route={}"
                DrainOutcome.SATURATED -> "event=request.body_drain_saturated declared_bytes={} read_bytes={} elapsed_ms={} route={}"
                else -> null
            }
        if (message != null) log.warn(message, declared, readBytes, Duration.ofNanos(nanoTime() - started).toMillis(), route.tag)
    }

    /** The §4.2 envelope for `request.body_too_large`, written through `auth`'s writer. */
    private fun refuse(
        request: HttpServletRequest,
        response: HttpServletResponse,
        cap: Long,
    ) {
        val code = PipelineErrorCodes.Request.BODY_TOO_LARGE
        errorWriter.write(
            request = request,
            response = response,
            status = ApiErrorCatalog.statusFor(code).value(),
            code = code,
            message = "Request body exceeded the $cap-byte cap; refused before any parser read it.",
            userMessage = ApiErrorCatalog.userMessageFor(code),
            details = mapOf("limit_bytes" to cap),
        )
    }

    /** Only methods that can carry a body are counted; GET/HEAD/OPTIONS pass through untouched. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method in BODY_LESS_METHODS

    /** The initial dispatch wraps the stream; an async re-dispatch must not wrap it again. */
    override fun shouldNotFilterAsyncDispatch(): Boolean = true

    /** Thrown by [CountingRequestWrapper] at the first byte past the cap — never after a response started. */
    class RequestBodyTooLargeException(
        val limitBytes: Long,
    ) : RuntimeException("request body exceeded the $limitBytes-byte cap")

    /**
     * The counting wrapper: every read goes through a delegate that serves at most [cap] bytes
     * and throws [RequestBodyTooLargeException] on the read that would go past it.
     */
    internal class CountingRequestWrapper(
        request: HttpServletRequest,
        private val cap: Long,
    ) : HttpServletRequestWrapper(request) {
        /**
         * LAZY — opened on the first `getInputStream()`/`getReader()`, never in the constructor.
         * Tomcat's `Request.getInputStream()` marks the request `usingInputStream`, and its
         * `parseParameters()` then returns WITHOUT reading a form body — so an eager open here
         * emptied every `application/x-www-form-urlencoded` POST's parameters (the 279 merge's
         * security pass: local login, logout, every UI form; 143 tests red on the merge gate).
         * A handler that reads parameters never opens the stream, and Tomcat's own
         * `max-http-form-post-size` bounds that body.
         */
        private val countingStream: CountingServletInputStream by lazy { CountingServletInputStream(super.getInputStream(), cap) }

        /** One reader per request (the servlet contract), over the counting stream, ISO-8859-1 when the request names no charset. */
        private val countingReader: BufferedReader by lazy {
            BufferedReader(InputStreamReader(countingStream, characterEncoding ?: DEFAULT_CHARSET))
        }

        override fun getInputStream(): ServletInputStream = countingStream

        override fun getReader(): BufferedReader = countingReader

        private companion object {
            /** RFC 9110 / the servlet spec's default for a body that names no charset. */
            const val DEFAULT_CHARSET = "ISO-8859-1"
        }
    }

    /** The counting delegate — the whole memory story is the one [cap]-sized high-water mark. */
    private class CountingServletInputStream(
        private val delegate: jakarta.servlet.ServletInputStream,
        private val cap: Long,
    ) : ServletInputStream() {
        private var count = 0L

        override fun read(): Int {
            if (count >= cap) return atCap()
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (count >= cap) return atCap()
            // Clamp so the underlying stream is never pulled past the cap.
            val allowed = minOf(len.toLong(), cap - count).toInt()
            val n = delegate.read(b, off, allowed)
            if (n > 0) count += n
            return n
        }

        /**
         * At exactly [cap] bytes consumed, a body of exactly the cap and a body past it look
         * the same until the next byte is asked for: an EOF-reading consumer (`@RequestBody
         * String`, the MCP transport's `readLine` loop) must get its `-1`, not a refusal (the
         * 279 pass, observation 2 — the first version refused any body of exactly the cap on
         * those routes). A declared-length body is finished here (Tomcat knows); a chunked one
         * is answered by ONE probe byte — the single byte this wrapper may pull past the cap.
         */
        private fun atCap(): Int {
            if (delegate.isFinished) return -1
            val probe = delegate.read()
            if (probe < 0) return -1
            throw RequestBodyTooLargeException(cap)
        }

        override fun available(): Int = delegate.available()

        override fun skip(n: Long): Long {
            val allowed = minOf(n, cap - count)
            val skipped = delegate.skip(allowed)
            count += skipped
            return skipped
        }

        override fun isFinished(): Boolean = delegate.isFinished

        override fun isReady(): Boolean = delegate.isReady

        override fun setReadListener(listener: ReadListener?) = delegate.setReadListener(listener)
    }

    /**
     * The ONE route exemption from the platform cap (#353; the implementation spec's §6.1, auth.md §8.6's inventory
     * row): `POST /api/v1/visualizations/{id}/tests/sessions/{sid}/screenshot` carries a raw PNG/WebP body up to the
     * spec's 4 MiB, so on that route — and only there — the filter enforces [CAP_BYTES] instead of
     * `datapipelines.web.max-request-bytes`, by the same two paths: the declared `Content-Length` first (before any parser
     * reads), the counting stream second (never pulled past the cap). The cap is the session service's own
     * constant, so the transport and the typed check cannot disagree. Matched by method AND the exact segment
     * shape; any other spelling falls to the platform cap (the conservative side).
     */
    internal object ScreenshotUploadRoute {
        val CAP_BYTES: Long = VisualizationTestSessionService.MAX_SCREENSHOT_BYTES.toLong()

        /**
         * The most of a refused body the route reads (#467): the cap plus [DRAIN_MARGIN_BYTES], so a screenshot a
         * little over the cap can get a clean close, subject to the shared budget and concurrency cap.
         */
        val DRAIN_BYTES: Long = CAP_BYTES + DRAIN_MARGIN_BYTES

        /** The default `server.tomcat.max-swallow-size`, 2 MiB: what Tomcat already reads of any refused body. */
        const val DRAIN_MARGIN_BYTES: Long = 2L * 1024 * 1024

        /**
         * Fixed safety budget, not a configuration key (configuration.md §3.13's byte-bound precedent).
         * Checked between reads: an in-flight read can add the connector timeout (60 s default),
         * hence 10 + 60 = 70 s per filter drain. Tomcat's later swallow is outside this bound.
         */
        val DRAIN_BUDGET: Duration = Duration.ofSeconds(10)

        /**
         * Both routes share eight refuse-at-once permits: at the default timeout, at most
         * 8 × (10 + 60) = 560 thread-seconds in concurrent filter drains. A fixed safety ceiling,
         * not a configuration key; saturation falls back to Tomcat's existing swallow.
         */
        const val MAX_CONCURRENT_DRAINS = 8

        private val PATH = Regex("^/api/v1/visualizations/[^/]+/tests/sessions/[^/]+/screenshot$")

        fun matches(request: HttpServletRequest): Boolean = request.method == "POST" && PATH.matches(request.appPath())
    }

    companion object {
        /** Just after the CORS filter ([co.datapipelines.web.CorrelationIdFilter.ORDER] + 1), before the security chain. */
        const val ORDER: Int = co.datapipelines.web.CorrelationIdFilter.ORDER + 2

        private val BODY_LESS_METHODS = setOf("GET", "HEAD", "OPTIONS")
        private const val DRAIN_BUFFER_BYTES = 8192
        const val DRAIN_METRIC = "datapipelines.web.request_body_drain"
    }
}

/** Wiring for the request-body cap (pipeline-contract §13.21, configuration.md §3.31). */
@Configuration
class RequestLimitsConfiguration {
    @Bean
    fun requestBodyCapFilter(
        limits: RequestLimitsProperties,
        errorWriter: AuthErrorWriter,
        meterRegistry: MeterRegistry,
    ): RequestBodyCapFilter = RequestBodyCapFilter(limits, errorWriter, meterRegistry)

    @Bean
    fun requestBodyCapFilterRegistration(filter: RequestBodyCapFilter): FilterRegistrationBean<RequestBodyCapFilter> =
        FilterRegistrationBean(filter).apply {
            order = RequestBodyCapFilter.ORDER
            isAsyncSupported = true
            // The two JSON surfaces the cap is documented on (§13.21, §3.31, rest-api §4.2,
            // mcp-server §3) — never `/*`: the first version registered no pattern, so Spring
            // mapped it to every path and the wrapper reached the UI's form posts. Every
            // `@RequestBody` handler in `web` lives under `/api/v1`; a form body is Tomcat's
            // `max-http-form-post-size` to bound.
            urlPatterns = URL_PATTERNS
        }

    /**
     * The REST surface's ONE mapper — Spring Boot's auto-configured `ObjectMapper`, which every
     * `web` and `app` collaborator injects and which the request message converters read bodies
     * through — is built over [RequestLimits.jsonFactory], never the pinned version's inherited
     * defaults (pipeline-contract §13.21). The MCP transport's mapper is built over the same
     * factory in [co.datapipelines.mcp.McpServerFactory], and every route that parses its own
     * `String` body reads it through [RequestLimits.requestMapper] (#291).
     */
    @Bean
    fun requestStreamReadConstraintsCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder -> builder.factory(RequestLimits.jsonFactory()) }

    companion object {
        /**
         * The API prefix pattern and the MCP endpoint — the two documented surfaces. (A directory
         * mapping also matches its own path; the bare MCP path is listed for readers. The patterns
         * are not spelled in this comment: Kotlin block comments nest on the slash-star sequence.)
         */
        val URL_PATTERNS: List<String> = listOf("/api/v1/*", "/mcp", "/mcp/*")
    }
}
