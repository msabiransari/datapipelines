package co.datapipelines.lake.maintenance

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.scheduler.KeyedSystemJob
import co.datapipelines.scheduler.SystemJobOutcome
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * `lake-maintenance` (#957 D2, D3): the keyed system job whose key is a managed table's id. One task instance per table;
 * a run executes on the scheduler thread that took the task, in the order D3 fixes:
 *
 * 1. the permission (`principal.holds(Permission.LAKE_MAINTAIN)`, D8), then the catalog's mode: refused or not `normal`
 *    → the task ends, no row;
 * 2. a slot, tried without waiting: none free → again in [HELD_RETRY], no row;
 * 3. every eligible part's declaration, from database rows only (a part is handed a [MaintenanceTable], which reads
 *    nothing);
 * 4. scratch for the sum of the declared demands of the parts expecting to be due; a sum of zero reserves nothing, a
 *    refusal leaves the run without scratch;
 * 5. the run row, one statement (D4): an earlier live run → no row, again at that run's deadline;
 * 6. settlement (D6a), then 7. the part loop (D6), the row `running` throughout;
 * 8. the row closed `finished` or `failed`, conditional on it still being `running`; then the reservation and the slot
 *    are released, in `finally` blocks that also run when a step throws.
 *
 * A purged table runs only the purge parts; any other table never runs one.
 */
