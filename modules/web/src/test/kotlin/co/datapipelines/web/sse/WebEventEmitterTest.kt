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
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.web.config.SseProperties
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
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

    private fun emitter(metrics: ExecutorMetrics? = null): WebEventEmitter =
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
            metrics = metrics,
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
            record.captured.executedBy shouldBe userId
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

    /**
     * The 306 merge's review (#325): a terminal UPDATE that matches NO row — the RUNNING insert was abandoned
     * past its bound or never landed — is counted as a lifecycle write failure and logged, never silent, and
     * nothing fabricates a COMPLETED. A real registry, not a mock: the assertion is the counter's value.
     */
    @Test
    fun `a terminal UPDATE that matches no row is counted as a lifecycle write failure - never a silent success`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns false
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs
            val registry = SimpleMeterRegistry()

            val emitter = emitter(metrics = ExecutorMetrics(registry))
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(PipelineCompleted(executionId, pipelineId, 3, NOW, NOW.plusMillis(900), 900, emptyList()))

            registry.counter(ExecutorMetrics.EXECUTIONS_LIFECYCLE_WRITE_FAILED).count() shouldBe 1.0
        }

    /**
     * 149: a `node_progress` sample takes the SAME path every other event does — a durable row,
     * a log entry, the live stream — and is NOT terminal: it completes no row and does not mark
     * the stream terminal, so the disconnect-grace rule is untouched by it.
     */
    @Test
    fun `node_progress is persisted, logged and streamed like any event and completes nothing`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            val types = mutableListOf<co.datapipelines.events.SseEventType>()
            val payloads = mutableListOf<String>()
            every { eventRepository.append(any<UUID>(), any(), capture(types), any(), capture(payloads)) } just runs
            val logged = mutableListOf<LoggedSseEvent>()
            every { eventLog.append(any(), capture(logged)) } just runs
            val mapper =
                com.fasterxml.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
            val stream =
                ExecutionStream(
                    executionId,
                    userId,
                    org.springframework.web.servlet.mvc.method.annotation
                        .SseEmitter(),
                    mapper,
                )

            val emitter =
                WebEventEmitter(
                    context =
                        ExecutionContext(pipelineId, 3, userId, correlationId, ExecutionTrigger.REST, "{}", workspaceId),
                    stream = stream,
                    streams = registry,
                    eventLog = eventLog,
                    eventRepository = eventRepository,
                    executionRepository = executionRepository,
                    persistenceDispatcher = Dispatchers.Default,
                )
            emitter.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
            emitter.emit(co.datapipelines.events.NodeProgress(executionId, writingSample()))

            types shouldBe listOf(SseEventType.EXECUTION_STARTED, SseEventType.NODE_PROGRESS)
            payloads[1].contains("\"state\":\"writing\"") shouldBe true
            payloads[1].contains("\"correlation_id\":\"$correlationId\"") shouldBe true
            logged.map { it.eventName } shouldBe listOf("execution_started", "node_progress")
            logged[1].eventId shouldBe 2
            stream.isTerminal shouldBe false
            verify(exactly = 0) { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    private fun writingSample() =
        co.datapipelines.executor.OperationSnapshot(
            nodeId = "n1",
            attempt = 1,
            sequence = 1,
            kind = co.datapipelines.executor.OperationKind.STAGE,
            destination =
                co.datapipelines.executor.OperationDestination
                    .tempdb("t"),
            state = co.datapipelines.executor.OperationState.WRITING,
            startedAt = NOW,
            observedAt = NOW.plusMillis(400),
            elapsedMs = 400,
            timingsMs = mapOf(co.datapipelines.executor.OperationPhase.WRITING to 300L),
            rowsFetched = 10,
            rowsWritten = 10,
            batchesWritten = 1,
            committed = null,
            rolledBack = null,
            childExecutionId = null,
        )

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
                    executionId,
                    pipelineId,
                    3,
                    NOW,
                    NOW.plusMillis(50),
                    50,
                    "n1",
                    record,
                    emptyList(),
                )

            emitter().emit(event)

            val errorJson = slot<String>()
            verify(exactly = 1) {
                executionRepository.complete(
                    executionId,
                    ExecutionStatus.FAILED,
                    any(),
                    any(),
                    any(),
                    "n1",
                    capture(errorJson),
                    any(),
                    any(),
                )
            }
            val stored = SseJson.mapper.readTree(errorJson.captured)
            stored["code"].asText() shouldBe "pipeline.node.datasource_connection_failed"
            stored["message"].asText() shouldBe "Failed to initialize pool"
            stored["user_message"].asText() shouldBe
                "We couldn't reach the database this step uses. Check that it is online and reachable from this server."
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

    /**
     * The launcher's trailing lambda registers the live stream, and `execution_started` must reach
     * that stream — so the lambda has to be the hook that runs BEFORE the stream lookup. #9 once
     * appended another function-typed parameter after it; Kotlin bound the launcher's lambda to the
     * new one, which runs after persistence, and every live stream silently lost its first event.
     * Falsified: moving `onRecorded` back after `onExecutionStarted` turns this red.
     */
    @Test
    fun `a trailing lambda is the started hook - a stream it registers receives execution_started itself`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs
            val sse = mockk<org.springframework.web.servlet.mvc.method.annotation.SseEmitter>(relaxed = true)
            // The payload carries java.time values; production's mapper has the module, so this one does too.
            val mapper =
                com.fasterxml.jackson.databind.json.JsonMapper
                    .builder()
                    .findAndAddModules()
                    .build()
            val withHook =
                WebEventEmitter(
                    ExecutionContext(pipelineId, 3, userId, correlationId, ExecutionTrigger.REST, "{}", workspaceId),
                    null,
                    registry,
                    eventLog,
                    eventRepository,
                    executionRepository,
                    Dispatchers.Default,
                ) { registry.register(ExecutionStream(it, userId, sse, mapper)) }

            withHook.emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))

            verify(exactly = 1) { sse.send(any<org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder>()) }
        }

    /**
     * #306 — the order for the first event: the started HOOK (the launcher's stream registration)
     * → the RUNNING row's insert → `onRecorded` (the scheduler's start barrier) → the live SEND.
     * The row is committed before the frame that carries the id, so a client that cancels or reads
     * on the id the moment the frame lands is answered 204/200, never `404
     * result.execution_not_found`. Red on the pre-#306 order (hook, send, insert, recorded) — the
     * send is first there. The stream is registered through the hook, exactly as the launcher does.
     */
    @Test
    fun `the started hook runs before persistence, the recorded hook only after the row exists`() =
        runTest {
            val order = mutableListOf<String>()
            every { executionRepository.create(any()) } answers {
                order += "insert"
                firstArg()
            }
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } just runs
            every { eventLog.append(any(), any()) } just runs
            val sse = mockk<org.springframework.web.servlet.mvc.method.annotation.SseEmitter>(relaxed = true)
            every { sse.send(any<SseEmitter.SseEventBuilder>()) } answers { order += "send" }

            hookedEmitter(
                onRecorded = { order += "recorded" },
                onStarted = {
                    order += "started"
                    // The payload carries java.time values; production's mapper has the module, so
                    // this one does too — the same shape the trailing-lambda case below uses.
                    val mapper =
                        com.fasterxml.jackson.databind.json.JsonMapper
                            .builder()
                            .findAndAddModules()
                            .build()
                    registry.register(ExecutionStream(it, userId, sse, mapper))
                },
            ).emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))

            order shouldBe listOf("started", "insert", "recorded", "send")
        }

    @Test
    fun `fail closed - an unwritable RUNNING row stops the scheduled execution before any event row (A14)`() =
        runTest {
            every { executionRepository.create(any()) } throws RuntimeException("db down")
            every { eventLog.append(any(), any()) } just runs
            var recorded = false

            val thrown =
                runCatching {
                    hookedEmitter(failClosed = true, onRecorded = { recorded = true })
                        .emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))
                }.exceptionOrNull()

            (thrown is ExecutionRecordUnwritableException) shouldBe true
            (thrown as ExecutionRecordUnwritableException).executionId shouldBe executionId
            recorded shouldBe false
            verify(exactly = 0) { eventRepository.append(any<UUID>(), any(), any(), any(), any()) }
        }

    @Test
    fun `without fail closed the same failure is swallowed and the recorded hook stays silent`() =
        runTest {
            every { executionRepository.create(any()) } throws RuntimeException("db down")
            every { eventRepository.append(any<UUID>(), any(), any(), any(), any()) } throws RuntimeException("db down")
            every { eventLog.append(any(), any()) } just runs
            var recorded = false

            hookedEmitter(onRecorded = { recorded = true }).emit(ExecutionStarted(executionId, pipelineId, 3, emptyMap(), startedAt = NOW))

            recorded shouldBe false
        }

    /**
     * #266 B.3: through the BATCHED recorder, two executions whose emits interleave on ONE writer
     * (one partition, one thread, shared batches) each keep their own 1..N order in what the store
     * receives — the writers are FIFO per execution and the emit is still awaited. Red if the
     * recorder stopped awaiting (a later event could overtake) or the writer reordered a batch.
     */
    @Test
    fun `two executions interleaving on one writer keep their own 1-to-N order in the recorded store`() =
        runTest {
            every { executionRepository.create(any()) } answers { firstArg() }
            every { executionRepository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            every { eventLog.entry(any(), any()) } answers {
                ReplayLogEntry(firstArg(), secondArg<LoggedSseEvent>().eventId, "{}")
            }
            val rowsSink = RecordingSink<co.datapipelines.executor.ExecutionEventRecord> { it.executionId }
            val replaySink = RecordingSink<ReplayLogEntry> { it.executionId }
            val config = co.datapipelines.persistence.BatchingConfig(writers = 1)
            val rows = co.datapipelines.persistence.BatchingWriter("rows", config, rowsSink)
            val replay = co.datapipelines.persistence.BatchingWriter("replay", config, replaySink)
            val direct =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            try {
                val recorder = BatchedEventRecorder(rows, replay, eventLog, direct)
                val executions = listOf(UUID.randomUUID(), UUID.randomUUID())
                // FORCED, not raced (#266b): an awaiting emitter has one row outstanding at a time, so
                // two executions share a batch only if both rows queue while the writer is busy with
                // something else. Left to timing that was a coin toss — red under load on the 266b
                // pregate. A sentinel row holds the one writer until both first rows are queued behind it.
                rowsSink.holdNextWrite()
                rows.submit(co.datapipelines.executor.ExecutionEventRecord(SENTINEL, 1, "hold", NOW, "{}")) shouldBe true
                rowsSink.awaitHeld()
                kotlinx.coroutines.coroutineScope {
                    executions.forEach { id ->
                        launch(Dispatchers.Default) {
                            val emitter = batchedEmitter(recorder)
                            emitter.emit(ExecutionStarted(id, pipelineId, 3, emptyMap(), startedAt = NOW))
                            repeat(INTERLEAVED) { n -> emitter.emit(NodeStarted(id, "n$n", NOW)) }
                            emitter.emit(PipelineCompleted(id, pipelineId, 3, NOW, NOW.plusMillis(900), 900, emptyList()))
                        }
                    }
                    launch(Dispatchers.Default) {
                        // The sentinel in its commit plus both executions' first rows.
                        val deadline =
                            System.nanoTime() +
                                java.util.concurrent.TimeUnit.SECONDS
                                    .toNanos(10)
                        while (rows.queueDepth() < 3 && System.nanoTime() < deadline) Thread.sleep(1)
                        rowsSink.release()
                    }
                }
                val expected = (1..INTERLEAVED + 2).toList()
                executions.forEach { id ->
                    rowsSink.items().filter { it.executionId == id }.map { it.eventId } shouldBe expected
                    replaySink.items().filter { it.executionId == id }.map { it.eventId } shouldBe expected
                }
                // Non-vacuity: the two executions really did share the one writer's batches.
                withClue("batches by execution: ${rowsSink.batches().map { b -> b.map { it.executionId } }}") {
                    rowsSink.batches().any { batch -> batch.map { it.executionId }.containsAll(executions) } shouldBe true
                }
                verify(exactly = 0) { eventRepository.append(any<UUID>(), any(), any(), any(), any()) }
            } finally {
                rows.close()
                replay.close()
                direct.shutdownNow()
            }
        }

    /** A REST execution's emitter recording through [recorder] — the batched path. */
    private fun batchedEmitter(recorder: ExecutionEventRecorder): WebEventEmitter =
        WebEventEmitter(
            context = ExecutionContext(pipelineId, 3, userId, correlationId, ExecutionTrigger.REST, "{}", workspaceId),
            stream = null,
            streams = registry,
            eventLog = eventLog,
            eventRepository = eventRepository,
            executionRepository = executionRepository,
            persistenceDispatcher = Dispatchers.Default,
            eventRecorder = recorder,
        )

    /** An in-memory store for the batched recorder: every batch, in commit order. */
    private class RecordingSink<T>(
        private val key: (T) -> Any,
    ) : co.datapipelines.persistence.BatchSink<T> {
        private val batches = java.util.Collections.synchronizedList(mutableListOf<List<T>>())

        fun batches(): List<List<T>> = synchronized(batches) { batches.toList() }

        fun items(): List<T> = batches().flatten()

        /** The gate the NEXT write waits on, and the one a write is waiting on now (a release reaches either). */
        @Volatile private var armed: java.util.concurrent.CountDownLatch? = null

        @Volatile private var holding: java.util.concurrent.CountDownLatch? = null
        private val held = java.util.concurrent.CountDownLatch(1)

        fun holdNextWrite() {
            armed = java.util.concurrent.CountDownLatch(1)
        }

        fun awaitHeld() {
            held.await(TEN_SECONDS, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        }

        fun release() {
            armed?.countDown()
            holding?.countDown()
        }

        override fun write(items: List<T>) {
            armed?.let { gate ->
                armed = null
                holding = gate
                held.countDown()
                gate.await(TEN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            }
            // A short commit, so emits from the other execution queue up behind it and share the next batch.
            Thread.sleep(1)
            batches += items
        }

        override fun partitionKey(item: T): Any = key(item)

        override fun sizeOf(item: T): Int = 1

        override fun describe(item: T): String = item.toString()
    }

    private fun hookedEmitter(
        failClosed: Boolean = false,
        onRecorded: (UUID) -> Unit = {},
        onStarted: (UUID) -> Unit = {},
    ): WebEventEmitter =
        WebEventEmitter(
            context = ExecutionContext(pipelineId, 3, userId, correlationId, ExecutionTrigger.SCHEDULE, "{}", workspaceId),
            stream = null,
            streams = registry,
            eventLog = eventLog,
            eventRepository = eventRepository,
            executionRepository = executionRepository,
            persistenceDispatcher = Dispatchers.Default,
            failClosedOnRecord = failClosed,
            onRecorded = onRecorded,
            onExecutionStarted = onStarted,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-05T14:30:00Z")
        const val INTERLEAVED = 60
        const val TEN_SECONDS = 10L

        /** The row that holds the one writer while both executions' first rows queue behind it. */
        val SENTINEL: UUID = UUID.fromString("5e471e11-0000-0000-0000-000000000266")
    }
}
