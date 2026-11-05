package co.datapipelines.executor

import co.datapipelines.pipeline.LakeAppendPort
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeExecutionInput
import co.datapipelines.pipeline.LakeExecutionLineage
import co.datapipelines.pipeline.LakePreparedExecution
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingFactory
import co.datapipelines.staging.StagingPreparation
import co.datapipelines.staging.StagingScratchExhaustedException
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterWireEncoder
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.util.UUID

/** The opened database and its execution-owned lake state; ordinary pipelines retain the same staging path. */
internal data class ExecutionStaging(
    val staging: Staging,
    val session: LakeAppendSession?,
    val lineage: LakeExecutionLineage?,
    val deliveryNodeId: String?,
)

/** Admission before opening, with trusted initialization registered for cancellation on the owning execution. */
internal object LakeExecutionSetup {
    // Preserve ownership inside the dispatch block; cancellation wins driver failures.
    @Suppress("LongParameterList", "ThrowsCount")
    suspend fun open(
        request: ExecuteRequest,
        executionId: UUID,
        context: RunContext,
        factory: StagingFactory,
        port: LakeAppendPort?,
        handle: CancellationHandle,
        onStaging: (Staging) -> Unit,
        onSession: (LakeAppendSession) -> Unit,
    ): ExecutionStaging {
        val deliveries = request.pipeline.nodes.filter { it.delivery != null }
        val lineage =
            if (deliveries.isNotEmpty() ||
                request.inheritedLakeLineage != null
            ) {
                LakeExecutionLineage(request.inheritedLakeLineage)
            } else {
                null
            }
        if (deliveries.isEmpty()) {
            val staging = factory.create(executionId, request.pipeline.settings.tempdb.engine).also(onStaging)
            return ExecutionStaging(staging, null, lineage, null)
        }
        if (deliveries.size != 1) throw DatapipelinesException(Lake.DELIVERY_ONE_PER_PIPELINE, "Only one delivery node may be executed.")
        val activePort = port ?: throw DatapipelinesException(Lake.TABLE_UNAVAILABLE, "The managed lake is not configured on this server.")
        val node = deliveries.single()
        val values = context.snapshot()
        val execution =
            LakeExecutionInput(
                executionId,
                request.pipelineId,
                request.pipelineVersion,
                request.workspaceId,
                request.pipeline,
                request.lakeExecutionMetadata,
                request.pipeline.parameters.mapValues { (name, parameter) -> encode(parameter, values[name]) },
                request.lakeAppendGrant,
                requireNotNull(lineage),
            )
        val prepared =
            runInterruptible {
                activePort
                    .prepare(execution, requireNotNull(node.delivery), values) { bytes ->
                        try {
                            factory.prepareDelivery(executionId, bytes)
                        } catch (_: StagingScratchExhaustedException) {
                            null
                        }
                    }.also { onSession(it.session) }
            }
        val staging = openPrepared(prepared, node.id, handle, onStaging)
        return ExecutionStaging(staging, prepared.session, lineage, node.id)
    }

    @Suppress("TooGenericExceptionCaught") // Parent cancellation must win a simultaneous driver failure.
    private suspend fun openPrepared(
        prepared: LakePreparedExecution<StagingPreparation>,
        nodeId: String,
        handle: CancellationHandle,
        onStaging: (Staging) -> Unit,
    ): Staging {
        val parentJob = currentCoroutineContext().job
        return coroutineScope {
            val cancellation =
                launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        handle.cancelStatements(nodeId)
                    }
                }
            try {
                val statements =
                    LakeStatementExecution { statement, action ->
                        runBlocking(parentJob) {
                            try {
                                parentJob.ensureActive()
                                handle.withStatement(nodeId, statement) { handle.whileExecuting(nodeId, statement, action) }
                            } catch (failure: Exception) {
                                parentJob.ensureActive()
                                throw failure
                            }
                        }
                    }
                val staging =
                    prepared.reservation
                        .open { connection ->
                            prepared.session.initialize(connection, statements)
                        }.also(onStaging)
                staging
            } finally {
                cancellation.cancelAndJoin()
            }
        }
    }

    private fun encode(
        parameter: Parameter,
        value: Any?,
    ): JsonNode =
        if (parameter.cardinality == ParameterCardinality.MULTI && value is List<*>) {
            PipelineJson.objectMapper().createArrayNode().also { array ->
                value.forEach { array.add(ParameterWireEncoder.encode(parameter.type, it)) }
            }
        } else {
            ParameterWireEncoder.encode(parameter.type, value)
        }
}
