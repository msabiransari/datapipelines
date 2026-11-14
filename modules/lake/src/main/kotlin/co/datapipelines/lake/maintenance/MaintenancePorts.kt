package co.datapipelines.lake.maintenance

import java.time.Duration
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * One maintenance operation of a table's run (#957 D6; the operations come from 960, 962 and 963). A part is a bean;
 * the worker runs every registered part, in [order], inside one admitted run.
 *
 * - [declare] answers BEFORE admission, from the metadata database's rows only (the table's row, its profile, stored
 *   measurements, the run history): no Iceberg read and no object-store call (D3 step 3). It is handed a
 *   [MaintenanceTable], which can read nothing.
 * - [dueNow] answers AFTER admission, with any read it needs (D3 step 7).
 * - [run] does the work. Every catalog commit goes through [MaintenanceRun.commit] (D5); a part that must hold the
 *   lease across several steps uses [MaintenanceRun.underLease]. A commit refused `busy` or `deadline` is answered with
 *   that outcome, and the run ends there. A throw ends the run `failed`.
 */
interface MaintenancePart {
    /** The part's name in the run row and the audit event; stable, `[a-z_]+`. */
    val name: String

    /** The record's order: cleanup, manifest rewrite, compaction (a purged table runs its purge deletion alone). */
    val order: Int

    /** True for the purge deletion: the only kind of part a purged table runs, and never run for any other table. */
    val purge: Boolean
        get() = false

    fun declare(table: MaintenanceTable): MaintenanceDeclaration

    fun dueNow(run: MaintenanceRun): Boolean

    fun run(run: MaintenanceRun): PartResult
}

/**
 * The gate asked before each part (#957 D6): 959 ships the real one (the window, a pause); this lane ships
 * [MaintenanceGate.ADMIT_ALL]. A held part is recorded with the gate's outcome and reason, not run; the others still run.
 */
fun interface MaintenanceGate {
    fun admit(
        table: MaintenanceTable,
        part: MaintenancePart,
    ): GateAnswer

    companion object {
        val ADMIT_ALL = MaintenanceGate { _, _ -> GateAnswer.Admit }
    }
}

/** The gate's answer for one part. */
sealed interface GateAnswer {
    data object Admit : GateAnswer

    /** The part is held: recorded with [result]'s outcome (`outside_window`, `paused_until`, …) and [reason]. */
    data class Held(
        val result: PartResult,
        val reason: String,
    ) : GateAnswer
}

/**
 * The scratch a run reserves before it is admitted (#957 D3 step 4; the cut's L4): the lake takes it through this port
 * and does not depend on staging's budget. [reserve] answers the held reservation, or null when it does not fit; the
 * caller closes it exactly once. 969 supplies the implementation over staging's budget; until then the default refuses
 * every non-zero request ([MaintenanceScratch.NONE]), so a part that declares scratch is recorded `capacity`.
 */
fun interface MaintenanceScratch {
    fun reserve(bytes: Long): AutoCloseable?

    companion object {
        val NONE = MaintenanceScratch { _ -> null }
    }
}

/**
 * Settlement before the first part (#957 D6a; the cut's L21). Implemented over the delivery settler; holds no
 * maintenance lease and keeps no cursor.
 */
fun interface MaintenanceSettlement {
    fun settle(run: MaintenanceRun): SettlementRecord
}

/**
 * The run's slot (#957 D3 step 2): one of `admission.max-concurrent-maintenance`, tried without waiting. [tryAcquire]
 * answering true is paired with exactly one [release].
 */
interface MaintenanceSlots {
    fun tryAcquire(): Boolean

    fun release()
}

/** The JVM's slots: a fair semaphore of [max] permits, as delivery admission's (`DeliveryAdmission`). */
class SemaphoreMaintenanceSlots(
    max: Int,
) : MaintenanceSlots {
    init {
        require(max > 0) { "max-concurrent-maintenance is at least 1: $max" }
    }

    private val permits = Semaphore(max, true)

    override fun tryAcquire(): Boolean = permits.tryAcquire()

    override fun release() = permits.release()
}

/**
 * The metadata rows a run reads and writes (#957 D3, D4), implemented by [MaintenanceRunRepository]. A port so the order
 * of the worker's calls can be recorded (R13b).
 */
interface MaintenanceRunStore {
    fun catalogNormal(): Boolean

    fun findTable(tableId: UUID): MaintenanceTable?

    fun admit(
        runId: UUID,
        tableId: UUID,
        kind: MaintenanceRunKind,
        timeout: Duration,
        scratchReservedBytes: Long,
    ): Admission

    fun recordProgress(
        runId: UUID,
        parts: List<PartRecord>,
        settlement: SettlementRecord?,
    ): Boolean

    fun close(
        runId: UUID,
        state: MaintenanceRunState,
        parts: List<PartRecord>,
        settlement: SettlementRecord?,
        errorCode: String?,
        errorMessage: String?,
    ): Boolean
}
