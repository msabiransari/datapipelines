package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.ZoneId

/**
 * **The Schedules page** (ui-screens.md §4.20; #9 slice 2 of the scheduler design revision §9.4).
 *
 * This controller renders the SHELL and nothing else. Every schedule read and every write on the
 * page — the list, a schedule, its upcoming occurrences, the pattern preview, its runs, one run
 * with its trail, create, edit, delete, pause, resume, unblock and Run now — is a `fetch` from
 * `static/js/schedules/` to rest-api.md §20 with the session cookie and the `DP-CSRF-Token`
 * header. The record's §6 is explicit (owner, 2026-09-22): the UI uses REST for every schedule
 * operation and there is no parallel htmx-partial route, so there is none here — not even a READ
 * fragment: the page would otherwise carry a second list query beside §20.1 that the REST
 * surface's promoter lens and paging would have to be kept equal to.
 *
 * What the server does decide, and so renders:
 *  - **the verbs**, by role ([RoleModel]; ui-screens §4.3e). They sit inside `canAuthor` guards in
 *    the page's `<template>` skeletons, so a viewer's or a promoter's page has no verb to clone.
 *    `canAuthor` is the author / workspace admin / super admin set, which is exactly the five
 *    `schedule.create/update/pause/delete/run` rows (`SchedulesUiControllerTest` pins the two
 *    sets equal). Hiding is not authorization: §20's routes enforce the same rows.
 *  - **the timezone list** ([ScheduleTimezones]) — the IANA region ids the scheduler accepts.
 *
 * Out of this slice, deliberately (record §6): the notification e-mail list (slice 4 — no
 * recipients column exists yet) and any key or role assignment (R2 dropped it: a schedule fires
 * as the system identity).
 */
@Controller
class SchedulesUiController(
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/schedules")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun page(
        model: Model,
        request: HttpServletRequest,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        model.addAttribute("timezoneGroups", ScheduleTimezones.GROUPS)
        return "schedules/index"
    }
}

/**
 * The timezone choices the schedule form offers, grouped by region for an `<optgroup>` each.
 *
 * The scheduler accepts any tzdb REGION id ([ZoneId.getAvailableZoneIds]; a fixed offset is refused
 * — scheduler.md §3). The form offers the ones a person means: `Area/Location` ids plus `UTC`. The
 * `Etc/GMT±n` ids (whose sign is inverted by POSIX convention), the `SystemV/` ids and the legacy
 * single-word aliases (`EST`, `Cuba`) stay accepted over REST but are not offered; a schedule saved
 * with one keeps it — the form adds the saved zone to the list when it is missing.
 */
object ScheduleTimezones {
    /** One `<optgroup>`: the region (`America`) and its zone ids, sorted. */
    data class Group(
        val region: String,
        val zones: List<String>,
    )

    val GROUPS: List<Group> =
        ZoneId
            .getAvailableZoneIds()
            .filter(::offered)
            .sorted()
            .groupBy { it.substringBefore('/') }
            .map { (region, zones) -> Group(region, zones) }
            .sortedBy { it.region }

    private fun offered(id: String): Boolean = id == UTC || ('/' in id && !id.startsWith("Etc/") && !id.startsWith("SystemV/"))

    private const val UTC = "UTC"
}
