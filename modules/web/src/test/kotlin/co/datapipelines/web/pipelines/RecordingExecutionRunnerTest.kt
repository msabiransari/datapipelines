package co.datapipelines.web.pipelines

import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionProgress
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.NodeStats
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.StoredResultView
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.WebEventEmitter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

/**
 * [RecordingExecutionRunner] — the runner's OWN assembly contract, through the optional
 * `executorFactory` seam its two siblings established (the emitter's recording behaviour is
 * [co.datapipelines.web.sse.WebEventEmitterTest]'s subject; the executor's is the dag suite's).
 *
 * What only this suite pins: an agent-initiated run is FORCED to `triggered_via = MCP`
 * whatever the incoming request carried, the executor is built on the workspace's own
 * template engine (T24), **no stream is ever registered** (the whole point of the
 * recording-only shape), and the §10.2 result-columns bookkeeping is fire-and-forget —
 * a missing or failing describe never fails the completed execution.
 */
class RecordingExecutionRunnerTest {
    private val templateEngines = mockk<WorkspaceTemplateEngines>()
    private val resultStore = mockk<ResultStore>()
    private val streams = mockk<ExecutionStreamRegistry>(relaxed = true)
    private val executionRepository = mockk<co.datapipelines.executor.ExecutionRepository>(relaxed = true)
    private val idempotencyStore = mockk<IdempotencyStore>()

    private val workspaceId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    private fun runner(
        executor: PipelineExecutor,
        capture: (WebEventEmitter) -> Unit = {},
    ) = RecordingExecutionRunner(
        templateEngines = templateEngines,
        datasourceRegistry = mockk(),
        stagingFactory = mockk(),
        writebackRunner = mockk(),
        resultStore = resultStore,
        cancellationRegistry = mockk(),
        cancellationFlags = mockk(),
        executionSlots = mockk(),
        executorDispatcher = mockk(),
        // The runner reads the #311 lifecycle bound off the config at emit construction.
        executorConfig =
            mockk {
                every { lifecycleWriteTimeoutSeconds } returns 10
            },
        resultUrls = mockk(),
        executorMetrics = mockk(),
        executionProgress = ExecutionProgress.NONE,
        persistenceDispatcher = Dispatchers.Unconfined,
        streams = streams,
        eventLog = mockk(relaxed = true),
        eventRepository = mockk(relaxed = true),
        executionRepository = executionRepository,
        idempotencyStore = idempotencyStore,
        executorFactory = {
            capture(it)
            executor
        },
    )

    @ParameterizedTest
    @ValueSource(strings = ["exception", "cancellation", "error"])
    fun `any throwable before the first event releases the MCP reservation and is rethrown unchanged`(kind: String) {
        runTest {
            val request = request().copy(idempotencyKey = "retry", executionId = executionId)
            val error =
                when (kind) {
                    "cancellation" -> kotlinx.coroutines.CancellationException("cancelled before start")
                    "error" -> AssertionError("failed before start")
                    else -> IllegalStateException("refused before start")
                }
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws error
            every { idempotencyStore.release(request.userId, "retry", executionId) } returns true

            shouldThrow<Throwable> { runner(executor).run(request, workspaceId, ExecutionTrigger.MCP) } shouldBe error

            verify(exactly = 1) { idempotencyStore.release(request.userId, "retry", executionId) }
        }
    }

    @Test
    fun `an MCP execution that emitted then failed keeps its reservation`() {
        runTest {
            val request = request().copy(idempotencyKey = "retry", executionId = executionId)
            val error = IllegalStateException("failed after start")
            val executor = mockk<PipelineExecutor>()
            lateinit var emitter: WebEventEmitter
            coEvery { executor.execute(any()) } coAnswers {
                emitter.emit(ExecutionStarted(executionId, request.pipelineId, 1, emptyMap(), startedAt = Instant.now()))
                throw error
            }

            shouldThrow<IllegalStateException> {
                runner(executor) { emitter = it }.run(request, workspaceId, ExecutionTrigger.MCP)
            } shouldBe error

            verify(exactly = 0) { idempotencyStore.release(any(), any(), any()) }
        }
    }

    @Test
    fun `a scheduler failure with no key never releases`() {
        runTest {
            val request = request(ExecutionTrigger.SCHEDULE).copy(executionId = executionId)
            val error = IllegalStateException("refused before start")
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws error

            shouldThrow<IllegalStateException> {
                runner(executor).run(request, workspaceId, ExecutionTrigger.SCHEDULE)
            } shouldBe error

            verify(exactly = 0) { idempotencyStore.release(any(), any(), any()) }
        }
    }

    @Test
    fun `a request with no reserved execution id never releases`() {
        runTest {
            val error = IllegalStateException("refused before start")
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws error

            shouldThrow<IllegalStateException> {
                runner(executor).run(request().copy(idempotencyKey = "retry"), workspaceId, ExecutionTrigger.MCP)
            } shouldBe error

            verify(exactly = 0) { idempotencyStore.release(any(), any(), any()) }
        }
    }

    @Test
    fun `a failed release cannot mask the MCP refusal`() {
        runTest {
            val request = request().copy(idempotencyKey = "retry", executionId = executionId)
            val error = IllegalStateException("refused before start")
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws error
            every { idempotencyStore.release(request.userId, "retry", executionId) } throws IllegalStateException("Redis unavailable")

            shouldThrow<IllegalStateException> { runner(executor).run(request, workspaceId, ExecutionTrigger.MCP) } shouldBe error

            verify(exactly = 1) { idempotencyStore.release(request.userId, "retry", executionId) }
        }
    }

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

            runner(executor).run(request(trigger = ExecutionTrigger.REST), workspaceId, ExecutionTrigger.MCP)

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

            val returned = runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            returned shouldBe expected
        }

    @Test
    fun `no stream is registered - the recording-only shape`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()

            runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            verify(exactly = 0) { streams.register(any()) }
        }

    @Test
    fun `the result-history columns land after a successful run`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            every { resultStore.describe("dp:result:$executionId") } returns view(rows = 42, bytes = 512)

            runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            // #328: the launcher passes view.schema verbatim — an empty one here — and the
            // repository serializes and bounds it.
            verify { executionRepository.recordResult(executionId, 42L, 512L, emptyList()) }
        }

    /**
     * #328 A — the stored result's schema reaches the repository: the launcher passes
     * `view.schema` and the record is the repository's business (serialize + bound there).
     */
    @Test
    fun `the result schema rides the record - the stored view schema reaches the repository`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            val schema = listOf(ColumnSchema("month", LogicalType.DATE, nullable = false))
            every { resultStore.describe("dp:result:$executionId") } returns
                view(rows = 42, bytes = 512).copy(schema = schema)

            runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            verify { executionRepository.recordResult(executionId, 42L, 512L, schema) }
        }

    @Test
    fun `no resultRef means no bookkeeping`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result(resultRef = null)
            every { templateEngines.engineFor(workspaceId) } returns mockk()

            runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            verify(exactly = 0) { executionRepository.recordResult(any(), any(), any()) }
        }

    @Test
    fun `an expired describe is a quiet skip`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            every { resultStore.describe(any()) } returns null

            runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            verify(exactly = 0) { executionRepository.recordResult(any(), any(), any()) }
        }

    @Test
    fun `a failing describe never fails the completed execution`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } returns result()
            every { templateEngines.engineFor(workspaceId) } returns mockk()
            every { resultStore.describe(any()) } throws IllegalStateException("store down")

            val returned = runner(executor).run(request(), workspaceId, ExecutionTrigger.MCP)

            returned.status shouldBe ExecutionStatus.SUCCESS
        }
}
