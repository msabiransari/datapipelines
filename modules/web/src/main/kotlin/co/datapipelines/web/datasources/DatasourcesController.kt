package co.datapipelines.web.datasources

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.PagedData
import co.datapipelines.web.api.Pagination
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The datasource endpoints (rest-api.md §9) under the workspaces model (design §5.3/D8/§6).
 *
 * ## Visibility (§5.3)
 *
 * Every read (list/get/test) resolves the caller's ACTIVE workspace and sees exactly its
 * bound datasources plus all global ones. The predicate is the repository's SQL
 * ([DatasourceRegistry.listVisible]/[getVisible]) — never a controller-side post-filter —
 * so paging totals count exactly the visible set. A workspace-bound datasource of ANOTHER
 * workspace is invisible: by-name access behaves as not-found. The datasource NAME
 * namespace stays flat and global — a cross-workspace create collision is
 * `datasource.validation.duplicate_name`, by design (design §3).
 *
 * ## The D8 gates — [DatasourceWorkspaceRules]
 *
 * One shared component answers "who may write what" for this controller AND the UI's form
 * partial, so the two surfaces cannot drift. The scope floor for the three CUD verbs is
 * [ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES] (author); admin-ness and the
 * config gate are not scopes and live in the rules.
 *
 * ## The registry owns the rest
 *
 * §9 validation with the save-time test pool build, AES-GCM encryption, the in-use delete
 * guard — and POOL INVALIDATION: every write path here crosses `registry.save`, which
 * evicts the pool on update, so a `readonly` flip rebuilds the pool under the new setting
 * at the next lease (design §6; D11/F5 — not widened here).
 */
