package co.datapipelines.web.requestlimits

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.config.RequestLimitsProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The platform-wide request-body cap (#279, pipeline-contract §13.21, rest-api §4.2): a body
 * over `datapipelines.web.max-request-bytes` is refused with `413 request.body_too_large` on
 * BOTH JSON surfaces — REST `/api/v1` and MCP `/mcp` — before any handler and before any
 * parser reads the body.
 *
 * ## The two refusal paths
 *
 *  - **A declared `Content-Length` over the cap** is refused here, before the chain runs: no
 *    byte is read, no parser is reached. This is the path every standard client takes — HTTP
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
 * Tomcat drains afterwards, so an abandoned over-cap upload costs the connection, not the
 * server.
 */
class RequestBodyCapFilter(
    private val limits: RequestLimitsProperties,
    private val errorWriter: AuthErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cap = limits.maxRequestBytes
        val declared = request.contentLengthLong
        if (declared > cap) {
            refuse(request, response, cap)
            return
        }
        try {
            filterChain.doFilter(CountingRequestWrapper(request, cap), response)
        } catch (e: RequestBodyTooLargeException) {
            // Surfaces whose servlet does not intercept the marker reach here; where an advice
            // (REST) or the SDK (MCP) answered first, the writer's committed check makes this a no-op.
            refuse(request, response, e.limitBytes)
        }
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

    companion object {
        /** Just after the CORS filter ([co.datapipelines.web.CorrelationIdFilter.ORDER] + 1), before the security chain. */
        const val ORDER: Int = co.datapipelines.web.CorrelationIdFilter.ORDER + 2

        private val BODY_LESS_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}

/** Wiring for the request-body cap (pipeline-contract §13.21, configuration.md §3.31). */
@Configuration
class RequestLimitsConfiguration {
    @Bean
    fun requestBodyCapFilter(
        limits: RequestLimitsProperties,
        errorWriter: AuthErrorWriter,
    ): RequestBodyCapFilter = RequestBodyCapFilter(limits, errorWriter)

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
