package co.datapipelines.web.pipelines

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionProgress
import co.datapipelines.executor.IdempotencyOutcome
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.PipelineConcurrencyLimitException
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.typesystem.DatapipelinesException
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
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
    private fun launcher(
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        executorFactory: (co.datapipelines.web.sse.WebEventEmitter) -> PipelineExecutor,
    ): ExecutionStreamLauncher =
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
            scope = scope,
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

    private fun refusal(kind: String): Exception =
        when (kind) {
            "slot" -> PipelineConcurrencyLimitException(co.datapipelines.executor.LimitScope.PER_USER, 1)
            "mapped" -> DatapipelinesException(PipelineErrorCodes.Result.EXPIRED, "pre-start refusal")
            else -> IllegalStateException("pre-start failure")
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(strings = ["slot", "mapped", "unexpected"])
    fun `every pre-start failure releases before completing the stream`(kind: String) {
        runTest {
            val reserved = UUID.randomUUID()
            val error = refusal(kind)
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws error
            every { idempotencyStore.reserve(userId, "release", any(), any(), any()) } returns IdempotencyOutcome.Reserved(reserved)
            lateinit var sse: SseEmitter
            every { idempotencyStore.release(userId, "release", reserved) } answers {
                ReflectionTestUtils.getField(sse, "failure") shouldBe null
                true
            }

            sse = launcher(scope = this) { executor }.launch(launchRequest(key = "release"))
            runCurrent()

            verify(exactly = 1) { idempotencyStore.release(userId, "release", reserved) }
            ReflectionTestUtils.getField(sse, "failure") shouldBe error
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["slot", "mapped", "unexpected"])
    fun `an execution that emitted then failed keeps its reservation`(kind: String) {
        runTest {
            val reserved = UUID.randomUUID()
            val executor = mockk<PipelineExecutor>()
            every { idempotencyStore.reserve(userId, "started", any(), any(), any()) } returns IdempotencyOutcome.Reserved(reserved)
            lateinit var emitter: co.datapipelines.web.sse.WebEventEmitter
            coEvery { executor.execute(any()) } coAnswers {
                emitter.emit(ExecutionStarted(reserved, pipelineId, 1, emptyMap(), startedAt = Instant.now()))
                throw refusal(kind)
            }
            launcher {
                emitter = it
                executor
            }.launch(launchRequest(key = "started"))

            verify(exactly = 0) { idempotencyStore.release(any(), any(), any()) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `release failure preserves the original refusal`() {
        runTest {
            val reserved = UUID.randomUUID()
            val error = refusal("slot")
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws error
            every { idempotencyStore.reserve(userId, "release", any(), any(), any()) } returns IdempotencyOutcome.Reserved(reserved)
            every { idempotencyStore.release(userId, "release", reserved) } throws IllegalStateException("Redis unavailable")

            val logger = LoggerFactory.getLogger(co.datapipelines.application.ExecutionLauncher::class.java) as Logger
            val warnings = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(warnings)
            try {
                val sse = launcher(scope = this) { executor }.launch(launchRequest(key = "release"))
                runCurrent()

                ReflectionTestUtils.getField(sse, "failure") shouldBe error
                verify(exactly = 1) { idempotencyStore.release(userId, "release", reserved) }
                val warning = warnings.list.single { it.formattedMessage.startsWith("event=execution.idempotency_release_failed ") }
                warning.level shouldBe Level.WARN
                warning.formattedMessage shouldBe
                    "event=execution.idempotency_release_failed user=$userId execution=$reserved error=IllegalStateException"
                warning.throwableProxy shouldBe null
            } finally {
                logger.detachAppender(warnings)
                warnings.stop()
            }
        }
    }

    @Test
    fun `an unkeyed pre-start failure does not release`() {
        runTest {
            val executor = mockk<PipelineExecutor>()
            coEvery { executor.execute(any()) } throws refusal("slot")

            launcher { executor }.launch(launchRequest())

            verify(exactly = 0) { idempotencyStore.release(any(), any(), any()) }
        }
    }

    @Test
    fun `the per-user stream cap rejects with rate_limit-exceeded`() {
        registry.open(UUID.randomUUID(), userId) // the one allowed stream is taken

        val error = shouldThrow<ApiException> { launcher(executorFactory = mockk()).launch(launchRequest()) }
        error.code shouldBe PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED
    }

    @Test
    fun `a missing required parameter is rejected before any reservation`() {
        val required = pipeline().copy(parameters = mapOf("start_date" to Parameter(LogicalType.DATE, required = true)))
        val error =
            shouldThrow<Exception> {
                launcher(executorFactory = mockk()).launch(launchRequest().copy(pipeline = required, idempotencyKey = "k"))
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

        val error = shouldThrow<ApiException> { launcher(executorFactory = mockk()).launch(launchRequest(key = "key-1")) }
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
