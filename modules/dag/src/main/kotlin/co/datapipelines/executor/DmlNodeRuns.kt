package co.datapipelines.executor

import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.pipeline.DmlFields
import co.datapipelines.pipeline.DmlOperation
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.StagingMemoryLimitException
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import kotlinx.coroutines.CancellationException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Instant

/**
 * A DML node's run (pipeline-contract §4.4, write-transactions record §3.4/§5, #1034): one node
 * type, three declared operations, ONE result contract on every engine that runs it.
 *
 *  - `insert` reads its input — one scalar row from declared parameters, or every row of a
 *    staged table — BEFORE any target connection is taken, then executes the template ONCE PER
 *    INPUT ROW on one prepared statement, binding the row's fields (and `id`) by name. Each
 *    execution must report exactly one affected row and return exactly the declared key column
 *    ([DmlKeys]); the output is every input row's payload plus its logical `id`. No batching:
 *    record §5 allows it only where pinned-driver tests prove correlation, and none do yet.
 *  - `update` / `delete` execute once and emit one row `{affected_rows}` — also for zero.
 *
 * The result goes where the node's output says: a staged table (what a dependent node reads),
 * the caller result, or nowhere (`discard`). It is delivered AFTER the target connection is
 * returned, so a tempdb write never nests inside a lease (staging §9.2).
 *
 * Atomicity of a multi-row insert: on the family's transaction connection the root settles
 * (#1033 rolls back every participating write when this node fails); on any other connection
 * the insert runs in its own node-local transaction, so a row that cannot be correlated never
 * leaves a partly inserted set behind (record §9: "no durable write or visible result").
 */
internal object DmlNodeRuns {
    @Suppress("LongParameterList") // the run's fixed facts, as NodeRunner's own legs take them
    suspend fun run(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        sql: String,
        target: DmlTarget,
        resultStore: ResultStore,
    ): NodeResult {
        val operation =
            node.dml?.operation
                ?: throw signal(
                    NodePhase.RENDER,
                    node.id,
                    DatapipelinesException(
                        code = PipelineErrorCodes.Validation.DML_OPERATION_REQUIRED,
                        message = "DML node '${node.id}' declares no operation; it was not admitted.",
                        details = mapOf("node" to node.id),
                    ),
                )
        val result =
            when (operation) {
                DmlOperation.INSERT -> insert(node, ctx, sql, target)
                DmlOperation.UPDATE, DmlOperation.DELETE -> count(node, ctx, sql, target)
            }
        return deliver(node, ctx, startedAt, result, resultStore)
    }

    /** The rows a DML node produced, and how many target rows its statement(s) wrote. */
    private class DmlRows(
        val columns: List<ColumnSchema>,
        val rows: List<List<Any?>>,
        val written: Long,
    )

    // ------------------------------------------------------------------ update / delete

    private suspend fun count(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        sql: String,
        target: DmlTarget,
    ): DmlRows {
        val bound = ScriptNodeRuns.phase(ctx, NodePhase.RENDER, node.id) { SqlBindTranslator.translate(sql, ctx.values) }
        val op = ctx.operations.observerFor(node.id)
        val affected =
            ScriptNodeRuns.phase(ctx, target.connectPhase, node.id) {
                target.withConnection { conn ->
                    countStatement(conn, bound, target).use { statement ->
                        statement.queryTimeout = target.resolved.seconds
                        ctx.handle.withStatement(node.id, statement) {
                            op.phase(OperationPhase.EXECUTING)
                            ScriptNodeRuns.phase(ctx, NodePhase.EXECUTE, node.id) {
                                withQueryTimeoutSource(target.resolved) {
                                    ctx.handle.whileExecuting(node.id, statement) { executeCount(statement, bound).toLong() }
                                }
                            }
                        }
                    }
                }
            }
        target.afterWrite()
        record(op, affected, target)
        return DmlRows(listOf(AFFECTED_ROWS_COLUMN), listOf(listOf(affected)), affected)
    }

    private fun countStatement(
        conn: Connection,
        bound: SqlBindTranslator.BoundSql,
        target: DmlTarget,
    ): Statement =
        if (target.preparesAlways) {
            conn.prepareStatement(bound.sql).also { if (bound.hasBindParameters) SqlBindTranslator.bind(it, bound.bindValues) }
        } else {
            BoundStatements.statementFor(conn, bound)
        }

