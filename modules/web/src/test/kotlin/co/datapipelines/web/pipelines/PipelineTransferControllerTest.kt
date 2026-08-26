package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * §5.8/§5.9 over mocked repositories: the import code mapping (including the mixed-failure shape,
 * gate C F10), the id-collision 409, and the export bundle.
 */
class PipelineTransferControllerTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val validator = mockk<PipelineValidator>()
    private val controller = PipelineTransferController(pipelines, templates, validator)

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val record =
        PipelineRecord(pipelineId, "monthly_revenue", "Monthly Revenue", "d", userId, 3, false, Instant.EPOCH, Instant.EPOCH)

    private val body =
        """{"schema_version":1,"name":"monthly_revenue","display_name":"Monthly Revenue","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},"nodes":[]}"""

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

    private fun failure(
        code: String,
        path: String,
    ) = ValidationFailure(code, path, "missing: $path")

    @Test
    fun `import of a new pipeline is 201 with the import missing-datasource code mapped`() {
        authenticate()
        every { validator.validate(any(), any()) } returns ValidationResult.VALID
        every { pipelines.findById(any(), any()) } returns null
        every { pipelines.create(any(), any<NewPipeline>(), any(), userId) } returns record

        val response = controller.import(body)
        response.statusCode.value() shouldBe 201
    }

    @Test
    fun `import with an existing id is a 200 version bump`() {
        authenticate()
        every { validator.validate(any(), any()) } returns ValidationResult.VALID
        every { pipelines.findById(any(), pipelineId) } returns record
        every { pipelines.update(any(), pipelineId, any(), any(), userId) } returns record.copy(currentVersion = 4)

        val withId = body.replace("\"nodes\":[]", "\"nodes\":[],\"id\":\"$pipelineId\"")
        controller.import(withId).statusCode.value() shouldBe 200
    }

    @Test
    fun `a missing datasource maps to the section-13-2 import code`() {
        authenticate()
        every { validator.validate(any(), any()) } returns
            ValidationResult(listOf(failure(PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE, "nodes[0].source")))

        val error = shouldThrow<ApiException> { controller.import(body) }
        error.code shouldBe PipelineErrorCodes.Import.MISSING_DATASOURCE
        error.details["missing_datasources"] shouldBe listOf("nodes[0].source")
    }

    @Test
    fun `mixed missing dependencies report BOTH sets under the datasource primary code`() {
        authenticate()
        every { validator.validate(any(), any()) } returns
            ValidationResult(
                listOf(
                    failure(PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE, "nodes[0].source"),
                    failure(PipelineErrorCodes.Validation.TEMPLATE_NOT_FOUND, "nodes[1].template"),
                ),
            )

        val error = shouldThrow<ApiException> { controller.import(body) }
        error.code shouldBe PipelineErrorCodes.Import.MISSING_DATASOURCE
        error.details["missing_datasources"] shouldBe listOf("nodes[0].source")
        error.details["missing_templates"] shouldBe listOf("nodes[1].template")
    }

    @Test
    fun `a missing template alone maps to missing_template`() {
        authenticate()
        every { validator.validate(any(), any()) } returns
            ValidationResult(listOf(failure(PipelineErrorCodes.Validation.TEMPLATE_VERSION_NOT_FOUND, "nodes[0].template")))

        shouldThrow<ApiException> { controller.import(body) }.code shouldBe PipelineErrorCodes.Import.MISSING_TEMPLATE
    }

    @Test
    fun `a non-dependency validation failure keeps its section-13-1 code`() {
        authenticate()
        every { validator.validate(any(), any()) } returns
            ValidationResult(listOf(failure(PipelineErrorCodes.Validation.CYCLE_DETECTED, "nodes")))

        val error = shouldThrow<co.datapipelines.pipeline.PipelineValidationException> { controller.import(body) }
        error.code shouldBe PipelineErrorCodes.Validation.CYCLE_DETECTED
    }

    @Test
    fun `an id colliding with a deleted pipeline is 409 version_conflict`() {
        authenticate()
        every { validator.validate(any(), any()) } returns ValidationResult.VALID
        every { pipelines.findById(any(), pipelineId) } returns null
        every { pipelines.create(any(), any<NewPipeline>(), any(), userId) } throws DuplicateKeyException("pipelines_pkey")

        val withId = body.replace("\"nodes\":[]", "\"nodes\":[],\"id\":\"$pipelineId\"")
        shouldThrow<ApiException> { controller.import(withId) }.code shouldBe PipelineErrorCodes.Import.VERSION_CONFLICT
    }

    @Test
    fun `export bundles the pipeline and the referenced template closure with a manifest`() {
        authenticate()
        val withNodes =
            body.replace(
                "\"nodes\":[]",
                """"nodes":[{"id":"n","type":"DQL","source":"pg","template":{"id":"t.sql","version":2},"depends_on":[]}]""",
            )
        every { pipelines.findById(any(), pipelineId) } returns record
        every { pipelines.findVersionBody(any(), pipelineId, 3) } returns withNodes
        every { templates.lookupVersion(any(), "t.sql", 2) } returns
            co.datapipelines.templates.TemplateVersion(
                id = "t.sql",
                version = 2,
                dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT 1",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )
        every { templates.findVersion(any(), "t.sql", 2) } returns
            co.datapipelines.templates.Template(
                id = "t.sql",
                version = 2,
                dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                displayName = "T",
                description = "d",
                body = "SELECT 1",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )

        val data = controller.export(pipelineId, includeTemplates = true).data

        @Suppress("UNCHECKED_CAST")
        val manifest = data["manifest"] as Map<String, Any?>
        manifest["pipeline_id"] shouldBe pipelineId.toString()
        manifest["pipeline_version"] shouldBe 3
        manifest["template_count"] shouldBe 1

        val without = controller.export(pipelineId, includeTemplates = false).data
        (without["templates"] as List<*>).size shouldBe 0
    }
}
