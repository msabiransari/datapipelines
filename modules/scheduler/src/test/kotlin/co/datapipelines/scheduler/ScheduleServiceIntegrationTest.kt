package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * **The management use cases** (scheduler design revision §6): validation at save, durable
 * idempotency (L1), the revision guard, rename without losing history, soft delete, the B18 guards
 * (L4), unblock's revalidation, workspace isolation (R9) and the lens (R8).
 */
class ScheduleServiceIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
    }

    @Test
    fun `create validates name, cron, zone, executor, payload shape and target - each with its own code`() {
        fun refused(request: ScheduleRequest) = shouldThrow<ScheduleException> { h.create(request) }.code

        refused(h.request(name = "flat")) shouldBe ScheduleErrorCodes.NAME_INVALID
        refused(h.request(name = "Bad/Name")) shouldBe ScheduleErrorCodes.NAME_INVALID
        refused(h.request(cron = "0 30 2 * * *")) shouldBe ScheduleErrorCodes.CRON_INVALID
        refused(h.request(timezone = "+02:00")) shouldBe ScheduleErrorCodes.TIMEZONE_INVALID
        refused(h.request().copy(executor = "cron-shell")) shouldBe ScheduleErrorCodes.EXECUTOR_UNKNOWN
        refused(h.request(payload = JsonNodeFactory.instance.arrayNode())) shouldBe ScheduleErrorCodes.PAYLOAD_INVALID
        refused(h.request(payload = FakeExecutor.payload("missing"))) shouldBe ScheduleErrorCodes.TARGET_NOT_FOUND
        refused(h.request(policy = "run_now")) shouldBe ScheduleErrorCodes.REQUEST_INVALID
    }

    @Test
    fun `the generic payload limits hold whatever the executor - 16 KiB and depth 8`() {
        val big =
            JsonNodeFactory.instance
                .objectNode()
                .put("job", "a")
                .put("pad", "x".repeat(ScheduleService.MAX_PAYLOAD_BYTES))
        shouldThrow<ScheduleException> { h.create(h.request(payload = big)) }.code shouldBe ScheduleErrorCodes.PAYLOAD_INVALID
        var deep = JsonNodeFactory.instance.objectNode().put("job", "a")
        repeat(ScheduleService.MAX_PAYLOAD_DEPTH) {
            deep =
                JsonNodeFactory.instance
                    .objectNode()
                    .put("job", "a")
                    .set("n", deep)
        }
        shouldThrow<ScheduleException> { h.create(h.request(payload = deep)) }.code shouldBe ScheduleErrorCodes.PAYLOAD_INVALID
    }

    @Test
    fun `the B18 guards - a too-frequent cron and the per-workspace cap (L4)`() {
        val strict = SchedulerHarness(properties = SchedulerProperties(minIntervalSeconds = 300, maxSchedulesPerWorkspace = 2))
        shouldThrow<ScheduleException> { strict.create(strict.request(cron = "* * * * *")) }.code shouldBe
            ScheduleErrorCodes.INTERVAL_TOO_SHORT
        strict.create(strict.request(name = "a/one", cron = "*/5 * * * *"))
        strict.create(strict.request(name = "a/two"))
        shouldThrow<ScheduleException> { strict.create(strict.request(name = "a/three")) }.code shouldBe
            ScheduleErrorCodes.LIMIT_PER_WORKSPACE
    }

    @Test
    fun `a live name is unique per workspace - a deleted one frees it, another workspace may reuse it`() {
        val first = h.create()
        shouldThrow<ScheduleException> { h.create() }.code shouldBe ScheduleErrorCodes.NAME_TAKEN
        h.service
            .create(SchedulerTestDb.OTHER_WORKSPACE, SchedulerTestDb.CREATOR, h.request(), null)
            .value.name shouldBe first.name
        h.service.delete(SchedulerTestDb.WORKSPACE, first.id, SchedulerTestDb.CREATOR, first.revision)
        h.create().id shouldNotBe first.id
    }

    @Test
    fun `create is idempotent under one key - a replay answers the original, a different request is refused (L1)`() {
        val a = h.service.create(SchedulerTestDb.WORKSPACE, SchedulerTestDb.CREATOR, h.request(), "k-1")
        val replay = h.service.create(SchedulerTestDb.WORKSPACE, SchedulerTestDb.CREATOR, h.request(), "k-1")

        a.replayed shouldBe false
        replay.replayed shouldBe true
        replay.value.id shouldBe a.value.id
        shouldThrow<ScheduleException> {
            h.service.create(SchedulerTestDb.WORKSPACE, SchedulerTestDb.CREATOR, h.request(cron = "30 * * * *"), "k-1")
        }.code shouldBe ScheduleErrorCodes.IDEMPOTENCY_KEY_REUSED
        h.service.list(SchedulerTestDb.WORKSPACE, null, 50, 0, TargetViewer.EVERYONE) shouldHaveSize 1
    }

    @Test
    fun `Run now is idempotent under one key - one run, and it is durable in Postgres, not Redis (L1)`() {
        val schedule = h.create()
        val first = h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, "run-1")
        val replay = h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, "run-1")

        replay.replayed shouldBe true
        replay.value.id shouldBe first.value.id
        h.runsOf(schedule.id) shouldHaveSize 1
        first.value.origin shouldBe RunOrigin.MANUAL
        first.value.scheduledAt shouldBe null
        h.runTasks() shouldContainExactly listOf(first.value.id.toString())
        SchedulerTestDb.jdbc.queryForObject(
            "SELECT idempotency_key FROM schedule_runs WHERE id = :id",
            mapOf("id" to first.value.id),
            String::class.java,
        ) shouldBe "run-1"
    }

    @Test
    fun `edits carry the revision - a stale one is a conflict, rename keeps the id and the runs`() {
        val schedule = h.create()
        h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null)
        val renamed =
            h.service.update(
                SchedulerTestDb.WORKSPACE,
                schedule.id,
                SchedulerTestDb.CREATOR,
                1,
                h.request(name = "finance/daily"),
            )

        renamed.id shouldBe schedule.id
        renamed.name shouldBe "finance/daily"
        renamed.revision shouldBe 2
        h.runsOf(schedule.id) shouldHaveSize 1
        shouldThrow<ScheduleException> {
            h.service.update(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, 1, h.request(name = "finance/other"))
        }.code shouldBe ScheduleErrorCodes.REVISION_CONFLICT
        shouldThrow<ScheduleException> {
            h.service.delete(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, 1)
        }.code shouldBe ScheduleErrorCodes.REVISION_CONFLICT
    }

    @Test
    fun `isolation is per workspace (R9) - another workspace's schedule is absent`() {
        val schedule = h.create()
        shouldThrow<ScheduleException> {
            h.service.get(SchedulerTestDb.OTHER_WORKSPACE, schedule.id, TargetViewer.EVERYONE)
        }.code shouldBe ScheduleErrorCodes.NOT_FOUND
        shouldThrow<ScheduleException> {
            h.service.runNow(SchedulerTestDb.OTHER_WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null)
        }.code shouldBe ScheduleErrorCodes.NOT_FOUND
        h.service.list(SchedulerTestDb.OTHER_WORKSPACE, null, 50, 0, TargetViewer.EVERYONE) shouldHaveSize 0
    }

    @Test
    fun `the lens narrows reads to admitted targets - a hidden schedule answers as absent (R8)`() {
        val a = h.create(h.request(name = "ops/a", payload = FakeExecutor.payload("a")))
        val b = h.create(h.request(name = "ops/b", payload = FakeExecutor.payload("b")))
        h.executor.lens = setOf("job:a")

        h.service.list(SchedulerTestDb.WORKSPACE, null, 50, 0, NarrowedViewer).map { it.id } shouldContainExactly listOf(a.id)
        shouldThrow<ScheduleException> { h.service.get(SchedulerTestDb.WORKSPACE, b.id, NarrowedViewer) }.code shouldBe
            ScheduleErrorCodes.NOT_FOUND
        h.service.list(SchedulerTestDb.WORKSPACE, null, 50, 0, TargetViewer.EVERYONE) shouldHaveSize 2
    }

    @Test
    fun `list narrows to a folder prefix and refuses a malformed one`() {
        h.create(h.request(name = "finance/daily/revenue"))
        h.create(h.request(name = "finance_x/daily"))
        h.create(h.request(name = "ops/nightly"))
        h.service.list(SchedulerTestDb.WORKSPACE, "finance", 50, 0, TargetViewer.EVERYONE).map { it.name } shouldContainExactly
            listOf("finance/daily/revenue")
        shouldThrow<ScheduleException> { h.service.list(SchedulerTestDb.WORKSPACE, "../x", 50, 0, TargetViewer.EVERYONE) }.code shouldBe
            ScheduleErrorCodes.REQUEST_INVALID
    }

    @Test
    fun `unblock revalidates, clears the block, names the person on the causing run's trail, and recomputes from now`() {
        val schedule = h.create()
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        h.executor.refuseAtPrepare = Preparation.Refused("pointer_null", block = true, "no current version")
        val run = h.runsOf(schedule.id).single()
        h.worker.handle(run.id)
        h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.blocked shouldBe true
        shouldThrow<ScheduleException> {
            h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null)
        }.code shouldBe ScheduleErrorCodes.BLOCKED
        // Resume never clears a block.
        h.service.resume(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR).blocked shouldBe true

        h.setTime(h.clock.instant().plus(Duration.ofHours(5)))
        val unblocked = h.service.unblock(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)

        unblocked.blocked shouldBe false
        unblocked.nextDueAt!!.isAfter(h.clock.instant()) shouldBe true
        h.runs
            .trail(run.id)
            .last()
            .kind shouldBe TrailKind.UNBLOCKED
        h.runs
            .trail(run.id)
            .last()
            .details
            .path("unblocked_by")
            .asText() shouldBe SchedulerTestDb.CREATOR.toString()
        h.tick().total shouldBe 0 // the five blocked hours are not missed
        shouldThrow<ScheduleException> {
            h.service.unblock(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        }.code shouldBe ScheduleErrorCodes.NOT_BLOCKED
    }

    @Test
    fun `unblock refuses while the payload still does not validate - the block stays`() {
        val schedule = h.create(h.request(payload = FakeExecutor.payload("will-vanish")))
        h.schedules.block(schedule.id, "target_not_found", java.util.UUID.randomUUID(), h.clock.instant())
        SchedulerTestDb.jdbc.update(
            "UPDATE schedules SET payload_json = '{\"job\": \"missing\"}'::jsonb WHERE id = :id",
            mapOf("id" to schedule.id),
        )
        shouldThrow<ScheduleException> {
            h.service.unblock(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        }.code shouldBe ScheduleErrorCodes.TARGET_NOT_FOUND
        h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.blocked shouldBe true
    }

    @Test
    fun `preview and upcoming share the ONE function and bound the count`() {
        val schedule = h.create(h.request(cron = "0 9 * * *", timezone = "America/New_York"))
        val upcoming = h.service.upcoming(SchedulerTestDb.WORKSPACE, schedule.id, 3, TargetViewer.EVERYONE)
        val preview = h.service.preview("0 9 * * *", "America/New_York", 3)
        upcoming shouldBe preview
        upcoming.first().at shouldBe schedule.nextDueAt
        shouldThrow<ScheduleException> { h.service.preview("0 9 * * *", "UTC", 0) }.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
        shouldThrow<ScheduleException> { h.service.preview("0 9 * * *", "UTC", 21) }.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
    }

    @Test
    fun `a run and its trail are read inside the schedule's workspace only`() {
        val schedule = h.create()
        val run = h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null).value
        h.service
            .run(SchedulerTestDb.WORKSPACE, schedule.id, run.id, TargetViewer.EVERYONE)
            .trail
            .map { it.kind } shouldContainExactly
            listOf(TrailKind.RECORDED)
        shouldThrow<ScheduleException> {
            h.service.run(SchedulerTestDb.WORKSPACE, schedule.id, java.util.UUID.randomUUID(), TargetViewer.EVERYONE)
        }.code shouldBe ScheduleErrorCodes.RUN_NOT_FOUND
    }

    @Test
    fun `pause and resume are idempotent - no revision churn on a repeat`() {
        val schedule = h.create()
        val paused = h.service.pause(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        h.service.pause(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR).revision shouldBe paused.revision
        paused.condition shouldBe "paused"
        val resumed = h.service.resume(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        h.service.resume(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR).revision shouldBe resumed.revision
        resumed.condition shouldBe "enabled"
    }
}
