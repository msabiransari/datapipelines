package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

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
 * For DML / DDL nodes an omitted `output` stays `null`: their side effect *is* the output
 * (§4.4/§4.5), and a present block is a validation failure (§12.4), not a shape this class
 * silently normalises away.
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
         * `template_not_found`, an absent source fails `unknown_datasource` — the failure is
         * reported, with a code from the catalog, by the component the spec assigns it to.
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
        ): Node =
            Node(
                id = id.orEmpty(),
                description = description.orEmpty(),
                type = type,
                source = source.orEmpty(),
                template = template ?: TemplateRef(),
                // D1 / §4.7: omitted output on a DQL node IS `{"target": "caller"}`.
                output = output ?: if (type == NodeType.DQL) NodeOutput.Caller else null,
                dependsOn = dependsOn ?: emptyList(),
            )
    }
}
