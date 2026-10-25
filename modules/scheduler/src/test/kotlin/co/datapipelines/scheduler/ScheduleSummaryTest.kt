package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

/**
 * #584 — [ScheduleService.summary] and its one bucket mapping, over mocked repositories: the counts
 * are [ScheduleService.list]'s admitted set at the workspace cap + 1 (never `countLive`), every
 * [RunState] sits in exactly one [LastRunOutcome], Up next is the dispatcher's predicate (a paused
 * schedule's stale `next_due_at` never qualifies), and the listed rows are a projection without
 * recipients, payload or block reason.
 */
class ScheduleSummaryTest {
    private val schedules = mockk<ScheduleRepository>()
    private val runs = mockk<ScheduleRunRepository>()
    private val executor = FakeExecutor()
    private val properties = SchedulerProperties(maxSchedulesPerWorkspace = CAP)
    private val service =
        ScheduleService(
            schedules,
            runs,
            JobExecutors(listOf(executor)),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.fixed(NOW, ZoneOffset.UTC),
            properties,
            mockk(relaxed = true),
            SchedulerAutoConfiguration.JSON,
            mockk(relaxed = true),
            NotificationPolicy(properties.notifications, NotificationDeployment.UNKNOWN),
            mockk(relaxed = true),
        ) { UUID.randomUUID() }

    @Test
    fun `every run state sits in exactly one bucket and no run at all is never ran`() {
        RunState.entries.forEach { state ->
            withClue("run state ${state.wire}") {
                LastRunOutcome.entries.count { state in it.states } shouldBe 1
                (state in LastRunOutcome.of(state).states) shouldBe true
            }
        }
        LastRunOutcome.of(null) shouldBe LastRunOutcome.NEVER_RAN
        LastRunOutcome.NEVER_RAN.states shouldBe emptySet()
        // The mapping the page and the docs name (ui-screens §4.20a) — read here, not re-derived.
        LastRunOutcome.FAILED.states shouldBe setOf(RunState.FAILED, RunState.ABORTED, RunState.UNKNOWN)
        LastRunOutcome.IN_PROGRESS.states shouldBe setOf(RunState.QUEUED, RunState.STARTING, RunState.RUNNING)
        LastRunOutcome.DID_NOT_RUN.states shouldBe setOf(RunState.NOT_STARTED, RunState.SKIPPED, RunState.CANCELLED)
    }

    @Test
    fun `the counts are the listed set at the cap plus one and countLive is never read`() {
        val enabledOk = schedule("ops/a")
        val paused = schedule("ops/b", enabled = false)
        val blocked = schedule("ops/c", blockedAt = NOW)
        val fresh = schedule("ops/d")
        every { schedules.listLive(WS, null, CAP + 1, 0) } returns listOf(enabledOk, paused, blocked, fresh)
        every { runs.latestStates(WS, listOf(enabledOk.id, paused.id, blocked.id, fresh.id)) } returns
            mapOf(enabledOk.id to RunState.SUCCEEDED, paused.id to RunState.FAILED)

        val summary = service.summary(WS, TargetViewer.EVERYONE)

        summary.complete shouldBe true
        listOf(summary.total, summary.enabled, summary.paused, summary.blocked) shouldBe listOf(4, 2, 1, 1)
        summary.lastRun shouldBe
            mapOf(
                LastRunOutcome.SUCCEEDED to 1,
                LastRunOutcome.FAILED to 1,
                LastRunOutcome.IN_PROGRESS to 0,
                LastRunOutcome.DID_NOT_RUN to 0,
                LastRunOutcome.NEVER_RAN to 2,
            )
        summary.lastRun.keys.toList() shouldContainExactly LastRunOutcome.entries
        verify(exactly = 0) { schedules.countLive(any()) }
    }

    @Test
    fun `a narrowed viewer's counts are the admitted schedules only`() {
        val shown = schedule("ops/a", target = "job:a")
        val hidden = schedule("ops/b", target = "job:b")
        executor.lens = setOf("job:a")
        every { schedules.listLiveAfter(WS, null, null, ScheduleService.LENS_WINDOW) } returns listOf(shown, hidden)
        every { runs.latestStates(WS, listOf(shown.id)) } returns emptyMap()

        val summary = service.summary(WS, NarrowedViewer)

        summary.total shouldBe 1
        summary.recentlyChanged.map { it.name } shouldContainExactly listOf("ops/a")
        summary.upNext.map { it.name } shouldContainExactly listOf("ops/a")
        verify(exactly = 0) { schedules.countLive(any()) }
    }

