package co.datapipelines.web.ui

import co.datapipelines.scheduler.LastRunOutcome
import co.datapipelines.scheduler.ScheduleCondition
import co.datapipelines.scheduler.ScheduleSummary
import co.datapipelines.scheduler.ScheduleSummaryEntry
import java.time.Instant

/** #643 — panels, metrics and recent sections for [ScheduleOverviewController], from its one summary read. */
internal object ScheduleHomePage {
    /**
     * Four tiles and two charts, every number from the one summary; the tiles become the strip's
     * metrics through the page's [lensed] flag, the charts its two panels.
     */
    fun panel(
        summary: ScheduleSummary,
        lensed: Boolean,
    ): OverviewPage.InventoryPanel {
        val counts =
            listOf(
                OverviewPage.Count(
                    key = "schedules",
                    label = "Schedules",
                    value = summary.total.toLong(),
                    help = "Each live schedule once: enabled, paused and blocked added together.",
                    // No filter selects every schedule; View all opens the whole list.
                    href = null,
                ),
                count(ScheduleCondition.ENABLED, summary.enabled, "Firing on their pattern."),
                count(ScheduleCondition.PAUSED, summary.paused, "Kept, but not firing until resumed."),
                count(ScheduleCondition.BLOCKED, summary.blocked, "Stopped by a failure until someone unblocks them."),
            )
        val condition = conditionChart(summary)
        val charts = listOf(condition, lastRunChart(summary))
        val tones = condition.bars.associate { it.key to it.tone }
        return OverviewPage.InventoryPanel(
            counts = counts,
            charts = charts,
            metrics = counts.map { OverviewPage.Metric.of(it, lensed).copy(tone = tones[it.key]) },
            panels = charts.map { Panel.Bars.of(it).copy(description = DESCRIPTIONS.getValue(it.key), explain = it.key) },
            viewAll = summary.total.toLong(),
            explanations = listOf(conditionExplained(summary), lastRunExplained(summary)),
        )
    }

    /** By condition's dialog: the three conditions, each counted, and which buckets open a filtered explorer. */
    private fun conditionExplained(summary: ScheduleSummary): OverviewPage.Explanation =
        OverviewPage.Explanation(
            key = "condition",
            title = "How conditions are counted",
            paragraphs =
                listOf(
                    "Each live schedule is counted once, in the condition the explorer shows on it. A schedule that " +
                        "is both paused and blocked counts as blocked: blocked wins.",
                    "Each bar and each condition tile opens the explorer filtered to that condition; the Last run bars are not filters.",
                ),
            terms =
                listOf(
                    OverviewPage.Term("Enabled", null, summary.enabled.toLong(), "Firing on its pattern."),
                    OverviewPage.Term("Paused", null, summary.paused.toLong(), "Kept, but not firing until resumed."),
                    OverviewPage.Term("Blocked", null, summary.blocked.toLong(), "Stopped until someone unblocks it."),
                ),
        )

    /** Last run's dialog: which run states each bucket holds (§4.20a), each counted. */
    private fun lastRunExplained(summary: ScheduleSummary): OverviewPage.Explanation =
        OverviewPage.Explanation(
            key = "last-run",
            title = "How last runs are counted",
            paragraphs =
                listOf(
                    "Each schedule is counted once, by the state of its newest run. Not a history: an earlier failure " +
                        "followed by a success counts as succeeded.",
                ),
            terms =
                LastRunOutcome.entries.map { outcome ->
                    OverviewPage.Term(outcome.label(), null, summary.lastRun.getValue(outcome).toLong(), outcome.states())
                },
        )

    /** A condition tile: its words and its link are [ScheduleConditionFilter]'s (#641). */
    private fun count(
        condition: ScheduleCondition,
        value: Int,
        help: String,
    ): OverviewPage.Count =
        OverviewPage.Count(
            key = condition.wire,
            label = ScheduleConditionFilter.label(condition),
            value = value.toLong(),
            help = help,
            href = ScheduleConditionFilter.href(condition),
        )

