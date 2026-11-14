package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.SnapshotExpiryFloor
import co.datapipelines.lake.maintenance.Guarded
import co.datapipelines.lake.maintenance.MaintenanceDeclaration
import co.datapipelines.lake.maintenance.MaintenancePart
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.PartResult
import co.datapipelines.lake.maintenance.keep.KeepHistories
import co.datapipelines.lake.maintenance.keep.KeepReconcile
import co.datapipelines.lake.maintenance.keep.KeepTags
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import co.datapipelines.lake.maintenance.plan.PlanStatus
import co.datapipelines.lake.maintenance.remove.RemovalResult
import co.datapipelines.lake.maintenance.remove.RemovalTarget
import co.datapipelines.lake.profiles.LakeProfiles
import org.apache.iceberg.SnapshotRef
import org.apache.iceberg.TableMetadata
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The `cleanup` part (brief 962 D1, D2) of an `active`, `deprecated` or `archived` table: under ONE table lease, held to
 * the end, (1) the table loaded fresh, (2) its keep tags reconciled (960's [KeepTags], which commits only a plan with a
 * change), (3) the snapshots older than the floor expired, the floor from [SnapshotExpiryFloor] over the DEPLOYMENT
 * [window] (never a profile value), every file Iceberg would delete handed to the remover as a hint, (4) the catalog's
 * clock read and 958's orphan removal, (5) the [observer], (6) the release. A table with no snapshot does steps 4–6 only.
 * Each ending is D2a's row; [CleanupProgress.answer] maps the remover's result.
 */
