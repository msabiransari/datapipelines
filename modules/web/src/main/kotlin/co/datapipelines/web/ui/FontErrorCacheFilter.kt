package co.datapipelines.web.ui

import co.datapipelines.auth.SecurityHeaders
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.web.filter.OncePerRequestFilter

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
 */
class FontErrorCacheFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        filterChain.doFilter(request, if (SecurityHeaders.isFontFile(request)) NotStorableOnError(response) else response)
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
    }
}
