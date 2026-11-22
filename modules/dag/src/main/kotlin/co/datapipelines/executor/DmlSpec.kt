package co.datapipelines.executor

import co.datapipelines.datasources.LocalWriteQualification
import co.datapipelines.datasources.QualificationStatus
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
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.DmlInsertQualification
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
 * pinned body's DML shape rules and the insert qualification, re-applied before any business
 * write. A stored version saved before a DML node had to declare its operation (the owner's
 * legacy-DML waiver) ends FAILED here with the code save-time validation gives, never under the
 * retired count-only contract.
 *
 * Both checks run over the WHOLE pinned family — the root body in [PipelineExecutor], every
 * reachable child body in [LocalWritePlanner]'s walk — so an operation-less or unqualified node
 * in a child is refused before an earlier writer anywhere in the family has run. Each execution
 * still re-checks its own body and each insert re-checks its live target (the per-node
 * backstops): a run admitted on yesterday's registry answer never writes through today's.
 */
internal object DmlAdmission {
    /**
     * Refuses [pipeline] when its DML nodes break the stable shape rules. [child] is the pinned
     * reference the body was reached through, and [via] the node ids leading to it (empty for
     * the root body); both are named in the refusal so an author can find the node.
     */
    fun refuseInvalidShape(
        pipeline: Pipeline,
        child: PipelineNodeRef? = null,
        via: List<String> = emptyList(),
    ) {
        val failure = DmlShapeCheck.failures(pipeline).firstOrNull() ?: return
        throw DatapipelinesException(
            code = failure.code,
            message = "Run refused: ${where(child)}${failure.message}",
            details = failure.details + ("path" to failure.path) + family(child, via),
        )
    }

    /**
     * True when an insert whose declared key is [keyType] may run on [dialect]: the dialect returns its
     * named key column ([DmlInsertQualification]) AND, on an engine the single-database transaction's
     * qualification matrix covers (#1036), that key type was measured in both key modes with the pinned
     * driver ([LocalWriteQualification.insertStatus]). A key type no matrix row covers is refused, never
     * admitted under another type's evidence. An invalid key (null type) is left to the key rules'
     * own `dml_key_invalid`.
     */
    fun insertQualified(
        dialect: Dialect,
        keyType: LogicalType?,
    ): Boolean =
        DmlInsertQualification.isQualified(dialect) &&
            (
                keyType == null ||
                    !LocalWriteQualification.inScope(dialect) ||
                    LocalWriteQualification.insertStatus(dialect, keyType) == QualificationStatus.QUALIFIED
            )

    /**
     * Refuses the first DML `insert` of [pipeline] whose target dialect is known and not
     * qualified for its declared key type ([insertQualified]). [dialectOf] answers the CURRENT
     * dialect of a node's destination, or null when it cannot be resolved — such a node is left to
     * fail at its own execution with the ordinary `datasource_not_found`, exactly as without this check.
     */
    fun refuseUnqualifiedInsert(
        pipeline: Pipeline,
        dialectOf: (Node) -> Dialect?,
        child: PipelineNodeRef? = null,
        via: List<String> = emptyList(),
    ) {
        pipeline.nodes
            .asSequence()
            .filter { it.type == NodeType.DML && it.dmlOperation == DmlOperation.INSERT }
            .firstNotNullOfOrNull { node ->
                dialectOf(node)?.takeUnless { insertQualified(it, node.key?.logicalType) }?.let { node to it }
            }?.let { (node, dialect) -> throw unqualified(node.id, dialect, child, via, node.key?.logicalType) }
    }

    /** The `dml_insert_unqualified` refusal, shared by run admission and the node-level backstop. */
    fun unqualified(
        nodeId: String,
        dialect: Dialect,
        child: PipelineNodeRef? = null,
        via: List<String> = emptyList(),
        keyType: LogicalType? = null,
    ): DatapipelinesException {
        // A dialect that returns keys at all but was not measured with THIS key type says so.
        val typeOnly = keyType != null && DmlInsertQualification.isQualified(dialect)
        return DatapipelinesException(
            code = PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED,
            message =
                "${where(child)}DML insert node '$nodeId' targets a ${dialect.wire} connection, " +
                    if (typeOnly) {
                        "which is not qualified to return ${keyType.wire} keys; nothing was executed."
                    } else {
                        "which is not qualified to return inserted keys; nothing was executed."
                    },
            details =
                mapOf("node" to nodeId, "dialect" to dialect.wire) +
                    (if (typeOnly) mapOf("key_type" to keyType.wire) else emptyMap()) + family(child, via),
        )
    }

    private fun where(child: PipelineNodeRef?): String = child?.let { "pinned child '${it.name}' v${it.version}: " }.orEmpty()

    private fun family(
        child: PipelineNodeRef?,
        via: List<String>,
    ): Map<String, Any?> = if (child == null) emptyMap() else mapOf("child" to "${child.name}@${child.version}", "via" to via)
}
