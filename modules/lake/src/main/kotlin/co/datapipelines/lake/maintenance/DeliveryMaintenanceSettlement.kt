package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.settle.DeliverySettler
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.lake.settle.SettleBudget
import co.datapipelines.lake.settle.SettlementDeferred
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Settlement before the first part (#957 D6a; the P6 lane cut's L21; design record §8b "at every maintenance pass"), over
 * [DeliverySettler.settle]: ONE page of the run's table from its first unsettled delivery (`afterId = null`), the sweep's
 * page size, and a budget that is the sweep's per-tick budget capped at the run's deadline. It holds no maintenance
 * lease (the settler takes the table lease itself for a takeover, under its own operation id) and keeps no cursor.
 *
 * A failure is logged once (`lake.maintenance.settlement_deferred`) and recorded; it never ends the run.
 */
class DeliveryMaintenanceSettlement(
    private val settler: DeliverySettler,
    private val leases: LakeLeases,
    private val budget: Duration = LakeSettlementSweep.SWEEP_BUDGET,
    private val pageSize: Int = LakeSettlementSweep.SETTLE_PAGE_SIZE,
) : MaintenanceSettlement {
    @Suppress("TooGenericExceptionCaught") // D6a: any failure is recorded and the parts run either way.
    override fun settle(run: MaintenanceRun): SettlementRecord =
        try {
            val sweepEnd = leases.catalogNowPlus(budget)
            val progress = settler.settle(run.table.id, SettleBudget.until(leases, minOf(sweepEnd, run.deadline)), null, pageSize)
            SettlementRecord(progress.changed, progress.visited, leftUnsettled = progress.resumeAfter != null, deferred = false)
        } catch (deferred: SettlementDeferred) {
            val cause = deferred.cause ?: deferred
            logDeferred(run, cause)
            SettlementRecord(
                deferred.progress.changed,
                deferred.progress.visited,
                leftUnsettled = true,
                deferred = true,
                errorCode = MaintenanceErrors.codeOf(cause),
            )
        } catch (failure: RuntimeException) {
            logDeferred(run, failure)
            SettlementRecord(0, 0, leftUnsettled = true, deferred = true, errorCode = MaintenanceErrors.codeOf(failure))
        }

    private fun logDeferred(
        run: MaintenanceRun,
        cause: Throwable,
    ) {
        LOG.warn(
            "event=lake.maintenance.settlement_deferred run_id={} table_id={} cause={} sql_state={}",
            run.runId,
            run.table.id,
            FailureShape.cause(cause),
            FailureShape.sqlState(cause),
        )
    }

    private fun minOf(
        a: Instant,
        b: Instant,
    ): Instant = if (a.isBefore(b)) a else b

    private companion object {
        private val LOG = LoggerFactory.getLogger(DeliveryMaintenanceSettlement::class.java)
    }
}
