package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

class DashboardControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val controller = DashboardPartialController(executions, pipelines)

    private val userId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        id: UUID,
        scopes: Set<Scope>,
    ) {
        val principal = AuthenticatedPrincipal(id, "u@d.p", "User", scopes, AuthMethod.OIDC)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun record(status: ExecutionStatus = ExecutionStatus.SUCCESS) =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = status,
            parametersJson = "{}",
            triggeredBy = userId,
            triggeredVia = ExecutionTrigger.REST,
            startedAt = Instant.now(),
        )

    @Test
    fun `stats returns pipeline count executions today and success rate`() {
        authenticate(userId, setOf(Scope.READ))
        every { pipelines.countAll() } returns 2
        val records = listOf(record(), record(ExecutionStatus.FAILED), record())
        every { executions.findByUser(userId, null, null, null, null, limit = 100, offset = 0) } returns records

        val model = ExtendedModelMap()
        val viewName = controller.stats(model)

        viewName shouldBe "partials/dashboard-stats"
        model["totalPipelines"] shouldBe 2
        model["executionsToday"] shouldBe 3
        model["successRate"] shouldBe 66
    }

    @Test
    fun `recent executions returns last 10 for user`() {
        authenticate(userId, setOf(Scope.READ))
        val records = (1..10).map { record() }
        every { executions.findByUser(userId, limit = 10, offset = 0) } returns records

        val model = ExtendedModelMap()
        val viewName = controller.recentExecutions(model)

        viewName shouldBe "partials/recent-executions"
        @Suppress("UNCHECKED_CAST")
        val execs = model["executions"] as List<ExecutionRecord>
        execs shouldHaveSize 10
    }

    @Test
    fun `recent executions empty state when no executions`() {
        authenticate(userId, setOf(Scope.READ))
        every { executions.findByUser(userId, limit = 10, offset = 0) } returns emptyList()

        val model = ExtendedModelMap()
        controller.recentExecutions(model)

        @Suppress("UNCHECKED_CAST")
        val execs = model["executions"] as List<ExecutionRecord>
        execs.shouldBeEmpty()
    }

    @Test
    fun `admin sees all pipelines and executions`() {
        authenticate(adminId, setOf(Scope.ADMIN))
        every { pipelines.countAll() } returns 1
        every { executions.findAll(limit = 100, offset = 0) } returns listOf(record())

        val model = ExtendedModelMap()
        controller.stats(model)

        model["totalPipelines"] shouldBe 1
        model["executionsToday"] shouldBe 1
    }
}
