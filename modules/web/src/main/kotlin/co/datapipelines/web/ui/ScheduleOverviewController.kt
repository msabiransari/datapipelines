package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.scheduler.LastRunOutcome
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.ScheduleSummary
import co.datapipelines.scheduler.ScheduleSummaryEntry
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.schedules.PrincipalTargetViewer
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Instant
import java.util.UUID

/**
 * #584 — the Schedules home (ui-screens.md §4.20a): the page the Schedules menu item lands on; the
 * explorer stays at `/schedules` as View all.
 *
 * Server-rendered like every home (owner ruling on #584, option 1), and still faithful to the
 * explorer's rule ([SchedulesUiController]'s KDoc: no second list query the promoter lens would
 * have to be kept equal to) — every number here comes from ONE [ScheduleService.summary], which is
 * §20.1's own [ScheduleService.list] at the workspace cap, so the home and the explorer count the
 * same admitted set by construction. Schedules is not a resource family: this builds the shared
 * [OverviewPage] frame directly (the [DatasourceOverviewController] shape), and the resource
 * browser stays hidden.
 *
 * There is no agent section — no MCP tool creates or reads a schedule — so the guide's "How to
 * create one" is a person's steps on the explorer. A failed read says so and shows no number, never
 * a zero (the FamilyInventory rule). The §7.6 row is `schedule.read`, the explorer's own.
 */
@Controller
class ScheduleOverviewController(
    private val schedules: ScheduleService,
    private val themeResolver: ThemeResolver,
) {
    private val log = LoggerFactory.getLogger(ScheduleOverviewController::class.java)

    @GetMapping(PATH)
    @RequiredScope(Permission.SCHEDULE_READ)
    fun overview(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        val viewer = PrincipalTargetViewer(principal)
        val read = readSummary(principal.requireWorkspace().id, viewer)
        model.addAttribute(
            OverviewController.MODEL,
            OverviewPage(
                family = "schedules",
                title = "Schedules",
                catalogHref = "/schedules",
                lensed = viewer.narrowed,
                inventory = read.summary?.let(::panel),
                unavailable = read.unavailable,
                recent = recent(read.summary, Instant.now()),
                guided = true,
            ),
        )
        return OverviewController.VIEW
    }

    /** The one read, or why there is none — an over-cap workspace included (its counts would be short). */
    @Suppress("TooGenericExceptionCaught") // the read boundary FamilyInventory draws
    private fun readSummary(
        workspaceId: UUID,
        viewer: PrincipalTargetViewer,
    ): SummaryRead {
        val summary =
            try {
                schedules.summary(workspaceId, viewer)
            } catch (failure: Exception) {
                log.warn("schedule overview unavailable for workspace {}: {}", workspaceId, failure.toString())
                return SummaryRead(null, UNAVAILABLE)
            }
        if (!summary.complete) {
            log.warn("schedule overview incomplete for workspace {}: more live schedules than the cap", workspaceId)
            return SummaryRead(null, OVER_CAP)
        }
        return SummaryRead(summary, null)
    }

    /** Four tiles and two charts, every number from the one summary. */
    private fun panel(summary: ScheduleSummary): OverviewPage.InventoryPanel =
        OverviewPage.InventoryPanel(
            counts =
                listOf(
                    count("schedules", "Schedules", summary.total, "Each live schedule once: enabled, paused and blocked added together."),
                    count("enabled", "Enabled", summary.enabled, "Firing on their pattern."),
                    count("paused", "Paused", summary.paused, "Kept, but not firing until resumed."),
                    count("blocked", "Blocked", summary.blocked, "Stopped by a failure until someone unblocks them."),
                ),
            charts = listOf(conditionChart(summary), lastRunChart(summary)),
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
    private fun recent(
        summary: ScheduleSummary?,
        now: Instant,
    ): OverviewPage.RecentPanel =
        OverviewPage.RecentPanel(
            review = null,
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

    /** The summary read's two outcomes: the summary, or the reason it is missing. */
    private data class SummaryRead(
        val summary: ScheduleSummary?,
        val unavailable: String?,
    )

    companion object {
        const val PATH = "/overview/schedules"

        const val UNAVAILABLE = "Schedules could not be read."
        const val OVER_CAP =
            "This workspace holds more schedules than its limit, so they cannot be counted in one read. View all lists every one."

        const val UP_NEXT = "Up next"
        const val RECENTLY_CHANGED = "Recently changed"

        /** The bar's key — the wire spelling of the bucket. */
        internal fun LastRunOutcome.wire(): String = name.lowercase().replace('_', '-')

        internal fun LastRunOutcome.label(): String =
            when (this) {
                LastRunOutcome.SUCCEEDED -> "Succeeded"
                LastRunOutcome.FAILED -> "Failed"
                LastRunOutcome.IN_PROGRESS -> "In progress"
                LastRunOutcome.DID_NOT_RUN -> "Did not run"
                LastRunOutcome.NEVER_RAN -> "Never ran"
            }

        /** The frame's four tones only — warning for a failure, muted for what did not happen. */
        internal fun LastRunOutcome.tone(): String =
            when (this) {
                LastRunOutcome.SUCCEEDED -> "released"
                LastRunOutcome.FAILED -> "draft"
                LastRunOutcome.IN_PROGRESS -> "mixed"
                LastRunOutcome.DID_NOT_RUN -> "discarded"
                LastRunOutcome.NEVER_RAN -> "discarded"
            }
    }
}
