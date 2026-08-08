package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.sql.ResultSetMetaData

/**
 * One column descriptor of the schema envelope (type-system.md §7.1).
 *
 * ## Omitted is not null
 *
 * Three fields are optional, and for each one **absence carries meaning** (§7.3):
 *  - `precision` absent on `BIGDECIMAL` means **unbounded** — the source declares no
 *    precision ceiling (§4). It never means "unknown", and a synthetic ceiling must
 *    never be substituted.
 *  - `scale` absent on `DECIMAL` means the origin was **approximate** (REAL/FLOAT/
 *    DOUBLE), whose per-value scale varies (§4.1).
 *  - `nullable` absent means the driver reported `columnNullableUnknown`. Clients MUST
 *    NOT read absence as `false`.
 *
 * `@JsonInclude(NON_NULL)` is therefore load-bearing, not cosmetic: emitting
 * `"precision": null` would assert something the contract does not define.
 *
 * ## Unknown fields are ignored on read (normative, §7.1)
 *
 * `additionalProperties` is `true` by design so §9.2 can add optional fields without a
 * version bump. A client that rejects an unrecognized property is non-conformant —
 * hence `@JsonIgnoreProperties(ignoreUnknown = true)`, which is this codebase acting as
 * its own reference client.
 *
 * Every field carries an explicit [JsonProperty] on all three use-site targets so a
 * naming strategy configured anywhere upstream cannot silently rewrite the wire keys.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ColumnSchema(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: LogicalType,
    @field:JsonProperty("precision") @get:JsonProperty("precision") @param:JsonProperty("precision")
    val precision: Int? = null,
    @field:JsonProperty("scale") @get:JsonProperty("scale") @param:JsonProperty("scale")
    val scale: Int? = null,
    @field:JsonProperty("nullable") @get:JsonProperty("nullable") @param:JsonProperty("nullable")
    val nullable: Boolean? = null,
) {
    init {
        require(name.isNotEmpty()) { "Column name must be non-empty (§7.1 minLength 1)" }
        require(precision == null || precision >= 1) { "precision must be >= 1 when present, was $precision" }
        require(scale == null || scale >= 0) { "scale must be >= 0 when present, was $scale" }
        // The two conditional-required rules from the §7.1 JSON Schema `allOf`.
        require(type != LogicalType.DECIMAL || precision != null) {
            "DECIMAL requires precision (§7.1 allOf), column '$name'"
        }
        require(type != LogicalType.BIGDECIMAL || scale != null) {
            "BIGDECIMAL requires scale (§7.1 allOf), column '$name'"
        }
    }

    /**
     * True when this descriptor came from an approximate numeric source (§3.4/§4.1):
     * a `DECIMAL` whose scale was omitted. Staging uses it to choose H2 `DOUBLE` over
     * `DECIMAL(p, s)` (§4.2), and clients use it to know not to assume fixed fractional
     * digits.
     *
     * `@get:JsonIgnore` is mandatory, not tidiness: Jackson serializes a computed `val`
     * as a property, and §7.1 says producers emit only the fields the spec defines. An
     * invented `isApproximateNumeric` key in the envelope would be a contract break —
     * caught here by a serialized-key-set assertion in `ColumnSchemaTest`.
     */
    @get:JsonIgnore
    val isApproximateNumeric: Boolean
        get() = type == LogicalType.DECIMAL && scale == null

    /**
     * True when this descriptor declares an unbounded source numeric (§4): a
     * `BIGDECIMAL` with precision omitted. Not serialized — see [isApproximateNumeric].
     */
    @get:JsonIgnore
    val isUnboundedPrecision: Boolean
        get() = type == LogicalType.BIGDECIMAL && precision == null

    companion object {
        /**
         * Translates a JDBC `ResultSetMetaData.isNullable()` verdict into the optional
         * `nullable` field (§7.3).
         *
         * `columnNullableUnknown` maps to `null` — the field is then omitted from the
         * envelope entirely, because "the driver would not say" and "the column is NOT
         * NULL" are different facts and conflating them is a lie clients act on.
         */
        fun nullableFromJdbc(jdbcNullability: Int): Boolean? =
            when (jdbcNullability) {
                ResultSetMetaData.columnNullable -> true
                ResultSetMetaData.columnNoNulls -> false
                else -> null
            }
    }
}