    @Test
    fun `up next is enabled and unblocked by next run - a paused schedule's stale next_due_at is not`() {
        val soon = schedule("ops/soon", nextDueAt = NOW.plusSeconds(60))
        val later = schedule("ops/later", nextDueAt = NOW.plusSeconds(3600))
        val stalePaused = schedule("ops/paused", enabled = false, nextDueAt = NOW.minusSeconds(60))
        val blocked = schedule("ops/blocked", blockedAt = NOW, nextDueAt = NOW.plusSeconds(30))
        val noNext = schedule("ops/never", nextDueAt = null)
        val many = (1..6).map { schedule("bulk/s$it", nextDueAt = NOW.plusSeconds(7200L + it)) }
        val all = listOf(later, stalePaused, soon, blocked, noNext) + many
        every { schedules.listLive(WS, null, CAP + 1, 0) } returns all
        every { runs.latestStates(WS, any()) } returns emptyMap()

        val summary = service.summary(WS, TargetViewer.EVERYONE)

        summary.upNext.map { it.name } shouldContainExactly listOf("ops/soon", "ops/later", "bulk/s1", "bulk/s2", "bulk/s3")
        summary.upNext.first().nextDueAt shouldBe NOW.plusSeconds(60)
    }

    @Test
    fun `recently changed is newest edit first, five at most, name breaking a tie`() {
        val rows =
            (1..7).map { schedule("ops/s$it", updatedAt = NOW.minusSeconds(it * 60L)) } +
                schedule("ops/a-tie", updatedAt = NOW.minusSeconds(60))
        every { schedules.listLive(WS, null, CAP + 1, 0) } returns rows
        every { runs.latestStates(WS, any()) } returns emptyMap()

        service.summary(WS, TargetViewer.EVERYONE).recentlyChanged.map { it.name } shouldContainExactly
            listOf("ops/a-tie", "ops/s1", "ops/s2", "ops/s3", "ops/s4")
    }

    @Test
    fun `a workspace past its cap is incomplete and counts no more than the cap`() {
        val rows = (1..CAP + 1).map { schedule("ops/s$it") }
        every { schedules.listLive(WS, null, CAP + 1, 0) } returns rows
        every { runs.latestStates(WS, rows.take(CAP).map { it.id }) } returns emptyMap()

        val summary = service.summary(WS, TargetViewer.EVERYONE)

        summary.complete shouldBe false
        summary.total shouldBe CAP
    }

    @Test
    fun `an empty workspace answers zeros with every bucket present and reads no runs`() {
        every { schedules.listLive(WS, null, CAP + 1, 0) } returns emptyList()
        every { runs.latestStates(WS, emptyList()) } returns emptyMap()

        val summary = service.summary(WS, TargetViewer.EVERYONE)

        listOf(summary.total, summary.enabled, summary.paused, summary.blocked) shouldBe listOf(0, 0, 0, 0)
        summary.lastRun.values.toList() shouldBe List(LastRunOutcome.entries.size) { 0 }
        summary.upNext shouldBe emptyList()
        summary.recentlyChanged shouldBe emptyList()
    }

    /** The listed row is a projection: what a row shows and links — no recipient, payload or block reason (auth.md §7.6). */
    @Test
    fun `a listed row carries no recipient address, payload or block reason`() {
        ScheduleSummaryEntry::class.primaryConstructor!!.parameters.map { it.name } shouldContainExactly
            listOf("id", "name", "condition", "nextDueAt", "updatedAt")
        val withSecrets =
            schedule("ops/a", blockedAt = NOW).copy(
                notificationRecipients = listOf("ops@example.com"),
                blockedReason = "pipeline error: relation secret_table",
            )
        ScheduleSummaryEntry.of(withSecrets).toString().let { text ->
            listOf("ops@example.com", "secret_table").forEach { (it in text) shouldBe false }
        }
    }

    private fun schedule(
        name: String,
        enabled: Boolean = true,
        blockedAt: Instant? = null,
        nextDueAt: Instant? = NOW.plusSeconds(600),
        updatedAt: Instant = NOW.minusSeconds(600),
        target: String = "job:a",
    ): Schedule =
        Schedule(
            id = UUID.randomUUID(),
            workspaceId = WS,
            name = name,
            revision = 1,
            executorId = FakeExecutor.ID,
            payloadSchemaVersion = 1,
            payload = FakeExecutor.payload("a"),
            parameters = JsonNodeFactory.instance.objectNode(),
            targetRef = target,
            cron = "0 * * * *",
            timezone = "UTC",
            missedRunPolicy = MissedRunPolicy.SKIP,
            enabled = enabled,
            blockedReason = blockedAt?.let { "blocked" },
            blockedAt = blockedAt,
            blockedRunId = null,
            nextDueAt = nextDueAt,
            createdBy = USER,
            updatedBy = USER,
            createdAt = NOW.minusSeconds(86_400),
            updatedAt = updatedAt,
            deletedAt = null,
        )

    private companion object {
        const val CAP = 10
        val WS: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000584")
        val USER: UUID = UUID.fromString("c4ea0000-0000-0000-0000-000000000584")
        val NOW: Instant = Instant.parse("2026-10-19T02:00:00Z")
    }
}
