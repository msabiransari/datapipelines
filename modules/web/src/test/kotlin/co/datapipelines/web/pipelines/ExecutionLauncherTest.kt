package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.ExecutionCancellationService
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
class ExecutionLauncherTest {
    private val idempotencyStore = mockk<IdempotencyStore>()
    private val streamer = mockk<SseLogStreamer>()
    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val correlationId = UUID.randomUUID()
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
    private fun launcher(executorFactory: (co.datapipelines.web.sse.WebEventEmitter) -> PipelineExecutor): ExecutionLauncher =
        ExecutionLauncher(
            templateEngine = mockk(),
            datasourceRegistry = mockk(),
            stagingFactory = mockk(),
            writebackRunner = mockk(),
            resultStore = mockk<ResultStore>(),
            cancellationRegistry = mockk(),
            cancellationFlags = mockk(),
            executionSlots = mockk(),
            executorDispatcher = mockk(),
            executorConfig = mockk(),
            resultUrls = mockk(),
            executorMetrics = mockk(),
            persistenceDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            streams = registry,
            eventLog = mockk(relaxed = true),
            streamer = streamer,
            eventRepository = mockk(relaxed = true),
            executionRepository = mockk(relaxed = true),
            idempotencyStore = idempotencyStore,
            idempotency = IdempotencyProperties(),
            mapper = JsonMapper.builder().build(),
            metrics = WebMetrics(SimpleMeterRegistry()),
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
        principal = AuthenticatedPrincipal(userId, "a@b.c", "A", setOf(Scope.EXECUTE), AuthMethod.API_KEY, "dpk_x"),
        parameters = parameters,
        parametersJson = "{}",
        correlationId = correlationId,
        resultTtlSeconds = null,
        idempotencyKey = key,
    )

    private fun pipeline() =
        Pipeline(
            schemaVersion = 1,
            name = "p",
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
        every { streamer.follow(executionId) } returns followEmitter

        val result = launcher { error("must not start a fresh execution") }.launch(launchRequest(key = "key-1"))

        result shouldBe followEmitter
    }

    @Test
    fun `a retry whose original event log has expired is a 410`() {
        val executionId = UUID.randomUUID()
        every { idempotencyStore.reserve(any(), "key-1", any(), any(), any()) } returns IdempotencyOutcome.Existing(executionId)
        every { streamer.hasLog(executionId) } returns false

        val error = shouldThrow<ApiException> { launcher(mockk()).launch(launchRequest(key = "key-1")) }
        error.code shouldBe PipelineErrorCodes.Result.EXPIRED
    }
}