    private fun executeCount(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Int = if (statement is PreparedStatement) statement.executeUpdate() else BoundStatements.update(statement, bound)

    // ------------------------------------------------------------------ insert

    private suspend fun insert(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        sql: String,
        target: DmlTarget,
    ): DmlRows {
        // Refused before input is read or any statement prepared: an unqualified engine is never
        // asked for a key it may answer with something else (SQLite's rowid).
        val declaredType = node.dml?.key?.logicalType
        if (!DmlAdmission.insertQualified(target.dialect, declaredType)) {
            throw signal(NodePhase.CONNECT, node.id, DmlAdmission.unqualified(node.id, target.dialect, keyType = declaredType))
        }
        val key = ScriptNodeRuns.phase(ctx, NodePhase.RENDER, node.id) { DeclaredKey.of(node.dml?.key, node.id) }
        val input = ScriptNodeRuns.phase(ctx, NodePhase.STAGE, node.id) { DmlInputs.load(node, ctx) }
        val payloadColumns = input.columns.filterIndexed { index, _ -> index != input.idIndex }
        val columns = payloadColumns + ColumnSchema(DmlFields.ID, key.type, nullable = false)
        // The strict shape check, on the loaded columns and before any connection: a template
        // name that is neither a pipeline value nor an input field fails sql_parameter_missing —
        // for an empty input too, which then emits zero rows under the declared schema (§5).
        val shape =
            ScriptNodeRuns.phase(ctx, NodePhase.RENDER, node.id) {
                SqlBindTranslator.translate(sql, rowScope(ctx, input, input.columns.map { null }))
            }
        if (input.rows.isEmpty()) return DmlRows(columns, emptyList(), 0L)
        val op = ctx.operations.observerFor(node.id)
        val ids =
            ScriptNodeRuns.phase(ctx, target.connectPhase, node.id) {
                target.withConnection { conn ->
                    ScriptNodeRuns.phase(ctx, NodePhase.EXECUTE, node.id) {
                        inNodeTransaction(conn, target) { insertRows(node, ctx, conn, sql, shape, input, key, target) }
                    }
                }
            }
        target.afterWrite()
        record(op, ids.size.toLong(), target)
        val rows = input.rows.mapIndexed { index, row -> row.filterIndexed { column, _ -> column != input.idIndex } + ids[index] }
        return DmlRows(columns, rows, ids.size.toLong())
    }

    @Suppress("LongParameterList") // one statement's fixed facts
    private suspend fun insertRows(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        conn: Connection,
        sql: String,
        shape: SqlBindTranslator.BoundSql,
        input: DmlInputRows,
        key: DeclaredKey,
        target: DmlTarget,
    ): List<Any> =
        conn.prepareStatement(shape.sql, arrayOf(key.column)).use { statement ->
            statement.queryTimeout = target.resolved.seconds
            ctx.handle.withStatement(node.id, statement) {
                ctx.operations.observerFor(node.id).phase(OperationPhase.EXECUTING)
                input.rows.mapIndexed { index, row ->
                    val bound = SqlBindTranslator.translate(sql, rowScope(ctx, input, row))
                    // Every row binds the same scalar names, so the positional SQL cannot differ
                    // from the prepared one; a difference would be a list value, which v1 refuses.
                    check(bound.sql == shape.sql) { "DML insert node '${node.id}' row $index changed the statement shape" }
                    insertOne(node, ctx, statement, bound, input, row, key, index, target)
                }
            }
        }

    @Suppress("LongParameterList") // one row's fixed facts
    private suspend fun insertOne(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        statement: PreparedStatement,
        bound: SqlBindTranslator.BoundSql,
        input: DmlInputRows,
        row: List<Any?>,
        key: DeclaredKey,
        index: Int,
        target: DmlTarget,
    ): Any {
        statement.clearParameters()
        SqlBindTranslator.bind(statement, bound.bindValues)
        val affected =
            withQueryTimeoutSource(target.resolved) {
                ctx.handle.whileExecuting(node.id, statement) { statement.executeUpdate() }
            }
        if (affected != 1) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.DML_AFFECTED_ROWS_MISMATCH,
                message =
                    "DML insert node '${node.id}' affected $affected rows for input row $index; an insert writes " +
                        "exactly one target row per input row. No row of this insert is kept.",
                details = mapOf("node" to node.id, "row_index" to index, "affected_rows" to affected),
            )
        }
        val returned = statement.generatedKeys.use { DmlKeys.read(it, key, node.id, index) }
        // A supplied id is stored unchanged (record §5): a trigger or default that rewrote it is
        // a miscorrelation, never a silently different id on the output row.
        val supplied = if (input.idIndex >= 0) row[input.idIndex] else null
        if (supplied != null && DmlKeys.normalize(supplied, key.type) != returned) {
            throw DmlKeys.correlationFailed(node.id, index, "key_rewritten")
        }
        return returned
    }

    /**
     * The insert's own transaction on a connection the family does not own (record §9: an
     * uncorrelated set leaves no durable write). A participant connection is left alone — the
     * root settles it. A connection already outside autocommit is the caller's to settle.
     */
    private suspend fun <T> inNodeTransaction(
        conn: Connection,
        target: DmlTarget,
        block: suspend () -> T,
    ): T {
        val own = !target.participant && conn.autoCommit
        if (!own) return block()
        conn.autoCommit = false
        @Suppress("TooGenericExceptionCaught") // every failure, cancellation included, rolls back
        try {
            return block().also { conn.commit() }
        } catch (t: Throwable) {
            runCatching { conn.rollback() }.exceptionOrNull()?.let(t::addSuppressed)
            throw t
        } finally {
            runCatching { conn.autoCommit = true }
        }
    }

    /**
     * One input row's bind scope: the execution's values, then the row's fields by name, then
     * `id` — the supplied value, or null to ask the database to generate it (record §5). The row
     * wins a name clash: inside an insert, `:field` means the row being inserted.
     */
    private fun rowScope(
        ctx: NodeExecutionContext,
        input: DmlInputRows,
        row: List<Any?>,
    ): Map<String, Any?> =
        LinkedHashMap<String, Any?>(ctx.values).apply {
            input.columns.forEachIndexed { index, column -> put(column.name, row[index]) }
            put(DmlFields.ID, if (input.idIndex >= 0) row[input.idIndex] else null)
        }

    // ------------------------------------------------------------------ delivery

    private suspend fun deliver(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        result: DmlRows,
        resultStore: ResultStore,
    ): NodeResult =
        when (val output = node.output) {
            is NodeOutput.Tempdb -> {
                val table = SqlIdentifiers.requireValidTable(output.table, PipelineErrorCodes.Node.STAGING_FAILED)
                ScriptNodeRuns.phase(ctx, NodePhase.STAGE, node.id) {
                    ctx.staging.stageRows(table, result.columns, result.rows.asSequence())
                }
                checkStagingBudget(node, ctx)
                NodeResult.of(node.id, result.written, startedAt)
            }

            NodeOutput.Caller -> {
                ScriptNodeRuns.phase(ctx, NodePhase.MATERIALIZE, node.id) { deliverCaller(node, ctx, startedAt, result, resultStore) }
            }

            NodeOutput.Discard -> {
                NodeResult.of(node.id, result.written, startedAt)
            }

            is NodeOutput.Datasource, null -> {
                // Unreachable past admission (§12.4: dml_has_output / dml_output_required).
                throw signal(
                    NodePhase.MATERIALIZE,
                    node.id,
                    DatapipelinesException(
                        code = PipelineErrorCodes.Validation.DML_OUTPUT_REQUIRED,
                        message = "DML node '${node.id}' has no tempdb, caller or discard output; it was not admitted.",
                        details = mapOf("node" to node.id),
                    ),
                )
            }
        }

    /** The caller fork, as every SQL path has it: the parent's direct sink, else the result store. */
    private suspend fun deliverCaller(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        result: DmlRows,
        resultStore: ResultStore,
    ): NodeResult {
        val sink = ctx.directSink
        if (sink != null) {
            sink.accept(result.columns, result.rows.asSequence())
            return NodeResult.of(nodeId = node.id, rowsOut = result.written, startedAt = startedAt, callerResultRef = null)
        }
        val stored = resultStore.materializeRows(ctx.executionId, result.columns, result.rows.asSequence(), ctx.resultTtlSeconds)
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = result.written,
            startedAt = startedAt,
            callerResultRef = stored.key,
            bytesOutEstimate = stored.bytes,
        )
    }

    // ------------------------------------------------------------------ small pieces

    /**
     * 149: the statement's write count is what its destination accepted. A participant's rows
     * are not committed when the node returns — the root's settle is the family's only commit —
     * so its committed evidence is withheld (NodeRunner's own rule for a participant statement).
     */
    private fun record(
        op: OperationObserver,
        written: Long,
        target: DmlTarget,
    ) {
        op.written(written)
        if (!target.participant) op.committed()
    }

    /** The per-pipeline staging budget, re-checked after the result is staged (the SQL paths' rule). */
    private suspend fun checkStagingBudget(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ) {
        val usedBytes = ScriptNodeRuns.phase(ctx, NodePhase.STAGE, node.id) { ctx.staging.stats().memoryUsedBytes }
        if (usedBytes > ctx.stagingMaxMemoryMb * BYTES_PER_MB) {
            val overflow = StagingMemoryLimitException(usedBytes, ctx.stagingMaxMemoryMb)
            throw NodeFailedSignal(ErrorCodeMapper.map(overflow, NodePhase.STAGE, node.id), overflow)
        }
    }

    private fun signal(
        phase: NodePhase,
        nodeId: String,
        error: Exception,
    ): Exception =
        when (error) {
            is CancellationException, is NodeFailedSignal -> error
            else -> NodeFailedSignal(ErrorCodeMapper.map(error, phase, nodeId), error)
        }

    internal const val BYTES_PER_MB = 1024L * 1024L

    /** The one column of an update/delete result (§4.4): a nonnegative BIGINTEGER, also for zero. */
    private val AFFECTED_ROWS_COLUMN = ColumnSchema(DmlFields.AFFECTED_ROWS, LogicalType.BIGINTEGER, nullable = false)
}

