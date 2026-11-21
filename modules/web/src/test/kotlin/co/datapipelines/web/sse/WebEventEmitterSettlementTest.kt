package co.datapipelines.web.sse

import co.datapipelines.events.ExecutionAborted
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.events.SseEventType
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.MappedError
import co.datapipelines.executor.SettledChild
import co.datapipelines.web.config.SseProperties
import io.kotest.matchers.shouldBe
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
    private val childId = UUID.randomUUID()
    private val childCorrelation = UUID.randomUUID()
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
        every { executionRepository.settleFamiliesDetailed(any()) } returns emptyList()
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
            verify(exactly = 0) { executionRepository.settleFamiliesDetailed(any()) }
        }

    /**
     * (#1035 R2, the reviewer's case) A provisional completion claims SUCCESS for writes nobody has made durable: it
     * reaches neither history store, spends no event id, and the live stream never sees it.
     */
    @Test
    fun `a provisional child completion is withheld from the durable and replay history`() =
        runTest {
            stubPersistence()
            every { executionRepository.completeProvisionally(executionId, any(), any()) } returns true

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(5), 5, emptyList(), provisional = true))

            verify(exactly = 0) { eventRepository.append(executionId, any(), SseEventType.PIPELINE_COMPLETED, any(), any()) }
            verify(exactly = 0) { eventLog.append(executionId, match { it.eventName == "pipeline_completed" }) }
            verify(exactly = 1) { eventRepository.append(executionId, 1, SseEventType.EXECUTION_STARTED, any(), any()) }
        }

    private fun withClueOf(
        status: ExecutionStatus,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue("root outcome $status", block)

    private fun settledChild(status: ExecutionStatus) =
        SettledChild(
            executionId = childId,
            pipelineId = pipelineId,
            pipelineVersion = 2,
            correlationId = childCorrelation,
            startedAt = NOW,
            completedAt = NOW.plusMillis(7),
            durationMs = 7,
            failedNodeId = null,
            nodeStatsJson = """[{"node_id":"w","status":"SUCCESS"}]""",
            status = status,
            errorJson = if (status == ExecutionStatus.SUCCESS) null else """{"code":"pipeline.execution.local_write_commit_failed"}""",
        )

    private fun rootTerminal(event: co.datapipelines.events.ExecutionEvent) =
        runTest {
            // The caller stubbed persistence (and the settlement's result) first: stubbing here would override it.
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(event)
        }

    private fun rootOutcomes() =
        listOf(
            Triple(
                PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(7), 7, emptyList(), settlesFamily = true),
                ExecutionStatus.SUCCESS,
                SseEventType.PIPELINE_COMPLETED,
            ),
            Triple(
                PipelineFailed(
                    executionId,
                    pipelineId,
                    3,
                    NOW,
                    NOW.plusMillis(7),
                    7,
                    null,
                    MappedError("pipeline.execution.local_write_commit_failed", "x", emptyMap()),
                    emptyList(),
                    status = ExecutionStatus.OUTCOME_UNKNOWN,
                    settlesFamily = true,
                ),
                ExecutionStatus.OUTCOME_UNKNOWN,
                SseEventType.PIPELINE_FAILED,
            ),
            Triple(
                PipelineFailed(
                    executionId,
                    pipelineId,
                    3,
                    NOW,
                    NOW.plusMillis(7),
                    7,
                    "w",
                    MappedError("pipeline.node.query_execution_failed", "x", emptyMap()),
                    emptyList(),
                    settlesFamily = true,
                ),
                ExecutionStatus.FAILED,
                SseEventType.PIPELINE_FAILED,
            ),
            Triple(
                ExecutionAborted(executionId, pipelineId, AbortReason.CANCELLED, NOW.plusMillis(7), emptyList(), settlesFamily = true),
                ExecutionStatus.ABORTED,
                SseEventType.EXECUTION_ABORTED,
            ),
        )

    /** The child's terminal history is written at settlement and says what the ROOT decided — success only after a confirmed root. */
    @Test
    fun `a settled child's history carries the root's final outcome with the child's next event id`() {
        val appended = mutableListOf<Triple<Int, SseEventType, String>>()
        val logged = mutableListOf<co.datapipelines.web.sse.LoggedSseEvent>()
        stubPersistence()
        every { eventRepository.lastEventId(childId) } returns 4
        every { eventRepository.append(childId, any(), any(), any(), any()) } answers {
            appended += Triple(secondArg<Int>(), thirdArg<SseEventType>(), lastArg<String>())
        }
        every { eventLog.append(childId, capture(logged)) } just runs
        rootOutcomes().forEach { (root, status, type) ->
            appended.clear()
            logged.clear()
            every { executionRepository.settleFamiliesDetailed(executionId) } returns listOf(settledChild(status))

            rootTerminal(root)

            withClueOf(status) {
                appended.map { it.first to it.second } shouldBe listOf(5 to type)
                logged.single().eventId shouldBe 5
                logged.single().eventName shouldBe type.wire
                logged.single().payload["status"] shouldBe status.name
                logged.single().payload["execution_id"] shouldBe childId
                logged.single().payload["correlation_id"] shouldBe childCorrelation
                (logged.single().payload["node_stats"] as List<*>).size shouldBe 1
            }
        }
    }

    /** One child's history failing to write costs that entry only — the settlement and the siblings stand. */
    @Test
    fun `a child history write that fails does not stop the siblings or the settlement`() {
        stubPersistence()
        val other = UUID.randomUUID()
        every { eventRepository.lastEventId(childId) } throws IllegalStateException("history store down")
        every { eventRepository.lastEventId(other) } returns 0
        every { executionRepository.settleFamiliesDetailed(executionId) } returns
            listOf(settledChild(ExecutionStatus.SUCCESS), settledChild(ExecutionStatus.SUCCESS).copy(executionId = other))

        rootTerminal(PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(7), 7, emptyList(), settlesFamily = true))

        verify(exactly = 1) { eventRepository.append(other, 1, SseEventType.PIPELINE_COMPLETED, any(), any()) }
        verify(exactly = 0) { eventRepository.append(childId, any(), any(), any(), any()) }
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
                executionRepository.settleFamiliesDetailed(executionId)
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
            verify(exactly = 1) { executionRepository.settleFamiliesDetailed(executionId) }
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

            verify(exactly = 0) { executionRepository.settleFamiliesDetailed(any()) }
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-05T14:30:00Z")
    }
}
