package co.datapipelines.web.templates

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
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
 * §8 over a mocked repository/engine: CRUD, versioned reads, render, import — and the gate C
 * `template.not_found` code on every miss path.
 */
class TemplatesControllerTest {
    private val repository = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val engine = mockk<TemplateEngine>()
    private val engines =
        mockk<WorkspaceTemplateEngines> {
            every { engineFor(any()) } returns engine
        }

    private val drafts = mockk<TemplateDraftService>()
    private val releases = mockk<TemplateReleaseService>()

    // Import moved to TemplateImportService (extracted for the D9 seeder); the real service is
    // used so the import cases still exercise the shipped parsing and per-entry semantics.
    private val controller =
        TemplatesController(repository, validator, engines, TemplateImportService(repository, validator), drafts, releases, co.datapipelines.pipeline.AuthoringGuard(true))

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun template(version: Int = 1) =
        Template(
            id = "fetch_orders.sql",
            version = version,
            dialect = Dialect.POSTGRES,
            displayName = "Fetch Orders",
            description = "d",
            body = "SELECT 1",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
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

    private val createBody =
        """{"dialect":"POSTGRES","display_name":"Fetch Orders","description":"d","body":"SELECT 1"}"""

    @Test
    fun `create validates and stores, returning version 1`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { repository.create(any(), any(), userId) } returns template()

        val stored = controller.create(createBody).data
        stored.id shouldBe "fetch_orders.sql"
        stored.version shouldBe 1
    }

    @Test
    fun `create and delete refuse with the catalogued code when authoring is disabled`() {
        // versioning §5.5's template mirror: a promotion receiver fails closed.
        authenticate()
        val receiver =
            TemplatesController(repository, validator, engines, TemplateImportService(repository, validator), drafts, releases, co.datapipelines.pipeline.AuthoringGuard(false))

        val create =
            shouldThrow<DatapipelinesException> {
                receiver.create("""{"dialect":"POSTGRES","display_name":"X","description":"d","body":"SELECT 1"}""")
            }
        create.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED
        create.details["config_key"] shouldBe co.datapipelines.pipeline.AuthoringGuard.CONFIG_KEY

        val delete = shouldThrow<DatapipelinesException> { receiver.delete("fetch_orders.sql") }
        delete.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED
    }

    @Test
    fun `get latest and get specific version`() {
        authenticate()
        every { repository.findLatest(any(), "fetch_orders.sql") } returns template(2)
        every { repository.findDraftDetail(any(), "fetch_orders.sql") } returns null
        val latest = controller.get("fetch_orders.sql").data
        latest.get("version").asInt() shouldBe 2

        every { repository.findVersion(any(), "fetch_orders.sql", 1) } returns template(1)
        controller.getVersion("fetch_orders.sql", 1).data.version shouldBe 1
    }

    @Test
    fun `get returns the working version - the draft's projection when one exists`() {
        authenticate()
        // §7.1's template mirror: the default read is the DRAFT, else an author rebases on
        // the released body and quietly discards the draft with the next write.
        every { repository.findDraftDetail(any(), "fetch_orders.sql") } returns
            TemplateVersionDetail(
                templateId = "fetch_orders.sql",
                version = 2,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                createdBy = userId,
            )
        every { repository.findVersion(any(), "fetch_orders.sql", 2) } returns
            template(2).copy(body = "SELECT 2", status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v2")

        val data = controller.get("fetch_orders.sql").data

        data.get("version").asInt() shouldBe 2
        data.get("status").asText() shouldBe "DRAFT"
        data.get("body").asText() shouldBe "SELECT 2"
        data.get("body_hash").asText() shouldBe "hash-v2"
        data.get("draft").get("version").asInt() shouldBe 2
    }

    @Test
    fun `misses are template-not_found, with the version in details for a versioned miss`() {
        authenticate()
        every { repository.findDraftDetail(any(), "nope.sql") } returns null
        every { repository.findLatest(any(), "nope.sql") } returns null
        shouldThrow<ApiException> { controller.get("nope.sql") }.code shouldBe "template.not_found"

        every { repository.findVersion(any(), "fetch_orders.sql", 9) } returns null
        every { repository.existsId(any(), "fetch_orders.sql") } returns true
        val error = shouldThrow<ApiException> { controller.getVersion("fetch_orders.sql", 9) }
        error.code shouldBe "template.not_found"
        error.details["version"] shouldBe 9

        every { repository.softDelete(any(), "nope.sql") } returns false
        shouldThrow<ApiException> { controller.delete("nope.sql") }.code shouldBe "template.not_found"
    }

    @Test
    fun `update requires If-Match, writes the draft branch, and 404s on an unknown id`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }

