package co.datapipelines.web.pipelines

import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.StoredResultView
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.sse.ExecutionStreamRegistry
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [McpRecordingExecutionRunner] — the runner's OWN assembly contract, through the optional
 * `executorFactory` seam its two siblings established (the emitter's recording behaviour is
 * [co.datapipelines.web.sse.WebEventEmitterTest]'s subject; the executor's is the dag suite's).
 *
 * What only this suite pins: an agent-initiated run is FORCED to `triggered_via = MCP`
 * whatever the incoming request carried, the executor is built on the workspace's own
 * template engine (T24), **no stream is ever registered** (the whole point of the
 * recording-only shape), and the §10.2 result-columns bookkeeping is fire-and-forget —
 * a missing or failing describe never fails the completed execution.
 */
class McpRecordingExecutionRunnerTest {
    private val templateEngines = mockk<WorkspaceTemplateEngines>()
    private val resultStore = mockk<ResultStore>()
    private val streams = mockk<ExecutionStreamRegistry>(relaxed = true)
    private val executionRepository = mockk<co.datapipelines.executor.ExecutionRepository>(relaxed = true)

    private val workspaceId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    private fun runner(executor: PipelineExecutor) =
        McpRecordingExecutionRunner(
            templateEngines = templateEngines,
            datasourceRegistry = mockk(),
            stagingFactory = mockk(),
            writebackRunner = mockk(),
            resultStore = resultStore,
            cancellationRegistry = mockk(),
            cancellationFlags = mockk(),
            executionSlots = mockk(),
            executorDispatcher = mockk(),
            executorConfig = mockk(),
            resultUrls = mockk(),
            executorMetrics = mockk(),
            persistenceDispatcher = Dispatchers.Unconfined,
            streams = streams,
            eventLog = mockk(relaxed = true),
            eventRepository = mockk(relaxed = true),
            executionRepository = executionRepository,
            executorFactory = { executor },
        )

    private fun request(trigger: ExecutionTrigger = ExecutionTrigger.REST) =
        ExecuteRequest(
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            pipeline = mockk<Pipeline>(),
            userId = UUID.randomUUID(),
            workspaceId = workspaceId,
            triggeredVia = trigger,
        )

    private fun result(resultRef: String? = "dp:result:$executionId") =
        ExecutionResult(
            executionId = executionId,
            status = ExecutionStatus.SUCCESS,
            nodeStats = emptyList<NodeStats>(),
            resultRef = resultRef,
            startedAt = Instant.EPOCH,
            completedAt = Instant.EPOCH,
            durationMs = 5,
        )

    private fun view(
        rows: Long = 42,
        bytes: Long = 512,
    ) = StoredResultView(
        key = "dp:result:$executionId",
        executionId = executionId,
        schema = emptyList(),
        firstPage = emptyList(),
        totalRows = rows,
        bytes = bytes,
        expiresAt = Instant.EPOCH,
    )

    @Test
    fun `the run is forced to the MCP trigger whatever the request carried`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()

            runner(executor).run(request(trigger = ExecutionTrigger.REST), WorkspaceContext(workspaceId, "acme"))

            coVerify {
                executor.execute(match { it.triggeredVia == ExecutionTrigger.MCP })
            }
        }

    @Test
    fun `the executor result flows back unchanged`() =
        runTest {
            val expected = result()
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns expected
            every { templateEngines.engineFor(workspaceId) } returns mockk()

            val returned = runner(executor).run(request(), WorkspaceContext(workspaceId, "acme"))

            returned shouldBe expected
        }

    @Test
    fun `no stream is registered - the recording-only shape`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()

            runner(executor).run(request(), WorkspaceContext(workspaceId, "acme"))

            verify(exactly = 0) { streams.register(any()) }
        }

    @Test
    fun `the result-history columns land after a successful run`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            every { resultStore.describe("dp:result:$executionId") } returns view(rows = 42, bytes = 512)

            runner(executor).run(request(), WorkspaceContext(workspaceId, "acme"))

            verify { executionRepository.recordResult(executionId, 42L, 512L) }
        }

    @Test
    fun `no resultRef means no bookkeeping`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result(resultRef = null)
            every { templateEngines.engineFor(workspaceId) } returns mockk()

            runner(executor).run(request(), WorkspaceContext(workspaceId, "acme"))

            verify(exactly = 0) { executionRepository.recordResult(any(), any(), any()) }
        }

    @Test
    fun `an expired describe is a quiet skip`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            every { resultStore.describe(any()) } returns null

            runner(executor).run(request(), WorkspaceContext(workspaceId, "acme"))

            verify(exactly = 0) { executionRepository.recordResult(any(), any(), any()) }
        }

    @Test
    fun `a failing describe never fails the completed execution`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            every { resultStore.describe(any()) } throws IllegalStateException("store down")

            val returned = runner(executor).run(request(), WorkspaceContext(workspaceId, "acme"))

            returned.status shouldBe ExecutionStatus.SUCCESS
        }
}
