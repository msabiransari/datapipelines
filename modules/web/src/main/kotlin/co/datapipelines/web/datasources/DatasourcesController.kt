package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.application.datasources.DatasourcePayloadBinder
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
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
    private val registrations: DatasourceCreateService,
) {
    /**
     * §9.1 — register. A name already taken is `409 datasource.validation.duplicate_name`
     * (the namespace is global across workspaces, design §3). Binding per D8: `global: true`
     * (admin) or an explicit `workspace` name (accessible to the caller), else the ACTIVE
     * workspace. `readonly` settable by whoever may create.
     *
     * The whole sequence — bind, D8 binding, duplicate-name check, registry save — is
     * [DatasourceCreateService], because the `datasources_create` MCP tool (mcp-server.md
     * §6.2.22) calls the SAME path: 049's rule, two entry points and one validated path. What
     * is left here is the HTTP shape (201, the §3.2 envelope) and nothing else.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun create(
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(registrations.create(body, currentPrincipal()).toResponse())

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
        val globalRequested = DatasourcePayloadBinder.booleanFlag(body, "global")
        val readonlyRequested = DatasourcePayloadBinder.booleanFlag(body, "readonly")
        rules.requireGlobalMutationAllowed(principal, existing, name)
        rules.requireMemberDatasourcesGate(principal)
        rules.requireGlobalFlagWriteAllowed(principal, globalRequested)

        val datasource =
            DatasourcePayloadBinder.bind(body, requirePassword = false, pathName = name).copy(
                isReadonly = readonlyRequested ?: existing.isReadonly,
                workspaceId =
                    rules.resolveUpdateBinding(principal, existing, globalRequested, DatasourcePayloadBinder.workspaceNameOf(body)),
            )
        return ApiResponse.of(datasources.save(datasource, principal.userId).toResponse())
    }

    /**
     * §9.5 — soft delete, D8-gated like update; `409 datasource.in_use` while any live
     * pipeline references it **in any version it has ever stored** (061/T79).
     *
     * `details` carries the referencing pipeline names AND the full reverse-scan rows —
     * pipeline, node, the carrying pipeline version and that version's status — the way
     * `template.in_use` does (040 D4). The versions are the load-bearing half: the reference
     * that used to slip through this guard lived in a released v1 that a later v2 had
     * dropped, and "pipeline X" alone would send the operator to look at v2, where the
     * datasource is not mentioned at all.
     */
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
                    "Datasource '$name' is referenced by ${result.referencingPipelines.size} pipeline(s)" +
                        " across ${result.references.size} node(s), including historical pipeline versions." +
                        " Remove or repoint the referencing nodes before deleting it.",
                    mapOf(
                        "datasource_name" to name,
                        "referencing_pipelines" to result.referencingPipelines,
                        "references" to
                            result.references.map {
                                mapOf(
                                    "pipeline" to it.pipelineName,
                                    "node_id" to it.nodeId,
                                    "pipeline_version" to it.pipelineVersion,
                                    "version_status" to it.versionStatus,
                                )
                            },
                    ),
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
     *
     * The body is the full wire form of `TestResult` datasources §8.1 documents — `tested_at`,
     * `latency_ms` and `error_class` included; three of the six fields were missing here while
     * the spec's example carried them. Since 061/T84 the probe also RECORDS its outcome on the
     * row (§8.1B), which is what puts it on the list screen.
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
                "tested_at" to result.testedAt.toString(),
                "latency_ms" to result.latencyMs,
                "server_version" to result.serverVersion,
                "error" to result.error,
                "error_class" to result.errorClass,
            ),
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
            // §8.1B (061/T84) — additive: the outcome of the LAST connection test, so a
            // reader learns a credential has stopped working without running one. NULL (not
            // absent) when never tested: "we have never checked" is a fact a client acts on
            // differently from "the field does not exist", and every pre-V9 row is in it.
            put(
                "last_test",
                lastTest?.let {
                    mapOf("tested_at" to it.testedAt.toString(), "ok" to it.ok, "message" to it.message)
                },
            )
        }

    private companion object {
        /** The longest echo of a caller-supplied value in an error message (the `dialect` filter). */
        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
