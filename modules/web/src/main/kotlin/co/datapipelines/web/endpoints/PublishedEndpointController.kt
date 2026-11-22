package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.appPath
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiErrorResponse
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.WebHeaders
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The published-endpoint surface: ONE handler for the published subtree (ruling R-EP5).
 *
 * ## Why a catch-all and not registered routes
 *
 * A published endpoint is a ROW. Registering Spring request mappings at runtime would make
 * publishing a mutation of the servlet container's state — per-instance, invisible to a
 * transaction, lost on restart, and impossible to roll back together with the row that caused
 * it. So user paths never reach Spring's mapping: this handler owns the subtree and matches
 * against the registry itself (`EndpointMatcher`, which uses Spring's `PathPatternParser` as a
 * library).
 *
 * ## The mapping IS the reservation (R-EP5)
 *
 * The catch-all is `/api/{category}/…` with the category constrained IN THE PATTERN
 * ([EndpointPath.CATEGORY_URL_PATTERN]): anything matching `v[0-9]+` — the product's own API
 * namespace — and the literal `api` do not match this handler at all, so a published path can
 * never shadow a product route by construction, and an unknown `/api/v1/…` path falls through
 * to the product's own 404 rather than the endpoint one. Engineers own every other category
 * beneath `/api`.
 *
 * ## Everything here is HTTP shape
 *
 * Resolution, authorisation, validation and execution belong to [PublishedEndpointServeService].
 * This class turns a servlet request into that service's input and its outcome into a response.
 *
 * The method refusal is answered by the SERVICE, not left to Spring: with no method mapped,
 * another verb would get Spring's own 405 without an `Allow` header and without the product's
 * error envelope, and a `POST` to an unknown path would 404 instead of the service's opaque
 * 404 — leaking which refusal came first. Since #1037 the `Allow` a `405` carries is exactly the
 * methods the presenting key can call at that path (published-mutation-apis §4.4), which only
 * the service can compute; the paging delegation stays GET-only, so a POST to a paging URL
 * takes the mutation gate like any other non-GET path.
 */
