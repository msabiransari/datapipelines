package co.datapipelines.executor

import co.datapipelines.pipeline.LakeAppendColumn
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.NodeOutput
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.sql.ResultSet
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
                            StableInput(statement, table, columns, checkActive) { action ->
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

    private class StableInput(
        private val statement: Statement,
        private val table: String,
        override val originalColumns: List<LakeAppendColumn>,
        private val checkActive: () -> Unit,
        private val execute: (() -> Unit) -> Unit,
    ) : LakeAppendInput {
        override fun scan(consume: (ResultSet) -> Unit) {
            checkActive()
            execute { statement.executeQuery("SELECT * FROM $table").use(consume) }
            checkActive()
        }
    }
}