@Suppress("LongParameterList")
class MaintenanceWorker(
    private val principal: () -> AuthenticatedPrincipal,
    private val store: MaintenanceRunStore,
    private val slots: MaintenanceSlots,
    private val scratch: MaintenanceScratch,
    parts: List<MaintenancePart>,
    private val gate: MaintenanceGate,
    private val settlement: MaintenanceSettlement,
    private val context: MaintenanceRunContext,
    private val audit: AuditEventSink,
    private val jobTimeout: Duration,
    private val clock: Clock,
) : KeyedSystemJob {
    private val parts = parts.sortedWith(compareBy({ it.order }, { it.name }))

    init {
        require(
            this.parts
                .map { it.name }
                .toSet()
                .size == this.parts.size,
        ) { "maintenance part names are unique: ${this.parts.map { it.name }}" }
    }

    override val name: String = NAME
    override val instances: String = INSTANCES

    @Suppress("ReturnCount") // D3 step 1: each check before the slot ends the task with no row, one guard per check, in order
    override fun run(key: String): SystemJobOutcome {
        val tableId = runCatching { UUID.fromString(key) }.getOrNull() ?: return skipped(key, REASON_INVALID_KEY)
        val actor = principal()
        if (!actor.holds(Permission.LAKE_MAINTAIN)) {
            LOG.warn(
                "event=lake.maintenance.refused table_id={} user_id={} permission={}",
                tableId,
                actor.userId,
                Permission.LAKE_MAINTAIN.wire,
            )
            return SystemJobOutcome.Done
        }
        if (!store.catalogNormal()) return skipped(key, REASON_MODE)
        val table = store.findTable(tableId) ?: return skipped(key, REASON_NO_TABLE)
        if (!slots.tryAcquire()) {
            val again = clock.instant().plus(HELD_RETRY)
            LOG.info("event=lake.maintenance.held table_id={} again_at={}", tableId, again)
            return SystemJobOutcome.AgainAt(again)
        }
        try {
            return reserveAndAdmit(table, actor.userId)
        } finally {
            slots.release()
        }
    }

    private fun reserveAndAdmit(
        table: MaintenanceTable,
        actorId: UUID,
    ): SystemJobOutcome {
        val eligible = parts.filter { it.purge == table.purged }
        val declared = eligible.associate { it.name to it.declare(table) }
        val demand = declared.values.filter { it.expectsDue }.fold(0L) { sum, d -> saturatingAdd(sum, d.scratchBytes) }
        val reservation = if (demand > 0) scratch.reserve(demand) else null
        try {
            val reserved = if (reservation != null) demand else 0L
            val perPart =
                if (reservation != null) declared.filterValues { it.expectsDue }.mapValues { it.value.scratchBytes } else emptyMap()
            val kind = if (table.purged) MaintenanceRunKind.PURGE else MaintenanceRunKind.MAINTENANCE
            return when (val admission = store.admit(UUID.randomUUID(), table.id, kind, jobTimeout, reserved)) {
                is Admission.EarlierRunLive -> {
                    val again = admission.deadline ?: clock.instant().plus(HELD_RETRY)
                    LOG.info("event=lake.maintenance.waiting table_id={} again_at={}", table.id, again)
                    SystemJobOutcome.AgainAt(again)
                }

                is Admission.Admitted -> {
                    LOG.info(
                        "event=lake.maintenance.admitted run_id={} table_id={} kind={} deadline={} scratch_reserved_bytes={}",
                        admission.runId,
                        table.id,
                        kind.wire,
                        admission.deadline,
                        reserved,
                    )
                    admission.abandoned.forEach { old ->
                        LOG.warn(
                            "event=lake.maintenance.abandoned run_id={} table_id={} successor_run_id={}",
                            old,
                            table.id,
                            admission.runId,
                        )
                    }
                    val run = context.open(admission.runId, table, admission.deadline, perPart, reservation)
                    execute(run, kind, eligible, declared, actorId)
                    SystemJobOutcome.Done
                }
            }
        } finally {
            reservation?.close()
        }
    }

    @Suppress("TooGenericExceptionCaught") // D6: any failure of a step ends the run `failed` with its code, no stack.
    private fun execute(
        run: MaintenanceRun,
        kind: MaintenanceRunKind,
        eligible: List<MaintenancePart>,
        declared: Map<String, MaintenanceDeclaration>,
        actorId: UUID,
    ) {
        var settled: SettlementRecord? = null
        val loop = MaintenancePartLoop(gate) { records -> store.recordProgress(run.runId, records, settled) }
        var failure: Exception? = null
        try {
            settled = settlement.settle(run)
            store.recordProgress(run.runId, loop.records, settled)
            loop.run(run, eligible, declared)
        } catch (thrown: Exception) {
            failure = thrown
        }
        finish(run, kind, loop.records.toList(), settled, failure, actorId)
    }

    private fun finish(
        run: MaintenanceRun,
        kind: MaintenanceRunKind,
        records: List<PartRecord>,
        settled: SettlementRecord?,
        failure: Exception?,
        actorId: UUID,
    ) {
        // The close and the audit row are statements on this thread: an interrupt flag left set (D7) must not refuse
        // them. The flag is set again before the task returns.
        val interrupted = Thread.interrupted()
        try {
            record(run, kind, records, settled, failure, actorId)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun record(
        run: MaintenanceRun,
        kind: MaintenanceRunKind,
        records: List<PartRecord>,
        settled: SettlementRecord?,
        failure: Exception?,
        actorId: UUID,
    ) {
        val state = if (failure == null) MaintenanceRunState.FINISHED else MaintenanceRunState.FAILED
        val code = failure?.let(MaintenanceErrors::codeOf)
        val closed = store.close(run.runId, state, records, settled, code, failure?.let(MaintenanceErrors::messageOf))
        if (!closed) {
            // A successor marked the row `abandoned`: it is left exactly as written, and nothing is concluded from it.
            LOG.warn("event=lake.maintenance.close_refused run_id={} table_id={} state={}", run.runId, run.table.id, state.wire)
        }
        val recordedState = if (closed) state else MaintenanceRunState.ABANDONED
        val recordedCode = if (closed) code else MaintenanceErrors.OUTCOME_NOT_CONFIRMED
        val recordedParts = if (closed) records else emptyList()
        audit.log(
            LakeMaintenanceAuditEvents.RUN_FINISHED,
            userId = actorId,
            details =
                linkedMapOf(
                    "table_id" to run.table.id.toString(),
                    "run_id" to run.runId.toString(),
                    "kind" to kind.wire,
                    "state" to recordedState.wire,
                    "outcome_confirmed" to closed,
                    "error_code" to recordedCode,
                    "parts" to
                        recordedParts.map { linkedMapOf("name" to it.name, "outcome" to it.outcome.wire, "attempted" to it.attempted) },
                ),
        )
        LOG.info(
            "event=lake.maintenance.finished run_id={} table_id={} state={} error_code={} parts={}",
            run.runId,
            run.table.id,
            recordedState.wire,
            recordedCode,
            recordedParts.joinToString(",") { "${it.name}:${it.outcome.wire}" },
        )
    }

    private fun skipped(
        key: String,
        reason: String,
    ): SystemJobOutcome {
        LOG.info("event=lake.maintenance.skipped table_id={} reason={}", key.take(MAX_KEY_LOGGED), reason)
        return SystemJobOutcome.Done
    }

    private fun saturatingAdd(
        a: Long,
        b: Long,
    ): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    companion object {
        private val LOG = LoggerFactory.getLogger(MaintenanceWorker::class.java)

        /** The task's name in `scheduled_tasks` and the entry inventory. */
        const val NAME = "lake-maintenance"

        /** The entry inventory's schedule cell for the task (auth.md §8.6.1). */
        const val INSTANCES = "one instance per table"

        /** How far ahead a task with no free slot (or no readable live deadline) comes again (D3 step 2). */
        val HELD_RETRY: Duration = Duration.ofSeconds(30)

        const val REASON_INVALID_KEY = "invalid_key"
        const val REASON_MODE = "catalog_mode"
        const val REASON_NO_TABLE = "no_table"
        private const val MAX_KEY_LOGGED = 64
    }
}
