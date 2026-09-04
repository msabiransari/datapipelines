package co.datapipelines.application.datasources

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * Binds the datasources.md §3.1 request body to a [Datasource] — the ONE reader of that payload
 * (rest-api.md §9.1/§9.4, mcp-server.md §6.2.22).
 *
 * ## Why it lives here and not on the controller
 *
 * It used to be `DatasourcesController.bind`, private. Round 068 gave MCP a `datasources_create`
 * tool, and 049's principle applies unchanged: **two entry points, one validated path**. A tool
 * that assembled its own `Datasource` from typed arguments would be a second copy of the dialect
 * lookup, the password rule and the §3.3 allowlist normalization — three rules that drift
 * silently, because the payloads look the same right up until one of them stops being checked.
 * So the MCP tool builds the §3.1 body and hands it here, exactly as `PipelineToolPayloads`
 * builds a §3 pipeline body for `PipelineService`.
 *
 * ## What it throws
 *
 * [DatapipelinesException] with a pipeline-contract §13 code — never an `ApiException`, never an
 * MCP wire type: this layer sits below both surfaces (module-structure §5.10). REST's
 * `ApiExceptionHandler` maps the base type through `ApiErrorCatalog` by CODE, so the HTTP status
 * for every one of these is identical to what the controller raised before the extraction, and
 * `McpToolDispatcher` reads the same `code` for its error payload.
 */
object DatasourcePayloadBinder {
    /** The longest echo of an operator-supplied value in an error message. */
    private const val MAX_ECHOED_VALUE_CHARS = 32

    private val MAPPER = JsonMapper.builder().build()

    /**
     * Binds the §9.1/§9.4 payload; wire-value problems are 400s with catalogued codes.
     *
     * @param requirePassword true on create (§9.1: a password is mandatory), false on update,
     *   where an absent password means "keep the stored one".
     * @param pathName the name from the URL on update — `name` is immutable (§11.1), so the path
     *   wins and the body's `name` is not read.
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    fun bind(
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
                ?: throw DatapipelinesException(
                    PipelineErrorCodes.Datasource.DIALECT_INVALID,
                    "Dialect '$dialectToken' is not one of ${Dialect.entries.map { it.wire }}.",
                    mapOf("dialect" to dialectToken.take(MAX_ECHOED_VALUE_CHARS)),
                )
        val password = body.get("password")?.takeIf { it.isTextual }?.asText()
        if (requirePassword && password.isNullOrEmpty()) {
            throw DatapipelinesException(
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

    /** The `workspace` binding name, when the payload names one. */
    fun workspaceNameOf(body: JsonNode): String? =
        body
            .get("workspace")
            ?.takeIf { it.isTextual }
            ?.asText()
            ?.trim()

    /** Reads a boolean flag; null when absent; a non-boolean value is a payload-shape 400. */
    fun booleanFlag(
        body: JsonNode,
        field: String,
    ): Boolean? {
        val node = body.get(field) ?: return null
        if (!node.isBoolean) {
            throw DatapipelinesException(
                PipelineErrorCodes.Datasource.PROPERTIES_INVALID,
                "Invalid datasource payload: '$field' must be a boolean.",
                mapOf("field" to field),
            )
        }
        return node.asBoolean()
    }

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

    private fun invalid(
        field: String,
        why: String,
    ): DatapipelinesException =
        DatapipelinesException(
            PipelineErrorCodes.Datasource.PROPERTIES_INVALID,
            "Invalid datasource payload: $why.",
            mapOf("field" to field),
        )
}
