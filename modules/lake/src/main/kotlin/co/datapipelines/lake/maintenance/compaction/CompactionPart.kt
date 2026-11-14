package co.datapipelines.lake.maintenance.compaction

import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceDeclaration
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.lake.maintenance.plan.PlanStatus
import co.datapipelines.lake.maintenance.plan.StoredMeasurement
import co.datapipelines.lake.profiles.LakeProfile
import org.apache.iceberg.DataFile
import org.apache.iceberg.RewriteFiles
import org.apache.iceberg.io.FileIO
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `compaction` (brief 969; record §11a call 34, call 36 items 4 and 5, call 41): the third part of a maintenance run.
 * It rewrites the table's candidate files ([CompactionCandidateRule], the same rule 959's measurement counts with) in
 * rewrite groups ([CompactionGroupPlanner]): each group is one partition of one spec, read by field id and ordered by
 * the table's sort order in the run's scratch database ([CompactionScratchPort]), written by the Java writer before any
 * lease ([CompactionRewriter]), and committed ONCE through the run's lease, fence and revision trigger
 * ([MaintenanceRun.commit]): `newRewrite().validateFromSnapshot(<planned>)` replacing the inputs by the outputs. It deletes
 * nothing: the inputs stay for the remover.
 *
 * - [declare] (D4), from database rows only: the factor times the smaller of the profile's bytes cap and the stored
 *   measurement's candidate bytes, at most [CompactionSettings.declarationCeilingBytes]; not due (zero) for a table
 *   that is not `active`/`deprecated`, never measured, or measured under another profile pair (959 D3a).
 * - [dueNow]: the table read again after admission: any candidate (or a delete file, which [run] refuses), and only
 *   when this part declared scratch at admission (a declaration of zero is not due, D4). A positive declaration whose
 *   reservation was refused IS due when the table has candidates: 957's loop then records it `capacity` with reason
 *   `scratch_refused` and never calls [run], so nothing is opened or rewritten without a grant.
 * - [run]: plans once for what no run can take (`capacity`), then before every group re-reads the clock and plans again
 *   with the time left now; a group that would cross what is left of the run's cap is not started (D3). One scratch
 *   database per run, opened when the first group starts and closed in `finally` (D5 step 6).
 *
 * [catalogNow] reads the catalog's clock (the run's deadline is on it); [clock] is the local clock the deadline is
 * carried on once translated, so a test can steer time.
 */
