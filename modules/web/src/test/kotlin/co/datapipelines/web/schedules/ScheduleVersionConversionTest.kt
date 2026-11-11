package co.datapipelines.web.schedules

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.ScheduleRun
import co.datapipelines.scheduler.ScheduleRunRepository
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
 * version; the precondition covers every run target (named `kind=run`, separately from schedules) before any write; a
 * run target that fails under the guard rolls the whole schedule's transaction back. The real-database legs (the A-to-B
 * edit at a real upgrade boot, the refused boot, the rollback race, the rerun) are `ScheduleVersionConversionE2eTest`.
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

    private val conversion =
        ScheduleVersionConversion(schedules, runs, pipelines, TransactionTemplate(transactions), MetadataWriterGuard.NONE)

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

        conversion.run()

        runPayloads.single() shouldBe mapper.readTree(RUN_PAYLOAD.format("new/pipeline", "5"))
    }

    @Test
    fun `a queued run whose copy names the schedule's OLD pipeline converts to the old pipeline's CURRENT, never the schedule's`() {
        // The schedule was edited from old/pipeline (CURRENT 2) to new/pipeline (CURRENT 5) after the run was queued.
        pipeline("old/pipeline", current = 2)
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "old/pipeline")

        val report = conversion.run()

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
        val refused = shouldThrow<ScheduleConversionRefusedException> { conversion.run() }
        refused.message shouldContain "$NAME queued run ${missing.id} -> old/gone: missing_pipeline"
        refused.message shouldContain "$NAME queued run ${noCurrent.id} -> old/no_current: no_current"
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

        val refused = shouldThrow<ScheduleConversionRefusedException> { conversion.run() }

        refused.refusals.single().pipeline shouldBe "old/pipeline"
        refused.refusals.single().reason shouldBe ScheduleConversionRefusal.Reason.NO_VERSIONS
        refused.message shouldContain "$NAME queued run $runId -> old/pipeline: no_versions"
        schedulePayloads shouldHaveSize 1 // the schedule's write happened inside the transaction ...
        transactions.rollbacks shouldBe 1 // ... and was rolled back with it
        transactions.commits shouldBe 0
        verify(exactly = 0) { runs.convertQueuedPayload(any(), any(), any(), any()) }
    }

    @Test
    fun `each converted queued run is logged with its own pipeline and version`() {
        pipeline("old/pipeline", current = 2)
        pipeline("new/pipeline", current = 5)
        fixture(schedulePipeline = "new/pipeline", runPipeline = "old/pipeline")

        val lines = captured { conversion.run() }

        lines shouldContainAll
            listOf(
                "event=scheduler.conversion.converted id=$scheduleId name=$NAME pipeline=new/pipeline pipeline_version=5 " +
                    "status=RELEASED queued_runs=1",
                "event=scheduler.conversion.converted_run id=$runId schedule=$scheduleId pipeline=old/pipeline pipeline_version=2 " +
                    "status=RELEASED",
            )
    }

    // ------------------------------------------------------------------ fixtures

    private fun schedule(pipeline: String) =
        mockk<Schedule> {
            every { id } returns scheduleId
            every { workspaceId } returns workspace
            every { name } returns NAME
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
    private fun captured(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(ScheduleVersionConversion::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

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
