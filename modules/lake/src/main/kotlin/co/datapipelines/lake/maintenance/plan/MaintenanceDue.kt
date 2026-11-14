package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import java.time.Duration
import java.time.Instant

/**
 * What is due, what is held back, and in which order (brief 959 D4): a PURE function of the tables' rows, their stored
 * measurements, their profiles and pauses, their run history (D5), the deployment's orphan grace, the part names the
 * worker registered, and `now` (the catalog's clock). The rules are the record's §11a call 34 tables "Which table gets
 * which work" and "Due, per operation", its "Order" paragraph, and D4's additions:
 *
 * - never measured, or the last measurement failed → no rewrite work; cleanup and purge deletion by time alone;
 * - stale figures (D3a) → compaction not due, `not measured (profile changed)`, no profile-dependent score term;
 *   manifest rewrite still from the manifest count and the CURRENT threshold;
 * - an unsorted file larger than the cap is left out of the score; nothing else to compact → `capacity`, no task;
 * - after a compaction attempt that ended `capacity` with nothing committed and nothing left for a fresh run, rewrite
 *   work waits `cleanup_interval_hours` from that run's end, unless the profile's `updated_at` or the assignment is
 *   later than it; `deadline`, or `capacity` with a file left for a fresh run, waits for nothing (L18);
 * - an operation whose part is not registered is never due (L16), reported `not registered`;
 * - the silence rule: a case no rule decides is not due and the table is marked [TableDue.silent].
 *
 * Order: first the tables with cleanup or purge deletion due and not held, longest overdue first; then the tables with
 * only rewrite work, by score, highest first; ties by the oldest last finished rewrite (none = oldest), then table id.
 * Only a table with at least one due part that is not held is ranked.
 */
