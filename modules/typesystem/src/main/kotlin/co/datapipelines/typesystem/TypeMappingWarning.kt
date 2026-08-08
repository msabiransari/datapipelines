package co.datapipelines.typesystem

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A non-fatal type-mapping warning carried in the response envelope's `warnings`
 * array (type-system.md §8.2, §10.5).
 *
 * These are **warnings, not errors** — pipeline-contract.md §13.6 registers both codes
 * with no HTTP status precisely because neither fails an execution. The pipeline
 * completes; the author sees the warning and usually fixes it with a `CAST` in the
 * source template.
 *
 * `source_type` is snake_case on the wire and absent from the §10.5 `sql_variant`
 * example, so it is nullable and dropped when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TypeMappingWarning(
    @field:JsonProperty("code") @get:JsonProperty("code") @param:JsonProperty("code")
    val code: String,
    @field:JsonProperty("message") @get:JsonProperty("message") @param:JsonProperty("message")
    val message: String,
    @field:JsonProperty("column") @get:JsonProperty("column") @param:JsonProperty("column")
    val column: String,
    @field:JsonProperty("source_type") @get:JsonProperty("source_type") @param:JsonProperty("source_type")
    val sourceType: String? = null,
) {
    companion object {
        /**
         * §8.2 — the source type has no canonical mapping in this dialect's table (or
         * the dialect itself has no mapper yet, §11.2). One per affected column.
         */
        const val UNKNOWN_SOURCE_TYPE = "type_mapping.unknown_source_type"

        /** §5.3 / §10.5 — MSSQL `sql_variant`, whose per-row type cannot be declared. */
        const val SQL_VARIANT = "type_mapping.sql_variant"

        /** Builds the §8.2 warning, wording taken from the spec's own example payload. */
        fun unknownSourceType(
            column: String,
            sourceType: String,
        ): TypeMappingWarning =
            TypeMappingWarning(
                code = UNKNOWN_SOURCE_TYPE,
                message =
                    "Source type '$sourceType' on column '$column' has no canonical mapping; " +
                        "falling back to STRING.",
                column = column,
                sourceType = sourceType,
            )

        /** Builds the §10.5 warning, wording taken from the spec's own example payload. */
        fun sqlVariant(column: String): TypeMappingWarning =
            TypeMappingWarning(
                code = SQL_VARIANT,
                message =
                    "Column '$column' is MSSQL sql_variant; values serialized as text. " +
                        "CAST to a concrete type in source SQL for typed access.",
                column = column,
            )
    }
}
