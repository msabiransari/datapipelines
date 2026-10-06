package co.datapipelines.web.dashboards.runtime

import co.datapipelines.application.dashboards.SourceLaunch
import co.datapipelines.application.dashboards.SourceOutcome
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.DirectResultSink
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutedByKeyKind
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.sse.ExecutionRecordUnwritableException
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [RecordingSourceStarter] — the launch seam of a refresh (spec §9 step 4, §18 premise 4) over a stubbed
 * [RecordingExecutionRunner]. The stub RECORDS the request it was handed (an effect assertion): the trigger is DASHBOARD,
 * the sink is the refresh's collector, the lease is one of the refresh's reserved slots, the run is fail-closed and the
 * correlation id is the refresh id. A failure is an OUTCOME by code; only a cancelled CALLER is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSourceStarterTest {
    private val runner = mockk<RecordingExecutionRunner>()
    private val pipelines =
        mockk<co.datapipelines.pipeline.PipelineRepository> {
            every { findVersionDetail(any(), any(), any()) } returns
                mockk {
                    every { status } returns co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
                }
        }
    private val starter =
        RecordingSourceStarter(
            runner,
            ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, maxCompositionDepth = 1),
            pipelines,
        )
    private val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 10)
    private val refreshId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val workspace = UUID.randomUUID()
    private val sink = DirectResultSink { _, _ -> }
    private val pipeline =
        mockk<Pipeline> {
            every { nodes } returns emptyList()
            every { parameters } returns emptyMap()
            every { calculatorOutputs(any()) } returns emptyMap()
            every { calculatorOutputGroups(any()) } returns emptyMap()
            every { transformOutputKeys() } returns emptySet()
        }

    private fun launch(parameters: Map<String, com.fasterxml.jackson.databind.JsonNode> = emptyMap()) =
        SourceLaunch(
            refreshId = refreshId,
            sourceName = "revenue",
            workspaceId = workspace,
            userId = user,
            executedByKeyKind = ExecutedByKeyKind.USER,
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 7,
            pipeline = pipeline,
            parameters = parameters,
            sink = sink,
            slot = runBlocking { slots.acquireInstanceOnly(1, Duration.ZERO)!!.next() },
        )

    private fun result() = ExecutionResult(executionId, ExecutionStatus.SUCCESS, emptyList(), null, Instant.now(), Instant.now(), 1)

    @Test
    fun `a live draft source starts only when the dashboard explicitly admits draft dependencies`() {
        runTest {
            every { pipelines.findVersionDetail(any(), any(), any()) } returns
                mockk {
                    every { status } returns co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
                }
            coEvery { runner.run(any(), workspace, ExecutionTrigger.DASHBOARD, any(), any()) } returns result()
            starter.run(launch()) {} shouldBe SourceOutcome.Failed(null, PipelineErrorCodes.Dashboard.RUNTIME_DEPENDENCY_MISSING)
            starter.run(launch().copy(allowDraftDependencies = true)) {} shouldBe SourceOutcome.Succeeded(executionId)
            io.mockk.coVerify(exactly = 1) { runner.run(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `the admission callback refuses an exact loaded child that now writes to a datasource`() {
        runTest {
            var admission: ((Pipeline, UUID, Int) -> Unit)? = null
            coEvery { runner.run(any(), workspace, ExecutionTrigger.DASHBOARD, any(), any()) } coAnswers {
                admission = firstArg<ExecuteRequest>().pipelineAdmission
                result()
            }
            starter.run(launch()) {} shouldBe SourceOutcome.Succeeded(executionId)
            val unsafe =
                mockk<Pipeline> {
                    every { nodes } returns
                        listOf(
                            co.datapipelines.pipeline.Node(
                                id = "write",
                                description = "Business write",
                                type = co.datapipelines.pipeline.NodeType.DML,
                                source = "warehouse",
                                template = co.datapipelines.pipeline.TemplateRef("write.sql", 1),
                                output = null,
                                dependsOn = emptyList(),
                            ),
                        )
                }
            org.junit.jupiter.api
                .assertThrows<DatapipelinesException> {
                    requireNotNull(admission)(unsafe, UUID.randomUUID(), 1)
                }.code shouldBe PipelineErrorCodes.Dashboard.SOURCE_NOT_READ_ONLY
        }
    }

    @Test
    fun `a source discarded after configuration resolution never starts`() {
        runTest {
            every { pipelines.findVersionDetail(any(), any(), any()) } returns
                mockk {
                    every { status } returns co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
                }
            starter.run(launch()) {} shouldBe SourceOutcome.Failed(null, PipelineErrorCodes.Dashboard.RUNTIME_DEPENDENCY_MISSING)
            io.mockk.coVerify(exactly = 0) { runner.run(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `the request carries the dashboard trigger, the collector, the lease, the correlation and the fail-closed run`() =
        runTest {
            val seen = mutableListOf<ExecuteRequest>()
            var failClosed = false
            val recorded = mutableListOf<UUID>()
            coEvery { runner.run(any(), workspace, ExecutionTrigger.DASHBOARD, any(), any()) } coAnswers {
                seen += firstArg<ExecuteRequest>()
                failClosed = arg<Boolean>(3)
                arg<(UUID) -> Unit>(4)(executionId) // the runner's hook, fired right after the RUNNING row
                result()
            }
            val launch = launch()

            val outcome = starter.run(launch) { recorded += it }

            outcome shouldBe SourceOutcome.Succeeded(executionId)
            recorded shouldBe listOf(executionId)
            failClosed.shouldBeTrue() // a source whose RUNNING row cannot be written does not run: every execution is linkable
            seen.single().let {
                it.triggeredVia shouldBe ExecutionTrigger.DASHBOARD
                it.directSink shouldBe sink
                it.slotLease shouldBe launch.slot
                it.correlationId shouldBe refreshId
                it.userId shouldBe user // the delegated act: executed_by is the refreshing person
                it.workspaceId shouldBe workspace
                it.pipelineVersion shouldBe 7
                it.executedByKeyKind shouldBe ExecutedByKeyKind.USER
            }
        }

    @Test
    fun `a parameter the pipeline's binder refuses is a clean source failure BEFORE any execution exists`() =
        runTest {
            var ran = false
            coEvery { runner.run(any(), any(), any(), any(), any()) } coAnswers {
                ran = true
                result()
            }

            every { pipeline.parameters } returns mapOf("year" to Parameter(type = LogicalType.INTEGER, required = true))

            val refused = starter.run(launch(emptyMap())) { } // the required `year` is bound to nothing
            val wrongType = starter.run(launch(mapOf("year" to JsonNodeFactory.instance.textNode("not a number")))) { }

            refused shouldBe SourceOutcome.Failed(null, PipelineErrorCodes.Execution.PARAMETER_REQUIRED)
            wrongType shouldBe SourceOutcome.Failed(null, PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE)
            ran.shouldBeFalse() // the pre-bind the launcher's decide() would have run — nothing was launched
        }

    @Test
    fun `an executor abort while the caller lives is an Aborted outcome - a failure is a code, never an exception`() =
        runTest {
            coEvery { runner.run(any(), any(), any(), any(), any()) } throws ExecutionAbortedException(AbortReason.CANCELLED)
            starter.run(launch()) { } shouldBe SourceOutcome.Aborted(null)

            coEvery { runner.run(any(), any(), any(), any(), any()) } throws
                DatapipelinesException(PipelineErrorCodes.Node.QUERY_TIMEOUT, "SECRET driver text")
            starter.run(launch()) { } shouldBe SourceOutcome.Failed(null, PipelineErrorCodes.Node.QUERY_TIMEOUT)

            coEvery { runner.run(any(), any(), any(), any(), any()) } throws ExecutionRecordUnwritableException(executionId)
            starter.run(launch()) { } shouldBe SourceOutcome.Failed(null, PipelineErrorCodes.Execution.INSTANCE_LOST)
        }

    @Test
    fun `an abort that arrives because the CALLER was cancelled is rethrown - the refresh's own cancellation is not a source outcome`() =
        runTest {
            coEvery { runner.run(any(), any(), any(), any(), any()) } coAnswers {
                currentCoroutineContext().job.cancel() // the refresh's scope stops the caller…
                throw ExecutionAbortedException(AbortReason.SHUTDOWN) // …and the executor surfaces it as its abort
            }
            var outcome: SourceOutcome? = null

            val job = launch { outcome = starter.run(launch()) { } }
            job.join()

            job.isCancelled.shouldBeTrue()
            outcome shouldBe null
        }
}