class CleanupPart(
    private val reads: CleanupReads,
    private val removal: CleanupRemoval,
    private val profiles: LakeProfiles,
    private val window: Duration,
    private val keepTags: KeepTags,
    private val observer: CleanupObserver,
) : MaintenancePart {
    init {
        require(window > Duration.ZERO) { "the protection window must be positive" }
    }

    override val name: String = NAME

    override val order: Int = MaintenancePartNames.ALL.indexOf(NAME)

    /** Expected for the statuses the record cleans (call 34); zero scratch (L4). */
    override fun declare(table: MaintenanceTable): MaintenanceDeclaration =
        MaintenanceDeclaration(expectsDue = table.status in PlanStatus.MEASURED, scratchBytes = 0)

    /**
     * The planner's rule (959 D4 "Cleanup"), read again after admission: never cleaned, or the last finished cleanup is
     * older than the profile's `cleanup_interval_hours` on the catalog's clock.
     */
    override fun dueNow(run: MaintenanceRun): Boolean {
        if (run.table.status !in PlanStatus.MEASURED) return false
        val last = reads.lastFinishedCleanup(run.table.id) ?: return true
        val interval = Duration.ofHours(profiles.forTable(run.table.id).cleanupIntervalHours.toLong())
        return removal.clock.now().isAfter(last.plus(interval))
    }

    override fun run(run: MaintenanceRun): PartResult {
        val progress = CleanupProgress()
        return run.cleanupUnderLease(NAME, progress) { pass(run, progress) }
    }

    @Suppress("ReturnCount") // a commit refused `busy` or `deadline` ends the pass where it happened (957 D6)
    private fun pass(
        run: MaintenanceRun,
        progress: CleanupProgress,
    ): PartResult {
        // D6: a restore can begin between admission and the part; nothing is written outside `normal` mode.
        if (!removal.checks.modeIsNormal()) throw CleanupIncomplete(IncompleteReason.MODE)
        val loaded = reads.metadata(run.table)
        val hints = ConcurrentLinkedQueue<String>()
        var metadata = loaded
        if (loaded == null || loaded.snapshots().isEmpty()) {
            progress.update { it.copy(floor = ExpiryFloor.NotApplicable) }
        } else {
            val kept =
                when (val step = keep(run, loaded, progress)) {
                    is Guarded.Refused -> return progress.result(step.outcome)
                    is Guarded.Done -> step.value
                }
            metadata =
                when (val step = expire(run, kept, hints, progress)) {
                    is Guarded.Refused -> return progress.result(step.outcome)
                    is Guarded.Done -> step.value
                }
        }
        val target = checkNotNull(removal.tables.resolve(run.table.id)) { "no managed table ${run.table.id}" }
        val listingTime = removal.clock.now()
        val result = removal.remover.removeOrphans(run.table.id, run.heldLease(), run.runId, hints.toList())
        progress.record(result)
        if (result is RemovalResult.Completed) observe(run, target, result, metadata, listingTime, progress)
        return progress.answer(RemovalVerdict.of(result), progress.figures.didWork)
    }

    /** Step 2: the keep plan applied (960 D1); answers the table as it now stands, or the commit's refusal. */
    private fun keep(
        run: MaintenanceRun,
        metadata: TableMetadata,
        progress: CleanupProgress,
    ): Guarded<TableMetadata> {
        val profile = profiles.forTable(run.table.id)
        val plan =
            KeepReconcile.plan(
                profile.keep,
                profile.keepTimeZone,
                reads.createdAt(run.table.id),
                KeepHistories.of(metadata),
                removal.clock.now(),
            )
        progress.update { figures -> figures.copy(notKept = plan.notKept.groupingBy { it.reason }.eachCount()) }
        return when (val applied = keepTags.apply(run, plan)) {
            is Guarded.Refused -> {
                applied
            }

            is Guarded.Done -> {
                if (!applied.value.committed) return Guarded.Done(metadata)
                progress.update {
                    it.copy(
                        tagsCreated = plan.creates.size,
                        tagsChanged = plan.ageChanges.size,
                        tagsRemoved = plan.removals.size,
                        tagCommitted = true,
                    )
                }
                Guarded.Done(reload(run))
            }
        }
    }

    /**
     * Step 3: the floor over the deployment window, the table's protected revisions and the reader pins; one commit only
     * when a snapshot older than the floor is not held by a ref. Answers the table after it, or the commit's refusal.
     */
    private fun expire(
        run: MaintenanceRun,
        metadata: TableMetadata,
        hints: ConcurrentLinkedQueue<String>,
        progress: CleanupProgress,
    ): Guarded<TableMetadata> {
        val now = removal.clock.now()
        val pinned = removal.pins.pinnedLocations(run.table.namespace, run.table.name)
        val floor = SnapshotExpiryFloor.compute(now, window, reads.protectedRevisions(run.table, now, pinned), pinned).expireOlderThan
        progress.update { it.copy(floor = ExpiryFloor.Applied(floor)) }
        if (expirable(metadata, floor, now).isEmpty()) return Guarded.Done(metadata)
        val committed =
            run.commit { table ->
                table
                    .expireSnapshots()
                    .expireOlderThan(floor.toEpochMilli())
                    .cleanExpiredMetadata(false)
                    .deleteWith { hints.add(it) }
            }
        if (committed is Guarded.Refused) return committed
        val after = reload(run)
        val expired = metadata.snapshots().map { it.snapshotId() }.toSet() - after.snapshots().map { it.snapshotId() }.toSet()
        progress.update {
            it.copy(
                snapshotsExpired = expired.size,
                expiryCommitted = after.metadataFileLocation() != metadata.metadataFileLocation(),
            )
        }
        return Guarded.Done(after)
    }

    /**
     * The snapshots older than [floor] that no ref Iceberg keeps points at (the head of a branch; a tag whose
     * `max-ref-age` has not passed). Empty → no commit (C3).
     */
    private fun expirable(
        metadata: TableMetadata,
        floor: Instant,
        now: Instant,
    ) = metadata.snapshots().filter { snapshot ->
        snapshot.timestampMillis() < floor.toEpochMilli() &&
            metadata.refs().values.none { ref -> ref.snapshotId() == snapshot.snapshotId() && kept(ref, snapshot.timestampMillis(), now) }
    }

    private fun kept(
        ref: SnapshotRef,
        committedAtMs: Long,
        now: Instant,
    ): Boolean {
        val maxAge = ref.maxRefAgeMs() ?: return true
        return ref.isBranch() || now.toEpochMilli() - committedAtMs <= maxAge
    }

    private fun reload(run: MaintenanceRun): TableMetadata =
        checkNotNull(reads.metadata(run.table)) { "the table's pointer disappeared under the lease" }

    /** Step 5 (D2b): one call; an [Exception] from the observer is logged and recorded, never propagated. */
    private fun observe(
        run: MaintenanceRun,
        target: RemovalTarget,
        result: RemovalResult.Completed,
        metadata: TableMetadata?,
        listingTime: Instant,
        progress: CleanupProgress,
    ) {
        val observation =
            CleanupObservation(
                runId = run.runId,
                tableId = run.table.id,
                location = target.location,
                listingTime = listingTime,
                currentSnapshotId = metadata?.currentSnapshot()?.snapshotId(),
                snapshots =
                    metadata?.snapshots().orEmpty().map {
                        ObservedSnapshot(
                            it.snapshotId(),
                            Instant.ofEpochMilli(it.timestampMillis()),
                        )
                    },
                refs =
                    metadata?.refs().orEmpty().map { (name, ref) ->
                        ObservedRef(name, if (ref.isBranch) ObservedRefType.BRANCH else ObservedRefType.TAG, ref.snapshotId())
                    },
                floor = checkNotNull(progress.figures.floor) { "the floor is set before the removal" },
                inventory = result.inventory,
                visits = result.visits,
                pass = result.stopped?.let { ObservedPass.Stopped(it) } ?: ObservedPass.Complete,
            )
        val outcome =
            @Suppress("TooGenericExceptionCaught") // D2b: an observer that throws never changes the cleanup
            try {
                observer.observed(observation)
                ObserverOutcome.OK
            } catch (failure: Exception) {
                CleanupEvents.observerFailed(run.table.id, run.runId, failure)
                ObserverOutcome.FAILED
            }
        progress.update { it.copy(observer = outcome) }
    }

    companion object {
        const val NAME = MaintenancePartNames.CLEANUP

        /** The production construction: the expiry window is the DEPLOYMENT's `retention.protection-days` (call 35). */
        fun of(
            properties: LakeProperties,
            reads: CleanupReads,
            removal: CleanupRemoval,
            profiles: LakeProfiles,
            observer: CleanupObserver,
        ): CleanupPart =
            CleanupPart(
                reads = reads,
                removal = removal,
                profiles = profiles,
                window = Duration.ofDays(properties.retention.protectionDays.toLong()),
                keepTags = KeepTags(),
                observer = observer,
            )
    }
}
