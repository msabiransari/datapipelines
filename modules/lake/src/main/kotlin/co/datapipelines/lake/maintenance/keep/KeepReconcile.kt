package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.profiles.KeepRule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The keep rules' reconcile (record §11b call 42 items 1–3; brief 960 D1–D7), a pure function of its arguments: no
 * clock, no table, no database.
 *
 * - **Asked for** (a new keep): a closed period (its end E is strictly before [now]) whose keep's end is not past
 *   (end ≥ now), and which did not end before the table's row was created. Its snapshot is the newest log entry at or
 *   before E; a log that does not reach E, or names a snapshot the table no longer has, gives no tag on any snapshot
 *   and the period is reported `history_not_available` (the silence rule). A branch holding the name: reported
 *   `name_taken_by_branch`, no tag.
 * - **An existing `dp-keep-` tag** is matched BY NAME and is its own evidence: it is never moved and never judged
 *   against the log again. It is asked for while its period's rule exists and its end is not past, whether or not its
 *   period has closed in the current zone (a zone change, D5a); its maximum age is set again only when it differs.
 *   Otherwise (no rule, end past, or a name no label spells) it is removed.
 * - **Never touched:** any ref that is not a TAG, and any tag whose name does not start with [KeepCalendar.PREFIX]
 *   (`dp-hold-` among them, call 43). No plan entry can name one.
 *
 * Every period end and keep end is computed in [zone], the profile's CURRENT zone; nothing remembers an earlier one.
 * The look-back walks periods backwards from [now] and stops at the first whose keep's end is past or which ended
 * before [tableCreatedAt]; never at a count (D7).
 */
object KeepReconcile {
    fun plan(
        rules: List<KeepRule>,
        zone: ZoneId,
        tableCreatedAt: Instant,
        history: KeepHistory,
        now: Instant,
    ): KeepPlan {
        val keepRefs = history.refs.filter { it.name.startsWith(KeepCalendar.PREFIX) }
        val tags = keepRefs.filter { it.type == KeepRefType.TAG }
        val existing = existingTags(tags, rules, zone, history, now)
        val fresh = newKeeps(rules, zone, tableCreatedAt, history, now, keepRefs)
        return existing.copy(creates = fresh.creates, notKept = fresh.notKept)
    }

    /** D5: every existing `dp-keep-` TAG is removed, re-aged or left standing; never moved, never judged against the log. */
    private fun existingTags(
        tags: List<ExistingRef>,
        rules: List<KeepRule>,
        zone: ZoneId,
        history: KeepHistory,
        now: Instant,
    ): KeepPlan {
        val rulesByPeriod = rules.associateBy { it.period }
        val ageChanges = mutableListOf<TagAgeChange>()
        val removals = mutableListOf<TagRemoval>()
        val standing = mutableListOf<TagStanding>()
        tags.forEach { tag ->
            val label = KeepCalendar.parse(tag.name)
            val rule = label?.let { rulesByPeriod[it.period] }
            val end = rule?.let { KeepCalendar.keepEnd(label, it.keepFor, zone) }
            val wanted = end?.let { e -> history.commitTimes[tag.snapshotId]?.let { maxAge(e, it) } }
            val knownEnd = end.takeIf { wanted != null }
            when {
                end == null || end < now -> removals += TagRemoval(tag.name, tag.snapshotId)
                wanted == null || wanted == tag.maxRefAgeMs -> standing += TagStanding(tag.name, tag.snapshotId, knownEnd)
                else -> ageChanges += TagAgeChange(tag.name, tag.snapshotId, tag.maxRefAgeMs, wanted, end)
            }
        }
        return KeepPlan(
            creates = emptyList(),
            ageChanges = ageChanges.sortedBy { it.name },
            removals = removals.sortedBy { it.name },
            standing = standing.sortedBy { it.name },
            notKept = emptyList(),
        )
    }

    /** D2, D3, D6: every closed period asked for whose name no `dp-keep-` ref holds yet is created or reported. */
    private fun newKeeps(
        rules: List<KeepRule>,
        zone: ZoneId,
        tableCreatedAt: Instant,
        history: KeepHistory,
        now: Instant,
        keepRefs: List<ExistingRef>,
    ): KeepPlan {
        val tagNames = keepRefs.filter { it.type == KeepRefType.TAG }.map { it.name }.toSet()
        val branchNames = keepRefs.filter { it.type == KeepRefType.BRANCH }.map { it.name }.toSet()
        val creates = mutableListOf<TagCreate>()
        val notKept = mutableListOf<NotKept>()
        rules.forEach { rule ->
            askedFor(rule, zone, tableCreatedAt, now)
                .filter { it.label.tagName !in tagNames } // a label that has a tag is never tagged twice (D5)
                .forEach { (label, periodEnd, end) ->
                    val name = label.tagName
                    val snapshot = currentAt(history.log, periodEnd)
                    val committed = snapshot?.let { history.commitTimes[it] }
                    when {
                        name in branchNames -> notKept += NotKept(name, KeepNotKeptReason.NAME_TAKEN_BY_BRANCH)
                        snapshot == null || committed == null -> notKept += NotKept(name, KeepNotKeptReason.HISTORY_NOT_AVAILABLE)
                        else -> creates += TagCreate(name, snapshot, maxAge(end, committed), end)
                    }
                }
        }
        return KeepPlan(creates.sortedBy { it.name }, emptyList(), emptyList(), emptyList(), notKept.sortedBy { it.name })
    }

    /** One closed period a rule asks for: its label, its end E and its keep's end. */
    private data class Asked(
        val label: KeepLabel,
        val periodEnd: Instant,
        val keepEnd: Instant,
    )

    /**
     * The closed periods [rule] asks for, newest first: from the period holding [now] backwards, skipping a period
     * that has not closed (E ≥ now) and stopping at the first whose keep's end is past or which ended before the
     * table's row was created. Both ends grow with E, so nothing older can be asked for.
     */
    private fun askedFor(
        rule: KeepRule,
        zone: ZoneId,
        tableCreatedAt: Instant,
        now: Instant,
    ): List<Asked> {
        val asked = mutableListOf<Asked>()
        var start = KeepCalendar.startOf(rule.period, now.atZone(zone).toLocalDate())
        while (true) {
            val label = KeepLabel(rule.period, start)
            val periodEnd = KeepCalendar.periodEnd(label, zone)
            val keepEnd = KeepCalendar.keepEnd(label, rule.keepFor, zone)
            if (keepEnd < now || periodEnd < tableCreatedAt) break
            if (periodEnd < now) asked += Asked(label, periodEnd, keepEnd)
            start = KeepCalendar.previous(rule.period, start)
        }
        return asked
    }

    /** The snapshot current at [at]: the newest log entry at or before it (`SnapshotUtil.nullableSnapshotIdAsOfTime`). */
    internal fun currentAt(
        log: List<SnapshotLogEntry>,
        at: Instant,
    ): Long? = log.lastOrNull { !it.at.isAfter(at) }?.snapshotId

    /**
     * The tag's maximum age: `end − the snapshot's commit time`, because Iceberg counts a tag's age from its snapshot's
     * commit time (record "What was checked"). At least one millisecond: Iceberg refuses a non-positive age, and only a
     * snapshot stamped at or after the keep's end (a writer clock ahead) could ask for one.
     */
    internal fun maxAge(
        end: Instant,
        committed: Instant,
    ): Long = Duration.between(committed, end).toMillis().coerceAtLeast(1L)
}
