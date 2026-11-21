package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A pipeline's structured request-body input (pipeline-contract §3.4; published-mutation-apis §3
 * item 3, #1038): the pipeline takes ONE JSON object or array of objects, delivered unchanged to
 * every SCRIPT input that binds [REFERENCE].
 *
 * The body is never a scalar parameter and never a Context key: no SQL template can bind it, no
 * caller can name it in `parameters`, and a JSON key inside it cannot shadow a parameter, a
 * Context key or a staged table (§12.13). Only a pinned SCRIPT transformer reads it, and only that
 * transformer's declared output has a field schema.
 *
 * [required] true: an execution that supplies no body is refused before any node runs
 * (`pipeline.execution.request_body_invalid`, reason `required`). False: the body is optional —
 * an execution without one runs with the input absent, which a transformer sees as undefined
 * (never null, never `{}` or `[]`).
 *
 * Lenient on read like every pipeline sub-object ([PipelineSettings]): an unknown key is ignored so
 * a later addition is not a breaking change. A pipeline with no declaration takes no body at all;
 * the field is omitted from the serialized body, so no stored version's hash moves.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RequestBodyDeclaration(
    @field:JsonProperty("required") @get:JsonProperty("required") @param:JsonProperty("required")
    val required: Boolean = false,
) {
    companion object {
        /**
         * The SCRIPT input binding that reads the request body (§4.12). The `@` prefix is outside
         * the staged-table and `$key` vocabularies, so no table name or Context key can ever spell
         * it.
         */
        const val REFERENCE = "@request_body"
    }
}
