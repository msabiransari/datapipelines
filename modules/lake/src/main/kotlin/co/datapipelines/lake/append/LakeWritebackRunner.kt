package co.datapipelines.lake.append

import co.datapipelines.lake.LakeCatalogSession
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeNames
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.lake.definitions.LakeApplyLeases
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.lake.definitions.LakeTableStatus
import co.datapipelines.lake.delivery.DeliveryConclusion
import co.datapipelines.lake.delivery.DeliveryLedger
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.lake.delivery.DeliveryManifest
import co.datapipelines.lake.delivery.DeliveryTarget
import co.datapipelines.lake.delivery.LakeDelivery
import co.datapipelines.lake.delivery.LakeDeliveryFailurePhase
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeExecutionInput
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.typesystem.DatapipelinesException
import org.apache.iceberg.Table
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

/** Frozen inputs of one target's publication; admission and trusted materialization already completed. */
data class AppendPublicationInput(
    val execution: LakeExecutionInput,
    val delivery: LakeDelivery,
    val manifest: DeliveryManifest,
    val target: DeliveryTarget,
    val rows: LakeAppendInput,
    val deadline: Instant,
)

/**
 * Implements managed publication behind the neutral port; it deliberately does not depend on dag's
 * JDBC WritebackRunner. Grant, table lease, conditional ledger claim, files, recipe, one commit, settlement.
 */
