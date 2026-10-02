package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionProgress
import co.datapipelines.executor.IdempotencyOutcome
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.config.IdempotencyProperties
import co.datapipelines.web.config.SseProperties
import co.datapipelines.web.metrics.WebMetrics
import co.datapipelines.web.sse.ExecutionStreamRegistry
import co.datapipelines.web.sse.SseLogStreamer
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The launch flow (rest-api §3.5/§6, §12.1): the stream cap, the up-front parameter gate, the
 * idempotency reservation, and the retry attach.
 */
class ExecutionStreamLauncherTest {
    private val idempotencyStore = mockk<IdempotencyStore>()
    private val streamer = mockk<SseLogStreamer>()
    private val executionRepository = mockk<co.datapipelines.executor.ExecutionRepository>()
    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val correlationId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private lateinit var registry: ExecutionStreamRegistry

    @BeforeEach
    fun freshRegistry() {
        registry =
            ExecutionStreamRegistry(
                SseProperties(maxStreamsPerUser = 1),
                mockk<ExecutionCancellationService>(),
                JsonMapper.builder().build(),
            )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launcher(executorFactory: (co.datapipelines.web.sse.WebEventEmitter) -> PipelineExecutor): ExecutionStreamLauncher =
        ExecutionStreamLauncher(
            templateEngines = mockk(),
            datasourceRegistry = mockk(),
            stagingFactory = mockk(),
            writebackRunner = mockk(),
            resultStore = mockk<ResultStore>(),
            cancellationRegistry = mockk(),
            cancellationFlags = mockk(),
            executionSlots = mockk(),
            executorDispatcher = mockk(),
            // The launcher reads the #311 lifecycle bound off the config at emit construction.
            executorConfig =
                mockk {
                    every { lifecycleWriteTimeoutSeconds } returns 10
                },
            resultUrls = mockk(),
            executorMetrics = mockk(),
            executionProgress = ExecutionProgress.NONE,
            persistenceDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            streams = registry,
            eventLog = mockk(relaxed = true),
            streamer = streamer,
            authority = mockk(relaxed = true),
            eventRepository = mockk(relaxed = true),
            executionRepository = executionRepository,
            launcher =
                co.datapipelines.application.ExecutionLauncher(
                    idempotencyStore = idempotencyStore,
                    idempotencyTtlSeconds = IdempotencyProperties().ttlSeconds,
                    metrics = WebIdempotencyMetrics(WebMetrics(SimpleMeterRegistry())),
                ),
            mapper = JsonMapper.builder().build(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            executorFactory = executorFactory,
        )

    private fun launchRequest(
        parameters: Map<String, com.fasterxml.jackson.databind.JsonNode> = emptyMap(),
        key: String? = null,
    ) = ExecuteLaunch(
        pipelineId = pipelineId,
        pipelineVersion = 1,
        pipeline = pipeline(),
        principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.API_KEY,
                "dpk_x",
                workspace = WorkspaceContext(workspaceId, "acme"),
            ),
        parameters = parameters,
        parametersJson = "{}",
        correlationId = correlationId,
        resultTtlSeconds = null,
        idempotencyKey = key,
    )

    private fun pipeline() =
        Pipeline(
            schemaVersion = 1,
            name = "test/p",
            displayName = "P",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes = emptyList(),
        )

    @Test
    fun `the per-user stream cap rejects with rate_limit-exceeded`() {
        registry.open(UUID.randomUUID(), userId) // the one allowed stream is taken

        val error = shouldThrow<ApiException> { launcher(mockk()).launch(launchRequest()) }
        error.code shouldBe PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED
    }

    @Test
    fun `a missing required parameter is rejected before any reservation`() {
        val required = pipeline().copy(parameters = mapOf("start_date" to Parameter(LogicalType.DATE, required = true)))
        val error =
            shouldThrow<Exception> {
                launcher(mockk()).launch(launchRequest().copy(pipeline = required, idempotencyKey = "k"))
            }
        verify(exactly = 0) { idempotencyStore.reserve(any(), any(), any(), any(), any()) }
        (error is co.datapipelines.typesystem.DatapipelinesException) shouldBe true
    }

    @Test
    fun `a fresh execution registers its stream`() =
        runTest {
            val executor = mockk<PipelineExecutor>()
            val reserved = UUID.randomUUID()
            every { idempotencyStore.reserve(any(), "key-1", any(), any(), any()) } returns IdempotencyOutcome.Reserved(reserved)
            var captured: co.datapipelines.web.sse.WebEventEmitter? = null
            coEvery { executor.execute(any()) } coAnswers {
                val emitter = captured!!
                val executionId = reserved // the reserved id IS the execution id
                emitter.emit(ExecutionStarted(executionId, pipelineId, 1, emptyMap(), startedAt = Instant.now()))
                emitter.emit(PipelineCompleted(executionId, pipelineId, 1, Instant.now(), Instant.now(), 1, emptyList()))
                mockk(relaxed = true)
            }
            val launcher =
                launcher { emitter ->
                    captured = emitter
                    executor
                }

            val sse = launcher.launch(launchRequest(key = "key-1"))

            sse shouldNotBe null
            // The stream was registered under the reserved id, then closed at the end.
            registry.activeStreams shouldBe 0
        }

    @Test
    fun `a retry with the same key attaches to the original instead of re-executing`() {
        val executionId = UUID.randomUUID()
        every { idempotencyStore.reserve(any(), "key-1", any(), any(), any()) } returns IdempotencyOutcome.Existing(executionId)
        every { streamer.hasLog(executionId) } returns true
        val followEmitter =
            org.springframework.web.servlet.mvc.method.annotation
                .SseEmitter(0L)
        every { streamer.follow(executionId, any()) } returns followEmitter

        val result = launcher { error("must not start a fresh execution") }.launch(launchRequest(key = "key-1"))

        result shouldBe followEmitter
    }

    /** #324 — the row decides when the log has no entry yet: terminal keeps the 410, else follow. */
    private fun rowRecord(
        executionId: UUID,
        completedAt: Instant?,
    ) = co.datapipelines.executor.ExecutionRecord(
        executionId = executionId,
        pipelineId = pipelineId,
        pipelineVersion = 1,
        status =
            if (completedAt == null) {
                co.datapipelines.executor.ExecutionStatus.RUNNING
            } else {
                co.datapipelines.executor.ExecutionStatus.SUCCESS
            },
        parametersJson = "{}",
        executedBy = userId,
        triggeredVia = co.datapipelines.executor.ExecutionTrigger.REST,
        completedAt = completedAt,
    )

    @Test
    fun `a retry whose original finished and its log expired keeps the 410 with the id and reason`() {
        val executionId = UUID.randomUUID()
        every { idempotencyStore.reserve(any(), "key-1", any(), any(), any()) } returns IdempotencyOutcome.Existing(executionId)
        every { streamer.hasLog(executionId) } returns false
        // A relaxed mock's findById answer is NOT a null — stub the terminal row explicitly.
        every { executionRepository.findById(workspaceId, executionId) } returns rowRecord(executionId, Instant.now())

        val error = shouldThrow<ApiException> { launcher(mockk()).launch(launchRequest(key = "key-1")) }
        error.code shouldBe PipelineErrorCodes.Result.EXPIRED
        // The details are the assertion: a stub wrong in either direction fails here by name.
        error.details["execution_id"] shouldBe executionId.toString()
        error.details["reason"] shouldBe "event_log_expired"
        verify(exactly = 0) { streamer.follow(any(), any()) }
    }

    @Test
    fun `a retry whose original has no row yet waits by following it`() {
        val executionId = UUID.randomUUID()
        every { idempotencyStore.reserve(any(), "key-1", any(), any(), any()) } returns IdempotencyOutcome.Existing(executionId)
        every { streamer.hasLog(executionId) } returns false
        every { executionRepository.findById(workspaceId, executionId) } returns null
        val followEmitter =
            org.springframework.web.servlet.mvc.method.annotation
                .SseEmitter(0L)
        every { streamer.follow(executionId, any()) } returns followEmitter

        val result = launcher { error("must not start a fresh execution") }.launch(launchRequest(key = "key-1"))

        result shouldBe followEmitter
        verify(exactly = 1) { streamer.follow(executionId, any()) }
    }

    @Test
    fun `a retry whose original is still running follows it instead of answering 410`() {
        val executionId = UUID.randomUUID()
        every { idempotencyStore.reserve(any(), "key-1", any(), any(), any()) } returns IdempotencyOutcome.Existing(executionId)
        every { streamer.hasLog(executionId) } returns false
        every { executionRepository.findById(workspaceId, executionId) } returns rowRecord(executionId, null)
        val followEmitter =
            org.springframework.web.servlet.mvc.method.annotation
                .SseEmitter(0L)
        every { streamer.follow(executionId, any()) } returns followEmitter

        val result = launcher { error("must not start a fresh execution") }.launch(launchRequest(key = "key-1"))

        result shouldBe followEmitter
        verify(exactly = 1) { streamer.follow(executionId, any()) }
    }
}
