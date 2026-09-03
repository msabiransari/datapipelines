package co.datapipelines.web.sse

import co.datapipelines.events.DataReady
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.events.SseEventType
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.web.config.SseProperties
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The emitter hub (dag-executor §10) over mocked persistence: row creation on `execution_started`,
 * the single terminal update, monotonic per-execution event ids, correlation stamping into the
 * durable payload, and the never-throw failure policy.
 */
class WebEventEmitterTest {
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

    @Test
    fun `execution_started creates the RUNNING row from the captured context`() =
        runTest {
            val record = slot<ExecutionRecord>()
            every { executionRepository.create(capture(record)) } answers { record.captured }
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs

            emitter().emit(ExecutionStarted(executionId, pipelineId, 3, mapOf("start_date" to "2026-01-01"), startedAt = NOW))

            record.captured.executionId shouldBe executionId
            record.captured.status shouldBe ExecutionStatus.RUNNING
            record.captured.triggeredBy shouldBe userId
            record.captured.triggeredVia shouldBe ExecutionTrigger.REST
            record.captured.correlationId shouldBe correlationId
            record.captured.parametersJson shouldBe """{"start_date":"2026-01-01"}"""
        }

    @Test
    fun `a child execution's row carries the composition lineage (metadata-db §4-6)`() =
        runTest {
            val parentExecutionId = UUID.randomUUID()
            val rootExecutionId = UUID.randomUUID()
            val record = slot<ExecutionRecord>()
            every { executionRepository.create(capture(record)) } answers { record.captured }
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs

            WebEventEmitter(
                context =
                    ExecutionContext(
                        pipelineId = pipelineId,
                        pipelineVersion = 4,
                        userId = userId,
                        correlationId = correlationId,
                        triggeredVia = ExecutionTrigger.PIPELINE,
                        parametersJson = """{"region":"EU"}""",
                        workspaceId = workspaceId,
                        parentExecutionId = parentExecutionId,
                        parentNodeId = "revenue",
                        rootExecutionId = rootExecutionId,
                    ),
                stream = null,
                streams = registry,
                eventLog = eventLog,
                eventRepository = eventRepository,
                executionRepository = executionRepository,
                persistenceDispatcher = Dispatchers.Default,
            ).emit(ExecutionStarted(executionId, pipelineId, 4, emptyMap(), startedAt = NOW))

            record.captured.triggeredVia shouldBe ExecutionTrigger.PIPELINE
            record.captured.parentExecutionId shouldBe parentExecutionId
            record.captured.parentNodeId shouldBe "revenue"
            record.captured.rootExecutionId shouldBe rootExecutionId
        }

    @Test
    fun `event ids are monotonic per execution and the terminal event completes the row`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            val eventIds = mutableListOf<Int>()
            every { eventRepository.append(any<UUID>(), capture(eventIds), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(NodeStarted(executionId, "n1", NOW))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(900), 900, emptyList()))
            // data_ready follows pipeline_completed and is NOT a second terminal update.
            emitter.emit(
                DataReady(executionId, pipelineId, emptyList(), emptyList(), 0, false, "http://x", NOW, 300),
            )

            eventIds shouldBe listOf(1, 2, 3, 4)
            verify(exactly = 1) {
                executionRepository.complete(executionId, ExecutionStatus.SUCCESS, any(), 900, any(), null, null, any(), any())
            }
        }

    @Test
    fun `error_json is the same error object the wire carried - record, catalog fields and correlation id`() =
        runTest {
            // 057: the stored copy is the PROJECTED error (user_message/doc_url included), so
            // GET /executions/{id}, the detail page and MCP executions_get read exactly what the
            // live stream showed — one object, not two shapes to keep in step.
            every { executionRepository.create(any()) } answers { firstArg() }
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs

            val record =
                co.datapipelines.executor.MappedError(
                    code = "pipeline.node.datasource_connection_failed",
                    message = "Failed to initialize pool",
                    details = mapOf("phase" to "connect"),
                    node = co.datapipelines.executor.NodeErrorContext("n1", "DQL", "sample-trips", "POSTGRES", "t.sql", 1),
                    sql = "SELECT 1",
                    exception = co.datapipelines.executor.ExceptionDetail("java.lang.RuntimeException", "boom"),
                )
            val event =
                co.datapipelines.events.PipelineFailed(
                    executionId, pipelineId, 3, NOW, NOW.plusMillis(50), 50, "n1", record, emptyList(),
                )

            emitter().emit(event)

            val errorJson = slot<String>()
            verify(exactly = 1) {
                executionRepository.complete(executionId, ExecutionStatus.FAILED, any(), any(), any(), "n1", capture(errorJson), any(), any())
            }
            val stored = SseJson.mapper.readTree(errorJson.captured)
            stored["code"].asText() shouldBe "pipeline.node.datasource_connection_failed"
            stored["message"].asText() shouldBe "Failed to initialize pool"
            stored["user_message"].asText() shouldBe "We couldn't reach the database this step uses. Check that it is online and reachable from this server."
            stored["correlation_id"].asText() shouldBe correlationId.toString()
            stored["node"]["datasource"].asText() shouldBe "sample-trips"
            stored["node"]["dialect"].asText() shouldBe "POSTGRES"
            stored["node"]["template_version"].asInt() shouldBe 1
            stored["sql"].asText() shouldBe "SELECT 1"
            stored["exception"]["class"].asText() shouldBe "java.lang.RuntimeException"
        }

    @Test
    fun `the durable payload carries the correlation id on every event`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            val payloads = mutableListOf<String>()
            every { eventRepository.append(any<UUID>(), any(), any(), any(), capture(payloads)) } just runs
            every { eventLog.append(any(), any()) } just runs

            val emitter = emitter()
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(NodeStarted(executionId, "n1", NOW))

            payloads.size shouldBe 2
            payloads.forEach { it.contains("\"correlation_id\":\"$correlationId\"") shouldBe true }
        }

    @Test
    fun `a persistence failure is logged and swallowed, never thrown into the executor`() =
        runTest {
            every { executionRepository.create(any()) } throws RuntimeException("db down")
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } throws RuntimeException("db down")
            every { eventLog.append(any(), any()) } just runs

            // Must not throw (dag-executor §10: the emitter never fails an execution).
            emitter().emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
        }

    @Test
    fun `the started hook fires with the executor-minted id`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs
            var hooked: UUID? = null
            val withHook =
                WebEventEmitter(
                    ExecutionContext(pipelineId, 3, userId, correlationId, ExecutionTrigger.REST, "{}", workspaceId),
                    null,
                    registry,
                    eventLog,
                    eventRepository,
                    executionRepository,
                    Dispatchers.Default,
                ) { hooked = it }
            withHook.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            hooked shouldBe executionId
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-05T14:30:00Z")
    }
}
