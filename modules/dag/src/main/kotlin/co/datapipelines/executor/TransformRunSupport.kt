package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.scripting.CanonicalJson
import co.datapipelines.scripting.GateRefusal
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.TransformOutput
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.JsonEncoder
import co.datapipelines.typesystem.LogicalType
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The value forms of the transform boundary (transform-nodes design §6): the WIRE form the
 * engine and the type gate speak, and the JVM storage form tempdb and the Context hold.
 *
 * There is no transform-specific type table (D-T5): a value crosses in the wire form
 * type-system.md §3 already fixes for its `LogicalType` — temporals as ISO-8601 strings
 * through the type system's canonical encoder (never a second formatter), `BIGDECIMAL` and
 * `BIGINTEGER` as strings, `DECIMAL` a JSON number, the rest as-is.
 */
internal object TransformValues {
    /** The rejects tables' companion column (record §2.2): the input/output columns plus `reason`. */
    val REASON_COLUMN = ColumnSchema("reason", LogicalType.STRING)

    /** [REASON_COLUMN] as a contract column, for the invariants' rejects read-back. */
    val REASON_CONTRACT_COLUMN = ContractColumn("reason", LogicalType.STRING, nullable = false)

    /**
     * A Context or staged value in the wire form the engine sees (§6): a `LocalDate` becomes
     * its ISO string, a `BigDecimal` for a `BIGDECIMAL` column its plain string, and so on.
     * Values already in wire form (what the function returned) pass through untouched.
     */
    fun wireValueOf(
        value: Any?,
        type: LogicalType,
    ): Any? =
        when (value) {
            null -> {
                null
            }

            is LocalDate, is LocalTime, is OffsetDateTime, is Instant, is LocalDateTime -> {
                JsonEncoder.encode(value, ColumnSchema("value", type, nullable = true))
            }

            is BigInteger -> {
                value.toString()
            }

            is BigDecimal -> {
                if (type == LogicalType.BIGDECIMAL) value.toPlainString() else value
            }

            else -> {
                value
            }
        }

    /** The JVM form a gated wire value is stored as — in tempdb and in the Context (§6 reversed). */
    fun storageValueOf(
        value: Any?,
        column: ContractColumn,
    ): Any? =
        when (value) {
            null -> {
                null
            }

            else -> {
                when (column.type) {
                    LogicalType.DATE -> {
                        LocalDate.parse(value.toString())
                    }

                    LogicalType.TIME -> {
                        LocalTime.parse(value.toString())
                    }

                    LogicalType.TIMESTAMP -> {
                        runCatching { OffsetDateTime.parse(value.toString()) }
                            .getOrElse { LocalDateTime.parse(value.toString()).toInstant(ZoneOffset.UTC) }
                    }

                    LogicalType.BIGDECIMAL -> {
                        BigDecimal(value.toString())
                    }

                    LogicalType.BIGINTEGER -> {
                        BigInteger(value.toString()).longValueExact()
                    }

                    LogicalType.INTEGER -> {
                        BigDecimal(value.toString()).intValueExact()
                    }

                    else -> {
                        value
                    }
                }
            }
        }

    /** One gated row as a storage row in contract column order. */
    fun storageRow(
        row: Map<String, Any?>,
        columns: List<ContractColumn>,
    ): List<Any?> = columns.map { storageValueOf(row[it.name], it) }

    /** One gated reject as a storage row: the row's values in column order, then the reason. */
    fun rejectRow(
        reject: RejectedRow,
        columns: List<ContractColumn>,
    ): List<Any?> = columns.map { storageValueOf(reject.row[it.name], it) } + reject.reason

    /** The value-mode Context write: the storage form, except an object, which crosses as-is (R4). */
    fun contextForm(
        gated: Any?,
        output: TransformOutput,
    ): Any? =
        when (output) {
            is TransformOutput.Obj -> {
                gated
            }

            is TransformOutput.Value -> {
                storageValueOf(
                    gated,
                    ContractColumn("value", output.type, output.precision, output.scale),
                )
            }

            is TransformOutput.Table -> {
                gated
            }
        }

    /** The text rendering of a value-mode write for the node stats (the typed value is in the Context). */
    fun renderContextValue(value: Any?): String? =
        when (value) {
            null -> null
            is Map<*, *>, is List<*> -> CanonicalJson.write(value)
            else -> value.toString()
        }
}

/**
 * The §13.18 refusals [TransformNodeRuns] raises — one place, so the code-to-shape mapping has
 * exactly one home (`ErrorCodeMapper` carries them through unchanged).
 */
internal object TransformFailures {
    fun inputContract(
        node: ExecutableNode,
        detail: String,
        rule: String,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Transform.INPUT_CONTRACT_VIOLATION,
        message = "TRANSFORM node '${node.id}': $detail",
        details = mapOf("node" to node.id, "rule" to rule),
    )

    fun tooLarge(
        node: ExecutableNode,
        detail: String,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Transform.INPUT_TOO_LARGE,
        message = "TRANSFORM node '${node.id}': $detail",
        details = mapOf("node" to node.id),
    )

    fun rowShape(
        node: ExecutableNode,
        batch: Int?,
        row: Int,
        detail: String,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH,
        message = "TRANSFORM node '${node.id}'${batch?.let { ", batch $it" }.orEmpty()} row $row: $detail.",
        details = mapOf("node" to node.id, "batch" to batch, "row" to row),
    )

