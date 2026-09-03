package co.datapipelines.mcp

import co.datapipelines.application.ExecutionLauncher
import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.IdempotencyOutcome
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.util.UUID

/**
 * **ARCH-AUDIT-2026-08 D6, the behavioural half** — the one item on the eight-entry web↔MCP drift
 * list that was a defect and not merely duplication: `POST /pipelines/{id}/execute` honoured
 * `Idempotency-Key`; `pipelines_execute` had no idempotency support at all, so an agent retrying
 * a timed-out call ran the pipeline a second time. Against a write-back pipeline that is not a
 * cosmetic difference.
 *
 * 056 fixes it by SHARING the code rather than by patching the tool: both surfaces call
 * `co.datapipelines.application.ExecutionLauncher`, and MCP inherits the reservation as a
 * consequence of that. This suite is the proof, and its second test is the falsification: the
 * same scenario against a tool built WITHOUT a launcher — which is exactly the pre-056 tool —
 * runs twice. A guard that cannot be shown red is not a guard.
 *
 * The store here is a real in-memory implementation, not a mock. A strict mock would make the
 * ABSENCE of a reservation unobservable — the suite would be green precisely because the call is
 * missing, which is the inverted-double trap.
 */
class McpExecuteIdempotencyTest {
    private val repository = mockk<PipelineRepository>()
    private val service = McpFixtures.pipelineService(repository)
    private val executor = mockk<PipelineExecutor>()
    private val executions = mockk<ExecutionRepository>()
    private val resultStore = mockk<ResultStore>(relaxed = true)
    private val resultUrls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" }
    private val store = InMemoryIdempotencyStore()

    /** Every execution the fake executor was asked to run, in order. */
    private val executed = mutableListOf<ExecuteRequest>()

    private val args =
        McpArguments(
            mapOf(
                "id" to McpFixtures.PIPELINE_ID.toString(),
                "parameters" to mapOf("month" to "2026-07"),
            ),
        )

    private fun tool(launcher: ExecutionLauncher?) =
        PipelineExecuteTool(
            pipelines = service,
            executor = executor,
            executions = executions,
            resultStore = resultStore,
            resultUrls = resultUrls,
            launcher = launcher,
        )

    private fun launcher() = ExecutionLauncher(idempotencyStore = store, idempotencyTtlSeconds = TTL_SECONDS)

