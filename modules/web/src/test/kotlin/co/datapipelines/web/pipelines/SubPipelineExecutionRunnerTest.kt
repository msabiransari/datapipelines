package co.datapipelines.web.pipelines

import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.DirectResultSink
import co.datapipelines.executor.ExecutableNode
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionAbortedException
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.ExecutorMetrics
import co.datapipelines.executor.InMemoryCancellationRegistry
import co.datapipelines.executor.NodeExecutionContext
import co.datapipelines.executor.PipelineExecutionFailed
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.RunContext
import co.datapipelines.executor.StoredResult
import co.datapipelines.executor.WarningSink
import co.datapipelines.executor.WritebackRunner
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.Staging
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The composition runtime (design 2026-08-13-pipeline-node-type §4): the child request's lineage
 * and trigger, the runtime depth backstop, `direct`-delivery sink adapters per output target,
 * failure mapping with the child's code and execution id, and the zero-caller side-effect shape.
 *
 * The repository and the executor are mocked — the runner's job is what it BUILDS and how it
 * maps outcomes; the executor's own behavior under those requests is dag's test surface
 * (`SubPipelineCompositionTest`).
 */
class SubPipelineExecutionRunnerTest {
    private val pipelines = mockk<PipelineRepository>()
    private val resultStore = mockk<ResultStore>()
    private val writebackRunner = mockk<WritebackRunner>()
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)

    private val parentExecutionId = UUID.randomUUID()
    private val parentRootId = UUID.randomUUID()
    private val parentUserId = UUID.randomUUID()
    private val parentWorkspaceId = UUID.randomUUID()
    private val parentCorrelationId = UUID.randomUUID()
    private val childRecordId = UUID.randomUUID()

    private val staging: Staging = H2StagingFactory(H2StagingProperties()).create(parentExecutionId)

    @AfterEach
    fun tearDown() = staging.close()

    // ------------------------------------------------------------- fixtures

    private val childBody =
        """
        {
          "schema_version": 1,
          "name": "monthly_revenue",
          "display_name": "Monthly revenue",
          "description": "",
          "parameters": {
            "region": {"type": "STRING", "required": true},
            "limit": {"type": "INTEGER", "default": 10}
          },
          "nodes": [
            {"id": "q", "description": "q", "type": "DQL", "source": "tempdb",
             "template": {"id": "tq", "version": 1}, "output": {"target": "caller"}}
          ]
        }
        """.trimIndent()

    private fun childRecord() =
        PipelineRecord(
            id = childRecordId,
            name = "monthly_revenue",
            displayName = "Monthly revenue",
            description = "",
            ownerId = parentUserId,
            currentVersion = 4,
            isDeleted = false,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun stubRegistry() {
        every { pipelines.findByNameIncludingDeleted(any(), "monthly_revenue") } returns childRecord()
        every { pipelines.findVersionBody(any(), childRecordId, 4) } returns childBody
    }

    private fun pipelineNode(
        output: NodeOutput? = null,
        parameters: Map<String, JsonNode>? = null,
    ): ExecutableNode =
        ExecutableNode.from(
            Node(
                id = "revenue",
                description = "Monthly revenue component.",
                type = NodeType.PIPELINE,
                source = "",
                template = TemplateRef(),
                output = output,
                dependsOn = emptyList(),
                pipeline = PipelineNodeRef("monthly_revenue", 4),
                parameters = parameters,
            ),
        )

    private fun context(
        compositionDepth: Int = 0,
        values: Map<String, Any?> = emptyMap(),
        directSink: DirectResultSink? = null,
    ): NodeExecutionContext =
        NodeExecutionContext(
            executionId = parentExecutionId,
            staging = staging,
            handle = InMemoryCancellationRegistry().register(parentExecutionId),
            values = RunContext.of(values),
            warnings = WarningSink(),
            resultTtlSeconds = 300,
            renderBudgetChars = 4096,
            stagingMaxMemoryMb = 1024,
            tempdbDialect = Dialect.H2,
            userId = parentUserId,
            rootExecutionId = parentRootId,
            compositionDepth = compositionDepth,
            directSink = directSink,
            correlationId = parentCorrelationId,
            workspaceId = parentWorkspaceId,
        )

    /**
     * The executor mock + capture slot. [drive] runs inside the mocked `execute` so a test can
     * play the child: invoke the sink, return a result, or throw.
     */
    private class ExecutorStub {
        val captured = mutableListOf<ExecuteRequest>()
        val executor: PipelineExecutor = mockk()
        var drive: suspend (ExecuteRequest) -> ExecutionResult = { req -> success(req) }

        init {
            coEvery { executor.execute(any()) } coAnswers {
                val request = firstArg<ExecuteRequest>()
                captured += request
                drive(request)
            }
        }

        fun success(request: ExecuteRequest): ExecutionResult =
            ExecutionResult(
                executionId = requireNotNull(request.executionId),
                status = ExecutionStatus.SUCCESS,
                nodeStats = emptyList(),
                resultRef = null,
                startedAt = Instant.now(),
                completedAt = Instant.now(),
                durationMs = 5,
            )
    }

    private fun runner(
        stub: ExecutorStub,
        maxCompositionDepth: Int = 5,
        /**
         * Real, not a mock (F6): `datapipelines.staging.rows` is recorded on this path, and a bare
         * `mockk()` would have turned the missing call into an unstubbed-call failure the moment it
         * appeared — which is to say it could never have shown the call was ABSENT.
         */
        metrics: ExecutorMetrics = ExecutorMetrics.inMemory(),
    ) = SubPipelineExecutionRunner(
        pipelines = pipelines,
        templateEngines = mockk(),
        datasourceRegistry = mockk(),
        stagingFactory = mockk(),
        writebackRunner = writebackRunner,
        resultStore = resultStore,
        cancellationRegistry = mockk(),
        cancellationFlags = mockk(),
        executionSlots = mockk(),
        executorDispatcher = mockk(),
        executorConfig = ExecutorConfig(maxCompositionDepth = maxCompositionDepth),
        resultUrls = mockk(),
        executorMetrics = metrics,
        persistenceDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        streams = mockk(),
        eventLog = mockk(relaxed = true),
        eventRepository = mockk(relaxed = true),
        executionRepository = executionRepository,
        executorFactory = { stub.executor },
    )

    private val schema =
        listOf(
            ColumnSchema("region", LogicalType.STRING),
            ColumnSchema("total", LogicalType.INTEGER),
        )

    // ---------------------------------------------------------------- tests

    @Test
    fun `the child request carries the lineage, the PIPELINE trigger, the incremented depth, and the parent's principal`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()

            val result = runner(stub).run(pipelineNode(), context())

            val request = stub.captured.single()
            request.pipelineId shouldBe childRecordId
            request.pipelineVersion shouldBe 4
            request.userId shouldBe parentUserId
            request.triggeredVia shouldBe ExecutionTrigger.PIPELINE
            request.parentExecutionId shouldBe parentExecutionId
            request.parentNodeId shouldBe "revenue"
            request.rootExecutionId shouldBe parentRootId
            request.compositionDepth shouldBe 1
            // Composition inherits the parent's workspace (§5.3, 025 A5): carried on the context,
            // no longer re-fetched from the parent execution's row.
            request.workspaceId shouldBe parentWorkspaceId
            request.executionId shouldNotBe null
            result.childExecutionId shouldBe request.executionId
        }

    /**
     * F5 — the child minted `correlationId = UUID.randomUUID()`, so its `pipeline_executions` row,
     * its SSE payloads (via `SseEventProjection`) and its logs all carried an id unrelated to the
     * request that started the family. Correlation id is the one field designed to join exactly
     * that (rest-api §3.4, observability §3.3), so a composition family was the one shape it could
     * not join.
     */
    @Test
    fun `the child request inherits the parent's correlation id rather than minting a fresh one`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()

            runner(stub).run(pipelineNode(), context())

            // On the REQUEST, so it also reaches the child's own `execution_started` payload and is
            // inherited again by any grandchild the child spawns.
            stub.captured.single().correlationId shouldBe parentCorrelationId
        }

    @Test
    fun `node parameters pass literals through and resolve refs against the parent's bound values`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            val node =
                pipelineNode(
                    parameters =
                        mapOf(
                            "region" to TextNode("\${region}"),
                            "limit" to IntNode(25),
                        ),
                )

            runner(stub).run(node, context(values = mapOf("region" to "EU")))

            val parameters = stub.captured.single().parameters
            parameters["region"] shouldBe TextNode("EU")
            parameters["limit"] shouldBe IntNode(25)
        }

    /**
     * F4 — the backstop's boundary, in the unit save-time validation uses: **pipelines**.
     *
     * `CompositionRules.referenceDepth` counts pipelines (a leaf pipeline is depth 1) and
     * `checkDepth` rejects `depth > max`. `CompositionRulesTest`'s
     * "a reference chain deeper than the configured maximum is composition_too_deep" pins the same
     * boundary on the save-time path with the same numbers: a `parent → child → grandchild` tree is
     * depth 3, accepted at `max = 3` and rejected at `max = 2`. This is the runtime twin, so the two
     * guards can be read against each other rather than drifting apart again.
     *
     * `compositionDepth` on the context counts HOPS above the parent execution (a root is 0), so a
     * parent at `compositionDepth = 1` is itself the 2nd pipeline and its child is the 3rd.
     */
    @Test
    fun `a child at exactly the maximum depth runs, and one beyond is refused with the catalogued code`() =
        runTest {
            stubRegistry()
            // max = 3, matching CompositionRulesTest: a parent at hop 1 (the 2nd pipeline) spawns
            // the 3rd — exactly the maximum, so it runs.
            val allowed = ExecutorStub()
            runner(allowed, maxCompositionDepth = 3).run(pipelineNode(), context(compositionDepth = 1))
            allowed.captured.single().compositionDepth shouldBe 2

            // One more generation is the 4th pipeline — refused before any registry read, and
            // refused at exactly the tree save-time validation would have rejected.
            val refused = ExecutorStub()
            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(refused, maxCompositionDepth = 3).run(pipelineNode(), context(compositionDepth = 2))
                }
            thrown.code shouldBe PipelineErrorCodes.Node.COMPOSITION_DEPTH_EXCEEDED
            thrown.details["depth"] shouldBe 4
            thrown.details["max"] shouldBe 3
            refused.captured shouldBe emptyList()
        }

    /**
     * F4's regression pin: at the configured default the backstop admits exactly the reference tree
     * §12.9 admits — 5 pipelines — and refuses the 6th. Counting hops instead admitted a 6th
     * pipeline, so the backstop would have run a chain `POST /pipelines` refuses to save (which is
     * what `PipelineCompositionE2eTest`'s depth-6 scenario asserts on the save side).
     */
    @Test
    fun `at the documented default the backstop admits the same five pipelines save-time validation does`() =
        runTest {
            stubRegistry()
            // The 5th pipeline: a parent at hop 3 is the 4th, its child the 5th.
            val fifth = ExecutorStub()
            runner(fifth, maxCompositionDepth = DEFAULT_MAX_DEPTH).run(pipelineNode(), context(compositionDepth = 3))
            fifth.captured.single().compositionDepth shouldBe 4

            // The 6th — the depth `pipeline.validation.composition_too_deep` rejects at save.
            val sixth = ExecutorStub()
            shouldThrow<DatapipelinesException> {
                runner(sixth, maxCompositionDepth = DEFAULT_MAX_DEPTH).run(pipelineNode(), context(compositionDepth = 4))
            }.details["depth"] shouldBe 6
            sixth.captured shouldBe emptyList()
        }

    @Test
    fun `a failed child fails the node with the child's error code and execution id in the detail`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            stub.drive = { request ->
                throw PipelineExecutionFailed(
                    failedNodeId = "q",
                    errorCode = PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
                    errorDetails = mapOf("sql_state" to "42S02"),
                )
            }

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(stub).run(pipelineNode(), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
            thrown.details["child_error_code"] shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
            thrown.details["child_failed_node_id"] shouldBe "q"
            thrown.details["child_execution_id"] shouldBe
                stub.captured
                    .single()
                    .executionId
                    .toString()
        }

    @Test
    fun `an aborted child propagates as cancellation, never as a node failure`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            stub.drive = { throw ExecutionAbortedException(AbortReason.CANCELLED) }

            shouldThrow<ExecutionAbortedException> {
                runner(stub).run(pipelineNode(), context())
            }
        }

    @Test
    fun `a pinned reference that vanished from the registry fails as a child failure, not an NPE`() =
        runTest {
            every { pipelines.findByNameIncludingDeleted(any(), "monthly_revenue") } returns null

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(ExecutorStub()).run(pipelineNode(), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
        }

    @Test
    fun `a caller-target node re-publishes the child's rows as the parent's own caller result`() =
        runTest {
            stubRegistry()
            val stored = StoredResult("dp:result:parent", 2, 40, Instant.now().plusSeconds(300))
            coEvery { resultStore.materializeRows(any(), any(), any(), any()) } returns stored
            val stub = ExecutorStub()
            stub.drive = { request ->
                request.directSink shouldNotBe null
                request.directSink?.accept(schema, sequenceOf(listOf("EU", 3), listOf("US", 4)))
                stub.success(request)
            }

            val result = runner(stub).run(pipelineNode(output = NodeOutput.Caller), context())

            result.callerResultRef shouldBe "dp:result:parent"
            result.rowsOut shouldBe 2
            result.bytesOutEstimate shouldBe 40
            // The result is keyed by the PARENT's execution, with the parent's TTL — never the child's.
            coVerifyStore(parentExecutionId, 300)
        }

    @Test
    fun `a caller-target node inside a direct child execution streams the rows onward to its own invoker`() =
        runTest {
            stubRegistry()
            // The PARENT execution is itself a child: its caller result must not materialize
            // under its own id — it streams to the sink its invoker attached (design §4.2,
            // identical post-node behavior to a DQL caller node under direct delivery).
            val upstreamRows = mutableListOf<List<Any?>>()
            var upstreamSchema: List<ColumnSchema>? = null
            val upstream =
                DirectResultSink { schema, rows ->
                    upstreamSchema = schema
                    rows.forEach { upstreamRows += it }
                }
            val stub = ExecutorStub()
            stub.drive = { request ->
                request.directSink?.accept(schema, sequenceOf(listOf("EU", 3), listOf("US", 4)))
                stub.success(request)
            }

            val result =
                runner(stub).run(pipelineNode(output = NodeOutput.Caller), context(directSink = upstream))

            upstreamSchema shouldBe schema
            upstreamRows shouldBe listOf(listOf("EU", 3), listOf("US", 4))
            result.rowsOut shouldBe 2
            result.callerResultRef shouldBe null
            coVerify(exactly = 0) { resultStore.materializeRows(any(), any(), any(), any()) }
        }

    @Test
    fun `a caller node whose child yields zero rows publishes an empty result, not an absent one`() =
        runTest {
            stubRegistry()
            val stored = StoredResult("dp:result:parent", 0, 0, Instant.now().plusSeconds(300))
            coEvery { resultStore.materializeRows(any(), any(), any(), any()) } returns stored
            val stub = ExecutorStub()
            stub.drive = { request ->
                request.directSink?.accept(schema, emptySequence())
                stub.success(request)
            }

            val result = runner(stub).run(pipelineNode(output = NodeOutput.Caller), context())

            result.callerResultRef shouldBe "dp:result:parent"
            result.rowsOut shouldBe 0
            coVerifyStore(parentExecutionId, 300)
        }

    @Test
    fun `a caller-target node whose child delivers nothing fails instead of reporting a silent empty success`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            // The child "succeeds" without ever invoking the sink — unreachable per §12.9, so the
            // runner's guard is what stands between this and a SUCCESS with no data_ready.
            stub.drive = { request -> stub.success(request) }

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(stub).run(pipelineNode(output = NodeOutput.Caller), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
        }

    /**
     * F3 — the delivery guard covered `caller` only, so the two silent shapes went unguarded.
     *
     * The guard exists for the case §12.9 makes unreachable: a body reaching the runtime without
     * save-time validation, which is exactly what `requireRef` and `loadChild` defend against. A
     * `tempdb` node whose pinned child has no caller node completed `SUCCESS` with `rowsOut = 0`
     * and **no table created** — and the failure then surfaced on a downstream node as an unrelated
     * "table not found", pointing at the wrong node entirely.
     */
    @Test
    fun `a tempdb-target node whose child delivers nothing fails instead of leaving the table uncreated`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            // The zero-caller child: it "succeeds" without ever invoking the sink.
            stub.drive = { request -> stub.success(request) }

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(stub).run(pipelineNode(output = NodeOutput.Tempdb("stg_absent")), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
            stagedTableCount("stg_absent") shouldBe 0L
        }

    /** F3, the other silent shape: a write-back that wrote nothing and said `SUCCESS`. */
    @Test
    fun `a datasource-target node whose child delivers nothing fails instead of silently writing nothing`() =
        runTest {
            stubRegistry()
            val output = NodeOutput.Datasource("warehouse", "revenue", WriteMode.APPEND)
            val stub = ExecutorStub()
            stub.drive = { request -> stub.success(request) }

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(stub).run(pipelineNode(output = output), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
            verify(exactly = 0) { writebackRunner.writebackRows(any(), any(), any(), any()) }
        }

    @Test
    fun `a tempdb-target node stages the child's rows into the parent's staging table`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            stub.drive = { request ->
                request.directSink?.accept(schema, sequenceOf(listOf("EU", 3), listOf("US", 4)))
                stub.success(request)
            }

            val result = runner(stub).run(pipelineNode(output = NodeOutput.Tempdb("stg_revenue")), context())

            result.rowsOut shouldBe 2
            staging.withConnection { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM \"stg_revenue\"").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 2L
                    }
                }
            }
        }

    /**
     * F6 — `NodeRunner.dispatchOutput`'s `Tempdb` branch calls `metrics.rowsStaged`; `sinkFor`'s did
     * not, so every row staged through a PIPELINE node was invisible to `datapipelines.staging.rows`.
     * This repo treats that metric as load-bearing: it had zero call sites before 009/F10 and was
     * "permanently 0", and a composition-heavy deployment would have quietly reproduced that.
     */
    @Test
    fun `a tempdb-target node records the staged rows on datapipelines_staging_rows`() =
        runTest {
            stubRegistry()
            val registry = SimpleMeterRegistry()
            val stub = ExecutorStub()
            stub.drive = { request ->
                request.directSink?.accept(schema, sequenceOf(listOf("EU", 3), listOf("US", 4)))
                stub.success(request)
            }

            runner(stub, metrics = ExecutorMetrics(registry))
                .run(pipelineNode(output = NodeOutput.Tempdb("stg_metered")), context())

            registry
                .find(ExecutorMetrics.STAGING_ROWS)
                .counter()
                ?.count() shouldBe 2.0
        }

    @Test
    fun `a child that fails after the sink consumed rows leaves no partial table and fails the node`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()
            stub.drive = { request ->
                val failing =
                    sequence {
                        yield(listOf("EU", 3))
                        throw ChildCursorDied()
                    }
                request.directSink?.accept(schema, failing)
                stub.success(request)
            }

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner(stub).run(pipelineNode(output = NodeOutput.Tempdb("stg_revenue")), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
            // The partial stage rolled back: no table, and the name is not poisoned.
            staging.withConnection { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'stg_revenue'",
                        ).use { rs ->
                            rs.next()
                            rs.getLong(1) shouldBe 0L
                        }
                }
            }
        }

    @Test
    fun `a datasource-target node streams the child's rows through the write-back runner`() =
        runTest {
            stubRegistry()
            val output = NodeOutput.Datasource("warehouse", "revenue", WriteMode.APPEND)
            every { writebackRunner.writebackRows(any(), any(), output, any()) } answers
                {
                    secondArg<Sequence<List<Any?>>>().count().toLong()
                }
            val stub = ExecutorStub()
            stub.drive = { request ->
                request.directSink?.accept(schema, sequenceOf(listOf("EU", 3), listOf("US", 4)))
                stub.success(request)
            }

            val result = runner(stub).run(pipelineNode(output = output), context())

            result.rowsOut shouldBe 2
            verify(exactly = 1) { writebackRunner.writebackRows(schema, any(), output, parentWorkspaceId) }
        }

    @Test
    fun `a zero-caller child completes as a side-effect-only node with no sink and no result`() =
        runTest {
            stubRegistry()
            val stub = ExecutorStub()

            val result = runner(stub).run(pipelineNode(output = null), context())

            stub.captured.single().directSink shouldBe null
            result.status.name shouldBe "SUCCESS"
            result.rowsOut shouldBe 0
            result.callerResultRef shouldBe null
            result.childExecutionId shouldNotBe null
            coVerify(exactly = 0) { resultStore.materializeRows(any(), any(), any(), any()) }
        }

    /** Base tables in the parent's tempdb catalog under [tableName] — 0 when nothing was staged. */
    private suspend fun stagedTableCount(tableName: String): Long =
        staging.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '$tableName'")
                    .use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
            }
        }

    private fun coVerifyStore(
        executionId: UUID,
        ttl: Long,
    ) {
        coVerify(exactly = 1) {
            resultStore.materializeRows(executionId, schema, any(), ttl)
        }
    }

    /** The mid-stream child failure, as a named type (detekt: no generic throws). */
    private class ChildCursorDied : RuntimeException("child cursor died mid-stream")

    private companion object {
        /** `datapipelines.pipelines.max-composition-depth`'s documented default (configuration.md §3.16). */
        const val DEFAULT_MAX_DEPTH = 5
    }
}
