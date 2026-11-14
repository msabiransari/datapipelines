package co.datapipelines.lake.maintenance.remove

import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import software.amazon.awssdk.services.s3.S3Client
import java.time.Duration
import java.util.UUID

/**
 * The one remover (record §8a "Deletion argument", §8b "One remover, one policy", §9, §11a; brief 958 D1–D10). Two passes,
 * [removeOrphans] and [purge], each called with a table lease the CALLER holds across the pass (962's run); the remover
 * takes no lease, re-checks the caller's (a heartbeat) and `lake_control.mode` before every batch and before deleting its
 * own marker, and stops, deleting nothing more, when either answers no.
 *
 * The order inside a pass is fixed (D2): refuse unless the mode is `normal`; the clock-skew probe; the fresh reachability
 * read (inside the pass, after the caller's acquire, its own statements; nothing walked before the call is reused); the
 * listing; the candidates; batches of at most 1000 keys; the pass's own marker last. A key is deleted only when it was
 * listed under the table's location, is unreached, is older than [orphanGrace] by the catalog clock, and is not under
 * `dp/` — the two clock-marker cases of D5 apart, both by exact key. When anything is unclear, nothing is deleted.
 *
 * Not called by production code in lane 958; 962 wires it into the maintenance run.
 */
@Suppress("LongParameterList") // the remover's collaborators; 962 builds it once
class LakeRemover(
    private val tables: RemovalTables,
    private val checks: RemovalChecks,
    private val walker: ReachabilityWalker,
    private val s3: S3Client,
    private val clock: CatalogClock,
    private val runStates: RunStates,
    private val orphanGrace: Duration,
    private val probe: ClockSkewProbe = ClockSkewProbe(s3, clock),
) {
    init {
        require(orphanGrace > Duration.ZERO) { "the orphan grace must be positive" }
    }

    private val listing = LocationListing(s3)

    /** Orphan removal for [tableId] under the caller's [lease]. [hints] are keys Iceberg handed the refusing FileIO (D4). */
    fun removeOrphans(
        tableId: UUID,
        lease: LeaseHandle,
        runId: UUID,
        hints: Collection<String> = emptyList(),
    ): RemovalResult = pass(target(tableId, lease), lease, runId, purge = false, hints = hints)

    /** Purge deletion for a `purged` table (D8): the same rules, plus the answer *finished* or *more to do*. Never stamps. */
    fun purge(
        tableId: UUID,
        lease: LeaseHandle,
        runId: UUID,
    ): RemovalResult = pass(target(tableId, lease), lease, runId, purge = true, hints = emptyList())

    private fun target(
        tableId: UUID,
        lease: LeaseHandle,
    ): RemovalTarget {
        val target = requireNotNull(tables.resolve(tableId)) { "no managed table $tableId" }
        require(lease.kind == LeaseKind.TABLE && lease.name == target.leaseName) {
            "the remover needs the table lease of $tableId, not $lease"
        }
        return target
    }

    private fun pass(
        target: RemovalTarget,
        lease: LeaseHandle,
        runId: UUID,
        purge: Boolean,
        hints: Collection<String>,
    ): RemovalResult {
        val before =
            when {
                purge && target.status != PURGED -> RefusalReason.NOT_PURGED
                !checks.modeIsNormal() -> RefusalReason.MODE_NOT_NORMAL
                !checks.leaseHeld(lease) -> RefusalReason.LEASE_LOST
                else -> null
            }
        if (before != null) return refused(target, runId, before, MarkerFate.NotWritten)
        val deleter = LakeObjectDeleter(s3, target.bucket)
        val marker = target.markerKey(runId)
        val reading = failing(target, runId) { probe.probe(target.bucket, marker) }
        if (!reading.admitted) {
            val fate = removeOwnMarker(target, runId, lease, deleter, marker)
            return refused(target, runId, RefusalReason.CLOCK_SKEW, fate, reading.interval)
        }
        val read = failing(target, runId) { readLocation(target) }
        val plan = RemovalPlan.of(target, read.listing, read.reached, clock.now(), orphanGrace, marker, runStates, hints)
        if (plan.otherDpKeys.isNotEmpty()) RemovalEvents.unexpectedDpKeys(target, plan.otherDpKeys)
        val batches = runBatches(target, runId, lease, deleter, plan.candidates)
        val fate = removeOwnMarker(target, runId, lease, deleter, marker)
        val result = completed(purge, read, plan, batches, fate, marker)
        RemovalEvents.ended(target, runId, purge, result)
        return result
    }

    /**
     * A probe, walk, catalog or listing failure: the clock, the protected graph or the location is unknown, so nothing is
     * deleted and a marker already PUT stays for the terminal-run rule (D3, D5). The caller sees the failure.
     */
    private fun <T> failing(
        target: RemovalTarget,
        runId: UUID,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            RemovalEvents.failed(target, runId, e)
            throw e
        }

    private class LocationRead(
        val listing: List<ListedObject>,
        val reached: Set<String>,
        val visits: List<ReachVisit>,
    )

    private fun readLocation(target: RemovalTarget): LocationRead {
        val visits = ArrayList<ReachVisit>()
        val declared = walker.walk(checks.protectedRevisions(target)) { visits += it }
        declared.values.forEach { location ->
            check(location.trimEnd('/') == target.location) { "a protected revision of ${target.tableId} declares another location" }
        }
        val reached = HashSet<String>()
        visits.forEach { visit ->
            val key = target.keyOf(visit.location)
            // A reach the remover cannot turn into a key of this bucket must not name this table's files in another spelling.
            check(key != null || target.keyPrefix !in visit.location) { "a reached file of ${target.tableId} has an unreadable location" }
            if (key != null) reached += key
        }
        return LocationRead(listing.list(target.bucket, target.keyPrefix), reached, visits)
    }

    private class Batches(
        val deleted: Set<String>,
        val left: Map<String, LeftReason>,
        val stopped: StopReason?,
    )

    private fun runBatches(
        target: RemovalTarget,
        runId: UUID,
        lease: LeaseHandle,
        deleter: LakeObjectDeleter,
        candidates: List<ListedObject>,
    ): Batches {
        val deleted = HashSet<String>()
        val left = LinkedHashMap<String, LeftReason>()
        var stopped: StopReason? = null
        for (batch in candidates.map { it.key }.chunked(LakeObjectDeleter.MAX_BATCH)) {
            stopped = stopped ?: batchRefusal(lease)
            if (stopped != null) {
                batch.forEach { left[it] = LeftReason.NOT_ATTEMPTED }
                continue
            }
            when (val outcome = deleter.delete(batch)) {
                is DeleteOutcome.Answered -> {
                    deleted += outcome.deleted
                    outcome.failed.forEach { left[it] = LeftReason.DELETE_FAILED }
                    if (outcome.failed.isNotEmpty()) RemovalEvents.batchFailed(target, runId, outcome.failed.size, "failed", null)
                }

                is DeleteOutcome.Unknown -> {
                    batch.forEach { left[it] = LeftReason.OUTCOME_UNKNOWN }
                    RemovalEvents.batchFailed(target, runId, batch.size, "unknown", outcome.cause)
                    stopped = StopReason.DELETE_FAILED
                }
            }
        }
        return Batches(deleted, left, stopped)
    }

    /** The two checks before a batch (D1, §8a (iv)); a check that cannot be made counts as a no. */
    private fun batchRefusal(lease: LeaseHandle): StopReason? =
        when {
            !answers { checks.leaseHeld(lease) } -> StopReason.LEASE_LOST
            !answers { checks.modeIsNormal() } -> StopReason.MODE_CHANGED
            else -> null
        }

    /**
     * D5: the marker this pass PUT goes by exact key, whatever its age, only after a lease check and a mode check made
     * for this deletion both answer yes; else it is left and the terminal-run rule clears it later.
     */
    private fun removeOwnMarker(
        target: RemovalTarget,
        runId: UUID,
        lease: LeaseHandle,
        deleter: LakeObjectDeleter,
        marker: String,
    ): MarkerFate {
        val reason =
            try {
                when {
                    !checks.leaseHeld(lease) -> {
                        MarkerLeftReason.LEASE_LOST
                    }

                    !checks.modeIsNormal() -> {
                        MarkerLeftReason.MODE_CHANGED
                    }

                    else -> {
                        val outcome = deleter.delete(listOf(marker))
                        if (outcome is DeleteOutcome.Answered && marker in outcome.deleted) null else MarkerLeftReason.DELETE_UNCONFIRMED
                    }
                }
            } catch (
                // The lease or mode check itself failed: unclear, so the marker is not deleted (the silence rule).
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                RemovalEvents.checkFailed(e)
                MarkerLeftReason.CHECK_FAILED
            }
        if (reason == null) return MarkerFate.Removed
        RemovalEvents.markerLeft(target, runId, reason)
        return MarkerFate.Left(reason)
    }

    private fun completed(
        purge: Boolean,
        read: LocationRead,
        plan: RemovalPlan,
        batches: Batches,
        fate: MarkerFate,
        marker: String,
    ): RemovalResult.Completed {
        val gone = if (fate == MarkerFate.Removed) batches.deleted + marker else batches.deleted
        val left = (plan.left + batches.left).values.groupingBy { it }.eachCount()
        val answer =
            when {
                !purge -> null
                batches.stopped == null && fate == MarkerFate.Removed && left.none { it.key.blocksPurge } -> PurgeAnswer.FINISHED
                else -> PurgeAnswer.MORE_TO_DO
            }
        val listedGone = read.listing.filter { it.key in gone }
        return RemovalResult.Completed(
            listed = read.listing.size,
            reachedKeys = read.reached.size,
            deleted = listedGone.size,
            bytesDeleted = listedGone.sumOf { it.size },
            left = left,
            deferredHints = plan.hints,
            stopped = batches.stopped,
            marker = fate,
            purge = answer,
            inventory = read.listing.filter { it.key !in gone },
            visits = read.visits,
        )
    }

    private fun refused(
        target: RemovalTarget,
        runId: UUID,
        reason: RefusalReason,
        marker: MarkerFate,
        skew: SkewInterval? = null,
    ): RemovalResult.Refused {
        RemovalEvents.refused(target, runId, reason, marker, skew)
        return RemovalResult.Refused(reason, marker, skew)
    }

    private companion object {
        const val PURGED = "purged"

        /** A yes/no check whose failure is a no (the silence rule). */
        fun answers(check: () -> Boolean): Boolean =
            try {
                check()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                RemovalEvents.checkFailed(e)
                false
            }
    }
}
