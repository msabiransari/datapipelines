package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.application.datasources.DatasourcePayloadBinder
import co.datapipelines.application.datasources.DatasourceUpdateService
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DialectAdapters
import co.datapipelines.datasources.pooling.PoolSettings
import co.datapipelines.datasources.visibleDialectProperties
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
    private val updates: DatasourceUpdateService,
    // 118 — the learned facts the detail carries (rest-api §9.7A), the same enrichment the MCP
    // twin uses. Defaulted so the slice tests that construct this controller by hand keep
    // compiling; the assembled application injects `SemanticsConfiguration`'s bean.
    private val facts: co.datapipelines.application.semantics.FactEnrichment = co.datapipelines.application.semantics.FactEnrichment.NONE,
) {
    /**
     * §9.1 — register. A name already taken is `409 datasource.validation.duplicate_name`
     * (the namespace is global across workspaces, design §3). Binding per D8: `global: true`
     * (admin) or an explicit `workspace` name (accessible to the caller), else the ACTIVE
     * workspace. `readonly` settable by whoever may create.
     *
     * The whole sequence — bind, D8 binding, duplicate-name check, registry save — is
     * [DatasourceCreateService]. It was extracted in 068 because an MCP `datasources_create`
     * called the same path (049's rule: two entry points, one validated path); 094 removed that
     * tool — no credential travels through an agent — so REST is the only programmatic caller
     * again. The extraction stays: the sequence is the same one the bootstrap registrar and the
     * UI form must not diverge from, and folding it back inline would make "one validated path"
     * a claim rather than a structure. What is left here is the HTTP shape (201, the §3.2
     * envelope) and nothing else.
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

    /**
     * §9.3 — one datasource; a workspace-bound datasource of another workspace is not-found (§5.3).
     * Carries `facts` (118, §9.7A): the datasource-wide learned facts, served as stored — this
     * read opens no connection, so there is nothing to recompute drift against.
     */
    @GetMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val datasource = datasources.getVisible(name, workspaceId) ?: throw ApiErrors.datasourceNotFound(name)
        return ApiResponse.of(datasource.toResponse() + ("facts" to facts.forDatasource(workspaceId, datasource)))
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
        // The gates, the binding and the save are [DatasourceUpdateService]'s — the same
        // sequence the §4.5 edit dialog runs, in one place since 097 §A. What is left here is
        // the payload shape (§3.1) and the §3.2 envelope.
        val saved =
            updates.update(name, existing, principal, globalRequested, DatasourcePayloadBinder.workspaceNameOf(body)) {
                DatasourcePayloadBinder
                    .bind(body, requirePassword = false, pathName = name)
                    .copy(isReadonly = readonlyRequested ?: existing.isReadonly)
            }
        return ApiResponse.of(saved.toResponse())
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
            // Top-level `username` stays (§12.1 frozen shape) and is now NULL for the kinds that
            // have none — a private key, a service-account blob, or no credential at all.
            put("username", username)
            // §3.4 additive: WHAT the stored credential is. The secret itself is never here and
            // never will be; `credential.kind` is the fact a client needs to render a form, and
            // `password_set` is derived from it (V13 makes kind='none' ⟺ no stored ciphertext).
            put(
                "credential",
                buildMap {
                    put("kind", credentialKind.wire)
                    username?.let { put("username", it) }
                },
            )
            put("password_set", credentialSet)
            put("query_timeout_seconds", queryTimeoutSeconds)
            // The envelope convention: absent (not null) when the allowlist is empty — which
            // is also today's default behavior for every pre-existing datasource.
            if (introspectionIncludeSchemas.isNotEmpty()) put("introspection_include_schemas", introspectionIncludeSchemas)
            // 109 §B — `properties.dialect` joins the wire: the non-secret keys only, through
            // the §5.6 classification (datasources.md §3.2's shown/hidden table is the
            // contract). The MCP twin reads the same table; the two surfaces do not share this
            // code, so the TABLE is what keeps them honest.
            put(
                "properties",
                mapOf(
                    "hikari" to properties.hikari,
                    "jdbc" to properties.jdbc,
                    "dialect" to visibleDialectProperties(dialect, properties.dialect),
                ),
            )
            // §5 (094) — additive, and NOT a duplicate of `properties.hikari`: that map is what
            // this row STORES (empty for almost every datasource), while `pool` is what the pool
            // actually RUNS with — each catalogued setting's effective value, its unit, and the
            // layer that supplied it (`configured` / `dialect_default` / `application_default` /
            // `hikari_default`). The camelCase keys are HikariCP's own, which is also what a
            // caller writes back under `properties.hikari.*`.
            put("pool", PoolSettings.wire(this@toResponse, DialectAdapters.forDialect(dialect)))
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
