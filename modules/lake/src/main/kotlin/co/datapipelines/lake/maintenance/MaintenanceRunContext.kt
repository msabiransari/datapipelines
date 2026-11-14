package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService

/**
 * What every [MaintenanceRun] shares (#957 D5): the lease protocol, the fenced catalogs, the heartbeat executor of a held
 * lease and the test seams. [open] hands the worker a run for an admitted row.
 */
class MaintenanceRunContext(
    private val leases: LakeLeases,
    private val catalogs: LakeCatalogs,
    private val heartbeats: ScheduledExecutorService,
    private val hooks: MaintenanceRunHooks = MaintenanceRunHooks.NONE,
) {
    /**
     * The run of the admitted row [runId]: its [deadline] is the row's, [scratch] the bytes reserved per part name,
     * [reservation] the held scratch reservation itself (null when none was granted; the worker still closes it), and
     * [declared] the bytes each part expecting to be due declared, granted or not (the granted map when not given).
     */
    fun open(
        runId: UUID,
        table: MaintenanceTable,
        deadline: Instant,
        scratch: Map<String, Long>,
        reservation: AutoCloseable? = null,
        declared: Map<String, Long> = scratch,
    ): MaintenanceRun = MaintenanceRun(runId, table, deadline, scratch, reservation, leases, catalogs, heartbeats, hooks, declared)
}