    private fun storedPipeline() {
        every { repository.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { repository.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()
        coEvery { executor.execute(any()) } answers {
            val request = firstArg<ExecuteRequest>()
            executed += request
            terminal(request.executionId ?: UUID.randomUUID())
        }
    }

    @Test
    fun `a repeated idempotency key returns the first execution instead of running a second`() {
        storedPipeline()
        val ctx = McpFixtures.ctx(Scope.EXECUTE, idempotencyKey = "retry-1")

        @Suppress("UNCHECKED_CAST")
        val first = tool(launcher()).call(args, ctx) as Map<String, Any?>
        val executionId = UUID.fromString(first["execution_id"] as String)
        // The retry reads the original's recorded row, exactly as the REST retry replays the
        // original's event log rather than starting anything.
        every { executions.findById(McpFixtures.WORKSPACE_ID, executionId) } returns record(executionId)

        @Suppress("UNCHECKED_CAST")
        val retry = tool(launcher()).call(args, ctx) as Map<String, Any?>

        assertAll(
            { withClue("the retry must report the ORIGINAL execution") { retry["execution_id"] shouldBe first["execution_id"] } },
            { withClue("the pipeline must have run exactly once") { executed.size shouldBe 1 } },
            { retry["status"] shouldBe ExecutionStatus.SUCCESS.name },
        )
    }

    @Test
    fun `the pre-056 tool - no launcher - runs the same key twice`() {
        // The falsification. This is not a hypothetical: it is the tool as it shipped before 056,
        // reconstructed by passing no launcher, and it is why the test above is meaningful.
        storedPipeline()
        val ctx = McpFixtures.ctx(Scope.EXECUTE, idempotencyKey = "retry-1")

        @Suppress("UNCHECKED_CAST")
        val first = tool(launcher = null).call(args, ctx) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val second = tool(launcher = null).call(args, ctx) as Map<String, Any?>

        assertAll(
            { withClue("pre-056: the key bought nothing and the pipeline ran twice") { executed.size shouldBe 2 } },
            { second["execution_id"] shouldNotBe first["execution_id"] },
        )
    }

    @Test
    fun `the same key with different parameters is refused, not silently re-run`() {
        storedPipeline()
        val ctx = McpFixtures.ctx(Scope.EXECUTE, idempotencyKey = "retry-1")
        tool(launcher()).call(args, ctx)

        val different =
            McpArguments(
                mapOf(
                    "id" to McpFixtures.PIPELINE_ID.toString(),
                    "parameters" to mapOf("month" to "2026-08"),
                ),
            )
        val error = shouldThrow<DatapipelinesException> { tool(launcher()).call(different, ctx) }

        assertAll(
            { error.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED },
            { withClue("the refused call must not have executed") { executed.size shouldBe 1 } },
        )
    }

    @Test
    fun `a call with no idempotency key runs and reserves nothing`() {
        storedPipeline()

        tool(launcher()).call(args, McpFixtures.ctx(Scope.EXECUTE))

        assertAll(
            { executed.size shouldBe 1 },
            { withClue("no key means no reservation — the store must not be touched") { store.reservations shouldBe 0 } },
        )
    }

    @Test
    fun `the Idempotency-Key header reaches the tool context`() {
        // The carrier, end to end: the same HTTP header REST uses, on the same POST /mcp request,
        // through the transport-context extractor the servlet transport is built with. The TOOL
        // SCHEMA is deliberately untouched — McpToolSurfaceSpecDriftTest freezes it against
        // mcp-server.md §6.2.3 and this round changes nothing on that wire.
        val request =
            MockHttpServletRequest().apply {
                setAttribute(McpTransportKeys.PRINCIPAL, McpFixtures.principal(Scope.EXECUTE))
                setAttribute(McpTransportKeys.CORRELATION_ID, McpFixtures.CORRELATION_ID)
                addHeader(McpServerFactory.IDEMPOTENCY_KEY_HEADER, "  retry-1  ")
            }

        val ctx = McpServerFactory.transportContext(request).toolContext()

        assertAll(
            { withClue("trimmed, as rest-api §3.5 specifies") { ctx.idempotencyKey shouldBe "retry-1" } },
            {
                withClue("the header spelling must match web's WebHeaders.IDEMPOTENCY_KEY") {
                    McpServerFactory.IDEMPOTENCY_KEY_HEADER shouldBe "Idempotency-Key"
                }
            },
        )
    }

    @Test
    fun `a blank Idempotency-Key header is treated as absent`() {
        val request =
            MockHttpServletRequest().apply {
                setAttribute(McpTransportKeys.PRINCIPAL, McpFixtures.principal(Scope.EXECUTE))
                setAttribute(McpTransportKeys.CORRELATION_ID, McpFixtures.CORRELATION_ID)
                addHeader(McpServerFactory.IDEMPOTENCY_KEY_HEADER, "   ")
            }

        McpServerFactory.transportContext(request).toolContext().idempotencyKey shouldBe null
    }

    // --------------------------------------------------------------------------- fixtures

    private fun terminal(executionId: UUID) =
        ExecutionResult(
            executionId = executionId,
            status = ExecutionStatus.SUCCESS,
            nodeStats = emptyList(),
            resultRef = null,
            startedAt = STARTED_AT,
            completedAt = COMPLETED_AT,
            durationMs = DURATION_MS,
        )

    private fun record(executionId: UUID) =
        ExecutionRecord(
            executionId = executionId,
            pipelineId = McpFixtures.PIPELINE_ID,
            pipelineVersion = 1,
            status = ExecutionStatus.SUCCESS,
            parametersJson = """{"month":"2026-07"}""",
            triggeredBy = McpFixtures.USER,
            triggeredVia = ExecutionTrigger.MCP,
            startedAt = STARTED_AT,
            completedAt = COMPLETED_AT,
            durationMs = DURATION_MS,
            nodeStatsJson = "[]",
        )

    /**
     * A real `SET NX` in a map — the semantics `RedisIdempotencyStore` implements, small enough to
     * read: first writer wins, an identical retry reads the winner, a different request under the
     * same key is refused.
     */
    private class InMemoryIdempotencyStore : IdempotencyStore {
        private val claims = mutableMapOf<String, Pair<String, UUID>>()

        var reservations = 0
            private set

        override fun reserve(
            userId: UUID,
            idempotencyKey: String,
            requestHash: String,
            executionId: UUID,
            ttlSeconds: Long,
        ): IdempotencyOutcome {
            reservations++
            val key = "$userId:$idempotencyKey"
            val held = claims[key]
            if (held == null) {
                claims[key] = requestHash to executionId
                return IdempotencyOutcome.Reserved(executionId)
            }
            if (held.first != requestHash) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED,
                    message = "Idempotency-Key was already used with a different request body.",
                    details = mapOf("idempotency_key" to idempotencyKey),
                )
            }
            return IdempotencyOutcome.Existing(held.second)
        }
    }

    private companion object {
        const val TTL_SECONDS = 86_400L
        const val DURATION_MS = 3_000L
        val STARTED_AT: Instant = Instant.parse("2026-09-03T12:00:00Z")
        val COMPLETED_AT: Instant = Instant.parse("2026-09-03T12:00:03Z")
    }
}
