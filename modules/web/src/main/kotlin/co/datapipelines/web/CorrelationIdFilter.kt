package co.datapipelines.web

import co.datapipelines.web.api.CorrelationId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Establishes the request's correlation id (rest-api.md §3.4, observability.md §3.3).
 *
 * Adopts an inbound `DP-Correlation-Id` that parses as a UUID, mints one otherwise
 * ([CorrelationId.resolve]), puts it in the MDC for the whole request, and echoes it on the
 * response — set **before** the chain runs so it survives a response committed downstream (an
 * SSE stream commits its headers on the first event; a filter that set the header on the way
 * back out would silently lose it there).
 *
 * ## Ordering
 * Registered at the highest precedence so the id exists before Spring Security rejects anything:
 * `auth`'s [co.datapipelines.auth.AuthErrorWriter] falls back to this MDC slot, so a 401 from the
 * filter chain carries the same id as a 200 from a controller. Being ahead of the security chain
 * also means an unauthenticated request still gets a quotable id — which is the whole point of
 * §3.4 for support.
 *
 * The MDC is cleared in a `finally`: leaving the slot populated would leak this request's id onto
 * whatever the container's thread does next.
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val id = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER))
        MDC.put(CorrelationId.MDC_KEY, id.toString())
        response.setHeader(CorrelationId.HEADER, id.toString())
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CorrelationId.MDC_KEY)
        }
    }

    /** Never skip: probes and static assets are logged too, and an id costs one UUID. */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    companion object {
        /** Ahead of Spring Security (`SecurityProperties.DEFAULT_FILTER_ORDER` = -100). */
        const val ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 10
    }
}