@RestController
@RequestMapping(PublishedEndpointController.ROOT)
class PublishedEndpointController(
    private val serveService: PublishedEndpointServeService,
    /** Keys v2 A16/B3 — the business-path paging read, reached THROUGH this catch-all (see its KDoc). */
    private val pagingService: PublishedExecutionPagingService,
) {
    /** Every method on the subtree; `GET` serves, everything else answers the method gate (§4.4). */
    @RequestMapping(CATCH_ALL)
    @RequiredScope(Permission.ENDPOINT_SERVE)
    fun serve(request: HttpServletRequest): ResponseEntity<Any> {
        val method = EndpointMethod.of(request.method)
        val path = pathOf(request)

        if (method == EndpointMethod.GET) {
            // Keys v2 A16/B3: the paging suffix is the key's own-run read under the business path.
            // Delegation happens after the serve path's session refusal — the shared rule order of
            // §5 — so a session on a paging route is refused exactly as on a serve route.
            parse(path)?.let { paged ->
                val principal = currentPrincipal()
                return if (paged.isResult) {
                    pagingService.executionResult(
                        businessPath = paged.businessPath,
                        executionId = paged.executionId,
                        principal = principal,
                        offset = request.getParameter("offset")?.toLongOrNull(),
                        limit = request.getParameter("limit")?.toIntOrNull(),
                        format = request.getParameter("format"),
                    )
                } else {
                    pagingService.executionMetadata(paged.businessPath, paged.executionId, principal)
                }
            }
        }

        val outcome =
            if (method == EndpointMethod.GET) {
                serveService.serve(path, method, currentPrincipal(), validatorRequest(request))
            } else {
                // #1039 — the query string is read from the URI itself, never through the parameter map: on a PUT or
                // DELETE the container's form filter would read (and consume) a form body to build that map.
                val query = queryOf(request.queryString) ?: return malformedQuery()
                serveService.serve(path, method, currentPrincipal(), validatorRequest(request, query), mutationInput(request))
            }
        return when (outcome) {
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
                    .apply { outcome.allow?.let { allow -> header(HttpHeaders.ALLOW, allow.joinToString(", ")) } }
                    .body(ApiErrorResponse.of(outcome.code, outcome.message, outcome.details))
            }
        }
    }

    /**
     * The request path beneath `/api`, with its leading `/`.
     *
     * Read from the servlet path rather than a `@PathVariable`: the mapping is a wildcard, and
     * the container hands the URI back already decoded and normalised — which is what the matcher
     * wants, and what keeps `%2F` from smuggling an extra segment past the grammar.
     */
    private fun pathOf(request: HttpServletRequest): String {
        val beneath = request.appPath().removePrefix(ROOT)
        return beneath.ifEmpty { "/" }
    }

    /**
     * The servlet request as the validator's input.
     *
     * `pathVariables` is empty here on purpose: only the MATCHER knows which pattern won, and
     * therefore what `{borough}` was bound to. The service fills them in from its own match.
     */
    private fun validatorRequest(
        request: HttpServletRequest,
        query: Map<String, List<String>> = request.parameterMap.mapValues { (_, values) -> values.toList() },
    ) = EndpointRequestValidator.Request(
        pathVariables = emptyMap(),
        queryParameters = query,
        accept = request.getHeader(HttpHeaders.ACCEPT),
        resultTtlSecondsHeader = request.getHeader(WebHeaders.RESULT_TTL),
        resultPageRowsHeader = request.getHeader(WebHeaders.RESULT_PAGE_ROWS),
    )

    /** The raw mutation inputs (#1039), handed to the service unread; nothing here is logged or echoed. */
    private fun mutationInput(request: HttpServletRequest) =
        MutationInput(
            idempotencyKey = request.getHeader(WebHeaders.IDEMPOTENCY_KEY),
            contentType = request.contentType,
            contentEncoding = request.getHeader(HttpHeaders.CONTENT_ENCODING),
            entity = request.inputStream,
            declaredLength = request.contentLengthLong,
        )

    /** A query string that is not valid percent-encoding: refused, never half-read — a dropped parameter would run. */
    private fun malformedQuery(): ResponseEntity<Any> =
        ResponseEntity
            .badRequest()
            .headers(baseHeaders())
            .body(
                ApiErrorResponse.of(
                    PipelineErrorCodes.EndpointRequest.INVALID,
                    "The request has 1 problem(s); details.errors lists them (at most 20).",
                    mapOf(
                        "errors" to
                            listOf(
                                mapOf(
                                    "parameter" to null,
                                    "code" to PipelineErrorCodes.EndpointRequest.INVALID,
                                    "message" to "The query string is not valid percent-encoded UTF-8.",
                                ),
                            ),
                        "truncated" to false,
                    ),
                ),
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
            // DP-Correlation-Id is NOT set here: `CorrelationIdFilter` already puts it on every
            // response, and setting it again emitted the header TWICE on a live serve — found by
            // reading the first real `curl -D -` rather than by any test, since MockMvc and
            // RestAssured both read a duplicated header as its first value.
        }

    companion object {
        /**
         * R-EP5's root: the published subtree is `/api/<category>/<version>/<path…>`, and the
         * product's own routes are the reserved `v<n>` categories of the same tree.
         */
        const val ROOT = "/api"

        /**
         * The catch-all, with the category constrained in the pattern itself
         * ([EndpointPath.CATEGORY_URL_PATTERN]) — a reserved category does not match this handler
         * at all, so `/api/v1/…` belongs to the product whether or not a route there exists.
         */
        const val CATCH_ALL = "/{category:" + EndpointPath.CATEGORY_URL_PATTERN + "}/**"

        /**
         * The keys-v2 paging suffix (A16/B3): `<business path>/executions/{id}` or
         * `<business path>/executions/{id}/result`, where the business path is at least
         * `<category>/<version>`. Parsed HERE so the serve controller can hand the request to
         * [PublishedExecutionPagingService] before the registry is consulted.
         */
        private val PAGING = Regex("^(/[^/]+/[^/]+(?:/[^/]+)*)/executions/([0-9a-fA-F-]{36})(/result)?$")
        private val EXECUTION_ID = Regex("^[0-9a-fA-F-]{36}$")

        /** A parsed paging path: the business path without the suffix, the run, and which read. */
        data class Paged(
            val businessPath: String,
            val executionId: UUID,
            val isResult: Boolean,
        )

        /**
         * The query string as name → values in order (`+` is a space, as the servlet container reads it); null when
         * any part is not valid percent-encoded UTF-8. An empty or absent query string is an empty map.
         */
        internal fun queryOf(raw: String?): Map<String, List<String>>? {
            if (raw.isNullOrEmpty()) return emptyMap()
            val result = LinkedHashMap<String, MutableList<String>>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val cut = pair.indexOf('=')
                val name = decoded(if (cut < 0) pair else pair.substring(0, cut)) ?: return null
                val value = if (cut < 0) "" else decoded(pair.substring(cut + 1)) ?: return null
                result.getOrPut(name) { mutableListOf() } += value
            }
            return result
        }

        private fun decoded(part: String): String? =
            try {
                URLDecoder.decode(part, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }

        fun parse(path: String): Paged? {
            val match = PAGING.matchEntire(path) ?: return null
            // A strict UUID or the shape is not paging — it may be an ordinary served path whose
            // last segment happens to look like one, so fall through to the registry.
            val id = runCatching { UUID.fromString(match.groupValues[2]) }.getOrNull() ?: return null
            return Paged(
                businessPath = match.groupValues[1],
                executionId = id,
                isResult = match.groupValues[3].isNotEmpty(),
            )
        }
    }
}
