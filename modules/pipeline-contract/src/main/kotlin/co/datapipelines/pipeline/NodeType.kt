package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The SQL category a node's template generates (pipeline-contract §4.6, enums.md §2) — or, for
 * `PIPELINE`, a node that runs no SQL of its own at all.
 *
 * The value drives executor behaviour (§8.4): `DQL` stages / returns / writes back a
 * ResultSet, `DML` records an affected-row count, `DDL` records success, `PIPELINE` executes
 * the pinned child pipeline and consumes its result (§8.5). It also decides whether an `output`
 * block is legal at all — `DQL` always may carry one, `PIPELINE` may when the pinned child has
 * a caller node (§12.9), `DML`/`DDL` never (§12.4).
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

    /** Executes another pipeline as a child execution (§4.9, §8.5); carries a `pipeline` ref, never `source`/`template`. */
    PIPELINE("PIPELINE"),
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