@Suppress("LongParameterList")
class CompactionPart(
    private val tables: CompactionTables,
    private val measurements: (UUID) -> StoredMeasurement?,
    private val io: () -> FileIO,
    private val scratch: CompactionScratchPort,
    private val settings: CompactionSettings,
    private val catalogNow: () -> Instant,
    private val clock: Clock,
) : MaintenancePart {
    override val name: String = MaintenancePartNames.COMPACTION
    override val order: Int = ORDER

    override fun declare(table: MaintenanceTable): MaintenanceDeclaration {
        if (table.status !in PlanStatus.REWRITTEN) return MaintenanceDeclaration.NONE
        val row = tables.row(table.id) ?: return MaintenanceDeclaration.NONE
        val candidateBytes =
            measurements(table.id)
                ?.takeIf { it.current(row.profileId, row.profileUpdatedAt) }
                ?.figures
                ?.candidateBytes
                ?.takeIf { it > 0 }
                ?: return MaintenanceDeclaration.NONE
        val demand = settings.scratchFor(minOf(row.profile.maxBytesRewrittenPerRun, candidateBytes))
        return MaintenanceDeclaration(expectsDue = true, scratchBytes = minOf(demand, settings.declarationCeilingBytes))
    }

    override fun dueNow(run: MaintenanceRun): Boolean {
        if (run.table.status !in PlanStatus.REWRITTEN) return false
        // D4: a part that declared zero (no current measurement) is not due: it has no scratch to rewrite with, and
        // 959's next measurement declares it. A positive declaration whose reservation was refused goes on: when the
        // table is due, 957's loop (which asks dueNow first) records `capacity` / `scratch_refused` and never runs it.
        if (run.declaredScratchBytes(this) <= 0L) return false
        val row = tables.row(run.table.id) ?: return false
        return io().use { io ->
            val pinned = tables.pinned(run.table, io)
            pinned != null && (pinned.filesWithDeletes > 0 || candidates(pinned, row.profile).isNotEmpty())
        }
    }

    override fun run(run: MaintenanceRun): PartResult {
        val row = tables.row(run.table.id) ?: return CompactionTally().result()
        // The run's deadline is on the catalog's clock: translated once onto the local clock every later check reads.
        val deadline = clock.instant().plus(Duration.between(catalogNow(), run.deadline))
        return io().use { io ->
            val pinned = tables.pinned(run.table, io) ?: return@use CompactionTally().result()
            if (pinned.filesWithDeletes > 0) throw CompactionDeleteFilesFound(run.table.id)
            CompactionRun(run, row.profile, pinned, deadline).execute()
        }
    }

    /** The candidates of [pinned] under [profile], as the planner sees them. */
    internal fun candidates(
        pinned: PinnedTable,
        profile: LakeProfile,
    ): List<CompactionCandidate> {
        val specs = pinned.table.specs()
        val rule =
            CompactionCandidateRule(
                profile,
                pinned.table
                    .sortOrder()
                    .takeIf { !it.isUnsorted }
                    ?.orderId(),
            )

        fun partition(file: DataFile) =
            CompactionCandidateRule.partitionKey(file.specId(), specs.getValue(file.specId()).partitionToPath(file.partition()))
        return rule.candidates(pinned.files, ::partition, DataFile::fileSizeInBytes, DataFile::sortOrderId).map { file ->
            CompactionCandidate(
                key = file.location(),
                partition = partition(file),
                sizeBytes = file.fileSizeInBytes(),
                sequenceNumber = file.dataSequenceNumber() ?: 0L,
                needsSort = rule.unsorted(file.sortOrderId()),
            )
        }
    }

    /** One run of the part: confined to the run's thread. */
    private inner class CompactionRun(
        private val run: MaintenanceRun,
        private val profile: LakeProfile,
        private val pinned: PinnedTable,
        private val deadline: Instant,
    ) {
        private val tally = CompactionTally()
        private val budget = CompactionRunBudget(profile.maxBytesRewrittenPerRun)
        private val grant = run.scratchBytes(this@CompactionPart)
        private val byKey = pinned.files.associateBy { it.location() }
        private val rewriter = CompactionRewriter(::checkDeadline)
        private var session: CompactionScratchSession? = null

        private var remaining = candidates(pinned, profile)
        private var ordinal = 0L

        fun execute(): PartResult {
            val first = CompactionGroupPlanner.plan(remaining, limits(timeLeft()))
            first.largestBlockedNeed?.let { tally.block(first.blocked.size, it, first.limit) }
            try {
                var goOn = true
                while (goOn) goOn = nextGroup()
            } finally {
                session?.close()
            }
            val result = tally.result()
            if (tally.blocked > 0) logBlocked()
            return result
        }

        /**
         * Plans again with the time left now and runs the plan's first group (D3's last rule); answers whether the part
         * goes on. A group that would cross what is left of the run's cap is not started and not shrunk: the part stops,
         * and those files are neither blocked nor left for a fresh run. At the deadline (before the group or inside it)
         * every group of this plan is a fresh run's.
         */
        private fun nextGroup(): Boolean {
            run.checkInterrupt()
            val timeLeft = timeLeft()
            val plan = CompactionGroupPlanner.plan(remaining, limits(timeLeft))
            tally.leftForFreshRun = plan.leftForFreshRun.size.toLong()
            val group = plan.groups.firstOrNull() ?: return false
            if (timeLeft.isNegative || timeLeft.isZero) tally.deadlineReached = true
            val committed = !tally.deadlineReached && budget.fits(group) && rewrite(group, ++ordinal)
            if (committed) remaining = remaining.filterNot { it in group }
            if (tally.deadlineReached) tally.leftForFreshRun += plan.groups.sumOf { it.size }
            return committed
        }

        /** One group; answers whether the part goes on to the next. */
        private fun rewrite(
            group: List<CompactionCandidate>,
            ordinal: Long,
        ): Boolean {
            val inputs = group.map { byKey.getValue(it.key) }
            val inputBytes = group.sumOf { it.sizeBytes }
            return try {
                val db = session ?: scratch.open(requireNotNull(run.scratchReservation) { "a granted run holds its reservation" }, deadline)
                session = db
                val statement = CompactionStatement.of(pinned.table.schema(), pinned.table.sortOrder())
                val started = System.nanoTime()
                val written = rewriter.write(pinned, inputs, statement, db, profile, run.runId, ordinal)
                val leaseStarted = System.nanoTime()
                val readWrite = seconds(started, leaseStarted)
                val guarded =
                    run.commit { table ->
                        replacing(table.newRewrite().validateFromSnapshot(pinned.snapshotId), inputs, written.outputs)
                    }
                when (guarded) {
                    is Guarded.Done -> committed(group, inputBytes, written, readWrite, seconds(leaseStarted, System.nanoTime()))
                    is Guarded.Refused -> refused(guarded.outcome)
                }
            } catch (_: CompactionDeadlinePassed) {
                tally.deadlineReached = true
                false
            } catch (_: CompactionScratchExhausted) {
                // D8: the factor was too low for this group. The blocked line is the evidence: the group's input (its
                // largest need) and the grant.
                tally.block(group.size, inputBytes, GroupLimit(grant / settings.scratchFactor, LimitSource.SCRATCH))
                false
            }
        }

        private fun committed(
            group: List<CompactionCandidate>,
            inputBytes: Long,
            written: RewrittenGroup,
            readWriteSeconds: Double,
            leaseSeconds: Double,
        ): Boolean {
            budget.spend(inputBytes)
            val figures =
                GroupFigures(
                    inputFiles = group.size,
                    inputBytes = inputBytes,
                    outputFiles = written.outputs.size,
                    outputBytes = written.outputs.sumOf { it.fileSizeInBytes() },
                    rows = written.rows,
                    readWriteSeconds = readWriteSeconds,
                    leaseSeconds = leaseSeconds,
                    scratchPeakBytes = written.scratchPeakBytes,
                )
            tally.groups += figures
            LOG.info(
                "event=lake.maintenance.compaction_group_committed run_id={} table_id={} input_files={} input_bytes={} " +
                    "output_files={} output_bytes={} rows={} read_write_seconds={} lease_seconds={} scratch_peak_bytes={}",
                run.runId,
                run.table.id,
                figures.inputFiles,
                figures.inputBytes,
                figures.outputFiles,
                figures.outputBytes,
                figures.rows,
                figures.readWriteSeconds,
                figures.leaseSeconds,
                figures.scratchPeakBytes,
            )
            return true
        }

        private fun refused(outcome: MaintenanceOutcome): Boolean {
            if (outcome == MaintenanceOutcome.BUSY) {
                tally.busy = true
            } else {
                tally.deadlineReached = true
            }
            return false
        }

        private fun logBlocked() {
            LOG.warn(
                "event=lake.maintenance.compaction_blocked run_id={} table_id={} blocked={} largest_need_bytes={} limit_bytes={} " +
                    "limit_source={} grant_bytes={} committed={} left_for_fresh_run={}",
                run.runId,
                run.table.id,
                tally.blocked,
                tally.largestNeedBytes,
                tally.limit?.bytes,
                tally.limit?.source?.wire,
                grant,
                tally.committed,
                tally.leftForFreshRun,
            )
        }

        private fun limits(timeLeft: Duration) =
            PlanLimits(
                capBytes = profile.maxBytesRewrittenPerRun,
                scratchGrantBytes = grant,
                scratchFactor = settings.scratchFactor,
                wholeRunBytes = settings.wholeRunBytes,
                bytesLeftNow = settings.bytesIn(timeLeft),
                scratchCeilingBytes = maxOf(grant, settings.declarationCeilingBytes),
            )

        private fun timeLeft(): Duration = Duration.between(clock.instant(), deadline)

        /** The stream's check between batches (D8): an interrupt throws; a passed deadline abandons the group. */
        private fun checkDeadline() {
            run.checkInterrupt()
            if (!clock.instant().isBefore(deadline)) throw CompactionDeadlinePassed()
        }

        private fun seconds(
            fromNanos: Long,
            toNanos: Long,
        ): Double = (toNanos - fromNanos) / NANOS_PER_SECOND
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(CompactionPart::class.java)

        /** The record's order: cleanup, manifest rewrite, compaction. */
        const val ORDER = 3

        private const val NANOS_PER_SECOND = 1e9

        /**
         * Brief 969 D7's call: the inputs replaced by the outputs in one `RewriteFiles`, a metadata change (nothing leaves
         * the bucket). The pinned 1.12.0 deprecates this form in favour of the per-file one, which it calls unchanged; the
         * per-file form's name is the FileIO deletion entry point `LakeDeletionArchitectureTest` keeps to the wrapper.
         */
        @Suppress("DEPRECATION")
        private fun replacing(
            rewrite: RewriteFiles,
            inputs: List<DataFile>,
            outputs: List<DataFile>,
        ): RewriteFiles = rewrite.rewriteFiles(inputs.toSet(), outputs.toSet())
    }
}
