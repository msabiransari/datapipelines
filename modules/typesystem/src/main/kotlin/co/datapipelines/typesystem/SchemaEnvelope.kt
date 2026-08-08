package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The schema envelope that travels with every result set (type-system.md §7.2).
 *
 * [schemaVersion] is the additive-evolution counter of §9.4: integers, monotonic,
 * never reused, bumped only when a new canonical type is added. Adding an *optional*
 * field to [ColumnSchema] does **not** bump it (§9.2) — that is what the open-object
 * rule in §7.1 buys.
 *
 * `schema_version` is exactly the snake_case shape a Jackson naming strategy would
 * mangle, so it carries an explicit [JsonProperty] on all three use-site targets.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SchemaEnvelope(
    @field:JsonProperty("schema") @get:JsonProperty("schema") @param:JsonProperty("schema")
    val schema: List<ColumnSchema>,
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        /** v1 of the canonical type system (§9.4). */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