    /** Enabled / paused / blocked — the condition the explorer shows on each schedule (blocked wins). */
    private fun conditionChart(summary: ScheduleSummary): OverviewPage.Chart =
        OverviewPage
            .Chart(
                key = "condition",
                caption = "By condition",
                bars =
                    listOf(
                        conditionBar(ScheduleCondition.ENABLED, summary.enabled, "success"),
                        conditionBar(ScheduleCondition.PAUSED, summary.paused, "warning"),
                        conditionBar(ScheduleCondition.BLOCKED, summary.blocked, "danger"),
                    ),
                headings = listOf("Condition", "Schedules"),
            ).withWidths()

    /** Each schedule's latest run, one outcome each — a snapshot, not a history. */
    private fun lastRunChart(summary: ScheduleSummary): OverviewPage.Chart =
        OverviewPage
            .Chart(
                key = "last-run",
                caption = "Last run",
                bars =
                    LastRunOutcome.entries.map {
                        // Unlinked: the explorer has no last-run filter (a bucket is not a filter, the #583 rule).
                        bar(it.wire(), it.label(), summary.lastRun.getValue(it).toLong(), it.tone(), href = null)
                    },
                note = "Each schedule counts once, by how its most recent run ended. Not a history.",
                headings = listOf("Last run", "Schedules"),
            ).withWidths()

    /** A By condition bar, linking the explorer filtered to it (#641, the §4.5d dialect precedent). */
    private fun conditionBar(
        condition: ScheduleCondition,
        value: Int,
        tone: String,
    ): OverviewPage.Bar =
        bar(condition.wire, ScheduleConditionFilter.label(condition), value.toLong(), tone, ScheduleConditionFilter.href(condition))

    private fun bar(
        key: String,
        name: String,
        value: Long,
        tone: String,
        href: String?,
    ): OverviewPage.Bar =
        OverviewPage.Bar(
            key = key,
            name = name,
            value = value,
            width = "0",
            tone = tone,
            label = "$name: ${schedules(value)}",
            href = href,
        )

    private fun OverviewPage.Chart.withWidths(): OverviewPage.Chart {
        val tallest = bars.maxOfOrNull { it.value } ?: 0L
        return copy(bars = bars.map { it.copy(width = OverviewPage.width(it.value, tallest)) })
    }

    /** Continue your work: Up next and Recently changed, both from the one summary (no second read). */
    fun recent(
        summary: ScheduleSummary?,
        now: Instant,
    ): OverviewPage.RecentPanel =
        OverviewPage.RecentPanel(
            review = null,
            tasks = listOfNotNull(summary?.let(::unblockTask)) + NEXT_TASK,
            description = "A clear next step for your schedules",
            sections =
                listOf(
                    section(
                        UP_NEXT,
                        "Enabled schedules by their next run, soonest first.",
                        summary?.upNext?.map { entry -> upNextItem(entry, now) },
                        "No enabled schedule is waiting to run.",
                    ),
                    section(
                        RECENTLY_CHANGED,
                        "The latest edits in this workspace, newest first. Each schedule's condition is on its page.",
                        summary?.recentlyChanged?.map { entry -> changedItem(entry, now) },
                        "No schedules yet.",
                    ),
                ),
        )

    private fun section(
        heading: String,
        note: String,
        entries: List<OverviewPage.RecentItem>?,
        emptyNote: String,
    ): OverviewPage.RecentSection =
        OverviewPage.RecentSection(
            heading = heading,
            note = note,
            entries = entries,
            unavailable = if (entries == null) "$heading could not be read." else null,
            emptyNote = emptyNote,
        )

    /** A due time already passed is the dispatcher's next tick, not an expiry. */
    private fun upNextItem(
        entry: ScheduleSummaryEntry,
        now: Instant,
    ): OverviewPage.RecentItem {
        val at = requireNotNull(entry.nextDueAt) { "an Up next row always has a next run" }
        return item(entry, at, if (at.isAfter(now)) RelativeTime.until(at, now) else "due now")
    }

