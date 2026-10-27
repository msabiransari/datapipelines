package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.scheduler.LastRunOutcome
import co.datapipelines.scheduler.ScheduleCondition
import co.datapipelines.scheduler.ScheduleService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
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
 *  - **the condition deep link** (#641, [ScheduleConditionFilter]) — `?condition=` is bound and
 *    validated HERE, as the catalogs' `?lifecycle=` is (a bad word is the house 400), and the
 *    parsed word is stamped on the page root for the script; the filtered read itself is §20.1's.
 *  - **the last-run deep link** (#725, [ScheduleOutcomeFilter]) — `?outcome=` the same way: bound,
 *    validated, stamped; the Schedules home's Last run bars open it.
 *
 * Notification settings are saved in slice 4 part a; the deployment's computed mail state (on, or off
 * with its reason) is rendered for the form's note (#442 part b).
 * A schedule fires as the system identity (R2), with no key or role assignment.
 */
@Controller
class SchedulesUiController(
    private val themeResolver: ThemeResolver,
    /** #442 — read for the deployment's computed mail state only; every schedule read stays a REST fetch. */
    private val schedules: ScheduleService,
) : ShellPage {
    @GetMapping("/schedules")
    @RequiredScope(Permission.SCHEDULE_READ)
    fun page(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) condition: String?,
        @RequestParam(required = false) outcome: String?,
    ): String {
        val only = ScheduleConditionFilter.parse(condition)
        val lastRun = ScheduleOutcomeFilter.parse(outcome)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        model.addAttribute("condition", only?.wire ?: "")
        model.addAttribute("conditionOptions", ScheduleConditionFilter.OPTIONS)
        model.addAttribute("outcome", lastRun?.wire ?: "")
        model.addAttribute("outcomeOptions", ScheduleOutcomeFilter.OPTIONS)
        model.addAttribute("timezoneGroups", ScheduleTimezones.GROUPS)
        model.addAttribute("notificationDelivery", schedules.notificationDelivery().toWire())
        return "schedules/index"
    }
}

/**
 * #641 — the explorer's `condition` query parameter, in [CatalogLifecycle]'s shape: its ONE page
 * parse, the words every surface shows for a condition, and the filtered explorer's address.
 *
 * Bound as a STRING by the page: an unknown value is the house 400 with the constant
 * [BAD_CONDITION], which never carries the input; the wire words are exact and lower-case, as
 * [ScheduleCondition.fromWire] is. The filtered READ is rest-api §20.1's own `condition` (the page
 * has no partial, §4.20), so a filtered explorer lists exactly what the Schedules home counts.
 */
object ScheduleConditionFilter {
    /** The refusal's one reason — a constant, so nothing of the input can ride it. */
    const val BAD_CONDITION = "The condition parameter is not a filter the schedules explorer offers."

    /** One control option: the query value and its words. */
    data class Option(
        val value: String,
        val label: String,
    )

    /** The select's options after "All conditions", in [ScheduleCondition]'s order. */
    val OPTIONS: List<Option> = ScheduleCondition.entries.map { Option(it.wire, label(it)) }

    /** `null`/empty → null (the whole list); a wire word → its condition; anything else → the 400. */
    fun parse(raw: String?): ScheduleCondition? {
        if (raw.isNullOrEmpty()) return null
        return ScheduleCondition.fromWire(raw) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_CONDITION)
    }

    /** The condition's name on every surface — the home's tiles and bars, the explorer's select. */
    fun label(condition: ScheduleCondition): String =
        when (condition) {
            ScheduleCondition.ENABLED -> "Enabled"
            ScheduleCondition.PAUSED -> "Paused"
            ScheduleCondition.BLOCKED -> "Blocked"
        }

    /** The explorer filtered to [condition] — what the home's tiles, bars and Unblock row open. */
    fun href(condition: ScheduleCondition): String = "/schedules?condition=${condition.wire}"
}

/**
 * #725 — the explorer's `outcome` query parameter: [ScheduleConditionFilter]'s twin over
 * [LastRunOutcome] (a twin, not one object over both: the two enums share no type, and
 * [ScheduleCondition] stays as #641 left it). Its ONE page parse, the words every surface shows
 * for a last-run bucket — the home's Last run bars and dialog, the explorer's select — and the
 * filtered explorer's address.
 *
 * Bound as a STRING by the page: an unknown value is the house 400 with the constant
 * [BAD_OUTCOME]; the wire words are [LastRunOutcome.wire], exact and lower-case. The filtered READ
 * is rest-api §20.1's own `outcome`, so a filtered explorer lists exactly what the home's bar counts.
 */
object ScheduleOutcomeFilter {
    /** The refusal's one reason — a constant, so nothing of the input can ride it. */
    const val BAD_OUTCOME = "The outcome parameter is not a filter the schedules explorer offers."

    /** The select's options after "All last runs", in [LastRunOutcome]'s order. */
    val OPTIONS: List<ScheduleConditionFilter.Option> =
        LastRunOutcome.entries.map { ScheduleConditionFilter.Option(it.wire, label(it)) }

    /** `null`/empty → null (the whole list); a wire word → its bucket; anything else → the 400. */
    fun parse(raw: String?): LastRunOutcome? {
        if (raw.isNullOrEmpty()) return null
        return LastRunOutcome.fromWire(raw) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_OUTCOME)
    }

    /** The bucket's name on every surface — the home's bars and dialog, the explorer's select. */
    fun label(outcome: LastRunOutcome): String =
        when (outcome) {
            LastRunOutcome.SUCCEEDED -> "Succeeded"
            LastRunOutcome.FAILED -> "Failed"
            LastRunOutcome.IN_PROGRESS -> "In progress"
            LastRunOutcome.DID_NOT_RUN -> "Did not run"
            LastRunOutcome.NEVER_RAN -> "Never ran"
        }

    /** The explorer filtered to [outcome] — what the home's Last run bars open. */
    fun href(outcome: LastRunOutcome): String = "/schedules?outcome=${outcome.wire}"
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
