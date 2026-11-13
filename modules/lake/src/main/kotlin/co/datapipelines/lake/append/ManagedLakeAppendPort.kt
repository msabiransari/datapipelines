package co.datapipelines.lake.append

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.delivery.DeliveryKey
import co.datapipelines.lake.delivery.DeliveryLanding
import co.datapipelines.lake.delivery.DeliveryMaterializer
import co.datapipelines.lake.delivery.DeliveryReceipt
import co.datapipelines.lake.delivery.DeliveryTarget
import co.datapipelines.lake.delivery.LakeStagingStorage
import co.datapipelines.lake.delivery.LandedDelivery
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.DeliveryRef
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendPort
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeExecutionInput
import co.datapipelines.pipeline.LakePreparedExecution
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.CancellationException

/** The neutral execution port's production implementation; lake imports no dag or staging type. */
class ManagedLakeAppendPort(
    private val properties: LakeProperties,
    private val landing: DeliveryLanding,
    private val storage: LakeStagingStorage,
    private val publisher: LakeWritebackRunner,
    private val registry: DatasourceRegistry,
) : LakeAppendPort {
    override val datasourceName: String get() = properties.name
    private val materializer = DeliveryMaterializer(properties.storage.bucket)

    // Admission owns metadata failures; only safe domain refusals reach execution events.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    override fun <T : AutoCloseable> prepare(
        execution: LakeExecutionInput,
        delivery: DeliveryRef,
        context: Map<String, Any?>,
        reserve: (Long) -> T?,
    ): LakePreparedExecution<T> =
        try {
            // The same $key context-reference form used by calculator/script inputs; feed stays literal.
            val id = if (delivery.id.startsWith("$")) context[delivery.id.substring(1)]?.toString().orEmpty() else delivery.id
            val key = DeliveryKey(delivery.feed, id)
            if (registry.describe(datasourceName, execution.workspaceId)?.managedLake != true) {
                throw DatapipelinesException(
                    PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND,
                    "The managed lake is unavailable in this workspace.",
                )
            }
            val targets =
                execution.pipeline.nodes.mapNotNull { node ->
                    val output = node.output as? NodeOutput.Datasource ?: return@mapNotNull null
                    if (output.datasource != datasourceName) return@mapNotNull null
                    if (node.type != NodeType.DQL || node.source != "tempdb" || output.mode != WriteMode.APPEND) throw invalidTarget()
                    qualifiedTarget(output.table)
                }
            if (targets.distinct().size != targets.size) {
                throw DatapipelinesException(Lake.APPEND_ONE_PER_TABLE, "A managed table has more than one append node.")
            }
            val landed = landing.land(key, targets, execution.executionId, execution.pipelineId, reserve)
            log.info(
                "event=lake.delivery.landed execution_id={} targets={} files={} bytes={} deadline={} cause=none sql_state=none",
                execution.executionId,
                targets.size,
                landed.manifest.files.size,
                landed.manifest.totalBytes,
                landed.deadline,
            )
            LakePreparedExecution(landed.reservation, Session(execution, landed, targets.zip(landed.receipts).toMap()))
        } catch (failure: DatapipelinesException) {
            throw failure
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InterruptedException) {
            throw failure
        } catch (failure: Exception) {
            log.warn(
                "event=lake.delivery.admission_failed execution_id={} cause={} sql_state={}",
                execution.executionId,
                FailureShape.cause(failure),
                FailureShape.sqlState(failure),
            )
            throw DatapipelinesException(
                Lake.DELIVERY_ADMISSION_FAILED,
                "Delivery admission failed; retry after checking its recorded state.",
            )
        }

    private inner class Session<T : AutoCloseable>(
        private val execution: LakeExecutionInput,
        private val landed: LandedDelivery<T>,
        private val receipts: Map<DeliveryTarget, DeliveryReceipt>,
    ) : LakeAppendSession {
        override val datasourceName: String get() = properties.name
        override val deadline: Instant get() = landed.deadline

        override fun initialize(
            connection: Connection,
            statements: LakeStatementExecution,
        ) = landed.initialize(connection, materializer, statements) { storage.configure(it, deadline, statements) }

        override fun readCompleted() = landed.readCompleted()

        override fun append(
            target: NodeOutput.Datasource,
            input: LakeAppendInput,
            checkActive: () -> Unit,
        ): Long {
            if (target.datasource != datasourceName || target.mode != WriteMode.APPEND) throw invalidTarget()
            val qualified = qualifiedTarget(target.table)
            val receipt =
                receipts[qualified] ?: throw DatapipelinesException(Lake.APPEND_REQUIRES_DELIVERY, "The append has no received delivery.")
            return when (receipt) {
                is DeliveryReceipt.AlreadyPublished -> {
                    log.info(
                        "event=lake.delivery.already_published delivery_id={} operation_id={} " +
                            "message=\"already published\" cause=none sql_state=none",
                        receipt.delivery.id,
                        receipt.delivery.operationId,
                    )
                    0
                }

                is DeliveryReceipt.Accepted -> {
                    publisher.publish(
                        AppendPublicationInput(execution, receipt.delivery, landed.manifest, qualified, input, deadline),
                        checkActive,
                    )
                }

                is DeliveryReceipt.Changed, is DeliveryReceipt.NeedsSettlement -> {
                    error("Unadmitted receipt escaped delivery landing.")
                }
            }
        }

        override fun failed(code: String?) = landed.failed(code)

        // Bounded cleanup failure is recoverable by the sweep; retain the primary outcome.
        @Suppress("TooGenericExceptionCaught")
        override fun close() {
            try {
                landed.close()
            } catch (failure: Exception) {
                log.warn(
                    "event=lake.delivery.cleanup_deferred execution_id={} cause={} sql_state={}",
                    execution.executionId,
                    FailureShape.cause(failure),
                    FailureShape.sqlState(failure),
                )
            }
        }
    }

    private fun qualifiedTarget(table: String): DeliveryTarget {
        val parts = table.split('.')
        if (parts.size != 2 || parts.any { it.isBlank() }) throw invalidTarget()
        return DeliveryTarget(parts[0], parts[1])
    }

    private fun invalidTarget() =
        DatapipelinesException(Lake.APPEND_INVALID_NODE, "Managed publication requires a qualified DQL/tempdb append target.")

    private companion object {
        val log = LoggerFactory.getLogger(ManagedLakeAppendPort::class.java)
    }
}
