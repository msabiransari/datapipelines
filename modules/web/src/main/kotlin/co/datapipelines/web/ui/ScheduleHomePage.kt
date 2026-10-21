package co.datapipelines.web.ui

import co.datapipelines.scheduler.LastRunOutcome
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
                count("schedules", "Schedules", summary.total, "Each live schedule once: enabled, paused and blocked added together."),
                count("enabled", "Enabled", summary.enabled, "Firing on their pattern."),
                count("paused", "Paused", summary.paused, "Kept, but not firing until resumed."),
                count("blocked", "Blocked", summary.blocked, "Stopped by a failure until someone unblocks them."),
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

    /** By condition's dialog: the three conditions, each counted, and why a bucket is not a filter. */
    private fun conditionExplained(summary: ScheduleSummary): OverviewPage.Explanation =
        OverviewPage.Explanation(
            key = "condition",
            title = "How conditions are counted",
            paragraphs =
                listOf(
                    "Each live schedule is counted once, in the condition the explorer shows on it. A schedule that " +
                        "is both paused and blocked counts as blocked: blocked wins.",
                    "The bars are not filters: View all opens the whole list, and each schedule's page names its condition.",
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

    private fun count(
        key: String,
        label: String,
        value: Int,
        help: String,
    ): OverviewPage.Count = OverviewPage.Count(key = key, label = label, value = value.toLong(), help = help, href = null)

    /** Enabled / paused / blocked — the condition the explorer shows on each schedule (blocked wins). */
    private fun conditionChart(summary: ScheduleSummary): OverviewPage.Chart =
        OverviewPage
            .Chart(
                key = "condition",
                caption = "By condition",
                bars =
                    listOf(
                        bar("enabled", "Enabled", summary.enabled.toLong(), "released"),
                        bar("paused", "Paused", summary.paused.toLong(), "discarded"),
                        bar("blocked", "Blocked", summary.blocked.toLong(), "draft"),
                    ),
                headings = listOf("Condition", "Schedules"),
            ).withWidths()

    /** Each schedule's latest run, one outcome each — a snapshot, not a history. */
    private fun lastRunChart(summary: ScheduleSummary): OverviewPage.Chart =
        OverviewPage
            .Chart(
                key = "last-run",
                caption = "Last run",
                bars = LastRunOutcome.entries.map { bar(it.wire(), it.label(), summary.lastRun.getValue(it).toLong(), it.tone()) },
                note = "Each schedule counts once, by how its most recent run ended. Not a history.",
                headings = listOf("Last run", "Schedules"),
            ).withWidths()

    private fun bar(
        key: String,
        name: String,
        value: Long,
        tone: String,
    ): OverviewPage.Bar =
        OverviewPage.Bar(
            key = key,
            name = name,
            value = value,
            width = "0",
            tone = tone,
            label = "$name: ${if (value == 1L) "1 schedule" else "$value schedules"}",
            // Unlinked: the explorer has no filter for a condition or an outcome (a bucket is not a
            // filter, the #583 rule); View all opens the whole list.
            href = null,
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
            tasks = TASKS,
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
     * Continue your work's row: the explorer, every schedule with its next run. No Unblock row —
     * the explorer has no condition filter, and a bucket is not a filter (§4.20a).
     */
    private val TASKS =
        listOf(
            OverviewPage.TaskRow(
                "next",
                "calendar-clock",
                "See what runs next",
                "Every schedule with its next run, its condition and its runs.",
                "/schedules",
                "released",
            ),
        )

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

    /** The frame's four tones only — warning for a failure, muted for what did not happen. */
    private fun LastRunOutcome.tone(): String =
        when (this) {
            LastRunOutcome.SUCCEEDED -> "released"
            LastRunOutcome.FAILED -> "draft"
            LastRunOutcome.IN_PROGRESS -> "mixed"
            LastRunOutcome.DID_NOT_RUN -> "discarded"
            LastRunOutcome.NEVER_RAN -> "discarded"
        }
}