/** Loads an insert's input rows (§4.4) — always before any target connection is taken. */
internal object DmlInputs {
    suspend fun load(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ): DmlInputRows {
        val spec = node.dml
        val table = spec?.input?.table
        val parameters = spec?.input?.parameters
        return when {
            parameters != null -> scalar(node, parameters, spec.scalarParameters, ctx)
            table != null -> staged(table, ctx)
            else -> throw inputInvalid(node.id)
        }
    }

    /** One row: each named parameter's value under its declared type. */
    private fun scalar(
        node: ExecutableNode,
        names: List<String>,
        declared: Map<String, co.datapipelines.pipeline.Parameter>,
        ctx: NodeExecutionContext,
    ): DmlInputRows {
        val columns =
            names.map { name ->
                val parameter = declared[name] ?: throw inputInvalid(node.id)
                ColumnSchema(name, parameter.type, parameter.precision, parameter.scale, nullable = true)
            }
        return DmlInputRows(columns, listOf(names.map { ctx.values[it] }))
    }

    /**
     * Every row of the staged table, held for the node's duration and bounded by the execution's
     * staging budget measured the result store's way ([ResultBytes]) — a staged input larger than
     * the budget fails `memory_limit_exceeded` before any target statement runs (record §5).
     */
    private suspend fun staged(
        table: String,
        ctx: NodeExecutionContext,
    ): DmlInputRows {
        val quoted = SqlIdentifiers.quote(SqlIdentifiers.requireValidTable(table, PipelineErrorCodes.Node.STAGING_FAILED))
        val budget = ctx.stagingMaxMemoryMb * DmlNodeRuns.BYTES_PER_MB
        return ctx.staging.withQuery("SELECT * FROM $quoted") { rs ->
            val columns = ResultRowReader.schemaOf(rs.metaData, ctx.tempdbDialect).columns
            var bytes = 0L
            val rows =
                buildList {
                    while (rs.next()) {
                        val row = columns.mapIndexed { index, column -> ResultRowReader.readValue(rs, index + 1, column) }
                        bytes += ResultBytes.rowBytes(row, columns)
                        if (bytes > budget) throw StagingMemoryLimitException(bytes, ctx.stagingMaxMemoryMb)
                        add(row)
                    }
                }
            DmlInputRows(columns, rows)
        }
    }

    private fun inputInvalid(nodeId: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Validation.DML_INPUT_INVALID,
            message = "DML insert node '$nodeId' declares no usable input; it was not admitted.",
            details = mapOf("node" to nodeId),
        )
}
