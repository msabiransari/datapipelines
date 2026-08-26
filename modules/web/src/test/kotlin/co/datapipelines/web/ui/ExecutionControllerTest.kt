package co.datapipelines.web.ui

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
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.executions.ResultCursor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import kotlin.enums.EnumEntries

class ExecutionControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val resultStore = mockk<ResultStore>()
    private val resultUrls = mockk<ResultUrlFactory>()
    private val cursor = mockk<ResultCursor>()
    private val cancellation = mockk<ExecutionCancellationService>()

    private val pageController = ExecutionHistoryController(pipelines)
    private val partialController = ExecutionHistoryPartialController(executions)
    private val detailController = ExecutionDetailController(executions, pipelines, resultStore, resultUrls)
    private val detailPartialController = ExecutionDetailPartialController(executions, resultStore, cursor, cancellation)

    private val owner = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        id: UUID,
        scopes: Set<Scope>,
    ) {
        val principal =
            AuthenticatedPrincipal(
                id,
                "u@d.p",
                "User",
                scopes,
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun record(status: ExecutionStatus = ExecutionStatus.SUCCESS) =
        ExecutionRecord(
            executionId = executionId,
            pipelineId = pipelineId,
            pipelineVersion = 1,
            status = status,
            parametersJson = "{}",
            triggeredBy = owner,
            triggeredVia = ExecutionTrigger.REST,
            startedAt = Instant.parse("2026-08-10T14:30:00Z"),
            durationMs = 1500,
        )

    private fun pipelineRecord() =
        PipelineRecord(
            id = pipelineId,
            name = "test-pipe",
            displayName = "Test Pipeline",
            description = "desc",
            ownerId = owner,
            currentVersion = 3,
            isDeleted = false,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `history page returns pipelines and statuses`() {
        authenticate(owner, setOf(Scope.READ))
        every { pipelines.findAll(any()) } returns listOf(pipelineRecord())
        val model = ExtendedModelMap()
        val viewName = pageController.list(model)

        viewName shouldBe "executions/list"
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<*>).shouldHaveSize(1)
        @Suppress("UNCHECKED_CAST")
        (model["statuses"] as EnumEntries<*>).shouldHaveSize(4)
    }

    @Test
    fun `history partial returns paginated executions`() {
        authenticate(owner, setOf(Scope.READ))
        val records = (1..21).map { record() }
        every { executions.findByUser(any(), owner, null, null, null, null, limit = 21, offset = 0) } returns records

        val model = ExtendedModelMap()
        val viewName = partialController.listPartial(null, null, null, null, 0, model)

        viewName shouldBe "partials/executions"
        @Suppress("UNCHECKED_CAST")
        (model["executions"] as List<*>).shouldHaveSize(20)
        model["hasMore"] shouldBe true
        model["nextOffset"] shouldBe 20
    }

    @Test
    fun `history partial empty state when no executions`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findByUser(any(), owner, null, null, null, null, limit = 21, offset = 0) } returns emptyList()

        val model = ExtendedModelMap()
        partialController.listPartial(null, null, null, null, 0, model)

        @Suppress("UNCHECKED_CAST")
        (model["executions"] as List<*>).shouldBeEmpty()
        model["hasMore"] shouldBe false
    }

    @Test
    fun `detail page shows execution data`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findById(any(), executionId) } returns record().copy(resultRowCount = 100)
        every { executions.findByRoot(any(), executionId) } returns listOf(record())
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        val viewName = detailController.detail(executionId, model)

        viewName shouldBe "executions/detail"
        model["resultState"] shouldBe "expired"
        model["canCancel"] shouldBe false
    }

    @Test
    fun `detail shows 404 for non-owner`() {
        authenticate(UUID.randomUUID(), setOf(Scope.READ))
        val otherRecord = record().copy(triggeredBy = owner)
        every { executions.findById(any(), executionId) } returns otherRecord

        shouldThrow<ResponseStatusException> {
            detailController.detail(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `detail shows 404 for missing execution`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findById(any(), executionId) } returns null

        shouldThrow<ResponseStatusException> {
            detailController.detail(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `admin can view any execution`() {
        val adminId = UUID.randomUUID()
        authenticate(adminId, setOf(Scope.ADMIN))
        every { executions.findById(any(), executionId) } returns record()
        every { executions.findByRoot(any(), executionId) } returns listOf(record())
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        val viewName = detailController.detail(executionId, model)

        viewName shouldBe "executions/detail"
        model["isAdmin"] shouldBe true
    }

    @Test
    fun `detail exposes the whole execution family via the root`() {
        authenticate(owner, setOf(Scope.READ))
        val childId = UUID.randomUUID()
        val child =
            record().copy(
                executionId = childId,
                parentExecutionId = executionId,
                parentNodeId = "revenue",
                rootExecutionId = executionId,
                triggeredVia = ExecutionTrigger.PIPELINE,
            )
        every { executions.findById(any(), executionId) } returns record()
        every { executions.findByRoot(any(), executionId) } returns listOf(child, record())
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        detailController.detail(executionId, model)

        @Suppress("UNCHECKED_CAST")
        (model["family"] as List<ExecutionRecord>).map { it.executionId } shouldBe listOf(childId, executionId)
    }

    @Test
    fun `detail shows canCancel for running execution with execute scope`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { executions.findByRoot(any(), executionId) } returns listOf(record(ExecutionStatus.RUNNING))
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        detailController.detail(executionId, model)

        model["canCancel"] shouldBe true
    }

    @Test
    fun `cancel by owner requests cancellation`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        val model = ExtendedModelMap()
        detailPartialController.cancel(executionId, model)

        model["cancelled"] shouldBe true
        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancel requires execute scope`() {
        authenticate(owner, setOf(Scope.READ))

        shouldThrow<ResponseStatusException> {
            detailPartialController.cancel(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `cancel non-running execution returns conflict`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)

        shouldThrow<ResponseStatusException> {
            detailPartialController.cancel(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.CONFLICT
    }
}