        // §4.2: no If-Match, no participation in the protocol at all — a 400, not a conflict.
        val missing = shouldThrow<ApiException> { controller.update("fetch_orders.sql", null, createBody) }
        missing.details["reason"] shouldBe "precondition_missing"

        val detail =
            TemplateVersionDetail(
                templateId = "fetch_orders.sql",
                version = 3,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v3",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                createdBy = userId,
            )
        every { drafts.write(any(), "fetch_orders.sql", any(), "hash-v2", userId) } returns detail
        every { repository.findVersion(any(), "fetch_orders.sql", 3) } returns
            template(3).copy(status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v3")
        val data = controller.update("fetch_orders.sql", "hash-v2", createBody).data
        data.get("version").asInt() shouldBe 3
        data.get("status").asText() shouldBe "DRAFT"
        data.get("body_hash").asText() shouldBe "hash-v3"

        val notFoundError =
            DatapipelinesException(
                PipelineErrorCodes.Template.NOT_FOUND,
                "Template 'nope.sql' not found.",
                emptyMap(),
            )
        every { drafts.write(any(), "nope.sql", any(), any(), userId) } throws notFoundError
        val thrown =
            shouldThrow<DatapipelinesException> { controller.update("nope.sql", "hash-v2", createBody) }
        thrown.code shouldBe "template.not_found"
    }

    @Test
    fun `release and discard require If-Match and delegate to the lifecycle service`() {
        authenticate()
        val releaseMissing = shouldThrow<ApiException> { controller.release("fetch_orders.sql", null) }
        releaseMissing.details["reason"] shouldBe "precondition_missing"
        val discardMissing = shouldThrow<ApiException> { controller.discard("fetch_orders.sql", null) }
        discardMissing.details["reason"] shouldBe "precondition_missing"

        every { releases.release(any(), "fetch_orders.sql", "hash-v3", userId) } returns
            TemplateReleaseService.Released(
                TemplateVersionDetail(
                    templateId = "fetch_orders.sql",
                    version = 3,
                    status = PipelineVersionStatus.RELEASED,
                    bodyHash = "hash-v3",
                    createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                    createdBy = userId,
                ),
                template(3),
            )
        val released = controller.release("fetch_orders.sql", "hash-v3").data
        released.get("status").asText() shouldBe "RELEASED"

        every { releases.discard(any(), "fetch_orders.sql", "hash-v3") } returns Unit
        controller.discard("fetch_orders.sql", "hash-v3")
    }

    @Test
    fun `list paginates and filters by dialect`() {
        authenticate()
        every { repository.list(any(), Dialect.POSTGRES, null, 0, 3) } returns listOf(template(), template(2))
        val data = controller.list(dialect = "POSTGRES", q = null, offset = 0, limit = 2).data
        data.items.size shouldBe 2
        data.pagination.hasMore shouldBe false

        shouldThrow<ApiException> { controller.list(dialect = "DB2", q = null, offset = null, limit = null) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
    }

    @Test
    fun `render returns the engine's SQL as the data payload`() {
        authenticate()
        every { repository.lookupVersion(any(), "fetch_orders.sql", 1) } returns
            TemplateVersion(
                id = "fetch_orders.sql",
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT \${x}",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )
        every { engine.render(TemplateRef("fetch_orders.sql", 1), mapOf("x" to 42)) } returns "SELECT 42"

        val rendered = controller.render("fetch_orders.sql", 1, """{"context":{"x":42}}""").data
        rendered shouldBe "SELECT 42"

        every { repository.lookupVersion(any(), "nope.sql", 1) } returns null
        every { repository.existsId(any(), "nope.sql") } returns false
        shouldThrow<ApiException> { controller.render("nope.sql", 1, """{"context":{}}""") }
            .code shouldBe "template.not_found"
    }

    @Test
    fun `import creates new ids and versions existing ones`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { repository.existsId(any(), "fetch_orders.sql") } returns true
        every { repository.appendReleasedVersion(any(), "fetch_orders.sql", any(), userId) } returns template(2)
        every { repository.existsId(any(), "new.sql") } returns false
        every { repository.create(any(), any(), userId) } returns template().copy(id = "new.sql")

        val body =
            """{"templates":[
                {"id":"fetch_orders.sql","dialect":"POSTGRES","display_name":"F","description":"d","body":"SELECT 1"},
                {"id":"new.sql","dialect":"POSTGRES","display_name":"N","description":"d","body":"SELECT 2"}
            ]}"""
        val data = controller.import(body).data
        data["imported"] shouldBe 2
    }
}
