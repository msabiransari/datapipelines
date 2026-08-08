package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/**
 * One input-parameter declaration — the descriptor of pipeline-contract §6.1.
 *
 * `parameters` is the pipeline's **input contract** and, since D3, the *single declaration
 * point* for every Freemarker variable any of its templates reference: templates carry no
 * parameter schema of their own, and save-time dry-rendering (§7.4, §12.6) is what proves
 * the two agree.
 *
 * ## `default` stays a [JsonNode]
 *
 * §12.7 `default_type_mismatch` is a rule about the **JSON type** of the default versus the
 * declared type's wire encoding (§6.2) — `BIGDECIMAL` takes a string, `INTEGER` a number.
 * Binding the default to a Kotlin type here would erase exactly the distinction the rule
 * checks: a JSON number and a JSON string both land in a `String` field once Jackson has
 * coerced them. Keeping the raw node means the validator sees what the author wrote.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Parameter(
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: LogicalType,
    @field:JsonProperty("required") @get:JsonProperty("required") @param:JsonProperty("required")
    val required: Boolean = false,
    @field:JsonProperty("default") @get:JsonProperty("default") @param:JsonProperty("default")
    val default: JsonNode? = null,
    @field:JsonProperty("precision") @get:JsonProperty("precision") @param:JsonProperty("precision")
    val precision: Int? = null,
    @field:JsonProperty("scale") @get:JsonProperty("scale") @param:JsonProperty("scale")
    val scale: Int? = null,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
) {
    /**
     * True when the declaration supplies a default the executor may apply.
     *
     * A JSON `null` default is *not* a default: §6.1 defines `default` as a value honoured
     * when the parameter is optional and absent, and `null` is indistinguishable from
     * "omitted" once bound. `@get:JsonIgnore` keeps this computed property out of the wire
     * shape — §6.1 defines the descriptor's keys exhaustively.
     */
    @get:JsonIgnore
    val hasDefault: Boolean get() = default != null && !default.isNull

    companion object {
        /**
         * The canonical types a parameter may declare — all 11 except `NULL` (§6.2,
         * enums.md §1 "Excluded from `parameters` declarations").
         */
        val ALLOWED_TYPES: Set<LogicalType> = LogicalType.entries.toSet() - LogicalType.NULL

        /** Their wire spellings, for validation messages and the §12.7 check. */
        val ALLOWED_TYPE_WIRE_VALUES: List<String> = ALLOWED_TYPES.map { it.wire }
    }
}