@RestController
@RequestMapping("/api/v1/datasources")
class DatasourcesController(
    private val datasources: DatasourceRegistry,
    private val rules: DatasourceWorkspaceRules,
) {
    /**
     * §9.1 — register. A name already taken is `409 datasource.validation.duplicate_name`
     * (the namespace is global across workspaces, design §3). Binding per D8: `global: true`
     * (admin) or an explicit `workspace` name (accessible to the caller), else the ACTIVE
     * workspace. `readonly` settable by whoever may create.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun create(
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val datasource =
            bind(body, requirePassword = true).copy(
                isReadonly = booleanFlag(body, "readonly") ?: false,
                workspaceId = rules.resolveCreateBinding(principal, booleanFlag(body, "global"), workspaceNameOf(body)),
            )
        if (datasources.exists(datasource.name)) {
            throw ApiException(
                PipelineErrorCodes.Datasource.DUPLICATE_NAME,
                "A datasource named '${datasource.name}' already exists.",
                mapOf("datasource_name" to datasource.name),
            )
        }
        return ApiResponse.of(datasources.save(datasource, principal.userId).toResponse())
    }

    /**
     * §9.2 — the listing, workspace-scoped: the active workspace's bound datasources plus
     * all global ones. Paginated with an EXACT total — the visibility predicate ran in SQL,
     * so `total` counts exactly what this principal can see (no post-filter paging leak).
     */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<PagedData<Map<String, Any?>>> {
        val filter =
            dialect?.let { raw ->
                Dialect.entries.firstOrNull { it.wire == raw.trim().uppercase() }
                    ?: throw ApiException(
                        PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                        "Unknown dialect '$raw'.",
                        mapOf("dialect" to raw.take(MAX_ECHOED_VALUE_CHARS), "supported" to Dialect.entries.map { it.wire }),
                    )
            }
        val page = Pagination.clampOffset(offset)
        val size = Pagination.clampLimit(limit)
        val workspaceId = currentPrincipal().requireWorkspace().id
        val visible = datasources.listVisible(filter, workspaceId)
        val items = visible.drop(page).take(size).map { it.toResponse() }
        return ApiResponse.of(PagedData(items, Pagination.of(page, size, visible.size.toLong(), items.size)))
    }

    /** §9.3 — one datasource; a workspace-bound datasource of another workspace is not-found (§5.3). */
    @GetMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val datasource = datasources.getVisible(name, workspaceId) ?: throw ApiErrors.datasourceNotFound(name)
        return ApiResponse.of(datasource.toResponse())
    }

    /**
     * §9.4 — update, under the D8 gates. `password` optional (omit to keep); `readonly`
     * and `global` optional flags — absent keeps the stored value, present attempts a gated
     * write. Every accepted write crosses `registry.save` → pool eviction.
     */
    @PutMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun update(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val existing = datasources.getVisible(name, workspaceId) ?: throw ApiErrors.datasourceNotFound(name)
        val globalRequested = booleanFlag(body, "global")
        val readonlyRequested = booleanFlag(body, "readonly")
        rules.requireGlobalMutationAllowed(principal, existing, name)
        rules.requireMemberDatasourcesGate(principal)
        rules.requireGlobalFlagWriteAllowed(principal, globalRequested)
        rules.requireReadonlyWriteAllowed(principal, existing, readonlyRequested)

        val datasource =
            bind(body, requirePassword = false, pathName = name).copy(
                isReadonly = readonlyRequested ?: existing.isReadonly,
                workspaceId = rules.resolveUpdateBinding(principal, existing, globalRequested, workspaceNameOf(body)),
            )
        return ApiResponse.of(datasources.save(datasource, principal.userId).toResponse())
    }

    /** §9.5 — soft delete, D8-gated like update; `409 datasource.in_use` while any live pipeline references it. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued code
    fun delete(
        @PathVariable name: String,
    ) {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val existing = datasources.getVisible(name, workspaceId) ?: throw ApiErrors.datasourceNotFound(name)
        rules.requireGlobalMutationAllowed(principal, existing, name)
        rules.requireMemberDatasourcesGate(principal)

        val result = datasources.delete(name)
        when {
            // 204 — the delete landed.
            result.deleted -> {}

            result.errorCode == PipelineErrorCodes.Datasource.IN_USE -> {
                throw ApiException(
                    PipelineErrorCodes.Datasource.IN_USE,
                    "Datasource '$name' is referenced by ${result.referencingPipelines.size} pipeline(s).",
                    mapOf("datasource_name" to name, "referencing_pipelines" to result.referencingPipelines),
                )
            }

            else -> {
                throw ApiErrors.datasourceNotFound(name)
            }
        }
    }

    /**
     * §9.6 — live connectivity probe. A connection failure is **data** (`200` with
     * `connected: false`), never an HTTP error; an unknown name is a 404 — and so is a name
     * bound to another workspace (§5.3 visibility).
     */
    @PostMapping("/{name}/test")
    @RequiredScope(ScopeMatrix.RestOperation.TEST_DATASOURCE)
    fun test(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        datasources.getVisible(name, workspaceId) ?: throw ApiErrors.datasourceNotFound(name)
        val result = datasources.testConnection(name) ?: throw ApiErrors.datasourceNotFound(name)
        return ApiResponse.of(
            mapOf(
                "connected" to result.connected,
                "server_version" to result.serverVersion,
                "error" to result.error,
            ),
        )
    }

    private fun workspaceNameOf(body: JsonNode): String? =
        body
            .get("workspace")
            ?.takeIf { it.isTextual }
            ?.asText()
            ?.trim()

    /** Reads a boolean flag; null when absent; a non-boolean value is a payload-shape 400. */
    private fun booleanFlag(
        body: JsonNode,
        field: String,
    ): Boolean? {
        val node = body.get(field) ?: return null
        if (!node.isBoolean) {
            throw ApiException(
                PipelineErrorCodes.Datasource.PROPERTIES_INVALID,
                "Invalid datasource payload: '$field' must be a boolean.",
                mapOf("field" to field),
            )
        }
        return node.asBoolean()
    }

    /** Binds the §9.1/§9.4 payload; wire-value problems are 400s with catalogued codes. */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    private fun bind(
        body: JsonNode,
        requirePassword: Boolean,
        pathName: String? = null,
    ): Datasource {
        val name =
            pathName ?: body.get("name")?.takeIf { it.isTextual }?.asText()
                ?: throw invalid("name", "a datasource name is required")
        val dialectToken =
            body.get("dialect")?.takeIf { it.isTextual }?.asText()
                ?: throw invalid("dialect", "a dialect is required")
        val dialect =
            Dialect.entries.firstOrNull { it.wire == dialectToken.trim().uppercase() }
                ?: throw ApiException(
                    PipelineErrorCodes.Datasource.DIALECT_INVALID,
                    "Dialect '$dialectToken' is not one of ${Dialect.entries.map { it.wire }}.",
                    mapOf("dialect" to dialectToken.take(MAX_ECHOED_VALUE_CHARS)),
                )
        val password = body.get("password")?.takeIf { it.isTextual }?.asText()
        if (requirePassword && password.isNullOrEmpty()) {
            throw ApiException(
                PipelineErrorCodes.Datasource.PASSWORD_MISSING,
                "A password is required when registering a datasource.",
                mapOf("datasource_name" to name),
            )
        }
        return Datasource(
            name = name,
            displayName = body.get("display_name")?.takeIf { it.isTextual }?.asText() ?: name,
            description = body.get("description")?.takeIf { it.isTextual }?.asText(),
            dialect = dialect,
            jdbcUrl =
                body.get("jdbc_url")?.takeIf { it.isTextual }?.asText()
                    ?: throw invalid("jdbc_url", "a JDBC URL is required"),
            username =
                body.get("username")?.takeIf { it.isTextual }?.asText()
                    ?: throw invalid("username", "a username is required"),
            password = password,
            queryTimeoutSeconds = body.get("query_timeout_seconds")?.takeIf { it.isInt }?.asInt(),
            // §3.3: the introspection allowlist — exact names. Normalized here as defense in
            // depth; the LOAD-BEARING normalization is the registry's save boundary (the
            // single place every write path crosses — non-REST writers cannot store a
            // mixed-case entry that would silently never match). Non-string entries are
            // payload-shape 400s like the rest.
            introspectionIncludeSchemas = includeSchemasOf(body),
            properties =
                body.get("properties")?.takeIf { it.isObject }?.let { node ->
                    DatasourceProperties.fromRaw(node.properties().associate { (k, v) -> k to MAPPER.convertValue(v, Any::class.java) })
                } ?: DatasourceProperties(),
        )
    }

    private fun invalid(
        field: String,
        why: String,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Datasource.PROPERTIES_INVALID,
            "Invalid datasource payload: $why.",
            mapOf("field" to field),
        )

    /**
     * §3.3: `introspection_include_schemas` — an array of exact schema names, normalized
     * through the ONE shared rule (`Datasource.normalizeIncludeSchemas` — never a re-inlined
     * trim+lowercase of this layer's own, R5 F6) as DEFENSE IN DEPTH; the load-bearing
     * normalization is the registry's save boundary, so non-REST write paths are covered too.
     * Absent = empty list (today's behavior). A non-array value or a non-string entry is a
     * payload-shape 400 like every other bind problem.
     */
    private fun includeSchemasOf(body: JsonNode): List<String> {
        val node = body.get("introspection_include_schemas") ?: return emptyList()
        if (!node.isArray) {
            throw invalid("introspection_include_schemas", "introspection_include_schemas must be an array of schema names")
        }
        return Datasource.normalizeIncludeSchemas(
            node.map { element ->
                if (!element.isTextual) {
                    throw invalid("introspection_include_schemas", "introspection_include_schemas entries must be strings")
                }
                element.asText()
            },
        )
    }

    /** The outbound shape — every field a reader is entitled to, `password_set` derived, plus the additive `workspace`/`readonly`. */
    fun Datasource.toResponse(): Map<String, Any?> =
        buildMap {
            put("name", name)
            put("display_name", displayName)
            put("description", description)
            put("dialect", dialect.wire)
            put("jdbc_url", jdbcUrl)
            put("username", username)
            put("password_set", true)
            put("query_timeout_seconds", queryTimeoutSeconds)
            // The envelope convention: absent (not null) when the allowlist is empty — which
            // is also today's default behavior for every pre-existing datasource.
            if (introspectionIncludeSchemas.isNotEmpty()) put("introspection_include_schemas", introspectionIncludeSchemas)
            put("properties", mapOf("hikari" to properties.hikari, "jdbc" to properties.jdbc))
            // Workspaces design §9 — additive: the bound workspace's NAME (null = global)
            // and the readonly flag (machine-readable, D6).
            put("workspace", workspaceName)
            put("readonly", isReadonly)
        }

    private companion object {
        val MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper
                .builder()
                .build()

        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
