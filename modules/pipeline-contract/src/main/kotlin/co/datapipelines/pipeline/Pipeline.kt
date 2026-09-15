package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorOutput
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
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
 *  - metadata-db §4.5 defines `pipeline_versions.body_json` as exactly these fields.
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
    /**
     * The pipeline's release checks (§3.3, 140) — optional, versioned with the body like
     * [nodes].
     *
     * `NON_EMPTY` inclusion is what makes this field **body-hash neutral** (versioning §9.2):
     * an existing pipeline — no `checks` key — deserializes to an empty list and serializes
     * back to byte-identical JSON, so no stored version's hash moves (§15.2 additive). An
     * explicit `"checks": []` canonicalizes to the same absent form.
     */
    @field:JsonProperty("checks") @get:JsonProperty("checks") @param:JsonProperty("checks")
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val checks: List<PipelineCheck> = emptyList(),
) {
    /** The node with this id, or null. */
    fun node(id: String): Node? = nodes.firstOrNull { it.id == id }

    /**
     * Every CALCULATOR node's Context keys, typed by the kind's output — the pipeline's
     * **calculator output declared set** (078 A5, owner ruling 2026-09-05).
     *
     * A calculator `context_key` is an implicit OPTIONAL execute input: a caller who supplies it
     * skips the node and provides the value; a caller who does not lets the node run and compute
     * it. The value type is the kind's output; `null` marks an ANY-output kind (coalesce,
     * if_null, map), whose supplied value may be any JSON scalar. One derivation, shared by the
     * execute path (`RunContext`, `ExecutionLauncher`), the save-time declared set
     * (`ReferenceRules`) and the node-run debug path (`NodeSqlResolver`) — the three must agree,
     * or a key one accepts another refuses.
     *
     * A node on a **multi-output** kind (121) contributes one entry PER mapped output, typed by
     * that output's declared type: its `context_keys` mapping is what names the keys. Every rule
     * that applies to a single node's key — collision, ordering, caller override — applies to
     * each of these, unchanged, because they all read this one set.
     *
     * A node whose `kind` is unknown contributes no key: §12.10 already refuses it, and a second
     * opinion here would only fork the verdict.
     *
     * [kinds] is the registry lookup, defaulted to the deployment's registry so production
     * callers pass nothing; a test injects a fixture kind through it (the same seam
     * `PipelineValidator.orgContext` established).
     */
    fun calculatorOutputs(kinds: (String) -> CalculatorKind? = CalculatorRegistry::find): Map<String, LogicalType?> =
        nodes
            .filter { it.type == NodeType.CALCULATOR }
            .flatMap { node ->
                val kind = node.kind?.let(kinds) ?: return@flatMap emptyList()
                calculatorOutputEntries(node, kind)
            }.toMap()

    /**
     * Every **multi-output** CALCULATOR node's written key set, by node id (121 D5) — the
     * groups the all-or-nothing caller-override rule applies to.
     *
     * A single-output node has no group: one key is trivially all-or-nothing. The override
     * refusal (`pipeline.execution.calculator_keys_partial`) is per NODE — a proper subset of
     * one of these sets is the shape it refuses — so the binder needs the keys grouped by the
     * node that writes them, not the flat [calculatorOutputs] map.
     */
    fun calculatorOutputGroups(kinds: (String) -> CalculatorKind? = CalculatorRegistry::find): Map<String, Set<String>> =
        nodes
            .filter { it.type == NodeType.CALCULATOR }
            .mapNotNull { node ->
                val kind = node.kind?.let(kinds) ?: return@mapNotNull null
                if (kind.outputs.isEmpty()) return@mapNotNull null
                val keys = calculatorOutputEntries(node, kind).map { it.first }.toSet()
                if (keys.isEmpty()) return@mapNotNull null
                node.id to keys
            }.toMap()

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
            @JsonProperty("checks") checks: List<PipelineCheck>?,
        ): Pipeline =
            Pipeline(
                schemaVersion = schemaVersion ?: SUPPORTED_SCHEMA_VERSION,
                name = name.orEmpty(),
                displayName = displayName.orEmpty(),
                description = description.orEmpty(),
                settings = settings ?: PipelineSettings(),
                parameters = parameters ?: emptyMap(),
                nodes = nodes ?: emptyList(),
                checks = checks ?: emptyList(),
            )
    }
}

/**
 * The Context keys [node] writes when its kind is [kind], each with its type — one entry for a
 * single-output kind (`context_key` typed by [CalculatorKind.output]), one per mapped output
 * for a multi-output kind (121: each `context_keys` value typed by its [CalculatorOutput]).
 *
 * The ONE derivation of "which keys does this node write": [Pipeline.calculatorOutputs] reads
 * the whole pipeline through it, and `CalculatorRules` resolves a `$reference`'s type through
 * the same entries — a key the declared set accepts and the type check resolved differently
 * would be two opinions about one fact.
 *
 * A blank key or an unmapped output contributes nothing: §12.10 owns those verdicts
 * (`calculator_output_name_invalid`, `calculator_outputs_incomplete`), and a second report here
 * would only fork them.
 */
internal fun calculatorOutputEntries(
    node: Node,
    kind: CalculatorKind,
): List<Pair<String, LogicalType?>> =
    if (kind.outputs.isEmpty()) {
        listOfNotNull(node.contextKey?.takeUnless { it.isBlank() }?.let { it to kind.output })
    } else {
        kind.outputs.mapNotNull { output ->
            node.contextKeys
                ?.get(output.name)
                ?.takeUnless { it.isBlank() }
                ?.let { it to output.type }
        }
    }
