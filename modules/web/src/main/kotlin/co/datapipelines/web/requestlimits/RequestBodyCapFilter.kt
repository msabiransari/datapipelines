package co.datapipelines.web.requestlimits

import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.config.RequestLimitsProperties
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
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
import java.nio.charset.Charset

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
        private val countingStream: CountingServletInputStream = CountingServletInputStream(request.getInputStream(), cap)

        override fun getInputStream(): ServletInputStream = countingStream

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(countingStream, characterEncoding ?: Charset.defaultCharset().name()))
    }

    /** The counting delegate — the whole memory story is the one [cap]-sized high-water mark. */
    private class CountingServletInputStream(
        private val delegate: jakarta.servlet.ServletInputStream,
        private val cap: Long,
    ) : ServletInputStream() {
        private var count = 0L

        override fun read(): Int {
            if (count >= cap) throw RequestBodyTooLargeException(cap)
            val b = delegate.read()
            if (b >= 0) count++
            return b
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (count >= cap) throw RequestBodyTooLargeException(cap)
            // Clamp so the underlying stream is never pulled past the cap.
            val allowed = minOf(len.toLong(), cap - count).toInt()
            val n = delegate.read(b, off, allowed)
            if (n > 0) count += n
            return n
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
        }

    /**
     * The REST surface's ONE mapper — Spring Boot's auto-configured `ObjectMapper`, which every
     * `web` and `app` collaborator injects and which the request message converters read bodies
     * through — is built over a `JsonFactory` carrying the stated [StreamReadConstraints],
     * never the pinned version's inherited defaults (pipeline-contract §13.21). The MCP
     * transport's mapper carries the same constraints, built from the same constants in
     * [co.datapipelines.mcp.McpServerFactory].
     */
    @Bean
    fun requestStreamReadConstraintsCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder.factory(
                JsonFactory
                    .builder()
                    .streamReadConstraints(streamReadConstraints())
                    .build(),
            )
        }

    companion object {
        /** The stated constraints, from the one constants home (`pipeline-contract`'s [RequestLimits]). */
        fun streamReadConstraints(): StreamReadConstraints =
            StreamReadConstraints
                .builder()
                .maxNestingDepth(RequestLimits.MAX_NESTING_DEPTH)
                .maxStringLength(RequestLimits.MAX_STRING_LENGTH)
                .maxNumberLength(RequestLimits.MAX_NUMBER_LENGTH)
                .build()
    }
}
