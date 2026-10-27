package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.RolePermissions
import co.datapipelines.scheduler.DeliveryOffReason
import co.datapipelines.scheduler.LastRunOutcome
import co.datapipelines.scheduler.NotificationDeliveryState
import co.datapipelines.scheduler.OccurrenceFunction
import co.datapipelines.scheduler.ScheduleService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.server.ResponseStatusException

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
    private val schedules = mockk<ScheduleService>()
    private val controller = SchedulesUiController(themeResolver, schedules)

    @Test
    fun `the page renders the schedules shell with the theme, the roles and the zone list`() {
        every { themeResolver.resolve(any()) } returns "dark"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.NO_ALLOWLIST)
        val model = ExtendedModelMap()

        controller.page(model, MockHttpServletRequest(), null, null) shouldBe "schedules/index"

        model["activeTheme"] shouldBe "dark"
        // No principal in this unit context: RoleModel's NONE — every verb boolean false.
        model["canAuthor"] shouldBe false
        model["timezoneGroups"] shouldBe ScheduleTimezones.GROUPS
        // #442: the deployment's COMPUTED mail state, reason included, for the form's note.
        model["notificationDelivery"] shouldBe mapOf("state" to "off", "reason" to "no_allowlist")
    }

    @Test
    fun `the page route declares schedule read - the row every member holds`() {
        val handler =
            SchedulesUiController::class.java.getMethod(
                "page",
                org.springframework.ui.Model::class.java,
                jakarta.servlet.http.HttpServletRequest::class.java,
                String::class.java,
                String::class.java,
            )
        handler.getAnnotation(RequiredScope::class.java).value shouldBe Permission.SCHEDULE_READ
        handler.getAnnotation(GetMapping::class.java).value.toList() shouldBe listOf("/schedules")
    }

    @ParameterizedTest
    @CsvSource("enabled", "paused", "blocked")
    fun `a condition deep link is bound at the page - the parsed word and the three options reach the model`(word: String) {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)
        val model = ExtendedModelMap()

        controller.page(model, MockHttpServletRequest(), word, null) shouldBe "schedules/index"

        model["condition"] shouldBe word
        (model["conditionOptions"] as List<*>).map { (it as ScheduleConditionFilter.Option).value } shouldBe
            listOf("enabled", "paused", "blocked")
        (model["conditionOptions"] as List<*>).map { (it as ScheduleConditionFilter.Option).label } shouldBe
            listOf("Enabled", "Paused", "Blocked")
    }

    @Test
    fun `no condition, or an empty one, is the whole list - no word reaches the model`() {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)
        listOf(null, "").forEach { raw ->
            val model = ExtendedModelMap()
            controller.page(model, MockHttpServletRequest(), raw, null)
            model["condition"] shouldBe ""
        }
    }

    @ParameterizedTest
    @CsvSource("nope", "BLOCKED", "Paused", "' blocked'", "blocked;x")
    fun `an unknown condition is the house 400 with a constant reason - nothing of the input rides it`(raw: String) {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)

        val refused = shouldThrow<ResponseStatusException> { controller.page(ExtendedModelMap(), MockHttpServletRequest(), raw, null) }

        refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        refused.reason shouldBe ScheduleConditionFilter.BAD_CONDITION
    }

    @ParameterizedTest
    @CsvSource("succeeded", "failed", "in-progress", "did-not-run", "never-ran")
    fun `an outcome deep link is bound at the page - the parsed word and the five options reach the model`(word: String) {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)
        val model = ExtendedModelMap()

        controller.page(model, MockHttpServletRequest(), null, word) shouldBe "schedules/index"

        model["outcome"] shouldBe word
        model["condition"] shouldBe ""
        (model["outcomeOptions"] as List<*>).map { (it as ScheduleConditionFilter.Option).value } shouldBe
            listOf("succeeded", "failed", "in-progress", "did-not-run", "never-ran")
        (model["outcomeOptions"] as List<*>).map { (it as ScheduleConditionFilter.Option).label } shouldBe
            listOf("Succeeded", "Failed", "In progress", "Did not run", "Never ran")
    }

    @Test
    fun `no outcome, or an empty one, is the whole list - no word reaches the model`() {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)
        listOf(null, "").forEach { raw ->
            val model = ExtendedModelMap()
            controller.page(model, MockHttpServletRequest(), null, raw)
            model["outcome"] shouldBe ""
        }
    }

    @ParameterizedTest
    @CsvSource("nope", "FAILED", "Failed", "' failed'", "in_progress", "never_ran", "blocked", "failed;x")
    fun `an unknown outcome is the house 400 with a constant reason - nothing of the input rides it`(raw: String) {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)

        val refused = shouldThrow<ResponseStatusException> { controller.page(ExtendedModelMap(), MockHttpServletRequest(), null, raw) }

        refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        refused.reason shouldBe ScheduleOutcomeFilter.BAD_OUTCOME
    }

    @Test
    fun `both deep links bind together - each parsed word reaches the model, and either bad word is its own 400`() {
        every { themeResolver.resolve(any()) } returns "saas"
        every { schedules.notificationDelivery() } returns NotificationDeliveryState(DeliveryOffReason.DISABLED)
        val model = ExtendedModelMap()

        controller.page(model, MockHttpServletRequest(), "blocked", "failed") shouldBe "schedules/index"

        model["condition"] shouldBe "blocked"
        model["outcome"] shouldBe "failed"
        shouldThrow<ResponseStatusException> { controller.page(ExtendedModelMap(), MockHttpServletRequest(), "blocked", "nope") }
            .reason shouldBe ScheduleOutcomeFilter.BAD_OUTCOME
        shouldThrow<ResponseStatusException> { controller.page(ExtendedModelMap(), MockHttpServletRequest(), "nope", "failed") }
            .reason shouldBe ScheduleConditionFilter.BAD_CONDITION
    }

    @Test
    fun `the home's Last run bars open the filtered explorer at the enum's own word`() {
        LastRunOutcome.entries.forEach { o ->
            ScheduleOutcomeFilter.href(o) shouldBe "/schedules?outcome=${o.wire}"
            ScheduleOutcomeFilter.parse(o.wire) shouldBe o
        }
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
