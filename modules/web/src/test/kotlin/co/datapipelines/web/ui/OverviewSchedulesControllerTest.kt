package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.scheduler.LastRunOutcome
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.scheduler.ScheduleSummary
import co.datapipelines.scheduler.ScheduleSummaryEntry
import co.datapipelines.scheduler.TargetViewer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * #584 — the Schedules home controller maps ONE [ScheduleService.summary] onto the shared frame:
 * four tiles and two DIFFERENT charts (By condition, Last run) from the same summary, Up next and
 * Recently changed linking the explorer's leaf, the viewer's lens passed through and named — and a
 * failed or over-cap read is unavailable with no number, never a zero.
 */
class OverviewSchedulesControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val service = mockk<ScheduleService>()
    private val controller = ScheduleOverviewController(service, mockk { every { resolve(any()) } returns "light" })

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `the summary becomes four tiles, the condition and last-run charts, and the two lists`() {
        authenticate(WorkspaceRole.WORKSPACE_ADMIN)
        val soon = Instant.now().plusSeconds(3 * 3600 + 60)
        every { service.summary(workspaceId, any()) } returns
            summary(
                upNext = listOf(entry(ID_A, "ops/nightly", "enabled", soon)),
                recentlyChanged = listOf(entry(ID_B, "ops/weekly", "paused", null)),
            )

        val model = ExtendedModelMap()
        controller.overview(model, MockHttpServletRequest()) shouldBe OverviewController.VIEW
        model["activeTheme"] shouldBe "light"
        model["canAuthor"] shouldBe true

        val page = model[OverviewController.MODEL] as OverviewPage
        page.family shouldBe "schedules"
        page.title shouldBe "Schedules"
        page.catalogHref shouldBe "/schedules"
        page.lensed shouldBe false
        page.guided shouldBe true
        val panel = page.inventory.shouldNotBeNull()
        panel.counts.map { it.key to it.value } shouldContainExactly
            listOf("schedules" to 4L, "enabled" to 2L, "paused" to 1L, "blocked" to 1L)
        panel.counts.map { it.href } shouldBe listOf(null, null, null, null)

        val (condition, lastRun) = panel.charts
        condition.key shouldBe "condition"
        condition.caption shouldBe "By condition"
        condition.bars.map { it.key to it.value } shouldContainExactly listOf("enabled" to 2L, "paused" to 1L, "blocked" to 1L)
        condition.bars.map { it.width } shouldContainExactly listOf("100.0", "50.0", "50.0")
        lastRun.key shouldBe "last-run"
        lastRun.caption shouldBe "Last run"
        lastRun.bars.map { it.key to it.value } shouldContainExactly
            listOf("succeeded" to 1L, "failed" to 1L, "in-progress" to 0L, "did-not-run" to 0L, "never-ran" to 2L)
        lastRun.bars.map { it.name } shouldContainExactly listOf("Succeeded", "Failed", "In progress", "Did not run", "Never ran")
        lastRun.bars.first().label shouldBe "Succeeded: 1 schedule"
        (condition.bars + lastRun.bars).map { it.href }.toSet() shouldBe setOf(null)

        val recent = page.recent.shouldNotBeNull()
        recent.review.shouldBeNull()
        val (upNext, changed) = recent.sections
        upNext.heading shouldBe "Up next"
        upNext.entries.shouldNotBeNull().single().let { row ->
            row.name shouldBe "ops/nightly"
            row.href shouldBe "/schedules?id=$ID_A"
            row.datetime shouldBe soon.toString()
            row.ago shouldBe "in 3 hours"
            row.hasDraft shouldBe false
        }
        changed.heading shouldBe "Recently changed"
        changed.entries.shouldNotBeNull().single().let { row ->
            row.href shouldBe "/schedules?id=$ID_B"
            row.datetime shouldBe AT.toString()
            row.ago shouldEndWith " ago"
        }
    }

    @Test
    fun `a promoter's page is lensed and the summary is asked through the narrowed viewer`() {
        authenticate(WorkspaceRole.PROMOTER)
        val viewer = slot<TargetViewer>()
        every { service.summary(workspaceId, capture(viewer)) } returns summary()

        val page = model()

        viewer.captured.narrowed shouldBe true
        page.lensed shouldBe true
    }

    @Test
    fun `a failed read is unavailable with no inventory and both lists say why`() {
        authenticate(WorkspaceRole.VIEWER)
        every { service.summary(workspaceId, any()) } throws IllegalStateException("database down")

        val page = model()

        page.inventory.shouldBeNull()
        page.unavailable shouldBe ScheduleOverviewController.UNAVAILABLE
        page.recent
            .shouldNotBeNull()
            .sections
            .map { it.entries to it.unavailable } shouldContainExactly
            listOf(null to "Up next could not be read.", null to "Recently changed could not be read.")
    }

    @Test
    fun `a workspace past its cap shows no short count`() {
        authenticate(WorkspaceRole.AUTHOR)
        every { service.summary(workspaceId, any()) } returns summary().copy(complete = false)

        val page = model()

        page.inventory.shouldBeNull()
        page.unavailable shouldBe ScheduleOverviewController.OVER_CAP
    }

    @Test
    fun `an empty workspace renders zero-width bars and the two empty notes`() {
        authenticate(WorkspaceRole.AUTHOR)
        every { service.summary(workspaceId, any()) } returns
            ScheduleSummary(true, 0, 0, 0, 0, LastRunOutcome.entries.associateWith { 0 }, emptyList(), emptyList())

        val page = model()

        val panel = page.inventory.shouldNotBeNull()
        panel.counts.map { it.value } shouldContainExactly listOf(0L, 0L, 0L, 0L)
        panel.charts.flatMap { chart -> chart.bars.map { it.width } }.toSet() shouldBe setOf("0")
        val sections = page.recent.shouldNotBeNull().sections
        sections.map { it.entries } shouldContainExactly listOf(emptyList(), emptyList())
        sections.map { it.emptyNote } shouldContainExactly listOf("No enabled schedule is waiting to run.", "No schedules yet.")
    }

    @Test
    fun `a next run already due reads due now, never expired`() {
        authenticate(WorkspaceRole.AUTHOR)
        every { service.summary(workspaceId, any()) } returns
            summary(upNext = listOf(entry(ID_A, "ops/late", "enabled", Instant.now().minusSeconds(5))))

        model()
            .recent
            .shouldNotBeNull()
            .sections
            .first()
            .entries
            .shouldNotBeNull()
            .single()
            .ago shouldBe "due now"
    }

    private fun model(): OverviewPage {
        val model = ExtendedModelMap()
        controller.overview(model, MockHttpServletRequest())
        return model[OverviewController.MODEL] as OverviewPage
    }

    private fun summary(
        upNext: List<ScheduleSummaryEntry> = emptyList(),
        recentlyChanged: List<ScheduleSummaryEntry> = emptyList(),
    ): ScheduleSummary =
        ScheduleSummary(
            complete = true,
            total = 4,
            enabled = 2,
            paused = 1,
            blocked = 1,
            lastRun =
                mapOf(
                    LastRunOutcome.SUCCEEDED to 1,
                    LastRunOutcome.FAILED to 1,
                    LastRunOutcome.IN_PROGRESS to 0,
                    LastRunOutcome.DID_NOT_RUN to 0,
                    LastRunOutcome.NEVER_RAN to 2,
                ),
            upNext = upNext,
            recentlyChanged = recentlyChanged,
        )

    private fun entry(
        id: UUID,
        name: String,
        condition: String,
        nextDueAt: Instant?,
    ): ScheduleSummaryEntry = ScheduleSummaryEntry(id, name, condition, nextDueAt, AT)

    private fun authenticate(role: WorkspaceRole) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", role),
                ),
                null,
            )
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-10-18T05:00:00Z")
        val ID_A: UUID = UUID.fromString("00000000-0000-0000-0000-0000000005a4")
        val ID_B: UUID = UUID.fromString("00000000-0000-0000-0000-0000000005b4")
    }
}
