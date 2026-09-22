package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** §19.5 publish body. The pipeline is named, not id'd — the name is the portable identity. */
data class CreateEndpointRequest(
    @field:JsonProperty("path") @get:JsonProperty("path") @param:JsonProperty("path")
    val path: String,
    @field:JsonProperty("pipeline") @get:JsonProperty("pipeline") @param:JsonProperty("pipeline")
    val pipeline: String,
    @field:JsonProperty("timeout_seconds") @get:JsonProperty("timeout_seconds") @param:JsonProperty("timeout_seconds")
    val timeoutSeconds: Int? = null,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
)

/**
 * §19.5 binding body — a key by NAME, so the same request shape works on any deployment,
 * or (179, D17) by ID, which is what the `/api-keys` page's association UI sends: a name is
 * owner-scoped and ambiguous deployment-wide, an id is neither.
 */
data class BindEndpointKeyRequest(
    @field:JsonProperty("path_prefix") @get:JsonProperty("path_prefix") @param:JsonProperty("path_prefix")
    val pathPrefix: String,
    @field:JsonProperty("api_key_name") @get:JsonProperty("api_key_name") @param:JsonProperty("api_key_name")
    val apiKeyName: String? = null,
    @field:JsonProperty("api_key_id") @get:JsonProperty("api_key_id") @param:JsonProperty("api_key_id")
    val apiKeyId: String? = null,
)

/**
 * The published-endpoint management surface (rest-api §19.5).
 *
 * ## Single-form addressing
 *
 * An endpoint is addressed by `?path=`, never by a path segment. The reason is measured and the
 * same one that moved templates to query addressing in 043: a `path_pattern` contains `/`, and on
 * the pinned Tomcat an encoded `%2F` is refused with a `400` below routing and below the security
 * chain, so `/api/v1/endpoints/{path}` cannot carry `/lending/{borough}/home` at all.
 *
 * ## Why publishing is `author` and binding is the workspace admin's
 *
 * Publishing exposes a released pipeline at a URL — an authoring act over content the author
 * already owns (`MANAGE_ENDPOINTS`, D9). Binding hands a CREDENTIAL authority over a subtree:
 * since 179 (D17) that is `MANAGE_API_KEYS`, the workspace admin's row, and the key is
 * addressed by id (the `/api-keys` page) or — the pre-179 shape, kept — by the caller's OWN
 * key's name.
 */
