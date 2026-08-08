package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The SQL category a node's template generates (pipeline-contract §4.6, enums.md §2).
 *
 * The value drives executor behaviour (§8.4): `DQL` stages / returns / writes back a
 * ResultSet, `DML` records an affected-row count, `DDL` records success. It also decides
 * whether an `output` block is legal at all — only `DQL` may carry one (§12.4).
 *
 * Wire values are UPPER and coincide with the constant names, but the `@JsonValue` /
 * `@JsonCreator` mapping is still explicit and mandatory: the enums.md catalog string, not
 * `Enum.name`, is the source of truth ("Case & serialization convention").
 */
enum class NodeType(
    @JsonValue val wire: String,
) {
    /** `SELECT`. Produces a ResultSet; may carry an `output` block. */
    DQL("DQL"),

    /** `INSERT` / `UPDATE` / `DELETE` / `MERGE`. Produces a row count; no `output` block. */
    DML("DML"),

    /** `CREATE` / `ALTER` / `DROP` / `TRUNCATE`. Produces success/failure; no `output` block. */
    DDL("DDL"),
    ;

    companion object {
        /** The wire values a payload may carry, in catalog order — used by validation messages. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        /** Resolves a wire value, or null when the payload's value is not in the catalog. */
        fun fromWireOrNull(value: String?): NodeType? = entries.firstOrNull { it.wire == value }

        /**
         * Resolves a wire value.
         *
         * Throws on an unknown value rather than returning null: by the time binding runs,
         * [PipelineDeserializer] has already rejected out-of-catalog values with
         * `pipeline.validation.type_invalid`, so reaching here with garbage is a bug, not
         * user input.
         */
        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): NodeType =
            fromWireOrNull(value)
                ?: throw IllegalArgumentException("Unknown NodeType: ${value.truncateForError()}")
    }
}
