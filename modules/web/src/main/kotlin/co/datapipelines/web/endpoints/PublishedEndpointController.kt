package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiErrorResponse
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.WebHeaders
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The published-endpoint surface: ONE handler for the whole `/api/x` subtree (ruling R-EP1).
 *
 * ## Why a catch-all and not registered routes
 *
 * A published endpoint is a ROW. Registering Spring request mappings at runtime would make
 * publishing a mutation of the servlet container's state — per-instance, invisible to a
 * transaction, lost on restart, and impossible to roll back together with the row that caused
 * it. So user paths never reach Spring's mapping: this handler owns the subtree and matches
 * against the registry itself (`EndpointMatcher`, which uses Spring's `PathPatternParser` as a
 * library). Engineers own everything beneath `/api/x`; the product's routes stay under
 * `/api/v1`, and nothing anyone publishes can shadow one.
 *
 * ## Everything here is HTTP shape
 *
 * Resolution, authorisation, validation and execution belong to [PublishedEndpointServeService].
 * This class turns a servlet request into that service's input and its outcome into a response.
 *
 * The `405` is answered here rather than left to Spring: with only `GET` mapped, another method
 * would get Spring's own 405 without an `Allow` header and without the product's error envelope,
 * and a `POST` to an unknown path would 404 instead — leaking which refusal came first.
 */
@RestController
@RequestMapping(PublishedEndpointController.ROOT)
class PublishedEndpointController(
    private val serveService: PublishedEndpointServeService,
) {
    /** Every method on the subtree; `GET` serves, everything else is a `405` carrying `Allow: GET`. */
    @RequestMapping("/**")
    @RequiredScope(ScopeMatrix.RestOperation.SERVE_PUBLISHED_ENDPOINT)
    fun serve(request: HttpServletRequest): ResponseEntity<Any> {
        if (request.method != HTTP_GET) {
            return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(baseHeaders())
                .header(HttpHeaders.ALLOW, HTTP_GET)
                .body(
                    ApiErrorResponse.of(
                        PipelineErrorCodes.Endpoint.METHOD_NOT_ALLOWED,
                        "Published endpoints answer GET only.",
                        mapOf("method" to request.method),
                    ),
                )
        }

        return when (val outcome = serveService.serve(pathOf(request), currentPrincipal(), validatorRequest(request))) {
            is PublishedEndpointServeService.Outcome.Served -> {
                ResponseEntity
                    .ok()
                    .headers(baseHeaders())
                    .header(WebHeaders.EXECUTION_ID, outcome.executionId.toString())
                    .body(outcome.payload)
            }

            is PublishedEndpointServeService.Outcome.Accepted -> {
                ResponseEntity
                    .accepted()
                    .headers(baseHeaders())
                    .header(WebHeaders.EXECUTION_ID, outcome.executionId.toString())
                    .body(outcome.body)
            }

            is PublishedEndpointServeService.Outcome.Refused -> {
                ResponseEntity
                    .status(outcome.status)
                    .headers(baseHeaders())
                    .body(ApiErrorResponse.of(outcome.code, outcome.message, outcome.details))
            }
        }
    }

    /**
     * The request path beneath `/api/x`, with its leading `/`.
     *
     * Read from the servlet path rather than a `@PathVariable`: the mapping is a wildcard, and
     * the container hands the URI back already decoded and normalised — which is what the matcher
     * wants, and what keeps `%2F` from smuggling an extra segment past the grammar.
     */
    private fun pathOf(request: HttpServletRequest): String {
        val beneath = request.requestURI.removePrefix(request.contextPath).removePrefix(ROOT)
        return beneath.ifEmpty { "/" }
    }

    /**
     * The servlet request as the validator's input.
     *
     * `pathVariables` is empty here on purpose: only the MATCHER knows which pattern won, and
     * therefore what `{borough}` was bound to. The service fills them in from its own match.
     */
    private fun validatorRequest(request: HttpServletRequest) =
        EndpointRequestValidator.Request(
            pathVariables = emptyMap(),
            queryParameters = request.parameterMap.mapValues { (_, values) -> values.toList() },
            accept = request.getHeader(HttpHeaders.ACCEPT),
            resultTtlSecondsHeader = request.getHeader(WebHeaders.RESULT_TTL),
            resultPageRowsHeader = request.getHeader(WebHeaders.RESULT_PAGE_ROWS),
        )

    /**
     * `Cache-Control: no-store` on every answer (§5.4), refusals included.
     *
     * A result is per-execution by design and the body carries a cursor bound to one TTL, so a
     * shared cache holding it would hand a second caller another caller's rows. And a cached
     * `403` would outlive the binding change that was meant to fix it.
     */
    private fun baseHeaders(): HttpHeaders =
        HttpHeaders().apply {
            cacheControl = "no-store"
            contentType = MediaType.APPLICATION_JSON
            set(CorrelationId.HEADER, CorrelationId.current())
        }

    companion object {
        /** Ruling R-EP1's root. Engineers own everything beneath it. */
        const val ROOT = "/api/x"

        private const val HTTP_GET = "GET"
    }
}
