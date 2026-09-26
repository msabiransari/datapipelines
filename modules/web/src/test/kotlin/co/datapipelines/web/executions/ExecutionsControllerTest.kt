package co.datapipelines.web.executions

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
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
 * `findAll`, a member's `findVisible` — their own runs plus the workspace's SCHEDULED runs (#9 R3).
 * And the durable event record (§10.3A): paged by `event_id`, `410` past retention, the metadata
 * read's visibility and session-only rule.
 */
class ExecutionsControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val cancellation = mockk<ExecutionCancellationService>()
    private val pipelines = mockk<co.datapipelines.pipeline.PipelineRepository>(relaxed = true)
    private val events = mockk<ExecutionEventRepository>()
    private val controller =
        ExecutionsController(
            executions = executions,
            cancellation = cancellation,
            cursor = mockk(),
            resultStore = mockk(),
            resultUrls = mockk(),
            streamer = mockk(),
            pipelines = pipelines,
            eventRecords = events,
        )

    private val owner = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun record(
        status: ExecutionStatus,
        executedBy: UUID = owner,
    ) = ExecutionRecord(
        executionId = executionId,
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        executedBy = executedBy,
        triggeredVia = ExecutionTrigger.REST,
        startedAt = Instant.parse("2026-08-05T14:30:00Z"),
    )

    private fun authenticate(
        userId: UUID,
        workspaceAdmin: Boolean = false,
        method: AuthMethod = AuthMethod.OIDC,
    ) {
        // A SESSION principal: keys v2 A16 makes the framework execution reads session-only,
        // so the ownership rule these tests pin is exercised the way its callers see it.
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                method,
                "dpk_x",
                workspace =
                    WorkspaceContext(
                        workspaceId,
                        "acme",
                        // RBAC round 1: a KEY whose owner is a workspace admin sees the workspace's
                        // runs; `Scope.ADMIN` no longer exists on the key axis at all (O-2).
                        if (workspaceAdmin) WorkspaceRole.WORKSPACE_ADMIN else WorkspaceRole.VIEWER,
                    ),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `cancel by the owner requests cancellation and returns 204`() {
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        controller.cancel(executionId)

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancel by a non-owner is a 404 and never reaches the cancellation service`() {
        authenticate(UUID.randomUUID())
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)

        shouldThrow<ApiException> { controller.cancel(executionId) }.code shouldBe "result.execution_not_found"
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    @Test
    fun `admin may cancel any execution`() {
        authenticate(UUID.randomUUID(), workspaceAdmin = true)
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        controller.cancel(executionId)

        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancelling a terminal execution is 409 not_running`() {
        authenticate(owner)
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
        authenticate(owner)
        val running = record(ExecutionStatus.SUCCESS)
        val key = running.pipelineId to running.pipelineVersion
        every { executions.findVisible(any(), owner, any(), any(), any(), any(), any(), any()) } returns listOf(running)

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
    fun `a member's listing reads findVisible - own runs plus scheduled ones (R3) - the admin listing findAll`() {
        authenticate(owner)
        every { executions.findVisible(any(), owner, any(), any(), any(), any(), any(), any()) } returns emptyList()
        controller.list(null, null, null, null, null, null)
        verify(exactly = 1) { executions.findVisible(any(), owner, null, null, null, null, any(), any()) }
        verify(exactly = 0) { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) }

        authenticate(UUID.randomUUID(), workspaceAdmin = true)
        every { executions.findAll(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        controller.list(null, null, null, null, null, null)
        verify(exactly = 1) { executions.findAll(any(), null, null, null, null, any(), any()) }
    }

    @Test
    fun `list filters are pushed into the repository query, not applied after the page cut`() {
        authenticate(owner)
        val after = Instant.parse("2026-08-01T00:00:00Z")
        every {
            executions.findVisible(any(), owner, any(), ExecutionStatus.SUCCESS, after, any(), any(), any())
        } returns listOf(record(ExecutionStatus.SUCCESS))

        val data = controller.list(null, "SUCCESS", after, null, 0, 50).data

        data.items.size shouldBe 1
        verify(exactly = 1) {
            executions.findVisible(any(), owner, null, ExecutionStatus.SUCCESS, after, null, limit = 51, offset = 0)
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
        authenticate(owner)
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
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.SUCCESS).copy(rootExecutionId = executionId)

        val data = controller.get(executionId).data

        // Present-and-null, not absent: a client can tell "root" from "field not implemented".
        data.containsKey("parent_execution_id") shouldBe true
        data["parent_execution_id"] shouldBe null
        data["parent_node_id"] shouldBe null
        data["root_execution_id"] shouldBe executionId.toString()
    }

    // ---------------------------------------------------------------- #9 R3 — scheduled runs

    @Test
    fun `a scheduled run is readable by any member - it is nobody's own - while another member's own run stays 404`() {
        val systemActor = UUID.randomUUID()
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.SUCCESS, executedBy = systemActor).copy(triggeredVia = ExecutionTrigger.SCHEDULE)

        controller.get(executionId).data["triggered_via"] shouldBe "SCHEDULE"

        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS, executedBy = UUID.randomUUID())
        shouldThrow<ApiException> { controller.get(executionId) }.code shouldBe "result.execution_not_found"
    }

    @Test
    fun `visibility is not ownership - a member cannot cancel a scheduled run`() {
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.RUNNING, executedBy = UUID.randomUUID()).copy(triggeredVia = ExecutionTrigger.SCHEDULE)

        shouldThrow<ApiException> { controller.cancel(executionId) }.code shouldBe "result.execution_not_found"
        verify(exactly = 0) { cancellation.cancel(any(), any()) }
    }

    // ---------------------------------------------------------------- §10.3A — the durable record

    @Test
    fun `the durable record pages by event_id - next_after and has_more - with the payload as JSON`() {
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)
        every { events.findPage(executionId, 0, 3) } returns (1..3).map(::event)

        val data = controller.durableEvents(executionId, after = null, limit = 2).data

        @Suppress("UNCHECKED_CAST")
        val items = data["events"] as List<Map<String, Any?>>
        items.map { it["event_id"] } shouldBe listOf(1, 2)
        items.first()["event"] shouldBe "node_started"
        (items.first()["data"] as com.fasterxml.jackson.databind.JsonNode)["node_id"].asText() shouldBe "n1"
        data["next_after"] shouldBe 2
        data["has_more"] shouldBe true
    }

    @Test
    fun `the page size is clamped to 1-500 and after never goes below zero`() {
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { events.findPage(executionId, any(), any()) } returns emptyList()

        controller.durableEvents(executionId, after = -5, limit = 10_000)
        controller.durableEvents(executionId, after = 7, limit = 0)

        verify(exactly = 1) { events.findPage(executionId, 0, 501) }
        verify(exactly = 1) { events.findPage(executionId, 7, 2) }
    }

    @Test
    fun `a completed execution with no rows left is 410 result_expired, reason event_record_expired`() {
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.SUCCESS).copy(completedAt = Instant.parse("2026-08-05T14:31:00Z"))
        every { events.findPage(executionId, 0, any()) } returns emptyList()

        val refused = shouldThrow<ApiException> { controller.durableEvents(executionId, null, null) }

        refused.code shouldBe "result.expired"
        refused.details["reason"] shouldBe "event_record_expired"
    }

    @Test
    fun `a running execution with no rows yet is an empty page, not 410`() {
        authenticate(owner)
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { events.findPage(executionId, 0, any()) } returns emptyList()

        val data = controller.durableEvents(executionId, null, null).data

        data["events"] shouldBe emptyList<Any>()
        data["next_after"] shouldBe null
        data["has_more"] shouldBe false
    }

    @Test
    fun `another member's run is 404 on the durable record, and it is never read`() {
        authenticate(UUID.randomUUID())
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)

        shouldThrow<ApiException> { controller.durableEvents(executionId, null, null) }.code shouldBe "result.execution_not_found"
        verify(exactly = 0) { events.findPage(any(), any(), any()) }
    }

    @Test
    fun `the durable record is session-only - a key is refused before any read`() {
        authenticate(owner, method = AuthMethod.API_KEY)

        shouldThrow<ApiException> { controller.durableEvents(executionId, null, null) }.code shouldBe "auth.session.required"
        verify(exactly = 0) { executions.findById(any(), any()) }
    }

    private fun event(id: Int) =
        ExecutionEventRecord(
            executionId = executionId,
            eventId = id,
            eventType = "node_started",
            timestamp = Instant.parse("2026-08-05T14:30:00Z").plusSeconds(id.toLong()),
            payloadJson = """{"node_id":"n$id"}""",
        )
}
