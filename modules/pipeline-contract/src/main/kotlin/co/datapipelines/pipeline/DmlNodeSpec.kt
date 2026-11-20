package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The declared operation of a DML node (pipeline-contract §4.4, write-transactions §3.4).
 *
 * One node type, three operations: an `insert` returns each input row with its logical `id`; an
 * `update` or `delete` returns one row holding `affected_rows`. The node carries the operation as
 * its wire string ([Node.operation]) so §12.4 can report an absent or misspelled value with a
 * catalog code; this enum is the resolved view the executor dispatches on.
 */
enum class DmlOperation(
    @JsonValue val wire: String,
) {
    INSERT("insert"),
    UPDATE("update"),
    DELETE("delete"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): DmlOperation? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * An insert node's physical key (§4.4): the ONE target column whose supplied or generated value
 * becomes the output row's logical `id`, and the canonical type that `id` is emitted as.
 *
 * Both fields are nullable strings so §12.4 — not binding — reports a missing column or an
 * unsupported type (`dml_key_invalid`). The type is declared rather than read off the driver's
 * key metadata because an empty input still emits the declared output schema (§5): there is no
 * generated-key cursor to read a type from when nothing was inserted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DmlKey(
    @field:JsonProperty("column") @get:JsonProperty("column") @param:JsonProperty("column")
    val column: String? = null,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: String? = null,
) {
    /** The declared canonical type when it is one of [SUPPORTED_TYPES], else null. */
    @get:JsonIgnore
    val logicalType: LogicalType? get() = SUPPORTED_TYPES.firstOrNull { it.wire == type }

    companion object {
        /**
         * The key types v1 correlates: a whole number (INTEGER / BIGINTEGER — §3.2's split, so a
         * value past JSON's safe integer range travels as a decimal string) or a string (text and
         * UUID keys). Composite, binary, temporal and decimal keys are refused at save time
         * (record §5: unsupported key forms never fall back to guessing).
         */
        val SUPPORTED_TYPES: List<LogicalType> = listOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.STRING)
    }
}

/**
 * An insert node's input (§4.4): exactly one of
 *  - [parameters] — the named declared pipeline parameters, bound as ONE input row (the scalar
 *    request-parameter case), or
 *  - [table] — a tempdb table an ancestor node stages; one target row per staged row.
 *
 * Each input field binds by its own name (`:name` in the template). The field `id`, when present
 * and non-null, is the supplied logical key; absent or null asks the database to generate it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DmlInput(
    @field:JsonProperty("parameters") @get:JsonProperty("parameters") @param:JsonProperty("parameters")
    val parameters: List<String>? = null,
    @field:JsonProperty("table") @get:JsonProperty("table") @param:JsonProperty("table")
    val table: String? = null,
)

/** The DML result-row field names (§4.4) — one home, so the executor, docs and tests agree. */
object DmlFields {
    /** The logical key every insert output row carries, whatever the physical key column is named. */
    const val ID = "id"

    /** The one field of an update/delete output row: a nonnegative BIGINTEGER. */
    const val AFFECTED_ROWS = "affected_rows"
}