    private fun changedItem(
        entry: ScheduleSummaryEntry,
        now: Instant,
    ): OverviewPage.RecentItem = item(entry, entry.updatedAt, RelativeTime.since(entry.updatedAt, now))

    private fun item(
        entry: ScheduleSummaryEntry,
        at: Instant,
        ago: String,
    ): OverviewPage.RecentItem =
        OverviewPage.RecentItem(
            name = entry.name,
            href = "/schedules?id=${entry.id}",
            datetime = at.toString(),
            ago = ago,
            absolute = RelativeTime.absolute(at),
            hasDraft = false,
        )

    private const val UP_NEXT = "Up next"
    private const val RECENTLY_CHANGED = "Recently changed"

    /** Each panel's line under its heading (#639). */
    private val DESCRIPTIONS =
        mapOf(
            "condition" to "Every live schedule once, by the condition the explorer shows.",
            "last-run" to "Each schedule once, by how its latest run ended.",
        )

    /**
     * #641 — Continue your work's first row while any schedule is blocked: the explorer filtered
     * to blocked, N from the one summary read — the set the filtered §20.1 lists, by construction
     * (§4.20a). The frame's task tones are draft (the warning tint) and released; it offers no
     * danger tint, so the row wears draft's.
     */
    private fun unblockTask(summary: ScheduleSummary): OverviewPage.TaskRow? =
        summary.blocked.takeIf { it > 0 }?.let { n ->
            OverviewPage.TaskRow(
                "unblock",
                "octagon-alert",
                "Unblock ${schedules(n.toLong())}",
                if (n == 1) {
                    "1 schedule is stopped by a failure until someone unblocks it."
                } else {
                    "$n schedules are stopped by a failure until someone unblocks them."
                },
                ScheduleConditionFilter.href(ScheduleCondition.BLOCKED),
                "draft",
            )
        }

    /** Continue your work's standing row: the explorer, every schedule with its next run. */
    private val NEXT_TASK =
        OverviewPage.TaskRow(
            "next",
            "calendar-clock",
            "See what runs next",
            "Every schedule with its next run, its condition and its runs.",
            "/schedules",
            "released",
        )

    /** "1 schedule", "3 schedules". */
    private fun schedules(value: Long): String = if (value == 1L) "1 schedule" else "$value schedules"

    /** The bar's key — the wire spelling of the bucket. */
    private fun LastRunOutcome.wire(): String = name.lowercase().replace('_', '-')

    private fun LastRunOutcome.label(): String =
        when (this) {
            LastRunOutcome.SUCCEEDED -> "Succeeded"
            LastRunOutcome.FAILED -> "Failed"
            LastRunOutcome.IN_PROGRESS -> "In progress"
            LastRunOutcome.DID_NOT_RUN -> "Did not run"
            LastRunOutcome.NEVER_RAN -> "Never ran"
        }

    /** The run states a bucket holds, in words (§4.20a's mapping). */
    private fun LastRunOutcome.states(): String =
        when (this) {
            LastRunOutcome.SUCCEEDED -> "The newest run succeeded."
            LastRunOutcome.FAILED -> "The newest run failed, was aborted or ended with an unknown outcome."
            LastRunOutcome.IN_PROGRESS -> "The newest run is queued, starting or running."
            LastRunOutcome.DID_NOT_RUN -> "The newest occurrence was not started, was skipped or was cancelled."
            LastRunOutcome.NEVER_RAN -> "No run yet."
        }

    /** Semantic success and danger for outcomes; mixed while running, muted for what did not happen. */
    private fun LastRunOutcome.tone(): String =
        when (this) {
            LastRunOutcome.SUCCEEDED -> "success"
            LastRunOutcome.FAILED -> "danger"
            LastRunOutcome.IN_PROGRESS -> "mixed"
            LastRunOutcome.DID_NOT_RUN -> "discarded"
            LastRunOutcome.NEVER_RAN -> "discarded"
        }
}
