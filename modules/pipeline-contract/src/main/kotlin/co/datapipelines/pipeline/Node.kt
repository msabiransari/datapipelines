package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/**
 * One node of the pipeline DAG (pipeline-contract §4).
 *
 * ## The omitted-`output` rule lives in this class's creator
 *
 * D1: a DQL node whose `output` block is omitted resolves to [NodeOutput.Caller], and the
 * default is applied **at deserialization time** so that "by the time the executor sees a
 * node, every DQL node has a concrete `NodeOutput` and the executor never re-derives a
 * default" (dag-executor §4.1). That rule needs `type` and `output` together, which a
 * per-property deserializer cannot see — hence the explicit [fromJson] creator.
 *
 * For DML / DDL / PIPELINE / CALCULATOR nodes an omitted `output` stays `null`: a DML/DDL side effect *is*
 * the output (§4.4/§4.5), and a PIPELINE node whose pinned child has no caller node is
 * side-effect-only (§4.9). A present block is a validation failure (§12.4, §12.9), not a shape
 * this class silently normalises away.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Node(
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: NodeType,
    @field:JsonProperty("source") @get:JsonProperty("source") @param:JsonProperty("source")
    val source: String,
    @field:JsonProperty("template") @get:JsonProperty("template") @param:JsonProperty("template")
    val template: TemplateRef,
    @field:JsonProperty("output") @get:JsonProperty("output") @param:JsonProperty("output")
    val output: NodeOutput?,
    @field:JsonProperty("depends_on") @get:JsonProperty("depends_on") @param:JsonProperty("depends_on")
    val dependsOn: List<String>,
    @field:JsonProperty("pipeline") @get:JsonProperty("pipeline") @param:JsonProperty("pipeline")
    val pipeline: PipelineNodeRef? = null,
    @field:JsonProperty("parameters") @get:JsonProperty("parameters") @param:JsonProperty("parameters")
    val parameters: Map<String, JsonNode>? = null,
    /**
     * CALCULATOR nodes only (§4.10): the catalog kind this node evaluates.
     *
     * Nullable, with a null default, and the class's `NON_NULL` inclusion keeps it off the wire
     * for every other node — which is what makes this change **body-hash neutral**: an existing
     * pipeline's canonical JSON is byte-identical after the three fields exist, so no stored
     * version's hash moves and no release has to be re-signed (versioning §9.2).
     */
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: String? = null,
    /**
     * CALCULATOR nodes only (§4.10): the kind's inputs, by input name.
     *
     * A `"$name"` string is a **reference** to a Context key; every other JSON value is a literal
     * typed against the kind's declared input type. Kept as raw [JsonNode] for the same reason
     * [Parameter.default] is: §12.10's type check is a rule about the JSON shape the author
     * wrote, and binding it to a Kotlin type here would erase the distinction the rule checks.
     */
    @field:JsonProperty("inputs") @get:JsonProperty("inputs") @param:JsonProperty("inputs")
    val inputs: Map<String, JsonNode>? = null,
    /**
     * CALCULATOR nodes only (§4.10): the Context key this node writes.
     *
     * Deliberately not called `output`: `output` is a §4.7 block naming a TABLE, and a calculator
     * produces a value that downstream nodes bind as `:context_key`. Two different things with
     * one name is how an author ends up looking for a table that was never created.
     */
    @field:JsonProperty("context_key") @get:JsonProperty("context_key") @param:JsonProperty("context_key")
    val contextKey: String? = null,
    /**
     * CALCULATOR nodes on a **multi-output** kind only (§4.10, 121): the Context key each of the
     * kind's named outputs is written to, as `{output name: context key}` — every declared
     * output mapped, no partial mapping.
     *
     * `context_key` XOR `context_keys`, never both and never neither: a single-output kind names
     * its one value with `context_key`; a multi-output kind maps its set with `context_keys`.
     * The verdicts are §12.10's — `calculator_output_shape_mismatch` for a wrong or doubled
     * shape, `calculator_output_unknown` for a name the kind does not declare,
     * `calculator_outputs_incomplete` for an unmapped output.
     *
     * Nullable, with a null default, under the class's `NON_NULL` inclusion — exactly as [kind],
     * [inputs] and [contextKey] are, and for the same reason: an existing pipeline's canonical
     * JSON is byte-identical after this field exists, so no stored version's body hash moves and
     * no release has to be re-signed (versioning §9.2).
     */
    @field:JsonProperty("context_keys") @get:JsonProperty("context_keys") @param:JsonProperty("context_keys")
    val contextKeys: Map<String, String>? = null,
    /**
     * Per-node execution settings (§6.4) — today only `timeout_seconds`, the node's own
     * wall-clock deadline.
     *
     * Nullable with a null default and `NON_NULL` inclusion, exactly as [kind]/[inputs]/
     * [contextKey] are, and for the same reason: an existing pipeline's canonical JSON is
     * byte-identical after this field exists, so no stored version's body hash moves and no
     * release has to be re-signed (versioning §9.2).
     */
    @field:JsonProperty("settings") @get:JsonProperty("settings") @param:JsonProperty("settings")
    val settings: NodeSettings? = null,
    /**
     * TRANSFORM nodes only (§4.12, transform-nodes design §3.1): fail the node when the
     * rejects table is non-empty (`pipeline.transform.rejects_strict`). Default is partition —
     * the rejects are written and the run continues.
     *
     * Nullable, with a null default, under the class's `NON_NULL` inclusion — exactly as
     * [kind], [inputs] and [contextKey] are, and for the same reason: an existing pipeline's
     * canonical JSON is byte-identical after this field exists, so no stored version's body
     * hash moves and no release has to be re-signed (versioning §9.2).
     */
    @field:JsonProperty("strict") @get:JsonProperty("strict") @param:JsonProperty("strict")
    val strict: Boolean? = null,
) {
    /** The resolved execution target (§4.8) — a registered datasource, or the tempdb literal. */
    @get:JsonIgnore
    val resolvedSource: NodeSource get() = NodeSource.from(source)

    /** True when this node resolves to `output.target: "caller"` — the result node of §9. */
    @get:JsonIgnore
    val isCallerNode: Boolean get() = output == NodeOutput.Caller

    companion object {
        /**
         * Jackson entry point. Applies the D1 omitted-`output` default and nothing else.
         *
         * Every parameter carries a lenient default because §12 owns the "field is missing"
         * verdicts and §17.2 requires them to be reported **together**: a Jackson
         * `MissingKotlinParameterException` on the first absent field would abort binding and
         * hand the author one error where the validator would have given them all of them.
         * A blank id fails `invalid_identifier`, an empty template ref fails
         * `template_not_found`, an absent source fails `unknown_datasource`, an absent
         * `pipeline` ref fails `pipeline_not_found` — the failure is reported, with a code
         * from the catalog, by the component the spec assigns it to.
         *
         * `type` is the exception: it has no lenient value, and [PipelineDeserializer] has
         * already rejected an absent or out-of-catalog `type` with
         * `pipeline.validation.type_invalid` before binding runs.
         */
        @JvmStatic
        @JsonCreator
        @Suppress("LongParameterList")
        fun fromJson(
            @JsonProperty("id") id: String?,
            @JsonProperty("description") description: String?,
            @JsonProperty("type") type: NodeType,
            @JsonProperty("source") source: String?,
            @JsonProperty("template") template: TemplateRef?,
            @JsonProperty("output") output: NodeOutput?,
            @JsonProperty("depends_on") dependsOn: List<String>?,
            @JsonProperty("pipeline") pipeline: PipelineNodeRef?,
            @JsonProperty("parameters") parameters: Map<String, JsonNode>?,
            @JsonProperty("kind") kind: String?,
            @JsonProperty("inputs") inputs: Map<String, JsonNode>?,
            @JsonProperty("context_key") contextKey: String?,
            @JsonProperty("context_keys") contextKeys: Map<String, String>?,
            @JsonProperty("settings") settings: NodeSettings?,
            @JsonProperty("strict") strict: Boolean?,
        ): Node =
            Node(
                id = id.orEmpty(),
                description = description.orEmpty(),
                type = type,
                source = source.orEmpty(),
                template = template ?: TemplateRef(),
                // D1 / §4.7: omitted output on a DQL node IS `{"target": "caller"}`. PIPELINE
                // gets no default: a zero-caller child is side-effect-only, and §12.9's
                // `pipeline_output_on_sideeffect_child` check must see the block was omitted.
                output = output ?: if (type == NodeType.DQL) NodeOutput.Caller else null,
                dependsOn = dependsOn ?: emptyList(),
                pipeline = pipeline,
                parameters = parameters,
                // Absent stays absent, never "" or an empty map: §12.10 must be able to tell a
                // CALCULATOR node that omitted `context_key` from one that declared it blank, and
                // an ordinary DQL node must serialize back byte-identically (body-hash neutrality).
                kind = kind,
                inputs = inputs,
                contextKey = contextKey,
                // Absent stays absent for the same body-hash reason as the three above: a node
                // that declared no output mapping must serialize back with no `context_keys` key.
                contextKeys = contextKeys,
                // Absent stays absent for the same body-hash reason as the three above: a node
                // that declared no settings must serialize back with no `settings` key at all.
                settings = settings,
                // Absent stays absent, same body-hash rule: a non-strict (or non-TRANSFORM) node
                // serializes back with no `strict` key at all.
                strict = strict,
            )
    }
}

