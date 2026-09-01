package co.datapipelines.web.executions

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The ownership rules of §10 (carry-forward #2): a non-owner's cancel/get is a 404, never a 403;
 * cancelling a terminal execution is `409 pipeline.execution.not_running`; the admin listing reads
 * `findAll`, a user's `findByUser`.
 */
class ExecutionsControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val cancellation = mockk<ExecutionCancellationService>()
    private val pipelines = mockk<co.datapipelines.pipeline.PipelineRepository>(relaxed = true)
    private val controller =
        ExecutionsController(
            executions = executions,
            cancellation = cancellation,
            cursor = mockk(),
            resultStore = mockk(),
            resultUrls = mockk(),
            streamer = mockk(),
            pipelines = pipelines,
        )

    private val owner = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun record(
        status: ExecutionStatus,
        triggeredBy: UUID = owner,
    ) = ExecutionRecord(
        executionId = executionId,
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        triggeredBy = triggeredBy,
        triggeredVia = ExecutionTrigger.REST,
        startedAt = Instant.parse("2026-08-05T14:30:00Z"),
    )

    private fun authenticate(
        userId: UUID,
        scopes: Set<Scope>,
    ) {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                scopes,
                AuthMethod.API_KEY,
                "dpk_x",
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `cancel by the owner requests cancellation and returns 204`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        controller.cancel(executionId)

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancel by a non-owner is a 404 and never reaches the cancellation service`() {
        authenticate(UUID.randomUUID(), setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)

        shouldThrow<ApiException> { controller.cancel(executionId) }.code shouldBe "result.execution_not_found"
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `admin may cancel any execution`() {
        authenticate(UUID.randomUUID(), setOf(Scope.ADMIN))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        controller.cancel(executionId)

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancelling a terminal execution is 409 not_running`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)

        val error = shouldThrow<ApiException> { controller.cancel(executionId) }
        error.code shouldBe "pipeline.execution.not_running"
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `draft_run is derived - started_at before released_at, or no released_at`() {
        // versioning §8: an execution of version N was a draft run when started_at <
        // released_at, or when that version has no released_at (still DRAFT/DISCARDED).
        // The marker is informational — a history label, never behaviour.
        authenticate(owner, setOf(Scope.READ))
        val running = record(ExecutionStatus.SUCCESS)
        val key = running.pipelineId to running.pipelineVersion
        every { executions.findByUser(any(), owner, any(), any(), any(), any(), any(), any()) } returns listOf(running)

        fun listedDraftRun(): Boolean {
            val items = controller.list(null, null, null, null, null, null).data.items
            return items.single()["draft_run"] as Boolean
        }

        // started_at (EPOCH-ish) < released_at ⇒ draft run.
        every { pipelines.releasedAtFor(any(), any()) } returns mapOf(key to java.time.Instant.parse("2026-09-02T00:00:00Z"))
        listedDraftRun() shouldBe true

        // released_at before started_at ⇒ it ran as a released version.
        every { pipelines.releasedAtFor(any(), any()) } returns mapOf(key to java.time.Instant.parse("2026-08-01T00:00:00Z"))
        listedDraftRun() shouldBe false

        // No released_at at all (still a draft, or discarded): always a draft run.
        every { pipelines.releasedAtFor(any(), any()) } returns mapOf(key to null)
        listedDraftRun() shouldBe true
    }

    @Test
    fun `the user listing reads findByUser, the admin listing findAll`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findByUser(any(), owner, any(), any(), any(), any(), any(), any()) } returns emptyList()
        controller.list(null, null, null, null, null, null)
        verify(exactly = 1) { executions.findByUser(any(), owner, null, null, null, null, any(), any()) }

        authenticate(UUID.randomUUID(), setOf(Scope.ADMIN))
        every { executions.findAll(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        controller.list(null, null, null, null, null, null)
        verify(exactly = 1) { executions.findAll(any(), null, null, null, null, any(), any()) }
    }

    @Test
    fun `list filters are pushed into the repository query, not applied after the page cut`() {
        authenticate(owner, setOf(Scope.READ))
        val after = Instant.parse("2026-08-01T00:00:00Z")
        every {
            executions.findByUser(any(), owner, any(), ExecutionStatus.SUCCESS, after, any(), any(), any())
        } returns listOf(record(ExecutionStatus.SUCCESS))

        val data = controller.list(null, "SUCCESS", after, null, 0, 50).data

        data.items.size shouldBe 1
        verify(exactly = 1) {
            executions.findByUser(any(), owner, null, ExecutionStatus.SUCCESS, after, null, limit = 51, offset = 0)
        }
    }

    /**
     * T1 — V3 added the three lineage columns, the repository selects them and the history UI
     * renders them; only this projection dropped them, so an API client could see a child execution
     * and never learn it was one. `GET /executions/{id}` is where a client goes after a
     * `node_completed` names a `child_execution_id`, and it could not answer "whose child?".
     */
    @Test
    fun `execution metadata exposes the composition lineage a child execution carries`() {
        authenticate(owner, setOf(Scope.READ))
        val parentExecutionId = UUID.randomUUID()
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.SUCCESS).copy(
                triggeredVia = ExecutionTrigger.PIPELINE,
                parentExecutionId = parentExecutionId,
                parentNodeId = "run_leaf",
                rootExecutionId = parentExecutionId,
            )

        val data = controller.get(executionId).data

        data["parent_execution_id"] shouldBe parentExecutionId.toString()
        data["parent_node_id"] shouldBe "run_leaf"
        data["root_execution_id"] shouldBe parentExecutionId.toString()
    }

    /** A root's lineage is present and honest: no parent, and it is its own family root. */
    @Test
    fun `a root execution reports a null parent and itself as the family root`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.SUCCESS).copy(rootExecutionId = executionId)

        val data = controller.get(executionId).data

        // Present-and-null, not absent: a client can tell "root" from "field not implemented".
        data.containsKey("parent_execution_id") shouldBe true
        data["parent_execution_id"] shouldBe null
        data["parent_node_id"] shouldBe null
        data["root_execution_id"] shouldBe executionId.toString()
    }
}
