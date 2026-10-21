package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.ScheduleSummary
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
 * browser stays hidden. [ScheduleHomePage] builds its panels and recent sections (#643).
 *
 * There is no agent section — no MCP tool creates or reads a schedule — so the guide's "How to
 * create one" is a person's steps on the explorer. A failed read says so and shows no number, never
 * a zero (the FamilyInventory rule). The §7.6 row is `schedule.read`, the explorer's own.
 *
 * #639 — on the v2 frame (ui-screens §4.20a): the four tiles are the strip's metrics (each
 * help its note; enabled, paused and blocked dotted in their By condition bar's tone, so a dot and
 * its bar never disagree), the total is the View all badge, each chart is a bars panel with a
 * description and an info dialog, and Continue your work opens on See what runs next. There is no
 * Unblock row: the explorer has no condition filter for it to open.
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
                inventory = read.summary?.let { ScheduleHomePage.panel(it, viewer.narrowed) },
                unavailable = read.unavailable,
                recent = ScheduleHomePage.recent(read.summary, Instant.now()),
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
    }
}