class MaintenanceDue(
    private val orphanGrace: Duration,
) {
    fun plan(
        tables: List<DueInput>,
        registered: Set<String>,
        now: Instant,
    ): DuePlan {
        val answers = tables.map { decide(it, registered, now) }
        val byId = tables.associateBy { it.tableId }
        val ranked = answers.filter { it.hasWork }
        val light =
            ranked
                .filter { it.light }
                .sortedWith(compareBy<TableDue> { it.overdueSince }.thenBy { it.tableId })
        val rewrites =
            ranked
                .filter { !it.light }
                .sortedWith(
                    compareByDescending<TableDue> { it.score }
                        .thenBy(nullsFirst()) { byId.getValue(it.tableId).history.lastFinishedRewrite }
                        .thenBy { it.tableId },
                )
        return DuePlan(answers, (light + rewrites).map { it.tableId })
    }

    /** One table's answer: each operation due or not (with the reported reason), then the gate, then the registry. */
    fun decide(
        input: DueInput,
        registered: Set<String>,
        now: Instant,
    ): TableDue {
        val notDue = linkedMapOf<String, String>()
        val dueSince = linkedMapOf<String, Instant>()
        var silent = false
        val stale = isStale(input)

        cleanupDueSince(input, now)?.let { dueSince[MaintenancePartNames.CLEANUP] = it }
        when (val purge = purgeDue(input, now)) {
            is Purge.Due -> dueSince[MaintenancePartNames.PURGE_DELETION] = purge.since
            Purge.Silent -> {
                silent = true
                notDue[MaintenancePartNames.PURGE_DELETION] = NotDueReason.RULE_SILENT
            }
            Purge.NotDue -> Unit
        }
        val rewrite = rewrites(input, now, stale)
        silent = silent || rewrite.silent
        notDue.putAll(rewrite.notDue)
        rewrite.due.forEach { dueSince[it] = now }

        val held = linkedMapOf<String, PartHold>()
        val due = linkedSetOf<String>()
        for (part in MaintenancePartNames.ALL) {
            if (part !in dueSince) continue
            val hold = MaintenanceHolds.hold(part, input.profile.maintenanceWindow, input.pause, now)
            when {
                part !in registered -> notDue[part] = NotDueReason.NOT_REGISTERED
                hold != null -> held[part] = hold
                else -> due += part
            }
        }
        val lightParts = due.filter { it == MaintenancePartNames.CLEANUP || it == MaintenancePartNames.PURGE_DELETION }
        return TableDue(
            tableId = input.tableId,
            due = due,
            held = held,
            notDue = notDue,
            light = lightParts.isNotEmpty(),
            overdueSince = lightParts.minOfOrNull { dueSince.getValue(it) },
            score = rewrite.score,
            stale = stale,
            silent = silent,
        )
    }

    /** D3a: the profile-dependent figures were computed under another profile pair than the table's current one. */
    private fun isStale(input: DueInput): Boolean {
        val measurement = input.measurement ?: return false
        return measurement.measuredAt != null && !measurement.current(input.profileId, input.profileUpdatedAt)
    }

    /** Cleanup: due when the last finished cleanup is older than the interval; never cleaned = overdue since creation. */
    private fun cleanupDueSince(
        input: DueInput,
        now: Instant,
    ): Instant? {
        if (input.status !in PlanStatus.MEASURED) return null
        val last = input.history.lastCleanup ?: return input.createdAt
        val next = last.plus(Duration.ofHours(input.profile.cleanupIntervalHours.toLong()))
        return next.takeIf { now.isAfter(it) }
    }

    private sealed interface Purge {
        data class Due(
            val since: Instant,
        ) : Purge

        data object NotDue : Purge

        data object Silent : Purge
    }

    /** Purge deletion: `purged`, not yet finished, and the final revision's `protected_until` plus the grace passed. */
    private fun purgeDue(
        input: DueInput,
        now: Instant,
    ): Purge {
        if (input.status != PlanStatus.PURGED || input.purgeDeletedAt != null) return Purge.NotDue
        val protectedUntil = input.finalProtectedUntil ?: return Purge.Silent
        val date = protectedUntil.plus(orphanGrace)
        return if (now.isAfter(date)) Purge.Due(date) else Purge.NotDue
    }

    private data class Rewrites(
        val due: List<String>,
        val notDue: Map<String, String>,
        val score: Long,
        val silent: Boolean,
    )

    @Suppress("ReturnCount") // each rule that ends the rewrite decision answers on its own line, in the record's order
    private fun rewrites(
        input: DueInput,
        now: Instant,
        stale: Boolean,
    ): Rewrites {
        if (input.status !in PlanStatus.REWRITTEN) return Rewrites(emptyList(), emptyMap(), 0, silent = false)
        val measurement = input.measurement
        val figures = measurement?.figures
        if (measurement == null || measurement.errorCode != null || figures == null) {
            return Rewrites(emptyList(), notMeasured(NotDueReason.NOT_MEASURED), 0, silent = false)
        }
        if (figures.snapshotId == null) return Rewrites(emptyList(), emptyMap(), 0, silent = false)
        val manifests = figures.manifests ?: 0
        val threshold = input.profile.rewriteManifestsMinCount.toLong()
        val manifestTerm = (manifests - threshold).coerceAtLeast(0)
        val small = if (stale) 0 else figures.smallFiles ?: 0
        val fitting = if (stale) 0 else ((figures.unsortedFiles ?: 0) - (figures.unsortedOverCap ?: 0)).coerceAtLeast(0)
        val score = small + fitting + manifestTerm

        val due = mutableListOf<String>()
        val notDue = linkedMapOf<String, String>()
        if (manifests >= threshold) due += MaintenancePartNames.MANIFEST_REWRITE
        when {
            stale -> notDue[MaintenancePartNames.COMPACTION] = NotDueReason.PROFILE_CHANGED
            small > 0 || fitting > 0 -> due += MaintenancePartNames.COMPACTION
            (figures.unsortedOverCap ?: 0) > 0 -> notDue[MaintenancePartNames.COMPACTION] = NotDueReason.CAPACITY
        }
        val wait = capacityWait(input, now)
        if (wait == Wait.WAITING && due.isNotEmpty()) {
            due.forEach { notDue[it] = NotDueReason.CAPACITY_WAIT }
            return Rewrites(emptyList(), notDue, score, silent = false)
        }
        return Rewrites(due, notDue, score, silent = wait == Wait.SILENT)
    }

    private fun notMeasured(reason: String): Map<String, String> = MaintenancePartNames.REWRITES.associateWith { reason }

    private enum class Wait { NONE, WAITING, SILENT }

    /**
     * D4's wait after a `capacity` attempt: exactly when the outcome is `capacity`, nothing was committed and nothing
     * left for a fresh run, the interval has not passed since that run's end, and neither the profile's values nor the
     * assignment changed after it. A `capacity` entry without 969's two fields gets no wait and is reported silent.
     */
    private fun capacityWait(
        input: DueInput,
        now: Instant,
    ): Wait {
        val attempt = input.history.lastRewriteAttempt ?: return Wait.NONE
        if (attempt.outcome != MaintenanceOutcome.CAPACITY) return Wait.NONE
        val committed = attempt.committed
        val left = attempt.leftForFreshRun
        if (committed == null || left == null) return Wait.SILENT
        if (committed || left > 0) return Wait.NONE
        val end = attempt.at.plus(Duration.ofHours(input.profile.cleanupIntervalHours.toLong()))
        val changed = input.profileUpdatedAt.isAfter(attempt.at) || input.profileAssignedAt.isAfter(attempt.at)
        return if (now.isBefore(end) && !changed) Wait.WAITING else Wait.NONE
    }
}
