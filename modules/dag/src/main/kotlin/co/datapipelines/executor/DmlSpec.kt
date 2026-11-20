package co.datapipelines.executor

import co.datapipelines.pipeline.DmlFields
import co.datapipelines.pipeline.DmlInput
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.DmlOperation
import co.datapipelines.pipeline.DmlShapeCheck
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * A DML node's declared shape as the executor sees it (pipeline-contract §4.4, #1034).
 *
 * @property operation null only on a stored operation-less node — run admission refuses that
 *   before any business write ([co.datapipelines.pipeline.DmlShapeCheck]); [DmlNodeRuns] refuses
 *   it again rather than fall back to the retired count-only contract.
 * @property scalarParameters an insert's `input.parameters`, each with its declaration, in the
 *   order the node lists them — the scalar input row's column types come from here.
 */
data class DmlSpec(
    val operation: DmlOperation?,
    val key: DmlKey?,
    val input: DmlInput?,
    val scalarParameters: Map<String, Parameter> = emptyMap(),
) {
    companion object {
        fun of(
            node: Node,
            parameters: Map<String, Parameter>,
        ): DmlSpec? =
            if (node.type != NodeType.DML) {
                null
            } else {
                DmlSpec(
                    operation = node.dmlOperation,
                    key = node.key,
                    input = node.input,
                    scalarParameters =
                        node.input
                            ?.parameters
                            .orEmpty()
                            .mapNotNull { name -> parameters[name]?.let { name to it } }
                            .toMap(),
                )
            }
    }
}

/**
 * Where one DML node's statement runs: a tempdb lease, an ordinary datasource lease, or the
 * family's local write transaction connection (write-transactions §3.3).
 */
internal interface DmlTarget {
    val dialect: Dialect
    val resolved: ResolvedQueryTimeout

    /** True on the family's transaction connection: the statement's effects wait for the root to settle. */
    val participant: Boolean

    /** The phase a failure to obtain the connection maps under (§8.2) — CONNECT for a datasource. */
    val connectPhase: NodePhase

    /**
     * True when an update/delete statement is always prepared, binds or not — the datasource
     * path's shape since before 042. Tempdb keeps [BoundStatements.statementFor]'s shape, which
     * creates a plain statement for parameterless SQL.
     */
    val preparesAlways: Boolean get() = false

    suspend fun <T> withConnection(block: suspend (Connection) -> T): T

    /** Runs after the statement's connection is returned — tempdb re-checks its staging budget. */
    suspend fun afterWrite() = Unit
}

/**
 * Reads and checks the ONE generated-key row an insert's statement returns (record §5): the
 * driver must hand back exactly the declared column, exactly one row, one non-null value of the
 * declared key type — and, when the input supplied the id, that same value unchanged. Anything
 * else fails the node with `dml_key_correlation_failed`; no value is guessed, and none is
 * quoted in the diagnostic (record §6: ids from business rows stay out of logs).
 */
internal object DmlKeys {
    fun read(
        keys: ResultSet,
        key: DeclaredKey,
        nodeId: String,
        rowIndex: Int,
    ): Any {
        val meta = keys.metaData
        var value: Any? = null
        // Checked in order; the first broken expectation names the reason (a cursor read moves it).
        val refusal =
            when {
                meta.columnCount != 1 -> "key_columns"
                !meta.getColumnLabel(1).equals(key.column, ignoreCase = true) -> "key_column"
                !keys.next() -> "key_missing"
                keys.getObject(1).also { value = it } == null -> "key_missing"
                keys.next() -> "key_ambiguous"
                else -> null
            }
        val normalized = value?.takeIf { refusal == null }?.let { normalize(it, key.type) }
        return normalized ?: throw correlationFailed(nodeId, rowIndex, refusal ?: "key_type")
    }

    /**
     * [value] as the canonical value of [type], or null when it is not one: INTEGER is a 32-bit
     * whole number (`ResultRowReader` reads it with `getInt`), BIGINTEGER any whole number
     * (a `Long` when it fits, else a `BigInteger` — §3.2's decimal-string wire form either
     * way), STRING a character string or a UUID.
     */
    fun normalize(
        value: Any,
        type: LogicalType,
    ): Any? =
        when (type) {
            LogicalType.INTEGER -> wholeNumber(value)?.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt()
            LogicalType.BIGINTEGER -> wholeNumber(value)?.let { if (it.bitLength() < Long.SIZE_BITS) it.toLong() else it }
            LogicalType.STRING -> (value as? CharSequence)?.toString() ?: (value as? UUID)?.toString()
            else -> null
        }

    private fun wholeNumber(value: Any): BigInteger? =
        when (value) {
            is Int, is Long, is Short, is Byte -> BigInteger.valueOf((value as Number).toLong())
            is BigInteger -> value
            is BigDecimal -> runCatching { value.toBigIntegerExact() }.getOrNull()
            else -> null
        }

    fun correlationFailed(
        nodeId: String,
        rowIndex: Int,
        reason: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.DML_KEY_CORRELATION_FAILED,
            message =
                "DML insert node '$nodeId' could not correlate input row $rowIndex with its key ($reason); " +
                    "no row of this insert is kept.",
            details = mapOf("node" to nodeId, "row_index" to rowIndex, "reason" to reason),
        )
}

/** An insert's declared key, resolved: the physical column and the canonical type of `id`. */
internal data class DeclaredKey(
    val column: String,
    val type: LogicalType,
) {
    companion object {
        fun of(
            key: DmlKey?,
            nodeId: String,
        ): DeclaredKey {
            val column = key?.column
            val type = key?.logicalType
            if (column.isNullOrBlank() || type == null) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Validation.DML_KEY_INVALID,
                    message = "DML insert node '$nodeId' declares no usable key; it was not admitted.",
                    details = mapOf("node" to nodeId),
                )
            }
            return DeclaredKey(column, type)
        }
    }
}

/** An insert's input as loaded: canonical columns and their rows, `id` among them when supplied. */
internal data class DmlInputRows(
    val columns: List<co.datapipelines.typesystem.ColumnSchema>,
    val rows: List<List<Any?>>,
) {
    /** The position of the supplied logical key, or -1 when the input carries none. */
    val idIndex: Int = columns.indexOfFirst { it.name.equals(DmlFields.ID, ignoreCase = true) }
}

/**
 * Run admission's DML gate (write-transactions §4 row 1, §7; pipeline-contract §12.4): the
 * pinned body's DML shape rules, re-applied before any business write. A stored version saved
 * before a DML node had to declare its operation (the owner's legacy-DML waiver) ends FAILED here
 * with the code save-time validation gives, never under the retired count-only contract.
 */
internal object DmlAdmission {
    fun refuseInvalidShape(pipeline: Pipeline) {
        val failure = DmlShapeCheck.failures(pipeline).firstOrNull() ?: return
        throw DatapipelinesException(
            code = failure.code,
            message = "Run refused: ${failure.message}",
            details = failure.details + ("path" to failure.path),
        )
    }
}
