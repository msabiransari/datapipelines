package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.executor.StoredResultView
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * [ExecutionDetailController] — the detail page's model contract beside the partial's
 * (ExecutionDetailPartialControllerTest). Pins the 404 that is really invisibility, the
 * four-way resultState derivation (D5's shared semantics at page level), the cancel
 * affordance's scope+status gate, and the lineage family read.
 */
class ExecutionDetailControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val resultStore = mockk<ResultStore>()
    private val resultUrlFactory = mockk<ResultUrlFactory>(relaxed = true)
    private val controller = ExecutionDetailController(executions, pipelines, resultStore, resultUrlFactory)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val executionId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        scopes: Set<Scope>,
        asUser: UUID = userId,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(asUser, "a@b.c", "A", scopes, AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme")),
                null,
                emptyList(),
            )
    }

    private fun record(
        status: ExecutionStatus,
        rowCount: Long? = 10,
    ) = ExecutionRecord(
        executionId = executionId,
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        triggeredBy = userId,
        triggeredVia = ExecutionTrigger.REST,
        resultRowCount = rowCount,
    )

    private fun view() =
        StoredResultView(
            key = "k",
            executionId = executionId,
            schema = emptyList(),
            firstPage = emptyList(),
            totalRows = 10,
            bytes = 100,
            expiresAt = Instant.EPOCH,
        )

    private fun stubReads(
        record: ExecutionRecord,
        view: StoredResultView?,
    ) {
        every { resultStore.keyFor(executionId) } returns "k"
        every { resultStore.describe("k") } returns view
        every { pipelines.findById(workspaceId, record.pipelineId) } returns mockk()
        every { executions.findByRoot(workspaceId, executionId) } returns listOf(record)
    }

    @Test
    fun `another user's execution is a 404 - never a hint it exists`() {
        authenticate(setOf(Scope.READ), asUser = UUID.randomUUID())
        every { executions.findById(workspaceId, executionId) } returns record(ExecutionStatus.SUCCESS)

        val error = shouldThrow<ResponseStatusException> { controller.detail(executionId, ExtendedModelMap()) }
        error.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `a successful run with a live result is available`() {
        authenticate(setOf(Scope.READ))
        val record = record(ExecutionStatus.SUCCESS)
        every { executions.findById(workspaceId, executionId) } returns record
        stubReads(record, view())

        val model = ExtendedModelMap()
        controller.detail(executionId, model)

        model["resultState"] shouldBe "available"
        model["record"] shouldBe record
    }

    @Test
    fun `a successful run whose result expired is expired`() {
        authenticate(setOf(Scope.READ))
        val record = record(ExecutionStatus.SUCCESS)
        every { executions.findById(workspaceId, executionId) } returns record
        stubReads(record, null)

        val model = ExtendedModelMap()
        controller.detail(executionId, model)
        model["resultState"] shouldBe "expired"
    }

    @Test
    fun `a success with zero rows is no-caller-result, not expired`() {
        authenticate(setOf(Scope.READ))
        val record = record(ExecutionStatus.SUCCESS, rowCount = 0)
        every { executions.findById(workspaceId, executionId) } returns record
        stubReads(record, null)

        val model = ExtendedModelMap()
        controller.detail(executionId, model)
        model["resultState"] shouldBe "no-caller-result"
    }

    @Test
    fun `a failed run is not-applicable - there is no result to page`() {
        authenticate(setOf(Scope.READ))
        val record = record(ExecutionStatus.FAILED)
        every { executions.findById(workspaceId, executionId) } returns record
        stubReads(record, null)

        val model = ExtendedModelMap()
        controller.detail(executionId, model)
        model["resultState"] shouldBe "not-applicable"
    }

    @Test
    fun `the cancel affordance needs a RUNNING row AND execute scope`() {
        authenticate(setOf(Scope.READ))
        val running = record(ExecutionStatus.RUNNING)
        every { executions.findById(workspaceId, executionId) } returns running
        stubReads(running, null)

        val model = ExtendedModelMap()
        controller.detail(executionId, model)
        model["canCancel"] shouldBe false

        SecurityContextHolder.clearContext()
        authenticate(setOf(Scope.EXECUTE))
        val done = record(ExecutionStatus.SUCCESS)
        every { executions.findById(workspaceId, executionId) } returns done
        stubReads(done, view())

        val model2 = ExtendedModelMap()
        controller.detail(executionId, model2)
        model2["canCancel"] shouldBe false
    }

    @Test
    fun `a running row with execute scope offers cancel`() {
        authenticate(setOf(Scope.EXECUTE))
        val running = record(ExecutionStatus.RUNNING)
        every { executions.findById(workspaceId, executionId) } returns running
        stubReads(running, null)

        val model = ExtendedModelMap()
        controller.detail(executionId, model)
        model["canCancel"] shouldBe true
    }

    @Test
    fun `the lineage family rides the root's index`() {
        authenticate(setOf(Scope.READ))
        val record = record(ExecutionStatus.SUCCESS, rowCount = null)
        every { executions.findById(workspaceId, executionId) } returns record
        every { resultStore.keyFor(executionId) } returns "k"
        every { resultStore.describe("k") } returns null
        every { pipelines.findById(workspaceId, record.pipelineId) } returns mockk()
        every { executions.findByRoot(workspaceId, executionId) } returns listOf(record, record(ExecutionStatus.SUCCESS))

        val model = ExtendedModelMap()
        controller.detail(executionId, model)

        (model["family"] as List<*>).size shouldBe 2
    }
}
