package co.datapipelines.web.schedules

import ch.qos.logback.classic.Logger
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.ScheduleRun
import co.datapipelines.scheduler.ScheduleRunRepository
import co.datapipelines.web.capturingLogEvents
import co.datapipelines.web.endpoints.StartupConversionConflict
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * #786 round 7, R1 — the startup conversion of a schedule's QUEUED runs, with the production [ScheduleVersionConversion]
 * over recording repositories and a transaction manager that records commit and rollback. The contract under test: a
 * queued run converts from ITS OWN frozen payload — its own pipeline's CURRENT, never the live schedule's resolved
 * version; the precondition covers every run target (named `kind=run`, separately from schedules) before any write;
 * the step runs inside the startup runner's ONE transaction (#895, driven here as the runner drives it: E.0, then plan
 * and convert in one transaction, then the commit-time effects), so a run target that fails under the guard on a LATER
 * schedule rolls back every earlier schedule and run with it, and a lost CAS is a conflict the step does NOT retry
 * itself — the runner's retry of the whole joint transaction is `ConversionRollbackBoundaryE2eTest`'s (B.6). The
 * real-database legs (the A-to-B edit at a real upgrade boot, the refused boot, the rollback race, the multi-schedule
 * rollback, the rerun) are `ScheduleVersionConversionE2eTest`.
 */
class ScheduleVersionConversionTest {
    private val mapper = ObjectMapper()
    private val workspace = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val runId = UUID.randomUUID()
    private val schedules = mockk<ScheduleRepository>()
    private val runs = mockk<ScheduleRunRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val transactions = RecordingTransactions()
    private val schedulePayloads = mutableListOf<JsonNode>()
    private val runPayloads = mutableListOf<JsonNode>()

    private val conversion = ScheduleVersionConversion(schedules, runs, pipelines)

    /** The live schedule names [schedulePipeline]; its one queued run's payload copy still names [runPipeline]. */
    private fun fixture(
        schedulePipeline: String,
        runPipeline: String,
    ) {
        val schedule = schedule(schedulePipeline)
        every { schedules.listLiveAtSchema(PipelineJobExecutor.EXECUTOR_ID, 1) } returns listOf(schedule)
        every { schedules.findAny(scheduleId) } returns schedule
        every { schedules.convertPayload(scheduleId, REVISION, 1, capture(schedulePayloads), 2) } returns true
        every { runs.listQueuedAtSchema(scheduleId, 1) } returns listOf(run(runPipeline))
        every { runs.convertQueuedPayload(runId, 1, capture(runPayloads), 2) } returns true
    }

    private fun pipeline(
        name: String,
        current: Int?,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ): UUID {
        val id = UUID.randomUUID()
        val record = mockk<PipelineRecord> { every { this@mockk.id } returns id }
        every { pipelines.findByNameAnyStatus(workspace, name) } returns record
        every { pipelines.findCurrentVersionDetail(workspace, id) } returns
            current?.let {
                mockk<PipelineVersionDetail> {
                    every { version } returns it
                    every { this@mockk.status } returns status
                }
            }
        every { pipelines.listVersions(workspace, id) } returns emptyList()
        return id
    }

    @Test
    fun `a queued run of the schedule's own pipeline converts to that pipeline's CURRENT`() {
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "new/pipeline")

        runOnce()

