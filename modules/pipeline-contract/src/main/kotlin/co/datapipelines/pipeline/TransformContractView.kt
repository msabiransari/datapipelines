package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType

/**
 * The pipeline validator's view of a pinned transform version's contract (§12.13).
 *
 * The contract's own model lives in the `templates` module (7b's `TransformContract`), which
 * pipeline-contract cannot see — the same inversion [TemplateDryRenderer] describes, one
 * level down: this is the shape the §12.13 rules check against, and the templates module's
 * implementation of [TemplateDryRenderer.transformContract] is the only place the two are
 * mapped. Field for field it is the record's §2.2 contract: the mode, the declared inputs,
 * the declared output, and whether the function partitions rows into a rejects half.
 */
data class TransformContractView(
    val mode: Mode,
    val inputs: Map<String, Input>,
    val output: Output,
    val rejects: Boolean,
) {
    /** The mode the pinned contract declares (record §2.2). */
    enum class Mode {
        ROW,
        TABLE,
        VALUE,
    }

    /** One declared column of a table input or a table output. */
    data class Column(
        val name: String,
        val type: LogicalType,
        val nullable: Boolean,
    )

    /** One declared input — a staged table, or a single Context value. */
    sealed interface Input {
        /** A staged table the contract names the columns of. */
        data class Table(
            val columns: List<Column>,
        ) : Input

        /** A single Context value of a declared LogicalType. */
        data class Value(
            val type: LogicalType,
        ) : Input
    }

    /** The contract's declared output — a table of rows, one value, or one object (R4). */
    sealed interface Output {
        /** A table output — `row`/`table` modes. */
        data class Table(
            val columns: List<Column>,
        ) : Output

        /** One value output (`value` mode) of a declared LogicalType. */
        data class Value(
            val type: LogicalType,
        ) : Output

        /** An object output (`value` mode) — an R4 object key, readable by TRANSFORM only. */
        data object Obj : Output
    }
}
