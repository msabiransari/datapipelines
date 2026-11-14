package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceRun
import org.apache.iceberg.HistoryEntry
import org.apache.iceberg.ManageSnapshots
import org.apache.iceberg.Snapshot
import org.apache.iceberg.SnapshotRef
import org.apache.iceberg.Table
import org.apache.iceberg.TableMetadata
import org.slf4j.LoggerFactory
import java.time.Instant

/** What [KeepTags.apply] did with a plan: [committed] is false when the plan had no change and nothing was committed. */
data class KeepApplied(
    val plan: KeepPlan,
    val committed: Boolean,
)

/** A plan no longer matches the table it is applied to; nothing was committed (the run ends `failed`, L14). */
class KeepPlanStale(
    message: String,
) : IllegalStateException(message)

/**
 * Applies a [KeepReconcile] plan (brief 960 D1): when the plan has at least one create, age change or removal, ONE
 * commit through [MaintenanceRun.commit] (the run's lease, a fresh load, the fence and the revision trigger) using
 * `manageSnapshots()`; a plan holding only standing tags and reported periods commits nothing. Before writing, the
 * plan is checked against the freshly loaded table: every tag it re-ages or removes is still a `dp-keep-` TAG on the
 * same snapshot, and every name it creates is free and its snapshot present. Nobody calls this in production in #960;
 * 962's cleanup calls it before it expires anything (the cut's L11).
 */
class KeepTags {
    fun apply(
        run: MaintenanceRun,
        plan: KeepPlan,
    ): Guarded<KeepApplied> {
        if (!plan.hasChanges) {
            log(run, plan, OUTCOME_NOTHING)
            return Guarded.Done(KeepApplied(plan, committed = false))
        }
        return when (val guarded = run.commit { table -> update(table, plan) }) {
            is Guarded.Done -> {
                log(run, plan, OUTCOME_COMMITTED)
                Guarded.Done(KeepApplied(plan, committed = true))
            }

            is Guarded.Refused -> {
                log(run, plan, guarded.outcome.wire)
                guarded
            }
        }
    }

    private fun log(
        run: MaintenanceRun,
        plan: KeepPlan,
        outcome: String,
    ) = LOG.info(
        "event=lake.maintenance.keep_reconciled run_id={} table_id={} outcome={} created={} re_aged={} removed={} standing={} not_kept={}",
        run.runId,
        run.table.id,
        outcome,
        plan.creates.size,
        plan.ageChanges.size,
        plan.removals.size,
        plan.standing.size,
        plan.notKept.size,
    )

    companion object {
        private val LOG = LoggerFactory.getLogger(KeepTags::class.java)
        private const val OUTCOME_COMMITTED = "committed"
        private const val OUTCOME_NOTHING = "nothing_to_commit"

        /**
         * The one `manageSnapshots()` update of [plan] on [table], after checking the plan still matches it (else
         * [KeepPlanStale]): removals, then creates with their maximum ages, then age changes.
         */
        fun update(
            table: Table,
            plan: KeepPlan,
        ): ManageSnapshots {
            check(table, plan)
            val update = table.manageSnapshots()
            plan.removals.forEach { update.removeTag(it.name) }
            plan.creates.forEach { update.createTag(it.name, it.snapshotId).setMaxRefAgeMs(it.name, it.maxRefAgeMs) }
            plan.ageChanges.forEach { update.setMaxRefAgeMs(it.name, it.toMs) }
            return update
        }

        private fun check(
            table: Table,
            plan: KeepPlan,
        ) {
            val refs = table.refs()
            (plan.removals.map { it.name to it.snapshotId } + plan.ageChanges.map { it.name to it.snapshotId }).forEach { (name, id) ->
                if (!isKeepTagOn(refs[name], name, id)) {
                    throw KeepPlanStale("keep tag $name is no longer a ${KeepCalendar.PREFIX} tag on snapshot $id")
                }
            }
            plan.creates.forEach { create ->
                if (create.name in refs || table.snapshot(create.snapshotId) == null) {
                    throw KeepPlanStale("keep tag ${create.name} cannot be created on snapshot ${create.snapshotId}")
                }
            }
        }

        /** D6 at the write: the ref is a TAG, its name is a keep's, and it is still on the planned snapshot. */
        private fun isKeepTagOn(
            ref: SnapshotRef?,
            name: String,
            snapshotId: Long,
        ): Boolean = ref != null && ref.isTag && name.startsWith(KeepCalendar.PREFIX) && ref.snapshotId() == snapshotId
    }
}

/** Reads a [KeepHistory] from an Iceberg table or its metadata: the snapshot log, the snapshots' commit times, the refs. */
object KeepHistories {
    fun of(table: Table): KeepHistory = history(table.history(), table.snapshots(), table.refs())

    fun of(metadata: TableMetadata): KeepHistory = history(metadata.snapshotLog(), metadata.snapshots(), metadata.refs())

    private fun history(
        log: List<HistoryEntry>,
        snapshots: Iterable<Snapshot>,
        refs: Map<String, SnapshotRef>,
    ) = KeepHistory(
        log = log.map { SnapshotLogEntry(Instant.ofEpochMilli(it.timestampMillis()), it.snapshotId()) },
        commitTimes = snapshots.associate { it.snapshotId() to Instant.ofEpochMilli(it.timestampMillis()) },
        refs =
            refs.map { (name, ref) ->
                ExistingRef(name, if (ref.isTag) KeepRefType.TAG else KeepRefType.BRANCH, ref.snapshotId(), ref.maxRefAgeMs())
            },
    )
}
