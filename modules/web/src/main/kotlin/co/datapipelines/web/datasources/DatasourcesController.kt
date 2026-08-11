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
 * The datasource endpoints (rest-api.md §9).
 *
 * The registry owns the rules: §9 validation with the save-time test pool build, AES-GCM
 * credential encryption, the in-use delete guard, and the connectivity probe. This controller
 * binds the inbound shape, maps outcomes to HTTP, and projects responses — and the projection is
 * field-by-field, never a serialized [Datasource]: that type can transiently carry the plaintext
 * password, and "credentials are never returned" must be a property of the code.
 *
 * `create` is `admin`-scoped and `test` is `author`-scoped per the §7.6 matrix — the annotation
 * is read by `auth`'s ScopeInterceptor; nothing here re-asserts a scope.
 */
@RestController
@RequestMapping("/api/v1/datasources")
class DatasourcesController(
    private val datasources: DatasourceRegistry,
) {
    /** §9.1 — register. A name already taken is `409 datasource.validation.duplicate_name`. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_DATASOURCES)
    fun create(
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val datasource = bind(body, requirePassword = true)
        if (datasources.exists(datasource.name)) {
            throw ApiException(
                PipelineErrorCodes.Datasource.DUPLICATE_NAME,
                "A datasource named '${datasource.name}' already exists.",
                mapOf("datasource_name" to datasource.name),
            )
        }
        return ApiResponse.of(datasources.save(datasource, currentPrincipal().userId).toResponse())
    }

    /**
     * §9.2 — the listing, optionally narrowed to one dialect. Passwords are never included.
     *
     * Paginated per rest-api §2 principle 6 ("list endpoints paginate") even though §9.2's example
     * shows no parameters (noted for doc-sync): the registry returns the full (small, bounded by
     * what a deployment configures) set, so the page is cut in memory with an **exact** total.
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
        val all = datasources.list(filter)
        val items = all.drop(page).take(size).map { it.toResponse() }
        return ApiResponse.of(PagedData(items, Pagination.of(page, size, all.size.toLong(), items.size)))
    }

    /** §9.3 — one datasource, sensitive fields excluded. */
    @GetMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun get(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of((datasources.get(name) ?: throw ApiErrors.datasourceNotFound(name)).toResponse())

    /** §9.4 — update. `password` is optional; omitting it keeps the stored credential. */
    @PutMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_DATASOURCES)
    fun update(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        if (!datasources.exists(name)) throw ApiErrors.datasourceNotFound(name)
        val datasource = bind(body, requirePassword = false, pathName = name)
        return ApiResponse.of(datasources.save(datasource, currentPrincipal().userId).toResponse())
    }

    /** §9.5 — soft delete; `409 datasource.in_use` while any live pipeline references it. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_DATASOURCES)
    fun delete(
        @PathVariable name: String,
    ) {
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
     * `connected: false`), never an HTTP error; only an unknown name is a 404.
     */
    @PostMapping("/{name}/test")
    @RequiredScope(ScopeMatrix.RestOperation.TEST_DATASOURCE)
    fun test(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> {
        val result = datasources.testConnection(name) ?: throw ApiErrors.datasourceNotFound(name)
        return ApiResponse.of(
            mapOf(
                "connected" to result.connected,
                "server_version" to result.serverVersion,
                "error" to result.error,
            ),
        )
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

    /** The outbound shape — every field a reader is entitled to, `password_set` derived. */
    private fun Datasource.toResponse(): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to displayName,
            "description" to description,
            "dialect" to dialect.wire,
            "jdbc_url" to jdbcUrl,
            "username" to username,
            "password_set" to true,
            "query_timeout_seconds" to queryTimeoutSeconds,
            "properties" to mapOf("hikari" to properties.hikari, "jdbc" to properties.jdbc),
        )

    private companion object {
        val MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper
                .builder()
                .build()

        const val MAX_ECHOED_VALUE_CHARS = 32
    }
}