@RestController
@RequestMapping("/api/v1/endpoints")
class EndpointsController(
    private val publishing: EndpointPublishService,
    private val endpointKeys: EndpointKeyService,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
    private val pipelines: PipelineRepository,
) {
    /**
     * §19.5 — publish. Transactional because the §4.1 ambiguity check takes a
     * transaction-scoped advisory lock; without one it would be released at the SELECT.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_ENDPOINTS)
    @Transactional("metadataTransactionManager")
    fun create(
        @RequestBody body: CreateEndpointRequest,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.of(
            publishing
                .publish(
                    principal = currentPrincipal(),
                    pathPattern = body.path,
                    pipelineName = body.pipeline,
                    timeoutSeconds = body.timeoutSeconds,
                    description = body.description.orEmpty(),
                ).toResponse(),
        )

    /** §19.5 — the workspace's endpoints, or one of them with `?path=`. */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        @RequestParam(required = false) path: String?,
    ): ApiResponse<Any> {
        val principal = currentPrincipal()
        return if (path == null) {
            ApiResponse.of(publishing.list(principal).map { it.toResponse() })
        } else {
            val endpoint = publishing.get(principal, path) ?: throw notFound(path)
            ApiResponse.of(endpoint.toResponse())
        }
    }

    /** §19.5 — unpublish. Idempotent-ish: an unknown path is a 404, never a silent success. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_ENDPOINTS)
    @Transactional("metadataTransactionManager")
    fun delete(
        @RequestParam path: String,
    ) {
        if (!publishing.unpublish(currentPrincipal(), path)) throw notFound(path)
    }

    /**
     * §19.5 — bind a key to a node of the tree.
     *
     * 179 (D17): this route moved from `MANAGE_ENDPOINTS` to `MANAGE_API_KEYS` — association
     * is the workspace admin's verb now, on both request shapes. The BY-NAME shape is kept
     * for REST compatibility: it resolves within the caller's OWN keys, exactly as before
     * (an admin binding their own named key), and nothing else changed about it.
     */
    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_API_KEYS)
    @Transactional("metadataTransactionManager")
    fun bind(
        @RequestBody body: BindEndpointKeyRequest,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val key = resolveKey(body.apiKeyId, body.apiKeyName)
        endpointKeys.bind(principal, key.id, body.pathPrefix)
        return ApiResponse.of(mapOf("path_prefix" to body.pathPrefix, "api_key_name" to key.name, "api_key_id" to key.id))
    }

    /** §19.5 — unbind. Both addressing forms, exactly as [bind]. */
    @DeleteMapping("/bindings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_API_KEYS)
    @Transactional("metadataTransactionManager")
    fun unbind(
        @RequestParam pathPrefix: String,
        @RequestParam(required = false) apiKeyName: String?,
        @RequestParam(required = false) apiKeyId: String?,
    ) {
        val key = resolveKey(apiKeyId, apiKeyName)
        if (!endpointKeys.unbind(currentPrincipal(), key.id, pathPrefix)) {
            throw notFound(pathPrefix)
        }
    }

    /**
     * The key a binding request addresses. BY ID (the `/api-keys` page's shape): an
     * `endpoint` key of the ACTIVE workspace — a foreign or wrong-kind id is not-found,
     * the non-disclosure rule the whole key surface follows. BY NAME (REST compatibility):
     * the caller's OWN live key, owner-scoped in SQL exactly as before 179.
     */
    private fun resolveKey(
        apiKeyId: String?,
        apiKeyName: String?,
    ): ApiKey {
        if (apiKeyId != null) {
            val key = apiKeys.findById(apiKeyId)
            val foreign =
                key == null || key.kind != ApiKeyKind.ENDPOINT || key.isRevoked ||
                    key.workspaceId != currentPrincipal().requireWorkspace().id
            if (foreign) {
                throw ApiException(
                    PipelineErrorCodes.Endpoint.NOT_FOUND,
                    "No API key with that id in this workspace.",
                    mapOf("api_key_id" to apiKeyId),
                )
            }
            return key
        }
        if (apiKeyName != null) return requireOwnedKey(apiKeyName)
        throw ApiException(
            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
            "A binding names its key by api_key_id or api_key_name.",
            mapOf("reason" to "key_reference_missing"),
        )
    }

    /**
     * The caller's own key with this name.
     *
     * Binding grants a CREDENTIAL authority over a subtree, so someone who cannot manage the key
     * must not be able to widen it. In v1 that means the OWNER only — an admin binds their own
     * keys like anyone else.
     *
     * Binding another user's key by name is deliberately not offered, and the reason is factual
     * rather than cautious: `api_keys.name` carries no uniqueness constraint (metadata-db §4.2),
     * so "the key named `ci`" is ambiguous deployment-wide, and a surface that resolved it would
     * have to pick one person's credential to widen. If cross-user binding is wanted it needs the
     * key ID, which is a separate decision.
     *
     * A key belonging to someone else is reported as **not found** rather than forbidden — the
     * same non-disclosure rule the rest of the key surface follows.
     */
    private fun requireOwnedKey(name: String) =
        apiKeys
            .findByUser(currentPrincipal().userId)
            .firstOrNull { it.name == name && !it.isRevoked }
            ?: throw ApiException(
                PipelineErrorCodes.Auth.API_KEY_INVALID,
                "No API key named '$name' that you can bind.",
                mapOf("api_key_name" to name),
            )

    private fun notFound(path: String) =
        ApiException(
            PipelineErrorCodes.Endpoint.NOT_FOUND,
            "No endpoint published at '$path'.",
            mapOf("path" to path),
        )

    /**
     * The outbound shape. The pipeline travels by NAME, so a reader gets the portable identity;
     * `url` is the full served URL (R-EP5: the stored pattern plus the `/api` root).
     */
    private fun PublishedEndpoint.toResponse(): Map<String, Any?> =
        mapOf(
            "path" to pathPattern,
            "pipeline" to pipelines.findById(workspaceId, pipelineId)?.name,
            "timeout_seconds" to timeoutSeconds,
            "description" to description,
            "enabled" to isEnabled,
            "path_variables" to pathVariables,
            "url" to "/api$pathPattern",
            // #191 — this endpoint's workspace's bindings only: a foreign row is inert at serve
            // time and its key id is nobody else's to list. Both predicates in the query (#199).
            "bindings" to bindings.findByPrefixes(listOf(pathPattern), workspaceId).map { it.apiKeyId },
            "created_at" to createdAt.toString(),
            "updated_at" to updatedAt.toString(),
        )
}
