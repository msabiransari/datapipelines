package co.datapipelines.executor

import co.datapipelines.pipeline.LakeAppendColumn
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeOrderKey
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.staging.DuckDbStaging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.UUID

/** The native managed-append leg: materialize once, then validate and write stable, cancellable scans. */
internal object LakeNodeWriteback {
    @Suppress("LongParameterList") // Mirrors the node dispatch boundary; session and staging have separate ownership.
    suspend fun run(
        node: ExecutableNode,
        output: NodeOutput.Datasource,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        resolved: ResolvedQueryTimeout,
        startedAt: Instant,
        session: LakeAppendSession,
        afterMaterialized: suspend () -> Unit,
    ): NodeResult {
        val table = SqlIdentifiers.quote("dp_append_" + UUID.randomUUID().toString().replace("-", ""))
        val op = ctx.operations.observerFor(node.id)
        op.phase(OperationPhase.WAITING_OUTPUT)
        val columns =
            ctx.staging.withConnection { connection ->
                val original =
                    connection.prepareStatement(bound.sql).use { statement ->
                        statement.queryTimeout = resolved.seconds
                        SqlBindTranslator.bind(statement, bound.bindValues)
                        ctx.handle.withStatement(node.id, statement) {
                            ctx.handle.whileExecuting(node.id, statement) {
                                val metadata = statement.metaData
                                (1..metadata.columnCount).map { index ->
                                    LakeAppendColumn(
                                        metadata.getColumnLabel(index),
                                        metadata.getColumnTypeName(index),
                                        metadata.getPrecision(index),
                                        metadata.getScale(index),
                                    )
                                }
                            }
                        }
                    }
                val materialize = SqlBindTranslator.translate("CREATE TABLE $table AS ${bound.originalSql}", ctx.values)
                BoundStatements.statementFor(connection, materialize).use { statement ->
                    statement.queryTimeout = resolved.seconds
                    ctx.handle.withStatement(node.id, statement) {
                        op.phase(OperationPhase.EXECUTING)
                        ctx.handle.whileExecuting(node.id, statement) { BoundStatements.update(statement, materialize) }
                    }
                }
                original
            }
        // The first lease returned (and checked scratch) before stats acquires another connection.
        afterMaterialized()
        val job = currentCoroutineContext().job
        val checkActive = {
            ctx.handle.abortReason?.let { throw ExecutionAbortedException(it) }
            job.ensureActive()
        }
        val written =
            ctx.staging.withConnection { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = resolved.seconds
                    ctx.handle.withStatement(node.id, statement) {
                        op.phase(OperationPhase.WRITING)
                        val input =
                            StableInput(statement, table, columns, checkActive, StableInput.engineFailures(ctx.staging)) { action ->
                                runBlocking(job) { ctx.handle.whileExecuting(node.id, statement, action) }
                            }
                        session.append(output, input, checkActive)
                    }
                }
            }
        op.written(written)
        op.committed()
        return NodeResult.of(node.id, written, startedAt)
    }

    /**
     * The materialized relation, scanned once per [scan] (#968 D1, D7). [orderBy] is validated before any statement runs
     * and reaches the text only as integers and the contract's own fixed text ([LakeAppendInput.orderByClause]): a
     * string key is compared by code points whatever collation the author's SQL gave the column (round 2 C1). A
     * [SQLException] of the statement or of reading its rows leaves as what [classify] makes of it (staging's own
     * engine-failure classification: a sort that outgrows the scratch reservation becomes
     * `pipeline.staging.scratch_limit_exceeded`; anything unmatched is unchanged); nothing else is caught.
     */
    internal class StableInput(
        private val statement: Statement,
        private val table: String,
        override val originalColumns: List<LakeAppendColumn>,
        private val checkActive: () -> Unit,
        private val classify: (SQLException) -> Exception,
        private val execute: (() -> Unit) -> Unit,
    ) : LakeAppendInput {
        override fun scan(
            orderBy: List<LakeOrderKey>,
            consume: (ResultSet) -> Unit,
        ) {
            val sql = "SELECT * FROM $table" + LakeAppendInput.orderByClause(orderBy, originalColumns.size)
            checkActive()
            execute {
                try {
                    statement.executeQuery(sql).use(consume)
                } catch (e: SQLException) {
                    throw classify(e)
                }
            }
            checkActive()
        }

        companion object {
            /** Staging's own engine-failure classification when the staging is DuckDB; any other engine's failure is unchanged. */
            fun engineFailures(staging: Any?): (SQLException) -> Exception =
                (staging as? DuckDbStaging)?.let { it::classifyEngineFailure } ?: { it }
        }
    }
}
