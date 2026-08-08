package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A pipeline — the central artifact: a versioned, declarative DAG of templated-SQL nodes
 * (pipeline-contract §3).
 *
 * ## This type is the pipeline *body*, and it carries no protected fields
 *
 * `id`, `owner`, `version`, `created_at` and `updated_at` are **server-assigned** and are
 * deliberately absent from this class. Three independent reasons agree:
 *
 *  - metadata-db §4.5 defines `pipeline_versions.body_json` as exactly these seven fields.
 *  - §14 defines the create/update payload as the pipeline JSON *without* `id`, `version`,
 *    `created_at`, `updated_at` — the server assigns them.
 *  - Security: a protected field that is absent from the inbound shape cannot be
 *    over-posted. Blocking it with `@JsonIgnoreProperties` or `@JsonView` instead is what
 *    the 2026 Jackson advisories (GHSA-5gvw-p9qm-jgwh and siblings) bypass.
 *
 * The server-assigned fields live on [PipelineRecord], read back from the database. A
 * surface that needs the §3.1 shape composes the two; nothing a client sends can reach
 * them.
 *
 * ## Portability
 *
 * Everything here is portable across environments (§11.1): datasources and templates are
 * referenced by *name* and `{id, version}`, never by connection detail or UUID. §12.1's
 * `forbidden_env_specific_value` is the mechanical guard on that promise.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Pipeline(
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int,
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("display_name") @get:JsonProperty("display_name") @param:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String,
    @field:JsonProperty("settings") @get:JsonProperty("settings") @param:JsonProperty("settings")
    val settings: PipelineSettings,
    @field:JsonProperty("parameters") @get:JsonProperty("parameters") @param:JsonProperty("parameters")
    val parameters: Map<String, Parameter>,
    @field:JsonProperty("nodes") @get:JsonProperty("nodes") @param:JsonProperty("nodes")
    val nodes: List<Node>,
) {
    /** The node with this id, or null. */
    fun node(id: String): Node? = nodes.firstOrNull { it.id == id }

    companion object {
        /** The only pipeline-JSON schema version v1 accepts (§3.2, §12.1). */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /**
         * Jackson entry point, lenient for the same reason [Node.fromJson] is: §12 owns the
         * "field is missing" verdicts and §17.2 requires them collected together, so an
         * absent `name` must reach `name_invalid` and absent `nodes` must reach
         * `empty_pipeline` rather than aborting binding.
         *
         * An absent `schema_version` defaults to [SUPPORTED_SCHEMA_VERSION] rather than to a
         * sentinel: §15.3 promises old pipeline JSON keeps loading, and there is no catalog
         * code for "required field missing" to report a sentinel with.
         */
        @JvmStatic
        @JsonCreator
        @Suppress("LongParameterList")
        fun fromJson(
            @JsonProperty("schema_version") schemaVersion: Int?,
            @JsonProperty("name") name: String?,
            @JsonProperty("display_name") displayName: String?,
            @JsonProperty("description") description: String?,
            @JsonProperty("settings") settings: PipelineSettings?,
            @JsonProperty("parameters") parameters: Map<String, Parameter>?,
            @JsonProperty("nodes") nodes: List<Node>?,
        ): Pipeline =
            Pipeline(
                schemaVersion = schemaVersion ?: SUPPORTED_SCHEMA_VERSION,
                name = name.orEmpty(),
                displayName = displayName.orEmpty(),
                description = description.orEmpty(),
                settings = settings ?: PipelineSettings(),
                parameters = parameters ?: emptyMap(),
                nodes = nodes ?: emptyList(),
            )
    }
}