    fun gateFailure(
        node: ExecutableNode,
        refusal: GateRefusal,
        batch: Int?,
    ) = DatapipelinesException(
        code = codeOf(refusal),
        message = "TRANSFORM node '${node.id}'${batch?.let { ", batch $it" }.orEmpty()}: ${describe(refusal)}",
        details = mapOf("node" to node.id, "batch" to batch),
    )

    fun invariantsTooLarge(
        node: ExecutableNode,
        total: Long,
        maxInputRows: Long,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Transform.INVARIANTS_TOO_LARGE,
        message =
            "TRANSFORM node '${node.id}': the invariants' read-back is $total rows " +
                "(cap $maxInputRows, max-input-rows). An assertion over an unbounded table " +
                "is a SQL node or a release check, not an invariant.",
        details = mapOf("node" to node.id, "rows" to total, "max_input_rows" to maxInputRows),
    )

    /**
     * A failure out of the pool or the engine: the §13.18-carried exceptions pass through
     * untouched (their code wins — `ErrorCodeMapper`'s own rule); anything else becomes
     * `evaluation_failed` with the message bounded (record §7).
     */
    fun decorate(
        error: Exception,
        node: ExecutableNode,
    ): Exception =
        when (error) {
            is CancellationException -> {
                error
            }

            is DatapipelinesException -> {
                error
            }

            else -> {
                DatapipelinesException(
                    code = PipelineErrorCodes.Transform.EVALUATION_FAILED,
                    message =
                        "TRANSFORM node '${node.id}': ${error.message?.take(MAX_MESSAGE_CHARS) ?: error.javaClass.simpleName}",
                    details = mapOf("node" to node.id),
                    cause = error,
                )
            }
        }

    /** The §13.18 code for one gate refusal — the mapping the whole system shares. */
    private fun codeOf(refusal: GateRefusal): String =
        when (refusal) {
            is GateRefusal.RowShapeMismatch -> PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH
            is GateRefusal.ValueTypeMismatch -> PipelineErrorCodes.Transform.VALUE_TYPE_MISMATCH
            is GateRefusal.PrecisionLost -> PipelineErrorCodes.Transform.PRECISION_LOST
            is GateRefusal.ValueTooLarge -> PipelineErrorCodes.Transform.VALUE_TOO_LARGE
        }

    private fun describe(refusal: GateRefusal): String =
        when (refusal) {
            is GateRefusal.RowShapeMismatch -> {
                "row ${refusal.row}: shape mismatch (missing ${refusal.missing}, extra ${refusal.extra})"
            }

            is GateRefusal.ValueTypeMismatch -> {
                "${refusal.column}: expected ${refusal.expected}, got ${refusal.actual}"
            }

            is GateRefusal.PrecisionLost -> {
                "${refusal.column}: ${refusal.detail}"
            }

            is GateRefusal.ValueTooLarge -> {
                "${refusal.column}: ${refusal.bytes} bytes above the cap"
            }
        }

    private const val MAX_MESSAGE_CHARS = 2_000
}

/**
 * The §5.1 production input checks: the staged schema's COVER rule and the per-batch
 * nullability scan.
 */
internal object TransformChecks {
    /**
     * §5.1: the staged table must COVER every contract column with a compatible type — exact
     * `LogicalType`, and exact scale where the contract declares one. A staged table may carry
     * MORE columns than the contract; the node reads only the declared ones.
     */
    fun coverCheck(
        node: ExecutableNode,
        inputName: String,
        columns: List<ContractColumn>,
        staged: List<ColumnSchema>,
    ) {
        columns.forEach { column ->
            val actual =
                staged.firstOrNull { it.name == column.name }
                    ?: throw TransformFailures.inputContract(
                        node,
                        "input '$inputName': the staged table has no column '${column.name}' " +
                            "(it stages ${staged.map { it.name }})",
                        "missing_column",
                    )
            val scaleFits = column.scale == null || column.scale == actual.scale
            if (actual.type != column.type || !scaleFits) {
                throw TransformFailures.inputContract(
                    node,
                    "input '$inputName' column '${column.name}': the contract declares " +
                        "${column.type.wire} but the staged table carries ${actual.type.wire}",
                    "type_mismatch",
                )
            }
        }
    }

    /**
     * §5.1's nullability half, per batch: a contract column with `nullable: false` is scanned
     * for nulls unless the staged schema POSITIVELY says `nullable = false` (an absent value
     * is "the driver did not say", never false — the `ColumnSchema` KDoc's own rule).
     */
    fun nullableScan(
        node: ExecutableNode,
        batch: List<Map<String, Any?>>,
        columns: List<ContractColumn>,
        staged: List<ColumnSchema>,
        batchNumber: Int,
    ) {
        columns.filter { !it.nullable }.forEach { column ->
            if (staged.firstOrNull { it.name == column.name }?.nullable == false) return@forEach
            batch.forEachIndexed { index, row ->
                if (row[column.name] == null) {
                    throw TransformFailures.inputContract(
                        node,
                        "batch $batchNumber row ${index + 1}: column '${column.name}' is null " +
                            "and the contract declares it nullable: false",
                        "null_value",
                    )
                }
            }
        }
    }
}
