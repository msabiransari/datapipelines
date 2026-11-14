package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.maintenance.MaintenanceDeclaration
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.lake.maintenance.remove.PurgeAnswer
import co.datapipelines.lake.maintenance.remove.RemovalResult
import java.time.Duration

/**
 * The `purge_deletion` part (brief 962 D1, D4) of a `purged` table, the only part such a table runs. It checks again,
 * itself, that the table is `purged`, not yet finished, its final revision's `protected_until` set and passed and the
 * [grace] passed since; then, under the table lease, 958's purge pass. *Finished* stamps `purge_deleted_at` in the same
 * pass; *more to do* stamps nothing and records the keys left as deferred deletions; every other ending of D2a NEVER
 * stamps. Keep tags are not consulted: a purge ends the table's keeps (§11b, owner ruling 3).
 */
class PurgeDeletionPart(
    private val reads: CleanupReads,
    private val removal: CleanupRemoval,
    private val grace: Duration,
) : MaintenancePart {
    init {
        require(grace > Duration.ZERO) { "the orphan grace must be positive" }
    }

    override val name: String = NAME

    override val order: Int = MaintenancePartNames.ALL.indexOf(NAME)

    override val purge: Boolean
        get() = true

    override fun declare(table: MaintenanceTable): MaintenanceDeclaration =
        MaintenanceDeclaration(expectsDue = table.purged, scratchBytes = 0)

    override fun dueNow(run: MaintenanceRun): Boolean = reads.purgeState(run.table.id)?.ripe(grace) == true

    override fun run(run: MaintenanceRun): PartResult {
        val progress = CleanupProgress()
        return run.cleanupUnderLease(NAME, progress) { pass(run, progress) }
    }

    private fun pass(
        run: MaintenanceRun,
        progress: CleanupProgress,
    ): PartResult {
        // D4: checked again under the lease; a row that no longer qualifies deletes nothing (the silence rule).
        if (reads.purgeState(run.table.id)?.ripe(grace) != true) return progress.result(MaintenanceOutcome.NOTHING_TO_DO)
        // D6: nothing is written outside `normal` mode.
        if (!removal.checks.modeIsNormal()) throw CleanupIncomplete(IncompleteReason.MODE)
        val result = removal.remover.purge(run.table.id, run.heldLease(), run.runId)
        progress.record(result)
        val verdict = RemovalVerdict.of(result)
        if (verdict == RemovalVerdict.Complete && (result as? RemovalResult.Completed)?.purge == PurgeAnswer.FINISHED) {
            val stamped = reads.stampPurgeDeleted(run.table.id)
            progress.update { it.copy(stamped = stamped) }
        }
        // D2a: finished and more to do are both `done`, or `nothing to do` when no key was deleted.
        return progress.answer(verdict, didWork = progress.figures.deleted > 0)
    }

    companion object {
        const val NAME = MaintenancePartNames.PURGE_DELETION

        /** The production construction: the grace is the deployment's `retention.orphan-grace-hours`. */
        fun of(
            properties: LakeProperties,
            reads: CleanupReads,
            removal: CleanupRemoval,
        ): PurgeDeletionPart = PurgeDeletionPart(reads, removal, Duration.ofHours(properties.retention.orphanGraceHours.toLong()))
    }
}
