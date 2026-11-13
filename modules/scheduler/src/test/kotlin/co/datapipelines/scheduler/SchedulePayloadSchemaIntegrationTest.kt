package co.datapipelines.scheduler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #786 D.2 (A33) — the stored `payload_schema_version` is READ, on a real PostgreSQL. The model is an upgraded
 * executor over rows saved before it: the schedule is created while the fake executor writes schema 1, then the
 * executor is raised to schema 2, so every stored row is a payload in a schema it no longer writes.
 *
 * - the save-side paths hand the executor the STORED schema: Run now (refused before any run is recorded), unblock
 *   (refused, the block stays) and a pin-unchanged edit's revalidation;
 * - the run side does too: a run admitted from such a row carries the stored schema into [Admission], and the
 *   executor's `prepare` receives it (the pipeline executor refuses it there, blocking — `PipelineJobExecutorTest`);
 * - re-saving the same JSON is a save in the executor's own schema: guarded, validated as schema 2, stored as 2.
 */
class SchedulePayloadSchemaIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
    }

    @Test
    fun `Run now on a stored schema the executor no longer writes is refused payload_schema_unsupported - no run is recorded`() {
        val schedule = h.create()
        h.executor.payloadSchemaVersion = 2

        val refused =
            shouldThrow<ScheduleException> { h.service.runNow(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR, null) }

        refused.code shouldBe ScheduleErrorCodes.PAYLOAD_SCHEMA_UNSUPPORTED
        refused.details shouldBe mapOf("stored" to 1, "supported" to 2)
        h.executor.validatedSchemas.last() shouldBe 1
        h.runsOf(schedule.id).shouldBeEmpty()
    }

    @Test
    fun `a run admitted from a stored schema-1 row carries schema 1 to prepare - the stored number, never the executor's`() {
        val schedule = h.create()
        h.executor.payloadSchemaVersion = 2
        h.setTime(schedule.nextDueAt!!.plusSeconds(5))
        h.tick()
        val run = h.runsOf(schedule.id).single()
        run.payloadSchemaVersion shouldBe 1

        h.executor.refuseAtPrepare = Preparation.Refused(ScheduleErrorCodes.PAYLOAD_SCHEMA_UNSUPPORTED, block = true, "schema 1")
        h.worker.handle(run.id)

        h.executor.preparedSchemas shouldBe listOf(1)
        h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.blocked shouldBe true
    }

    @Test
    fun `unblock revalidates in the stored schema - refused, the block stays`() {
        val schedule = h.create()
        h.schedules.block(schedule.id, "payload_schema_unsupported", UUID.randomUUID(), h.clock.instant())
        h.executor.payloadSchemaVersion = 2

        shouldThrow<ScheduleException> {
            h.service.unblock(SchedulerTestDb.WORKSPACE, schedule.id, SchedulerTestDb.CREATOR)
        }.code shouldBe ScheduleErrorCodes.PAYLOAD_SCHEMA_UNSUPPORTED
        h.executor.validatedSchemas.last() shouldBe 1
        h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.blocked shouldBe true
    }

    @Test
    fun `re-saving the same JSON is a save in the executor's schema - validated as 2 and stored as 2, never refused as stale`() {
        val schedule = h.create()
        h.executor.payloadSchemaVersion = 2

        val saved =
            h.service.update(
                SchedulerTestDb.WORKSPACE,
                schedule.id,
                SchedulerTestDb.CREATOR,
                schedule.revision,
                h.request(name = "reports/renamed"),
            )

        h.executor.validatedSchemas.last() shouldBe 2
        saved.payloadSchemaVersion shouldBe 2
        h.schedules.findLive(SchedulerTestDb.WORKSPACE, schedule.id)!!.payloadSchemaVersion shouldBe 2
    }

    @Test
    fun `a pin-unchanged edit of a row in the executor's own schema stays on that schema`() {
        val schedule = h.create()

        val saved =
            h.service.update(
                SchedulerTestDb.WORKSPACE,
                schedule.id,
                SchedulerTestDb.CREATOR,
                schedule.revision,
                h.request(name = "reports/renamed"),
            )

        h.executor.validatedSchemas.last() shouldBe 1
        saved.payloadSchemaVersion shouldBe 1
    }
}
