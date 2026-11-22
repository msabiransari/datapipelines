package co.datapipelines.web.ui

import co.datapipelines.auth.SecurityHeaders
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.web.filter.OncePerRequestFilter
import java.io.PrintWriter

/**
 * An error answer on the font path is not storable (#1023 round 3, #1028).
 *
 * The security chain does not write its cache headers on a request the font handler answers
 * (`SecurityHeaders.isFontFile`): it must decide before the handler runs, so it cannot know whether the answer will be
 * the file or an error. Without this filter every error under the font prefix went out with no cache policy at all (a
 * missing name, a family directory: the handler's 404) or with the font rule itself (an unsatisfiable `Range` on an
 * existing file: the handler has already written the one-year rule when it sends 416).
 *
 * So, for exactly the requests that predicate admits, the response is wrapped: the moment a status of 400 or above is
 * set or sent ([NotStorableOnError]), the chain's three headers are SET, replacing whatever was written before, so the
 * font rule cannot survive on an error. A success (200, 206, 304) is not touched and keeps the font rule alone. Every
 * other request passes unwrapped. The three values are [CACHE_CONTROL], [PRAGMA] and [EXPIRES]; a unit test pins them
 * to what the chain's own writer answers on another path, so the two cannot drift.
 *
 * ## Ordering
 * Registered container-wide by [StaticAssetCacheConfig] at [ORDER], just ahead of the Spring Security chain, so the
 * wrapper also sees an error the chain itself answers on a font path (an MCP key used off its surface: 403, written
 * after the chain's header writer skipped its cache headers). Being outside the chain also means a request the
 * firewall refuses under the font prefix is answered with these headers. On the container's error dispatch the
 * request's path is the error page's, so the predicate is false and nothing is wrapped twice.
 *
 * ## Any origin may use a font file (#1064 D4)
 * A page of another origin that loads the client library's stylesheet fetches its fonts from here in CORS mode, and a
 * browser refuses a font answer without `Access-Control-Allow-Origin` (measured: six of six faces failed). So a
 * SUCCESSFUL answer of a font file (200, 206, 304; GET or HEAD; with or without an `Origin` on the request) carries
 * [ALLOW_ORIGIN_HEADER]: [ANY_ORIGIN] ([AnyOriginOnSuccess], inside the wrapper above). An answer of 400 or above never
 * does — a missing name (404), a range the file cannot satisfy (416), the chain's own refusal (403). The handler sends
 * 416 AFTER writing its success headers and a servlet response cannot take a header back, so the header is not written
 * with them: it is written at the moment the outcome is known — when the body starts (the status is final by then),
 * when the response is flushed, or when the chain returns with nothing committed (a HEAD, a 304) — and only if the
 * status then is below 400. The star, never an echoed origin: the bytes are public and the same for every visitor,
 * and the answer is kept a year by any cache, so an echoed origin would be wrong for the next site. No credentials
 * header, no other path, no preflight (a font fetch sends none).
 */
class FontErrorCacheFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!SecurityHeaders.isFontFile(request)) {
            filterChain.doFilter(request, response)
            return
        }
        val anyOrigin = AnyOriginOnSuccess(response)
        filterChain.doFilter(request, NotStorableOnError(anyOrigin))
        anyOrigin.chainReturned()
    }

    /**
     * A response that sets [ALLOW_ORIGIN_HEADER]: [ANY_ORIGIN] when its outcome is known and is a success: when the
     * body is first asked for, when it is flushed, or when the chain returns with nothing committed
     * ([chainReturned]), each only while the status is below [FIRST_ERROR_STATUS] and the response is not committed.
     */
    class AnyOriginOnSuccess(
        response: HttpServletResponse,
    ) : HttpServletResponseWrapper(response) {
        override fun getOutputStream(): ServletOutputStream {
            allowIfSuccess()
            return super.getOutputStream()
        }

        override fun getWriter(): PrintWriter {
            allowIfSuccess()
            return super.getWriter()
        }

        override fun flushBuffer() {
            allowIfSuccess()
            super.flushBuffer()
        }

        /** The chain has returned: an answer with no body (a HEAD, a 304) gets the header here, if a success. */
        fun chainReturned() = allowIfSuccess()

        private fun allowIfSuccess() {
            if (status < FIRST_ERROR_STATUS && !isCommitted && !containsHeader(ALLOW_ORIGIN_HEADER)) {
                setHeader(ALLOW_ORIGIN_HEADER, ANY_ORIGIN)
            }
        }
    }

    /**
     * A response that sets [CACHE_CONTROL], [PRAGMA] and [EXPIRES] when a status of [FIRST_ERROR_STATUS] or above is
     * set or sent, before the status reaches the container (after `sendError` no header may change).
     */
    class NotStorableOnError(
        response: HttpServletResponse,
    ) : HttpServletResponseWrapper(response) {
        override fun setStatus(sc: Int) {
            notStorableIfError(sc)
            super.setStatus(sc)
        }

        override fun sendError(sc: Int) {
            notStorableIfError(sc)
            super.sendError(sc)
        }

        override fun sendError(
            sc: Int,
            msg: String?,
        ) {
            notStorableIfError(sc)
            super.sendError(sc, msg)
        }

        private fun notStorableIfError(status: Int) {
            if (status >= FIRST_ERROR_STATUS) {
                setHeader("Cache-Control", CACHE_CONTROL)
                setHeader("Pragma", PRAGMA)
                setHeader("Expires", EXPIRES)
            }
        }
    }

    companion object {
        /** Just ahead of the Spring Security chain (`SecurityProperties.DEFAULT_FILTER_ORDER` = -100). */
        const val ORDER: Int = SecurityProperties.DEFAULT_FILTER_ORDER - 1

        /** The first status that is an error: a client error (4xx) or a server error (5xx). */
        const val FIRST_ERROR_STATUS: Int = 400

        /** The security chain's own `Cache-Control` (its framework writer's value, pinned by the unit test). */
        const val CACHE_CONTROL: String = "no-cache, no-store, max-age=0, must-revalidate"

        /** The security chain's own `Pragma`. */
        const val PRAGMA: String = "no-cache"

        /** The security chain's own `Expires`. */
        const val EXPIRES: String = "0"

        /** The header that lets a page of another origin use a font file (#1064 D4). */
        const val ALLOW_ORIGIN_HEADER: String = "Access-Control-Allow-Origin"

        /** Any origin: the font files are public and identical for every visitor. */
        const val ANY_ORIGIN: String = "*"
    }
}