@Suppress("LongParameterList") // Explicit state-machine collaborators; no service locator or lake-to-dag dependency.
class LakeWritebackRunner(
    private val properties: LakeProperties,
    private val leases: LakeLeases,
    private val catalogs: LakeCatalogs,
    private val definitions: LakeDefinitionRepository,
    private val deliveries: DeliveryLedgerRepository,
    private val ledger: DeliveryLedger,
    private val recipes: RecipeWriterFactory,
    private val heartbeat: LakeAppendHeartbeat,
    private val settler: DeliverySettler,
) {
    private val committer = AppendCommitter(deliveries)
    private val writer = ParquetAppendWriter()
    private val recipeInputs = AppendRecipeAssembler(definitions, deliveries, leases)

    /** Authority is established only by the entry-point grant, before acquiring any table lease. */
    fun publish(
        request: AppendPublicationInput,
        checkActive: () -> Unit,
    ): Long {
        if (request.execution.grant == null) throw refusal(Lake.APPEND_GRANT_MISSING, "The execution has no lake append grant.")
        val lease = takeLease(request, checkActive)
        try {
            return publishLeased(request, lease, checkActive)
        } finally {
            releaseAndSettle(request)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements") // Both the fixed deadline and bounded busy wait terminate this lease poll.
    private fun takeLease(
        request: AppendPublicationInput,
        checkActive: () -> Unit,
    ): LeaseHandle {
        val waitUntil = minOf(request.deadline, leases.catalogNowPlus(Duration.ofSeconds(properties.admission.leaseWaitSeconds)))
        while (true) {
            checkActive()
            when (
                val taken =
                    leases.acquire(
                        LeaseKind.TABLE,
                        LakeNames.tableLeaseName(request.target.namespace, request.target.table),
                        request.delivery.operationId,
                        LakeApplyLeases.APPLY_LEASE_TTL,
                        request.deadline,
                        "append",
                    )
            ) {
                is LeaseOutcome.Acquired -> return taken.lease
                LeaseOutcome.DeadlinePassed -> break
                LeaseOutcome.Busy -> if (!leases.catalogNowPlus(LakeApplyLeases.DEFAULT_POLL_INTERVAL).isBefore(waitUntil)) break
            }
            Thread.sleep(LakeApplyLeases.DEFAULT_POLL_INTERVAL.toMillis())
        }
        throw refusal(Lake.TABLE_BUSY, "Could not take the table lease before the admitted deadline.")
    }

    @Suppress("TooGenericExceptionCaught") // Own the durable verdict on every unwind, including fatal thread termination.
    private fun publishLeased(request: AppendPublicationInput, lease: LeaseHandle, callerCheck: () -> Unit): Long {
        var publishing: LakeDelivery? = null
        var commitEntered = false
        try {
            heartbeat.start(lease, request.deadline) { leases.heartbeat(lease, LakeApplyLeases.APPLY_LEASE_TTL) }.use { watch ->
                val checkActive = {
                    callerCheck()
                    watch.checkActive()
                }
                checkActive()
                ensureActiveTarget(request)
                if (!ledger.beginPublishing(
                        request.delivery,
                    )
                ) {
                    throw refusal(Lake.TABLE_BUSY, "This delivery attempt no longer owns publication.")
                }
                val row = request.delivery.copy(state = LakeDeliveryState.PUBLISHING)
                publishing = row
                log.info(
                    "event=lake.append.started delivery_id={} operation_id={} table_id={} deadline={} cause=none sql_state=none",
                    row.id,
                    row.operationId,
                    row.tableId,
                    request.deadline,
                )
                catalogs.open(lease).use { session ->
                    val table = session.loadTable(request.target.namespace, request.target.table)
                    val provenance = recipeInputs.build(request, table)
                    val files =
                        writer.write(
                            table,
                            request.rows,
                            row.operationId,
                            properties.admission.maxOpenPartitionsPerAppend,
                            checkActive,
                        )
                    checkActive()
                    val recipe = recipes.forExecution(request.execution).write(table.location(), provenance, request.deadline)
                    check(deliveries.attachRecipe(row, recipe.key, recipe.sha256) == 1) { "Publication lost its recipe attachment." }
                    checkActive()
                    val outcome =
                        committer.commit(
                            session,
                            table,
                            row,
                            request.manifest,
                            files,
                            recipe,
                            checkActive,
                        ) { commitEntered = true }
                    return conclude(row, files, outcome)
                }
            }
        } catch (failure: Throwable) {
            failPublication(request, publishing, commitEntered, failure)
        }
    }

    // A failed stamp leaves PUBLISHING for the sweep; it cannot establish a negative commit verdict.
    @Suppress("TooGenericExceptionCaught")
    private fun failPublication(
        request: AppendPublicationInput,
        publishing: LakeDelivery?,
        commitEntered: Boolean,
        failure: Throwable,
    ): Nothing {
        publishing?.let { row ->
            val conclusion =
                if (commitEntered) {
                    DeliveryConclusion.Unknown(Lake.APPEND_OUTCOME_UNKNOWN)
                } else {
                    DeliveryConclusion.Failed(LakeDeliveryFailurePhase.PUBLISH, (failure as? DatapipelinesException)?.code)
                }
            try {
                ledger.conclude(row, conclusion)
            } catch (stampFailure: Exception) {
                logFailure("lake.append.stamp_failed", request, stampFailure)
            }
        }
        logFailure("lake.append.failed", request, failure)
        throw publicFailure(failure)
    }

    private fun publicFailure(failure: Throwable): Throwable =
        when {
            failure is CancellationException || failure is Error -> failure
            failure is DatapipelinesException && failure.code.startsWith("lake.") && !failure.code.startsWith("lake.catalog.") -> failure
            else -> refusal(Lake.APPEND_FAILED, "The append could not be published; inspect its recorded delivery outcome.")
        }

    private fun ensureActiveTarget(request: AppendPublicationInput) {
        val status = definitions.findTable(request.delivery.tableId)?.status
        val code =
            when (status) {
                LakeTableStatus.ACTIVE -> null
                LakeTableStatus.DEPRECATED -> Lake.TABLE_DEPRECATED
                LakeTableStatus.ARCHIVED, LakeTableStatus.PURGED, null -> Lake.TABLE_UNAVAILABLE
            }
        if (code != null) throw refusal(code, "The managed target does not accept delivery ingestion.")
    }

    private fun conclude(
        row: LakeDelivery,
        files: WrittenAppend,
        outcome: AppendCommitOutcome,
    ): Long =
        when (outcome) {
            is AppendCommitOutcome.Published -> {
                ledger.conclude(row, DeliveryConclusion.Published(outcome.evidence.metadataLocation))
                if (outcome.evidence.operationId == row.operationId) files.rows else 0
            }

            AppendCommitOutcome.Contended -> {
                ledger.conclude(row, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.PUBLISH, Lake.COMMIT_CONTENDED))
                throw refusal(Lake.COMMIT_CONTENDED, "The catalog remained contended after bounded publication attempts.")
            }

            AppendCommitOutcome.Refused -> {
                ledger.conclude(row, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.PUBLISH, Lake.APPEND_AUTHORITY_LOST))
                throw refusal(Lake.APPEND_AUTHORITY_LOST, "The catalog refused the append's authority.")
            }

            AppendCommitOutcome.Unknown -> {
                ledger.conclude(row, DeliveryConclusion.Unknown(Lake.APPEND_OUTCOME_UNKNOWN))
                throw refusal(Lake.APPEND_OUTCOME_UNKNOWN, "The publication outcome is unconfirmed; settlement will determine it.")
            }
        }

    @Suppress("TooGenericExceptionCaught") // A deferred release/repair must not replace the durable publication verdict.
    private fun releaseAndSettle(request: AppendPublicationInput) {
        try {
            leases.release(request.delivery.operationId)
        } catch (failure: Exception) {
            logFailure("lake.append.lease_release_failed", request, failure)
        }
        try {
            settler.settle(request.delivery.tableId, request.deadline)
        } catch (failure: Exception) {
            logFailure("lake.append.settlement_deferred", request, failure)
        }
    }

    private fun logFailure(
        event: String,
        request: AppendPublicationInput,
        failure: Throwable,
    ) {
        log.warn(
            "event={} delivery_id={} operation_id={} cause={} sql_state={}",
            event,
            request.delivery.id,
            request.delivery.operationId,
            FailureShape.cause(failure),
            FailureShape.sqlState(failure),
        )
    }

    private fun refusal(
        code: String,
        message: String,
    ) = DatapipelinesException(code, message)

    private companion object {
        val log = LoggerFactory.getLogger(LakeWritebackRunner::class.java)
    }
}