        runPayloads.single() shouldBe mapper.readTree(RUN_PAYLOAD.format("new/pipeline", "5"))
    }

    @Test
    fun `a queued run whose copy names the schedule's OLD pipeline converts to the old pipeline's CURRENT, never the schedule's`() {
        // The schedule was edited from old/pipeline (CURRENT 2) to new/pipeline (CURRENT 5) after the run was queued.
        pipeline("old/pipeline", current = 2)
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "old/pipeline")

        val report = runOnce()

        schedulePayloads.single()["version"].asInt() shouldBe 5
        // Only the run's `version` selector changes: its pipeline, its bindings and every other member stay its own.
        runPayloads.single() shouldBe mapper.readTree(RUN_PAYLOAD.format("old/pipeline", "2"))
        report.converted.single().pipelineVersion shouldBe 5
        report.converted.single().queuedRuns shouldBe 1
        transactions.commits shouldBe 1
        transactions.rollbacks shouldBe 0
    }

    @Test
    fun `the precondition names a queued run whose own pipeline is missing or has no CURRENT, separately, and nothing is written`() {
        pipeline("new/pipeline", current = 5)
        val noCurrentId = pipeline("old/no_current", current = null)
        every { pipelines.listVersions(workspace, noCurrentId) } returns listOf(mockk())
        val schedule = schedule("new/pipeline")
        val missing = run("old/gone")
        val noCurrent = run("old/no_current", id = UUID.randomUUID())
        every { schedules.listLiveAtSchema(PipelineJobExecutor.EXECUTOR_ID, 1) } returns listOf(schedule)
        every { runs.listQueuedAtSchema(scheduleId, 1) } returns listOf(missing, noCurrent)
        every { pipelines.findByNameAnyStatus(workspace, "old/gone") } returns null

        val refusals = conversion.precondition()

        // Both run targets refuse, each under its own pipeline; the schedule's own target (new/pipeline) is fine.
        refusals.map { Triple(it.scheduleId, it.pipeline, it.reason) } shouldContainExactly
            listOf(
                Triple(scheduleId, "old/gone", ScheduleConversionRefusal.Reason.MISSING_PIPELINE),
                Triple(scheduleId, "old/no_current", ScheduleConversionRefusal.Reason.NO_CURRENT),
            )
        // E.0 as the runner reads it: each refusal logged with its own structured line; the runner refuses the start.
        val lines = captured { conversion.logPrecondition() shouldBe 2 }
        lines shouldContainAll
            listOf(
                "event=scheduler.conversion.refused kind=run id=${missing.id} schedule=$scheduleId name=$NAME pipeline=old/gone " +
                    "reason=missing_pipeline",
                "event=scheduler.conversion.refused kind=run id=${noCurrent.id} schedule=$scheduleId name=$NAME " +
                    "pipeline=old/no_current reason=no_current",
            )
        verify(exactly = 0) { schedules.convertPayload(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { runs.convertQueuedPayload(any(), any(), any(), any()) }
        transactions.commits shouldBe 0
    }

    @Test
    fun `a run target that lost its CURRENT under the guard refuses after the schedule's write and the transaction rolls back`() {
        pipeline("new/pipeline", current = 5)
        val old = pipeline("old/pipeline", current = 2)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "old/pipeline")
        val two =
            mockk<PipelineVersionDetail> {
                every { version } returns 2
                every { status } returns PipelineVersionStatus.RELEASED
            }
        // The precondition reads CURRENT 2; the guarded re-read inside the transaction finds it gone.
        every { pipelines.findCurrentVersionDetail(workspace, old) } returnsMany listOf(two, null)

        val refused = shouldThrow<ScheduleConversionRefusedException> { runOnce() }

        refused.refusals.single().pipeline shouldBe "old/pipeline"
        refused.refusals.single().reason shouldBe ScheduleConversionRefusal.Reason.NO_VERSIONS
        refused.message shouldContain "$NAME queued run $runId -> old/pipeline: no_versions"
        schedulePayloads shouldHaveSize 1 // the schedule's write happened inside the transaction ...
        transactions.rollbacks shouldBe 1 // ... and was rolled back with it
        transactions.commits shouldBe 0
        verify(exactly = 0) { runs.convertQueuedPayload(any(), any(), any(), any()) }
    }

    @Test
    fun `a refusal on the SECOND schedule rolls back the first schedule and its run in the same one transaction`() {
        pipeline("first/pipeline", current = 3)
        val second = pipeline("second/pipeline", current = 7)
        val seven =
            mockk<PipelineVersionDetail> {
                every { version } returns 7
                every { status } returns PipelineVersionStatus.RELEASED
            }
        val secondId = UUID.randomUUID()
        val firstSchedule = schedule("first/pipeline", name = "a_first")
        val secondSchedule = schedule("second/pipeline", id = secondId, name = "z_second")
        val firstRun = run("first/pipeline")
        val secondRun = run("second/pipeline", id = UUID.randomUUID())
        every { schedules.listLiveAtSchema(PipelineJobExecutor.EXECUTOR_ID, 1) } returns listOf(firstSchedule, secondSchedule)
        // By-id re-reads (the per-schedule shape round 8 replaced): stubbed so a regression fails on the assertions.
        every { schedules.findAny(scheduleId) } returns firstSchedule
        every { schedules.findAny(secondId) } returns secondSchedule
        every { schedules.convertPayload(any(), REVISION, 1, capture(schedulePayloads), 2) } returns true
        every { runs.listQueuedAtSchema(scheduleId, 1) } returns listOf(firstRun)
        every { runs.listQueuedAtSchema(secondId, 1) } returns listOf(secondRun)
        every { runs.convertQueuedPayload(any(), 1, capture(runPayloads), 2) } returns true
        // The precondition reads the second pipeline's CURRENT 7 twice (the schedule, then its run); the guarded re-read,
        // after the first pair's writes, finds it gone.
        every { pipelines.findCurrentVersionDetail(workspace, second) } returnsMany listOf(seven, seven, null)

        val lines = captured { shouldThrow<ScheduleConversionRefusedException> { runOnce() } }

        verify(exactly = 1) { schedules.convertPayload(scheduleId, REVISION, 1, any(), 2) }
        verify(exactly = 1) { runs.convertQueuedPayload(runId, 1, any(), 2) }
        verify(exactly = 0) { schedules.convertPayload(secondId, any(), any(), any(), any()) }
        withClue("the first pair was written inside the ONE transaction, which rolled back; nothing committed") {
            transactions.rollbacks shouldBe 1
            transactions.commits shouldBe 0
        }
        withClue("no converted line is logged for a conversion that rolled back") {
            lines.none { it.startsWith("event=scheduler.conversion.converted") } shouldBe true
        }
    }

    @Test
    fun `a lost CAS is a conflict the step does not retry itself, and the transaction rolls back with no converted line`() {
        pipeline("first/pipeline", current = 3)
        pipeline("second/pipeline", current = 7)
        val secondId = UUID.randomUUID()
        val firstSchedule = schedule("first/pipeline", name = "a_first")
        val secondSchedule = schedule("second/pipeline", id = secondId, name = "z_second")
        every { schedules.listLiveAtSchema(PipelineJobExecutor.EXECUTOR_ID, 1) } returns listOf(firstSchedule, secondSchedule)
        every { schedules.convertPayload(scheduleId, REVISION, 1, any(), 2) } returns true
        // The second schedule's CAS loses to a concurrent edit; a step-local retry would win on the second call.
        every { schedules.convertPayload(secondId, REVISION, 1, any(), 2) } returnsMany listOf(false, true)
        every { runs.listQueuedAtSchema(any(), 1) } returns emptyList()

        // Moved, not dropped (#895 B.5/B.6): the whole-transaction retry is the runner's, asserted on a real database in
        // ConversionRollbackBoundaryE2eTest; here the step hands the conflict up after ONE try.
        val lines = captured { shouldThrow<StartupConversionConflict> { runOnce() } }

        verify(exactly = 1) { schedules.convertPayload(scheduleId, REVISION, 1, any(), 2) }
        verify(exactly = 1) { schedules.convertPayload(secondId, REVISION, 1, any(), 2) }
        transactions.rollbacks shouldBe 1
        transactions.commits shouldBe 0
        lines.none { it.startsWith("event=scheduler.conversion.") } shouldBe true
    }

    @Test
    fun `convert reports nothing itself - the converted lines and the summary are published only by the outcome`() {
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "new/pipeline")
        val attempt = conversion.plan()
        lateinit var outcome: ScheduleConversionOutcome

        val whileConverting = captured { outcome = attempt.convert(attempt.workspaces) }
        val published = captured { outcome.publish() }

        whileConverting.shouldBeEmpty()
        published shouldContainExactly
            listOf(
                "event=scheduler.conversion.converted id=$scheduleId name=$NAME pipeline=new/pipeline pipeline_version=5 " +
                    "status=RELEASED queued_runs=1",
                "event=scheduler.conversion.converted_run id=$runId schedule=$scheduleId pipeline=new/pipeline pipeline_version=5 " +
                    "status=RELEASED",
                "event=scheduler.conversion.summary converted=1 released=1 draft=0 queued_runs=1",
            )
    }

    @Test
    fun `a schedule or queued run outside the guarded workspaces is a conflict, before any write`() {
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "new/pipeline")
        val attempt = conversion.plan()

        attempt.workspaces shouldContainExactly setOf(workspace)
        shouldThrow<StartupConversionConflict> { attempt.convert(emptySet()) }

        verify(exactly = 0) { schedules.convertPayload(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { runs.convertQueuedPayload(any(), any(), any(), any()) }
    }

    @Test
    fun `each converted queued run is logged with its own pipeline and version`() {
        pipeline("old/pipeline", current = 2)
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "old/pipeline")

        val lines = captured { runOnce() }

        lines shouldContainAll
            listOf(
                "event=scheduler.conversion.converted id=$scheduleId name=$NAME pipeline=new/pipeline pipeline_version=5 " +
                    "status=RELEASED queued_runs=1",
                "event=scheduler.conversion.converted_run id=$runId schedule=$scheduleId pipeline=old/pipeline pipeline_version=2 " +
                    "status=RELEASED",
            )
    }

    // ------------------------------------------------------------------ fixtures

    /** One run as the startup runner drives this step: E.0, then plan and convert in ONE transaction, then publish. */
    private fun runOnce(): ScheduleConversionReport {
        conversion.logPrecondition() shouldBe 0
        val outcome =
            TransactionTemplate(transactions)
                .execute { conversion.plan().let { attempt -> attempt.convert(attempt.workspaces) } }
                .shouldNotBeNull()
        outcome.publish()
        return outcome.report
    }

    private fun schedule(
        pipeline: String,
        id: UUID = scheduleId,
        name: String = NAME,
    ) = mockk<Schedule> {
        every { this@mockk.id } returns id
        every { workspaceId } returns workspace
        every { this@mockk.name } returns name
        every { revision } returns REVISION
        every { executorId } returns PipelineJobExecutor.EXECUTOR_ID
        every { payloadSchemaVersion } returns 1
        every { deletedAt } returns null
        every { targetRef } returns PipelineJobExecutor.TARGET_PREFIX + pipeline
        every { payload } returns mapper.readTree("""{"pipeline":"$pipeline","version":"current"}""")
    }

    private fun run(
        pipeline: String,
        id: UUID = runId,
    ) = mockk<ScheduleRun> {
        every { this@mockk.id } returns id
        every { workspaceId } returns workspace
        every { payload } returns mapper.readTree(RUN_PAYLOAD.format(pipeline, "\"current\""))
    }

    /** The formatted INFO/ERROR lines the conversion logged while [block] ran (its exception, if any, rethrown). */
    private fun captured(block: () -> Unit): List<String> =
        capturingLogEvents(LoggerFactory.getLogger(ScheduleVersionConversion::class.java) as Logger, block = block)
            .map { it.formattedMessage }

    /** A transaction manager that records what each transaction ended with. */
    private class RecordingTransactions : PlatformTransactionManager {
        var commits = 0
        var rollbacks = 0

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) {
            commits++
        }

        override fun rollback(status: TransactionStatus) {
            rollbacks++
        }
    }

    private companion object {
        const val NAME = "nightly"
        const val REVISION = 4

        /** A run's frozen payload copy: its pipeline, its version selector and a binding that must survive untouched. */
        const val RUN_PAYLOAD =
            """{"pipeline":"%s","version":%s,"parameter_bindings":{"as_of":{"source":"keyword","name":"YESTERDAY"}}}"""
    }
}
