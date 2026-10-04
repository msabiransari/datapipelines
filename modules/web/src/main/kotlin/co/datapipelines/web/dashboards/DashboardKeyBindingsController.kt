package co.datapipelines.web.dashboards

import co.datapipelines.application.dashboards.DashboardKeyService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.requestlimits.StrictRequestBodies
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * §23.2 binding body — the `BindEndpointKeyRequest` twin: a `dashboard` key by ID (the Keys
 * page's shape) or by the caller's OWN key's name (REST compatibility, the pre-179 endpoint
 * shape). `name_prefix` is a folder of the dashboard name space, or the root `/`.
 */
data class BindDashboardKeyRequest(
    @field:JsonProperty("name_prefix") @get:JsonProperty("name_prefix") @param:JsonProperty("name_prefix")
    val namePrefix: String,
    @field:JsonProperty("api_key_name") @get:JsonProperty("api_key_name") @param:JsonProperty("api_key_name")
    val apiKeyName: String? = null,
    @field:JsonProperty("api_key_id") @get:JsonProperty("api_key_id") @param:JsonProperty("api_key_id")
    val apiKeyId: String? = null,
)

/**
 * The `dashboard` key's binding routes (rest-api §23.2, L5): bind a key to a folder of the
 * dashboard name space, unbind it. `EndpointsController`'s binding pair, copied route for route:
 * `dashboard.key.bind` here where the twin declares `api_key.bind`, the key resolved BY ID (a
 * `dashboard` key of the ACTIVE workspace — a foreign or wrong-kind id is not-found, the
 * non-disclosure rule the whole key surface follows) or BY NAME (the caller's OWN live key,
 * kind-filtered — the fix the endpoint twin's by-name path took in this lane, kept here from
 * birth). Binding and unbinding are audited `dashboard.key_bound` / `dashboard.key_unbound`.
 */
@RestController
@RequestMapping("/api/v1/dashboards")
class DashboardKeyBindingsController(
    private val dashboardKeys: DashboardKeyService,
    private val apiKeys: ApiKeyRepository,
) {
    /** §23.2 — bind a `dashboard` key to a folder of the dashboard name space. */
    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.DASHBOARD_KEY_BIND)
    @Transactional("metadataTransactionManager")
    fun bind(
        @RequestBody tree: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        // #382: the strict read - see StrictRequestBodies.
        val body = StrictRequestBodies.bind<BindDashboardKeyRequest>(tree)
        val principal = currentPrincipal()
        val key = resolveKey(body.apiKeyId, body.apiKeyName)
        dashboardKeys.bind(principal, key.id, body.namePrefix)
        return ApiResponse.of(mapOf("name_prefix" to body.namePrefix, "api_key_name" to key.name, "api_key_id" to key.id))
    }

    /** §23.2 — unbind. Both addressing forms, exactly as [bind]. */
    @DeleteMapping("/bindings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(Permission.DASHBOARD_KEY_BIND)
    @Transactional("metadataTransactionManager")
    fun unbind(
        @RequestParam namePrefix: String,
        @RequestParam(required = false) apiKeyName: String?,
        @RequestParam(required = false) apiKeyId: String?,
    ) {
        val key = resolveKey(apiKeyId, apiKeyName)
        if (!dashboardKeys.unbind(currentPrincipal(), key.id, namePrefix)) {
            throw notFound(namePrefix)
        }
    }

    /**
     * The key a binding request addresses. BY ID: a `dashboard` key of the ACTIVE workspace — a
     * foreign or wrong-kind id is not-found. BY NAME: the caller's OWN live `dashboard` key,
     * owner-scoped in SQL and KIND-FILTERED — a dashboard binding on any other kind would be
     * rows nothing reads (the gap the endpoint twin's by-name path had; closed there in this
     * lane and never opened here).
     */
    private fun resolveKey(
        apiKeyId: String?,
        apiKeyName: String?,
    ): ApiKey {
        if (apiKeyId != null) {
            val key = apiKeys.findById(apiKeyId)
            val foreign =
                key == null || key.kind != ApiKeyKind.DASHBOARD || key.isRevoked ||
                    key.workspaceId != currentPrincipal().requireWorkspace().id
            if (foreign) {
                throw ApiException(
                    DashboardErrorCodes.NOT_FOUND,
                    "No API key with that id in this workspace.",
                    mapOf("api_key_id" to apiKeyId),
                )
            }
            return key
        }
        if (apiKeyName != null) return requireOwnedKey(apiKeyName)
        throw ApiException(
            DashboardErrorCodes.NOT_FOUND,
            "A binding names its key by api_key_id or api_key_name.",
            mapOf("reason" to "key_reference_missing"),
        )
    }

    /** The caller's own key with this name — a `dashboard` key, and nothing else answers here. */
    private fun requireOwnedKey(name: String): ApiKey =
        apiKeys
            .findByUser(currentPrincipal().userId)
            .firstOrNull { it.name == name && !it.isRevoked && it.kind == ApiKeyKind.DASHBOARD }
            ?: throw ApiException(
                DashboardErrorCodes.NOT_FOUND,
                "No API key named '$name' that you can bind.",
                mapOf("api_key_name" to name),
            )

    /** The binding family's 404 — the same answer an unknown folder, key or binding gets. */
    private fun notFound(namePrefix: String) =
        ApiException(
            DashboardErrorCodes.NOT_FOUND,
            "No dashboard binding at '$namePrefix'.",
            mapOf("name_prefix" to namePrefix),
        )
}
