package co.datapipelines.lake.maintenance.manifests

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceDeclaration
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartFigures
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.lake.maintenance.plan.PlanStatus
import co.datapipelines.lake.profiles.LakeProfiles
import org.apache.iceberg.PendingUpdate
import org.apache.iceberg.Table
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `manifest_rewrite` (brief 963; record §11, §11a call 34; the P6 cut's L4, L10): folds a table's small manifests into
 * fewer, second in a run's order, with no scratch. A rewrite changes which manifests list the files and nothing else:
 * no data file, delete file, row count, snapshot's totals or tag changes.
 *
 * - **declare**: expects to be due for a table whose status is `active` or `deprecated`, never `archived` or `purged`.
 * - **dueNow** (D2): the current snapshot's manifest count, read from the table with no lease, is at least the profile's
 *   `maintenance.rewrite_manifests_min_count`. The gate (959's, asked by 957's worker) holds it by
 *   `pause.compaction_until` and never by the window.
 * - **run** (D2a, D3): [ManifestFold] over the manifest list; nothing folds → `nothing to do`, reason `nothing to fold`,
 *   no lease and no commit. Otherwise ONE commit through [MaintenanceRun.commit], inside [MaintenanceRun.underLease] so
 *   the time the lease is held is measured (D4): the rule is decided again on the table loaded inside the lease, and
 *   core's `rewriteManifests()` rewrites exactly the folding candidates, every entry to ONE cluster key per spec (core
 *   keeps specs apart and rolls each writer at the target size). Core writes the new manifests inside `commit()`, so
 *   under the lease (L10: class (a) of §8a's deletion argument). The replaced manifests stay in the bucket for the
 *   remover; a delete the library asks for goes to the refusing FileIO's callback and nothing else.
 * - after the commit the manifest counts are compared: fewer → `done`, `folded: true`; not fewer → still `done`,
 *   `folded: false`, both counts recorded, no retry in this run (the next run decides afresh).
 *
 * Outcomes are 957's words: `done`, `nothing to do`, `busy` (the lease or the compare-and-swap was refused),
 * `deadline`. An unknown commit outcome throws and the run ends `failed` (L14).
 */
class ManifestRewritePart(
    private val reader: ManifestListReader,
    /** The table's profile's `maintenance.rewrite_manifests_min_count` (`LakeProfiles.forTable`). */
    private val threshold: (UUID) -> Int,
    private val nanoTime: () -> Long = System::nanoTime,
) : MaintenancePart {
    override val name: String = MaintenancePartNames.MANIFEST_REWRITE

    override val order: Int = ORDER

    override fun declare(table: MaintenanceTable): MaintenanceDeclaration =
        MaintenanceDeclaration(expectsDue = rewritten(table), scratchBytes = 0)

    override fun dueNow(run: MaintenanceRun): Boolean {
        if (!rewritten(run.table)) return false
        val view = reader.current(run.table) ?: return false
        return view.count >= threshold(run.table.id)
    }

    override fun run(run: MaintenanceRun): PartResult {
        val read = reader.current(run.table) ?: return PartResult.NOTHING_TO_DO
        return if (ManifestFold.plan(read.manifests, read.targetBytes).folds) fold(run) else nothingToFold(read.count)
    }

    /** The lease, the decision made again on the table loaded inside it, and the one commit. */
    private fun fold(run: MaintenanceRun): PartResult {
        val attempt = Attempt()
        val held =
            run.underLease {
                val started = nanoTime()
                val committed = run.commit { table -> attempt.decide(table) }
                committed to TimeUnit.NANOSECONDS.toMillis(nanoTime() - started)
            }
        return when (held) {
            is Guarded.Refused -> PartResult(held.outcome)
            is Guarded.Done -> settled(run, attempt, held.value.first, held.value.second)
        }
    }

    private fun settled(
        run: MaintenanceRun,
        attempt: Attempt,
        committed: Guarded<Unit>,
        leaseMillis: Long,
    ): PartResult {
        val decided = attempt.decided
        return when {
            committed is Guarded.Refused -> PartResult(committed.outcome)
            decided == null -> nothingToFold(attempt.manifestsUnderLease)
            else -> folded(run, decided, leaseMillis)
        }
    }

    private fun folded(
        run: MaintenanceRun,
        decided: Decided,
        leaseMillis: Long,
    ): PartResult {
        val before = decided.manifests.size
        val after = reader.childOf(run.table, decided.parentSnapshotId)?.count
        val details =
            buildMap<String, Any> {
                put(FOLDED, after != null && after < before)
                put(MANIFESTS_BEFORE, before)
                if (after != null) put(MANIFESTS_AFTER, after)
                put(LEASE_HELD_MILLIS, leaseMillis)
            }
        val figures =
            PartFigures(
                files =
                    decided.plan.paths.size
                        .toLong(),
                bytes = decided.plan.bytes(decided.manifests),
            )
        return PartResult(MaintenanceOutcome.DONE, figures, details = details)
    }

    private fun nothingToFold(manifests: Int): PartResult =
        PartResult(MaintenanceOutcome.NOTHING_TO_DO, reason = REASON_NOTHING_TO_FOLD, details = mapOf(MANIFESTS_BEFORE to manifests))

    private fun rewritten(table: MaintenanceTable): Boolean = table.status in PlanStatus.REWRITTEN

    /** What was decided on the table loaded inside the lease: the snapshot rewritten, its manifest list and the plan. */
    private class Decided(
        val parentSnapshotId: Long,
        val manifests: List<ManifestFact>,
        val plan: FoldPlan,
    )

    /** One commit's decision, made on the table [MaintenanceRun.commit] loaded inside the lease. */
    private class Attempt {
        var decided: Decided? = null
        var manifestsUnderLease: Int = 0

        fun decide(table: Table): PendingUpdate<*> {
            val snapshot = table.currentSnapshot() ?: return Unchanged
            val manifests = snapshot.allManifests(table.io()).map(ManifestFact::of)
            manifestsUnderLease = manifests.size
            val plan = ManifestFold.plan(manifests, ManifestFold.targetBytes(table))
            if (!plan.folds) return Unchanged
            decided = Decided(snapshot.snapshotId(), manifests, plan)
            // ONE constant key: every rewritten entry of a spec goes to one writer. Never the file, its partition or
            // anything the manifest reader owns: the reader reuses its containers, and a key that changes under the
            // writers map splits the fold.
            return table
                .rewriteManifests()
                .rewriteIf { it.path() in plan.paths }
                .clusterBy { CLUSTER_KEY }
        }
    }

    /** The table moved between the read and the lease and nothing folds any more: no catalog write at all. */
    private object Unchanged : PendingUpdate<Unit> {
        override fun apply() = Unit

        override fun commit() = Unit
    }

    companion object {
        /** Second in the record's order: after cleanup (1), before compaction (3). */
        const val ORDER = 2

        /** The part's reason when the table is due but no group folds (D2a). */
        const val REASON_NOTHING_TO_FOLD = "nothing to fold"

        /** Details on the part's `parts_json` entry (D3, D4). */
        const val FOLDED = "folded"
        const val MANIFESTS_BEFORE = "manifests_before"
        const val MANIFESTS_AFTER = "manifests_after"
        const val LEASE_HELD_MILLIS = "lease_held_millis"

        private const val CLUSTER_KEY = "fold"

        /**
         * The part as the lake wires it: the manifest list read with no lease through the delete-refusing metadata FileIO
         * of [catalogs], the threshold read from the table's profile on every ask (an edit applies to the next run).
         */
        fun over(
            jdbc: NamedParameterJdbcTemplate,
            catalogName: String,
            catalogs: LakeCatalogs,
            profiles: LakeProfiles,
        ): ManifestRewritePart =
            ManifestRewritePart(
                reader = ManifestListReader(jdbc, catalogName, catalogs::metadataFileIo),
                threshold = { tableId -> profiles.forTable(tableId).rewriteManifestsMinCount },
            )
    }
}
