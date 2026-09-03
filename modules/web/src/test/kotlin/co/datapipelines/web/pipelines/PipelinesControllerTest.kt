package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
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
 * body+metadata projection, and the list pagination contract. Since the lifecycle round, also the
 * draft-write semantics of PUT (If-Match required, status/body_hash in the response) and the
 * release/discard endpoints' error mapping.
 */
class PipelinesControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val validator = mockk<PipelineValidator>()
    private val drafts = mockk<PipelineDraftService>()
    private val releases = mockk<PipelineReleaseService>()

    // 056: the controller takes the SERVICE, built here over the very same mocks this suite
    // already stubbed — so every `every { repository… }` / `every { drafts… }` below still fires
    // unchanged and not one assertion in this file moved.
    private val controller =
        PipelinesController(
            pipelines =
                PipelineService(
                    pipelines = repository,
                    validator = validator,
                    drafts = drafts,
                    releases = releases,
                    authoring = AuthoringGuard(true),
                ),
        )

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
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
    private val releasedDetail =
        PipelineVersionDetail(
            pipelineId = pipelineId,
            version = 1,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "hash-v1",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
            releasedAt = Instant.parse("2026-08-01T00:00:01Z"),
            releasedBy = userId,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `create validates, stores and returns the merged projection with version 1 RELEASED`() {
        authenticate()
        val body =
            """{"schema_version":1,"name":"monthly_revenue","display_name":"Monthly Revenue",""" +
                """"description":"d","parameters":{},"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { repository.create(any(), any<NewPipeline>(), any(), any()) } returns record
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns releasedDetail

        val response = controller.create(body)

        val data = response.data
        data.get("id").asText() shouldBe pipelineId.toString()
        data.get("version").asInt() shouldBe 1
        data.get("status").asText() shouldBe "RELEASED"
        data.get("body_hash").asText() shouldBe "hash-v1"
        data.get("owner").asText() shouldBe userId.toString()
        data.get("name").asText() shouldBe "monthly_revenue"
        response.schemaVersion shouldBe 1
        response.correlationId.isNotBlank() shouldBe true
    }

    @Test
    fun `get on an unknown pipeline is the catalogued 404`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns null

        val error = shouldThrow<ApiException> { controller.get(pipelineId) }
        error.code shouldBe "pipeline.execution.not_found"
    }

    @Test
    fun `get returns the working version - the draft's body, status and hash when one exists`() {
        authenticate()
        val draftDetail =
            PipelineVersionDetail(
                pipelineId = pipelineId,
                version = 2,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                createdBy = userId,
                updatedBy = userId,
                updatedAt = Instant.parse("2026-08-02T00:00:00Z"),
            )
        every { repository.findById(any(), pipelineId) } returns record
        every { repository.findDraftDetail(any(), pipelineId) } returns draftDetail
        every { repository.findVersionBody(any(), pipelineId, 2) } returns """{"schema_version":1,"name":"monthly_revenue"}"""

        val data = controller.get(pipelineId).data

        // §7.1: the DEFAULT body is the working version — the draft when one exists — and
        // the response states which version and status it returned.
        data.get("name").asText() shouldBe "monthly_revenue"
        data.get("version").asInt() shouldBe 2
        data.get("status").asText() shouldBe "DRAFT"
        data.get("body_hash").asText() shouldBe "hash-v2"
        // current_version still names the latest RELEASED version (§7.1: never repointed).
        data.get("current_version").asInt() shouldBe 1
        data.get("draft").get("version").asInt() shouldBe 2
        data.get("draft").get("body_hash").asText() shouldBe "hash-v2"
    }

    @Test
    fun `get without a draft returns the released version as the working version`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns record
        every { repository.findDraftDetail(any(), pipelineId) } returns null
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns releasedDetail
        every { repository.findVersionBody(any(), pipelineId, 1) } returns """{"schema_version":1,"name":"monthly_revenue"}"""

        val data = controller.get(pipelineId).data

        data.get("version").asInt() shouldBe 1
        data.get("status").asText() shouldBe "RELEASED"
        data.get("body_hash").asText() shouldBe "hash-v1"
        data.get("current_version").asInt() shouldBe 1
        data.has("draft") shouldBe false
    }

    @Test
    fun `update requires the If-Match precondition header`() {
        authenticate()

        val error = shouldThrow<ApiException> { controller.update(pipelineId, null, """{"schema_version":1}""") }

        error.code shouldBe "pipeline.execution.invalid_parameter_type"
        error.details["reason"] shouldBe "precondition_missing"
    }

    @Test
    fun `update writes the draft branch and answers with the draft's status and hash`() {
        authenticate()
        val body =
            """{"schema_version":1,"name":"monthly_revenue","display_name":"Monthly Revenue",""" +
                """"description":"d","parameters":{},"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        val draftDetail =
            PipelineVersionDetail(
                pipelineId = pipelineId,
                version = 2,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                createdBy = userId,
                updatedBy = userId,
                updatedAt = Instant.parse("2026-08-02T00:00:00Z"),
            )
        every { drafts.write(any(), pipelineId, any(), any(), "hash-v1", userId) } returns
            PipelineDraftService.DraftWrite(record, draftDetail, body)
        every { repository.findDraftDetail(any(), pipelineId) } returns draftDetail

        val data = controller.update(pipelineId, "hash-v1", body).data

        data.get("version").asInt() shouldBe 2
        data.get("status").asText() shouldBe "DRAFT"
        data.get("body_hash").asText() shouldBe "hash-v2"
        data.get("current_version").asInt() shouldBe 1
    }

    @Test
    fun `a no-op update answers with the current RELEASED state and no draft pointer`() {
        authenticate()
        val body =
            """{"schema_version":1,"name":"monthly_revenue","display_name":"Monthly Revenue",""" +
                """"description":"d","parameters":{},"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        val storedBody = """{"schema_version":1,"name":"monthly_revenue"}"""
        // versioning §5.1: identical body ⇒ the service returns the RELEASED detail and
        // the STORED body; the response must say so plainly — not a 4xx, not a draft.
        every { drafts.write(any(), pipelineId, any(), any(), "hash-v1", userId) } returns
            PipelineDraftService.DraftWrite(record, releasedDetail, storedBody)
        every { repository.findDraftDetail(any(), pipelineId) } returns null

        val data = controller.update(pipelineId, "hash-v1", body).data

        data.get("version").asInt() shouldBe 1
        data.get("status").asText() shouldBe "RELEASED"
        data.get("body_hash").asText() shouldBe "hash-v1"
        data.get("current_version").asInt() shouldBe 1
        data.has("draft") shouldBe false
    }

    @Test
    fun `release requires If-Match and delegates to the release service`() {
        authenticate()
        shouldThrow<ApiException> { controller.release(pipelineId, null) }.details["reason"] shouldBe "precondition_missing"

        val draftBody = """{"schema_version":1,"name":"monthly_revenue"}"""
        every { releases.release(any(), pipelineId, "hash-v2", userId) } returns
            PipelineReleaseService.Released(record.copy(currentVersion = 2), releasedDetail.copy(version = 2), draftBody)

        val data = controller.release(pipelineId, "hash-v2").data

        data.get("version").asInt() shouldBe 2
        data.get("status").asText() shouldBe "RELEASED"
        data.get("current_version").asInt() shouldBe 2
    }

    @Test
    fun `discard requires If-Match and is a 204 on success`() {
        authenticate()
        shouldThrow<ApiException> { controller.discard(pipelineId, null) }.details["reason"] shouldBe "precondition_missing"

        every { releases.discard(any(), pipelineId, "hash-v2") } returns PipelineReleaseService.Discarded.Deleted

        controller.discard(pipelineId, "hash-v2")
    }

    @Test
    fun `list paginates the memoized scan and derives has_more honestly`() {
        authenticate()
        val records = (1..5).map { i -> record.copy(id = UUID.randomUUID(), name = "p$i") }
        every { repository.findAll(any(), null) } returns records

        val first = controller.list(owner = null, datasource = null, q = null, offset = 0, limit = 2).data
        first.items.size shouldBe 2
        first.pagination.hasMore shouldBe true

        val last = controller.list(owner = null, datasource = null, q = null, offset = 4, limit = 2).data
        last.items.size shouldBe 1
        last.pagination.hasMore shouldBe false
    }

    @Test
    fun `delete is 204 on success and 404 when nothing was live`() {
        authenticate()
        every { repository.softDelete(any(), pipelineId) } returns true
        controller.delete(pipelineId)

        every { repository.softDelete(any(), pipelineId) } returns false
        shouldThrow<ApiException> { controller.delete(pipelineId) }.code shouldBe "pipeline.execution.not_found"
    }

    @Test
    fun `create and delete refuse with the catalogued code when authoring is disabled`() {
        // versioning §5.5: a promotion receiver's write path fails closed. The refusal is
        // a DatapipelinesException the REST layer maps through ApiErrorCatalog (403).
        authenticate()
        val receiver =
            PipelinesController(
                pipelines =
                    PipelineService(
                        pipelines = repository,
                        validator = validator,
                        drafts = drafts,
                        releases = releases,
                        authoring = AuthoringGuard(false),
                    ),
            )

        val body =
            """{"schema_version":1,"name":"x","display_name":"X","description":"","parameters":{},"nodes":[]}"""
        val create =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                receiver.create(body)
            }
        create.code shouldBe "pipeline.authoring.disabled"
        create.details["config_key"] shouldBe "datapipelines.deployment.authoring-enabled"

        val delete = shouldThrow<co.datapipelines.typesystem.DatapipelinesException> { receiver.delete(pipelineId) }
        delete.code shouldBe "pipeline.authoring.disabled"
    }
}
