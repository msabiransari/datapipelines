package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The pipeline CRUD controller over a mocked repository: server-assigned fields, 404s, the merged
 * body+metadata projection, and the list pagination contract.
 */
class PipelinesControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val validator = mockk<PipelineValidator>()
    private val controller =
        PipelinesController(
            pipelines = repository,
            validator = validator,
            bodies = PipelineBodies(repository),
        )

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val record =
        PipelineRecord(
            id = pipelineId,
            name = "monthly_revenue",
            displayName = "Monthly Revenue",
            description = "desc",
            ownerId = userId,
            currentVersion = 1,
            isDeleted = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal = AuthenticatedPrincipal(userId, "a@b.c", "A", setOf(Scope.AUTHOR), AuthMethod.OIDC)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `create validates, stores and returns the merged projection with version 1`() {
        authenticate()
        val body =
            """{"schema_version":1,"name":"monthly_revenue","display_name":"Monthly Revenue",""" +
                """"description":"d","parameters":{},"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""
        every { validator.validateOrThrow(any()) } answers { firstArg() }
        every { repository.create(any<NewPipeline>(), any(), any()) } returns record

        val response = controller.create(body)

        val data = response.data
        data.get("id").asText() shouldBe pipelineId.toString()
        data.get("version").asInt() shouldBe 1
        data.get("owner").asText() shouldBe userId.toString()
        data.get("name").asText() shouldBe "monthly_revenue"
        response.schemaVersion shouldBe 1
        response.correlationId.isNotBlank() shouldBe true
    }

    @Test
    fun `get on an unknown pipeline is the catalogued 404`() {
        every { repository.findById(pipelineId) } returns null

        val error = shouldThrow<ApiException> { controller.get(pipelineId) }
        error.code shouldBe "pipeline.execution.not_found"
    }

    @Test
    fun `get merges server fields into the stored body`() {
        every { repository.findById(pipelineId) } returns record
        every { repository.findVersionBody(pipelineId, 1) } returns """{"schema_version":1,"name":"monthly_revenue"}"""

        val data = controller.get(pipelineId).data
        data.get("name").asText() shouldBe "monthly_revenue"
        data.get("id").asText() shouldBe pipelineId.toString()
        data.get("created_at").asText() shouldBe "2026-08-01T00:00:00Z"
    }

    @Test
    fun `list paginates the memoized scan and derives has_more honestly`() {
        val records = (1..5).map { i -> record.copy(id = UUID.randomUUID(), name = "p$i") }
        every { repository.findAll(null) } returns records

        val first = controller.list(owner = null, datasource = null, q = null, offset = 0, limit = 2).data
        first.items.size shouldBe 2
        first.pagination.hasMore shouldBe true

        val last = controller.list(owner = null, datasource = null, q = null, offset = 4, limit = 2).data
        last.items.size shouldBe 1
        last.pagination.hasMore shouldBe false
    }

    @Test
    fun `delete is 204 on success and 404 when nothing was live`() {
        every { repository.softDelete(pipelineId) } returns true
        controller.delete(pipelineId)

        every { repository.softDelete(pipelineId) } returns false
        shouldThrow<ApiException> { controller.delete(pipelineId) }.code shouldBe "pipeline.execution.not_found"
    }
}
