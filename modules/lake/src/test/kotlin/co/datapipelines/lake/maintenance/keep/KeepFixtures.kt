package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.profiles.KeepDuration
import co.datapipelines.lake.profiles.KeepPeriod
import co.datapipelines.lake.profiles.KeepRule
import co.datapipelines.lake.profiles.KeepUnit
import java.time.Instant
import java.time.ZoneId

/**
 * #960 — small builders for the reconcile's inputs, and [after], which applies a plan to a history's refs the way
 * Iceberg applies `manageSnapshots()` (remove, create, set age), so a test can reconcile again over the result.
 * Named without the `Test` suffix so `verifyTestsExecuted` counts only real test classes.
 */
internal object KeepFixtures {
    val UTC: ZoneId = ZoneId.of("UTC")
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    fun at(text: String): Instant = Instant.parse(text)

    fun rule(
        period: KeepPeriod,
        n: Int,
        unit: KeepUnit,
    ) = KeepRule(period, KeepDuration(n, unit))

    fun days(
        period: KeepPeriod,
        n: Int,
    ) = rule(period, n, KeepUnit.DAYS)

    /** A history whose log and snapshots are [commits] (snapshot id to commit time), in order, with [refs]. */
    fun history(
        vararg commits: Pair<Long, String>,
        refs: List<ExistingRef> = emptyList(),
    ): KeepHistory =
        KeepHistory(
            log = commits.map { (id, time) -> SnapshotLogEntry(at(time), id) },
            commitTimes = commits.associate { (id, time) -> id to at(time) },
            refs = refs,
        )

    fun tag(
        name: String,
        snapshotId: Long,
        maxRefAgeMs: Long? = null,
    ) = ExistingRef(name, KeepRefType.TAG, snapshotId, maxRefAgeMs)

    fun branch(
        name: String,
        snapshotId: Long,
        maxRefAgeMs: Long? = null,
    ) = ExistingRef(name, KeepRefType.BRANCH, snapshotId, maxRefAgeMs)

    /** The history with [plan] applied to its refs: removals dropped, creates added, ages set. */
    fun KeepHistory.after(plan: KeepPlan): KeepHistory {
        val removed = plan.removals.map { it.name }.toSet()
        val ages = plan.ageChanges.associate { it.name to it.toMs }
        val kept = refs.filter { it.name !in removed }.map { ref -> ages[ref.name]?.let { ref.copy(maxRefAgeMs = it) } ?: ref }
        return copy(refs = kept + plan.creates.map { tag(it.name, it.snapshotId, it.maxRefAgeMs) })
    }

    /** The `dp-keep-` tags of a history, by name. */
    fun KeepHistory.keepTags(): Map<String, ExistingRef> =
        refs.filter { it.type == KeepRefType.TAG && it.name.startsWith(KeepCalendar.PREFIX) }.associateBy { it.name }

    /** Every name a plan entry carries, of any kind. */
    fun KeepPlan.allNames(): Set<String> =
        (
            creates.map { it.name } + ageChanges.map { it.name } + removals.map { it.name } + standing.map { it.name } +
                notKept.map { it.name }
        ).toSet()

    fun dayTag(date: String) = "dp-keep-day-$date"
}
