package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.RolePermissions
import co.datapipelines.scheduler.OccurrenceFunction
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.bind.annotation.GetMapping

/**
 * #9 slice 2 — [SchedulesUiController]: the page is a SHELL on `schedule.read`, and the two facts
 * the server decides for it — the verbs' role and the timezone list — are pinned here.
 *
 * The verbs render by `canAuthor` (the page's templates), so the claim the page's KDoc makes —
 * "`canAuthor` is exactly the five `schedule.*` write rows" — is a test, not a sentence: were the
 * catalog to give Run now to a promoter tomorrow, the page would hide a verb the server allows,
 * and this fails by permission name before anyone sees a missing button.
 */
class SchedulesUiControllerTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = SchedulesUiController(themeResolver)

    @Test
    fun `the page renders the schedules shell with the theme, the roles and the zone list`() {
        every { themeResolver.resolve(any()) } returns "dark"
        val model = ExtendedModelMap()

        controller.page(model, MockHttpServletRequest()) shouldBe "schedules/index"

        model["activeTheme"] shouldBe "dark"
        // No principal in this unit context: RoleModel's NONE — every verb boolean false.
        model["canAuthor"] shouldBe false
        model["timezoneGroups"] shouldBe ScheduleTimezones.GROUPS
    }

    @Test
    fun `the page route declares schedule read - the row every member holds`() {
        val handler =
            SchedulesUiController::class.java.getMethod(
                "page",
                org.springframework.ui.Model::class.java,
                jakarta.servlet.http.HttpServletRequest::class.java,
            )
        handler.getAnnotation(RequiredScope::class.java).value shouldBe Permission.SCHEDULE_READ
        handler.getAnnotation(GetMapping::class.java).value.toList() shouldBe listOf("/schedules")
    }

    @Test
    fun `canAuthor is exactly the roles holding each schedule write permission`() {
        // RoleModel.canAuthor = principal.isAuthor = super admin || template.update.
        val authors = RolePermissions.rolesHolding(Permission.TEMPLATE_UPDATE)
        val writes =
            listOf(
                Permission.SCHEDULE_CREATE,
                Permission.SCHEDULE_UPDATE,
                Permission.SCHEDULE_PAUSE,
                Permission.SCHEDULE_DELETE,
                Permission.SCHEDULE_RUN,
            )
        writes.forEach { permission ->
            withClue("${permission.wire}: the page shows its verb to canAuthor, so its roles must be canAuthor's") {
                RolePermissions.rolesHolding(permission) shouldBe authors
            }
        }
        // …and the read is every role's: the page itself opens for all of them.
        RolePermissions.rolesHolding(Permission.SCHEDULE_READ) shouldBe RolePermissions.rolesHolding(Permission.PIPELINE_READ)
    }

    @Test
    fun `the offered zones are region ids the scheduler accepts, grouped and sorted`() {
        val offered = ScheduleTimezones.GROUPS.flatMap { it.zones }
        offered.size shouldBeGreaterThan 300
        offered shouldContain "America/New_York"
        offered shouldContain "Europe/London"
        offered shouldContain "UTC"
        // Accepted over REST but not offered: the inverted-sign POSIX ids, SystemV, the aliases.
        offered shouldNotContain "Etc/GMT+5"
        offered shouldNotContain "SystemV/EST5"
        offered shouldNotContain "EST"
        offered shouldNotContain "Cuba"

        val refused = offered.filter { runCatching { OccurrenceFunction.zone(it) }.isFailure }
        withClue("every zone the form offers must be one the save accepts (OccurrenceFunction.zone)") { refused.shouldBeEmpty() }

        ScheduleTimezones.GROUPS.map { it.region } shouldBe ScheduleTimezones.GROUPS.map { it.region }.sorted()
        ScheduleTimezones.GROUPS.forEach { g -> g.zones shouldBe g.zones.sorted() }
        ScheduleTimezones.GROUPS.first { it.region == "America" }.zones shouldContain "America/Argentina/Buenos_Aires"
    }
}