/**
 * One node's own execution settings (pipeline-contract §6.4).
 *
 * The only member in v1 is [timeoutSeconds] — the node's WALL-CLOCK deadline, overriding
 * `datapipelines.executor.node-timeout-seconds` for this node alone. It is not the statement
 * timeout: the statement bound is the datasource's `query_timeout_seconds` (else
 * `node-query-timeout-seconds`) and bounds ONE `execute*` call, while this bounds the node's
 * whole lifecycle — render, connect, execute, stage, materialize. §6.4 states the precedence:
 * `settings.execution.timeout_seconds` >= node deadline >= statement query timeout.
 *
 * A legitimately long scan is what this exists for. It is **not** a licence to route around a
 * timeout by slicing a scan into quarters — the authoring playbook says so in as many words —
 * and an author who raises it is expected to say why.
 *
 * Bounded at save time by `datapipelines.executor.node-timeout-max-seconds`
 * (`pipeline.validation.node_timeout_invalid`, §12.8): a per-node override that could exceed
 * the operator's ceiling would let one pipeline hold an execution slot for as long as it liked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeSettings(
    @field:JsonProperty("timeout_seconds") @get:JsonProperty("timeout_seconds") @param:JsonProperty("timeout_seconds")
    val timeoutSeconds: Int? = null,
    /**
     * This node's own SQL **statement** timeout (§4.11, 156, #2) — overrides the pipeline's
     * `settings.query_timeout_seconds` (§5.3), the datasource's `query_timeout_seconds` and the
     * operator's per-dialect/application default, for this node alone. Legal only on a node type
     * that runs a statement (`DQL`, `DML`, `DDL`); `PipelineValidator` refuses it on `PIPELINE`/
     * `CALCULATOR` (§12.8, `pipeline.validation.node_query_timeout_invalid`).
     *
     * Distinct from [timeoutSeconds]: that one is the node's WALL-CLOCK deadline (render through
     * materialize, executor-enforced); this one bounds a single `execute*` call and is enforced
     * by the JDBC driver. Bounded by the same operator ceiling as [timeoutSeconds]
     * (`datapipelines.executor.node-query-timeout-max-seconds`) and additionally may not exceed
     * this node's own effective [timeoutSeconds] — refused, not clamped, naming both numbers.
     */
    @field:JsonProperty("query_timeout_seconds") @get:JsonProperty("query_timeout_seconds") @param:JsonProperty("query_timeout_seconds")
    val queryTimeoutSeconds: Int? = null,
)

/**
 * An immutable reference to one version of one pipeline — the `pipeline` block of a PIPELINE
 * node (pipeline-contract §4.9, design 2026-08-13-pipeline-node-type D5).
 *
 * `name` is the documented cross-pipeline identifier (§3.2) and `version` pins an existing,
 * immutable pipeline version — there is no "latest". The pair mirrors [TemplateRef]'s
 * `{id, version}` pinning and carries the same defaults for the same reason: a partial block
 * must still bind so §12.9, not Jackson, reports what is missing (`pipeline_not_found`).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PipelineNodeRef(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String = "",
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int = 0,
)
