package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.executions.ExecutionMetadataProjection
import co.datapipelines.web.executions.ExecutionVisibility
import co.datapipelines.web.executions.ResultCursor
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.web.api.ApiException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.UUID

/**
 * Business-path result paging (keys v2 A16/B3, #233) — `GET /api/<category>/v<n>/<path>/executions/{id}`
 * and `…/executions/{id}/result`, the two reads a published endpoint's caller was given on the
 * FRAMEWORK's own execution routes and now gets under the business path it is bound to. The
 * framework reads (`/api/v1/executions/{id}`[`/result`]) are session-only from keys v2 on; this
 * is the key's whole remaining reach for its own runs.
 *
 * ## Why this is reached THROUGH the serve controller
 *
 * The published subtree is one catch-all mapping (`PublishedEndpointController`, R-EP5): paging
 * paths — whose middle is a variable-length business path — cannot be separated from serving at
 * the mapping level without shadowing it. So the serve handler recognises the paging suffix and
 * delegates here; the mapping the walk sees stays the serve route's, and every rule the serve
 * path already applies is inherited for free: sessions are refused before this runs
 * (`auth.session.required`, the serve route's answer), an `mcp` or `server` key never passes the
 * kind table, and `ScopeInterceptor.reachableBy` admits an `endpoint` key to the whole published
 * tree only.
 *
 * ## The rule, in order (§5's resolve-authorise shape, kept)
 *
 * 1. **Bound** — the same hierarchical walk the serve path applies ([EndpointAuthorizer] over
 *    the business path's ancestors, the key's OWN workspace's bindings): the first ancestor
 *    carrying any binding decides, and an unbound path authorises nothing. "Bound to that path"
 *    (B3) is this walk, nothing looser.
 * 2. **Own** — the execution must exist in the key's pinned workspace AND be one the key
 *    STARTED ([ExecutionVisibility]: since V34 the run's `executed_by` is the key's identity).
 *    Anyone else's run — another key's, a person's — is the plain
 *    `404 result.execution_not_found`: the URL is not a capability.
 * 3. Then the identical semantics the framework route serves: the §10.2 metadata projection
 *    (shared, never re-derived), and the §7 result cursor's format/status/TTL table.
 */
class PublishedExecutionPagingService(
    private val executions: ExecutionRepository,
    private val cursor: ResultCursor,
    private val visibility: ExecutionVisibility,
    private val metadata: ExecutionMetadataProjection,
    private val authorizer: EndpointAuthorizer,
    private val bindings: EndpointKeyBindingRepository,
) {
    /**
     * `GET …/executions/{id}` — the §10.2 metadata of a run the key started, under the business
     * path it is bound to.
     */
    fun executionMetadata(
        businessPath: String,
        executionId: UUID,
        principal: AuthenticatedPrincipal,
    ): ResponseEntity<Any> {
        requireBound(businessPath, principal)
        val record = ownExecution(executionId, principal)
        return ResponseEntity.ok(co.datapipelines.web.api.ApiResponse.of(metadata.project(record, principal.requireWorkspace().id, includeResult = true)))
    }

    /**
     * `GET …/executions/{id}/result` — the §7 cursor under the business path: `format=json`
     * pages through the envelope, `csv` streams. The format/status table and its codes are the
     * cursor's, unchanged.
     */
    fun executionResult(
        businessPath: String,
        executionId: UUID,
        principal: AuthenticatedPrincipal,
        offset: Long?,
        limit: Int?,
        format: String?,
    ): ResponseEntity<Any> {
        requireBound(businessPath, principal)
        val chosen = cursor.formatOf(format)
        val record = cursor.readable(executionId, principal)
        return if (chosen == ResultCursor.FORMAT_CSV) {
            val body = StreamingResponseBody { out -> cursor.writeCsv(record, out) }
            ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).body<Any>(body)
        } else {
            val page = cursor.jsonPage(record, offset ?: 0L, limit)
            ResponseEntity.ok(co.datapipelines.web.api.ApiResponse.of(page))
        }
    }

    /**
     * Rule 1 — BOUND. The hierarchical walk over the BUSINESS path (the paging suffix removed),
     * against the pinned workspace's bindings. Refused with the serve path's own codes and
     * statuses, so a caller paging under an unbound path learns exactly what a caller SERVING
     * an unbound path learns.
     */
    private fun requireBound(
        businessPath: String,
        principal: AuthenticatedPrincipal,
    ) {
        if (principal.authMethod != AuthMethod.API_KEY) {
            // Sessions are refused by the serve handler before this runs; this is the second line.
            throw ApiException(
                PipelineErrorCodes.Auth.SESSION_REQUIRED,
                "Published endpoints are a machine surface: present an API key, not a browser session.",
                emptyMap(),
            )
        }
        val workspaceId = principal.requireWorkspace().id
        val ancestors = EndpointPath.ancestors(businessPath)
        val decision =
            authorizer.authorize(businessPath, principal, workspaceId, bindings.findByPrefixes(ancestors, workspaceId))
        when (decision) {
            is EndpointAuthorizer.Decision.Allowed -> Unit
            is EndpointAuthorizer.Decision.Refused ->
                throw DatapipelinesException(
                    code = decision.code,
                    message = decision.message,
                    details = mapOf("path" to businessPath),
                )
        }
    }

    /**
     * Rule 2 — OWN. The run must live in the key's pinned workspace and be one the key started;
     * anything else is the not-found answer, never a 403 (the URL is not a capability).
     */
    private fun ownExecution(
        executionId: UUID,
        principal: AuthenticatedPrincipal,
    ) = executions.findById(principal.requireWorkspace().id, executionId)
        ?.takeIf { visibility.visible(it, principal, executionId) }
        ?: throw ApiErrors.executionNotFound(executionId.toString())
}
