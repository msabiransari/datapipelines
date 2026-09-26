package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode

/**
 * How many values one parameter carries (enums.md §26; parameter-engine record §3.2, P28).
 *
 * `MULTI` is part of the contract from #194 lane A so pipelines and the parameter engine share
 * one declaration model, but a pipeline refuses it at save until the dashboard round adopts list
 * binding (pipeline-contract §6.2, `pipeline.validation.cardinality_unsupported`).
 */
enum class ParameterCardinality(
    @JsonValue val wire: String,
) {
    /** One value — the default, and the only shape a pipeline parameter binds today. */
    SINGLE("SINGLE"),

    /** A JSON array of distinct values of the declared type; `[]` reads as unsupplied (P25). */
    MULTI("MULTI"),
    ;

    companion object {
        /** The wire spellings, for validation messages. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        /** The constant for [value], or null — for pre-scans that refuse with a catalogued code. */
        fun fromWireOrNull(value: String?): ParameterCardinality? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): ParameterCardinality =
            fromWireOrNull(value) ?: throw IllegalArgumentException("Unknown ParameterCardinality: ${value.truncateForError()}")
    }
}

/**
 * The value rules a declaration may carry beyond its type (parameter-engine record §3.5, P19):
 * a value that breaks one is REFUSED, never rounded, trimmed or clamped.
 *
 * `min`/`max` stay [JsonNode]s because they are wire-encoded in the parameter's OWN type — a
 * `BIGDECIMAL` bound is a string, an `INTEGER` one a number — and binding them to a Kotlin type
 * here would erase the distinction [ParameterValueValidator.checkDeclaration] checks (the reason
 * `pipeline-contract`'s `Parameter.default` is a [JsonNode] too). The keys are snake_case on the
 * wire, spelled on all three use sites (the Kotlin-Jackson naming trap).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ParameterConstraints(
    @field:JsonProperty("min") @get:JsonProperty("min") @param:JsonProperty("min")
    val min: JsonNode? = null,
    @field:JsonProperty("max") @get:JsonProperty("max") @param:JsonProperty("max")
    val max: JsonNode? = null,
    @field:JsonProperty("min_length") @get:JsonProperty("min_length") @param:JsonProperty("min_length")
    val minLength: Int? = null,
    @field:JsonProperty("max_length") @get:JsonProperty("max_length") @param:JsonProperty("max_length")
    val maxLength: Int? = null,
    @field:JsonProperty("pattern") @get:JsonProperty("pattern") @param:JsonProperty("pattern")
    val pattern: String? = null,
) {
    companion object {
        /** Every wire key — a pre-scan refuses any other, so a misspelt rule is never silently absent. */
        val KEYS: Set<String> = setOf("min", "max", "min_length", "max_length", "pattern")

        /** The two keys whose wire value must be a non-negative JSON integer. */
        val LENGTH_KEYS: Set<String> = setOf("min_length", "max_length")
    }
}

/**
 * One parameter declaration as [ParameterValueValidator] reads it — the fields P28 names, and
 * nothing a caller does not share: a pipeline parameter (pipeline-contract §6.1) and a parameter
 * set's `INPUT` (the engine) both project onto it, so both are judged by the same rules.
 */
data class ParameterDeclaration(
    val type: LogicalType,
    val precision: Int? = null,
    val scale: Int? = null,
    val required: Boolean = false,
    /** Wire-encoded like any supplied value; null or JSON null means "no default". */
    val default: JsonNode? = null,
    val constraints: ParameterConstraints? = null,
    val cardinality: ParameterCardinality = ParameterCardinality.SINGLE,
)

/**
 * The bounds a caller configures the validator with (config over code — the engine binds them to
 * `datapipelines.parameters.*` in lane B; pipelines use the defaults).
 *
 * @property maxRegexReads the read budget of the counting `CharSequence` a `pattern` runs over
 *   (P31): past it the match is refused as `pattern_budget`, whatever the pattern does.
 * @property defaultMaxLength the `max_length` a `STRING`/`BINARY` value gets when its declaration
 *   states none; null means unbounded — a pipeline's behaviour before and after #194.
 */
data class ParameterValueLimits(
    val maxRegexReads: Long = DEFAULT_MAX_REGEX_READS,
    val defaultMaxLength: Int? = null,
) {
    init {
        require(maxRegexReads > 0) { "maxRegexReads must be positive; got $maxRegexReads" }
        require(defaultMaxLength == null || defaultMaxLength >= 0) { "defaultMaxLength must be non-negative; got $defaultMaxLength" }
    }

    companion object {
        /** Parameter-engine record §11's `max-regex-steps` default (P31). */
        const val DEFAULT_MAX_REGEX_READS: Long = 100_000L
    }
}
