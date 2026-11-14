package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeCatalogException
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import co.datapipelines.pipeline.PipelineErrorCodes
import org.apache.iceberg.PendingUpdate
import org.apache.iceberg.Table
import org.apache.iceberg.exceptions.CommitFailedException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What a guarded step answered: it ran ([Done]), or the table lease was refused ([Refused]) and nothing was committed. */
sealed interface Guarded<out T> {
    data class Done<T>(
        val value: T,
    ) : Guarded<T>

    /** [outcome] is `busy` or `deadline`; [code] the catalogued code (`lake.table.busy`, `lake.maintenance.deadline`). */
    data class Refused(
        val outcome: MaintenanceOutcome,
        val code: String,
    ) : Guarded<Nothing>
}

/** The thread running the part was interrupted (D7): the run ends `failed`, error `interrupted`. */
class MaintenanceInterrupted : RuntimeException("the maintenance run was interrupted")

/** Test seams of a run; production passes [NONE]. */
interface MaintenanceRunHooks {
    /** Called inside [MaintenanceRun.commit] after the lease is held and the table loaded, before the catalog write starts. */
    fun beforeWrite(run: MaintenanceRun) = Unit

    /** Called when the catalog's fence refused the write of [run] ([failure] carries the SQLSTATE), before it is answered. */
    fun writeRefused(
        run: MaintenanceRun,
        failure: Throwable,
    ) = Unit

    companion object {
        val NONE = object : MaintenanceRunHooks {}
    }
}

/**
 * One admitted maintenance run of one table (#957 D5): the run row's id, its table, and the deadline its row carries.
 * Every catalog commit of every part goes through [commit]; a part that must hold the table across several steps uses
 * [underLease]. Both acquire the TABLE lease with the run's [deadline], never a fresh `job-timeout`: nothing gives an
 * expired run fresh authority (§11). The lease operation id is the run id. Confined to the scheduler thread that runs it.
 */
class MaintenanceRun internal constructor(
    /** The `lake_maintenance_runs` row and the lease operation id. */
    val runId: UUID,
    val table: MaintenanceTable,
    /** `lake_maintenance_runs.deadline_at`, on the catalog's clock. */
    val deadline: Instant,
    private val scratch: Map<String, Long>,
    /**
     * The scratch reservation the worker holds for this run (#969 D5 step 1; null when none was granted). A part hands
     * it to the port that opens the run's scratch database; the worker closes it after the run in any case, which is a
     * no-op once a database that took it has closed.
     */
    val scratchReservation: AutoCloseable?,
    private val leases: LakeLeases,
    private val catalogs: LakeCatalogs,
    private val heartbeats: ScheduledExecutorService,
    private val hooks: MaintenanceRunHooks,
) {
    private var held: LeaseHandle? = null

    /** The scratch bytes reserved for [part] at admission: its declaration when the reservation was granted, else 0. */
    fun scratchBytes(part: MaintenancePart): Long = scratch[part.name] ?: 0L

    /** True once the catalog's clock has reached [deadline] (one statement). */
    fun pastDeadline(): Boolean = !leases.catalogNowPlus(Duration.ZERO).isBefore(deadline)

    /** Throws [MaintenanceInterrupted] when the thread's interrupt flag is set (D7); the flag is left set. */
    fun checkInterrupt() {
        if (Thread.currentThread().isInterrupted) throw MaintenanceInterrupted()
    }

    /**
     * Commits [block]'s update once (D5): under the table lease (this call's, or the one [underLease] holds), on the table
     * loaded fresh inside the lease, with the table's own zero commit retries (`commit.retry.num-retries = 0`, written
     * at creation). A refused acquire answers [Guarded.Refused] with nothing committed: `busy`, or `deadline` with the
     * catalogued `lake.maintenance.deadline`. A fence refusal at the write answers the same way: `deadline` when the
     * catalog's clock has reached the run's deadline, else `busy` (the lease was taken over). A commit whose outcome is
     * unknown THROWS, and the run ends `failed` with nothing concluded (L14).
     */
    fun commit(block: (Table) -> PendingUpdate<*>): Guarded<Unit> {
        checkInterrupt()
        val lease = held ?: return withLease { commitUnder(it, block) }
        return commitUnder(lease, block)
    }

    /**
     * Holds the table lease across [block] (D5; 962's cleanup), heartbeating it every [HEARTBEAT] with its expiry capped at
     * the run's deadline by the lease protocol. A [commit] inside reuses this lease. Never nested.
     */
    fun <T> underLease(block: () -> T): Guarded<T> {
        check(held == null) { "underLease is never nested" }
        checkInterrupt()
        return withLease { Guarded.Done(block()) }
    }

    private fun <T> withLease(block: (LeaseHandle) -> Guarded<T>): Guarded<T> =
        when (val outcome = leases.acquire(LeaseKind.TABLE, table.leaseName, runId, TTL, deadline, OPERATION)) {
            is LeaseOutcome.Acquired -> {
                val lease = outcome.lease
                held = lease
                val beat =
                    heartbeats.scheduleWithFixedDelay(
                        { runCatching { leases.heartbeat(lease, TTL) } },
                        HEARTBEAT.toMillis(),
                        HEARTBEAT.toMillis(),
                        TimeUnit.MILLISECONDS,
                    )
                try {
                    block(lease)
                } finally {
                    beat.cancel(false)
                    held = null
                    leases.release(runId)
                }
            }

            LeaseOutcome.DeadlinePassed -> {
                deadlineRefusal()
            }

            LeaseOutcome.Busy -> {
                Guarded.Refused(MaintenanceOutcome.BUSY, PipelineErrorCodes.Lake.TABLE_BUSY)
            }
        }

    private fun commitUnder(
        lease: LeaseHandle,
        block: (Table) -> PendingUpdate<*>,
    ): Guarded<Unit> {
        checkInterrupt()
        // The session is closed before the lease is released (withLease's finally runs after this block).
        catalogs.open(lease).use { session ->
            val loaded = session.loadTable(table.namespace, table.name)
            hooks.beforeWrite(this)
            return try {
                session.write { block(loaded).commit() }
                Guarded.Done(Unit)
            } catch (fenced: LakeCatalogException.Fenced) {
                hooks.writeRefused(this, fenced)
                if (pastDeadline()) deadlineRefusal() else Guarded.Refused(MaintenanceOutcome.BUSY, PipelineErrorCodes.Lake.TABLE_BUSY)
            } catch (_: CommitFailedException) {
                // A definite refusal of the compare-and-swap: the table moved under a lease that should have excluded
                // every writer. Nothing was committed; treated as another holder.
                Guarded.Refused(MaintenanceOutcome.BUSY, PipelineErrorCodes.Lake.TABLE_BUSY)
            }
        }
    }

    private fun deadlineRefusal(): Guarded.Refused {
        LOG.warn(
            "event=lake.maintenance.deadline_refused run_id={} table_id={} deadline={} code={}",
            runId,
            table.id,
            deadline,
            PipelineErrorCodes.Lake.MAINTENANCE_DEADLINE,
        )
        return Guarded.Refused(MaintenanceOutcome.DEADLINE, PipelineErrorCodes.Lake.MAINTENANCE_DEADLINE)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MaintenanceRun::class.java)

        /** The lease's time to live between heartbeats; its expiry never passes the run's deadline. */
        val TTL: Duration = Duration.ofSeconds(60)

        /** The heartbeat of a held lease: a third of [TTL], so two missed beats still leave it live. */
        val HEARTBEAT: Duration = Duration.ofSeconds(20)

        /** `lake_leases.operation` of a maintenance lease. */
        const val OPERATION = "maintenance"
    }
}
