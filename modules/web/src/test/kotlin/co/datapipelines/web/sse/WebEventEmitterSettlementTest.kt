package co.datapipelines.web.sse

import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.MappedError
import co.datapipelines.web.config.SseProperties
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #1035 — what settles an execution row, and when, over mocked persistence: a provisional child completion
 * keeps its row RUNNING, the root's terminal write is the family's one settlement moment, and a confirmed
 * commit whose result was never delivered is recorded on the SUCCESS row. Split from [WebEventEmitterTest],
 * whose size was at the detekt LargeClass limit.
 */
class WebEventEmitterSettlementTest {
    private val executionRepository = mockk<ExecutionRepository>()
    private val eventRepository = mockk<ExecutionEventRepository>()
    private val eventLog = mockk<SseEventLog>()
    private val registry =
        ExecutionStreamRegistry(
            SseProperties(),
            mockk<ExecutionCancellationService>(),
            com.fasterxml.jackson.databind.json.JsonMapper
                .builder()
                .build(),
        )
    private val executionId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val correlationId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun emitter(): WebEventEmitter =
        WebEventEmitter(
            context =
                ExecutionContext(
                    pipelineId = pipelineId,
                    pipelineVersion = 3,
                    userId = userId,
                    correlationId = correlationId,
                    triggeredVia = ExecutionTrigger.REST,
                    parametersJson = """{"start_date":"2026-01-01"}""",
                    workspaceId = workspaceId,
                ),
            stream = null,
            streams = registry,
            eventLog = eventLog,
            eventRepository = eventRepository,
            executionRepository = executionRepository,
            persistenceDispatcher = Dispatchers.Default,
        )

    private fun stubPersistence() {
        every { executionRepository.create(any()) } answers { firstArg() }
        every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
        every { eventLog.append(any(), any()) } just runs
        every { executionRepository.settleFamilies(any()) } returns 0
    }

    /**
     * A child of an enrolled family finished its work, but its writes are not durable until the root commits: its
     * row keeps RUNNING (pending settlement) — no terminal UPDATE, no family settlement of its own.
     */
    @Test
    fun `a provisional child completion keeps the row RUNNING and settles nothing`() =
        runTest {
            stubPersistence()
            every { executionRepository.completeProvisionally(executionId, any(), any()) } returns true

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(5), 5, emptyList(), provisional = true))

            verify(exactly = 1) { executionRepository.completeProvisionally(executionId, any(), any()) }
            verify(
                exactly = 0,
            ) { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { executionRepository.settleFamilies(any()) }
        }

    /** The root's terminal write is the family's one settlement moment — and it is OUTCOME_UNKNOWN, not FAILED. */
    @Test
    fun `a root that ends OUTCOME_UNKNOWN writes that status and then settles its pending children`() =
        runTest {
            stubPersistence()
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(
                PipelineFailed(
                    executionId,
                    pipelineId,
                    3,
                    NOW,
                    NOW.plusMillis(7),
                    7,
                    null,
                    MappedError("pipeline.execution.local_write_commit_failed", "outcome unknown", emptyMap()),
                    emptyList(),
                    status = ExecutionStatus.OUTCOME_UNKNOWN,
                    settlesFamily = true,
                ),
            )

            verifyOrder {
                executionRepository.complete(
                    executionId,
                    ExecutionStatus.OUTCOME_UNKNOWN,
                    any(),
                    7,
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
                executionRepository.settleFamilies(executionId)
            }
        }

    @Test
    fun `a confirmed commit whose result could not be delivered records result_unavailable on the SUCCESS row`() =
        runTest {
            stubPersistence()
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(
                PipelineCompleted(
                    executionId,
                    pipelineId,
                    3,
                    NOW,
                    NOW.plusMillis(9),
                    9,
                    emptyList(),
                    resultUnavailable = true,
                    settlesFamily = true,
                ),
            )

            verify(exactly = 1) {
                executionRepository.complete(executionId, ExecutionStatus.SUCCESS, any(), 9, any(), any(), any(), any(), any(), any(), true)
            }
            verify(exactly = 1) { executionRepository.settleFamilies(executionId) }
        }

    /** A plain (non-family) terminal write never runs the family settlement. */
    @Test
    fun `an ordinary terminal write does not run the family settlement`() =
        runTest {
            stubPersistence()
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(3), 3, emptyList()))

            verify(exactly = 0) { executionRepository.settleFamilies(any()) }
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-05T14:30:00Z")
    }
}
