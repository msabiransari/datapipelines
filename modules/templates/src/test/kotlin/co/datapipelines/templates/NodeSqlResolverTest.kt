package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.NodeSqlResolution.ParameterRejected
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * [NodeSqlResolver] — the six resolution states (032's contract, extracted), the E5 version
 * default (draft-if-exists, else current released), and the 042 `:name` bind gate. The web
 * controller test covers the SAME engine end-to-end through the partial's model states; this
 * suite owns the version rule and the bind translation, which the controller never exercised.
 */
class NodeSqlResolverTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val engines = mockk<WorkspaceTemplateEngines>()
    private val engine = mockk<TemplateEngine>()
    private val resolver = NodeSqlResolver(pipelines, templates, engines)
    private val mapper = ObjectMapper()

    private val workspaceId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()

    /** v1 RELEASED (the pipeline's current_version), v2 DRAFT — the E5 shape. */
    private val released = detail(1, PipelineVersionStatus.RELEASED)
    private val draft = detail(2, PipelineVersionStatus.DRAFT)

    private val draftBody =
        """
        {
          "schema_version": 1,
          "name": "fixture",
          "display_name": "Fixture",
          "description": "",
          "settings": {"tempdb": {"engine": "H2"}},
          "parameters": {
            "start_date": {"type": "DATE", "required": true},
            "limit": {"type": "INTEGER", "required": false, "default": 100}
          },
          "nodes": [
            {"id": "fetch", "type": "DQL", "source": "sample-trips",
             "template": {"id": "fetch.sql", "version": 1}, "depends_on": []},
            {"id": "run_child", "type": "PIPELINE", "pipeline": {"name": "child_pipe", "version": 3}, "depends_on": []}
          ]
        }
        """.trimIndent()

    private val releasedBody =
        draftBody.replace("\"id\": \"fetch\"", "\"id\": \"fetch_v1_renamed\"")

    @BeforeEach
    fun wire() {
        every { pipelines.findCurrentVersionDetail(workspaceId, pipelineId) } returns released
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draft
        every { pipelines.findVersionBody(workspaceId, pipelineId, 1) } returns releasedBody
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { engines.engineFor(workspaceId) } returns engine
        every { templates.lookupVersion(workspaceId, "fetch.sql", 1) } returns version("fetch.sql")
    }

    @Test
    fun `E5 - absent version prefers the DRAFT over the released current`() {
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } returns "SELECT 1"

        val outcome = resolver.resolve(workspaceId, pipelineId, "fetch", null, null)

        (outcome as NodeSqlResolution.Rendered).version shouldBe draft
    }

    @Test
    fun `E5 - with no draft the released current version resolves`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } returns "SELECT 1"

        val outcome = resolver.resolve(workspaceId, pipelineId, "fetch_v1_renamed", null, null)

        (outcome as NodeSqlResolution.Rendered).version shouldBe released
    }

    @Test
    fun `E5 - an explicit version wins over the draft, and an unknown one is NoSuchElement`() {
        every { pipelines.findVersionDetail(workspaceId, pipelineId, 1) } returns released
        every { pipelines.findVersionDetail(workspaceId, pipelineId, 9) } returns null
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } returns "SELECT 1"

        val explicit = resolver.resolve(workspaceId, pipelineId, "fetch_v1_renamed", 1, null)
        (explicit as NodeSqlResolution.Rendered).version shouldBe released

        shouldThrow<NoSuchElementException> { resolver.resolve(workspaceId, pipelineId, "fetch", 9, null) }
    }

    @Test
    fun `the rendered SQL carries both the name form and the positional bind translation`() {
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } returns
            "SELECT * FROM t WHERE d = :start_date AND n < :limit"

        val outcome =
            resolver.resolve(
                workspaceId,
                pipelineId,
                "fetch",
                null,
                mapOf(
                    "start_date" to mapper.readTree("\"2026-09-01\""),
                    "limit" to mapper.readTree("5"),
                ),
            ) as NodeSqlResolution.Rendered

        assertAll(
            { outcome.sql shouldBe "SELECT * FROM t WHERE d = :start_date AND n < :limit" },
            { outcome.positionalSql shouldBe "SELECT * FROM t WHERE d = ? AND n < ?" },
            { outcome.bindValues shouldBe listOf(java.time.LocalDate.parse("2026-09-01"), 5) },
            { outcome.sampledParameters shouldBe emptyList<String>() },
        )
    }

    @Test
    fun `a rendered name the context does not declare fails loudly before anything executes`() {
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } returns
            "SELECT * FROM t WHERE d = :start_date AND x = :not_declared"

        val thrown =
            shouldThrow<DatapipelinesException> {
                resolver.resolve(
                    workspaceId,
                    pipelineId,
                    "fetch",
                    null,
                    mapOf("start_date" to mapper.readTree("\"2026-09-01\"")),
                )
            }

        thrown.code shouldBe co.datapipelines.pipeline.PipelineErrorCodes.Node.SQL_PARAMETER_MISSING
    }

    @Test
    fun `an unsupplied required parameter renders from the sample context and is labelled`() {
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } returns "SELECT 1"

        val outcome = resolver.resolve(workspaceId, pipelineId, "fetch", null, null) as NodeSqlResolution.Rendered

        outcome.sampledParameters shouldBe listOf("start_date")
    }

    @Test
    fun `a supplied override failing coercion rejects and renders nothing`() {
        val outcome =
            resolver.resolve(
                workspaceId,
                pipelineId,
                "fetch",
                null,
                mapOf("limit" to mapper.readTree("\"5\"")),
            ) as ParameterRejected

        outcome.failures.single().parameter shouldBe "limit"
        io.mockk.verify(exactly = 0) { engine.render(any(), any(), any()) }
    }

    @Test
    fun `the non-rendered states survive the extraction`() {
        resolver.resolve(workspaceId, pipelineId, "no_such_node", null, null)::class shouldBe
            NodeSqlResolution.NodeMissing::class

        val child = resolver.resolve(workspaceId, pipelineId, "run_child", null, null) as NodeSqlResolution.ChildPipeline
        child.childName shouldBe "child_pipe"
        child.childVersion shouldBe 3

        every { templates.lookupVersion(workspaceId, "fetch.sql", 1) } returns null
        resolver.resolve(workspaceId, pipelineId, "fetch", null, null)::class shouldBe
            NodeSqlResolution.TemplateMissing::class

        every { templates.lookupVersion(workspaceId, "fetch.sql", 1) } returns version("fetch.sql")
        every { engine.render(TemplateRef("fetch.sql", 1), any(), any()) } throws
            TemplateRenderException("undefined variable: nope", TemplateRef("fetch.sql", 1))
        val failed = resolver.resolve(workspaceId, pipelineId, "fetch", null, null) as NodeSqlResolution.RenderFailed
        failed.message shouldContain "undefined variable"
    }

    private fun version(id: String) =
        TemplateVersion(
            id = id,
            version = 1,
            dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
            isLibrary = false,
            imports = emptyList(),
            body = "SELECT 1",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ) = PipelineVersionDetail(
        pipelineId = pipelineId,
        version = version,
        status = status,
        bodyHash = "hash-$version",
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        createdBy = UUID.randomUUID(),
        releasedAt = if (status == PipelineVersionStatus.RELEASED) Instant.parse("2026-08-01T00:00:00Z") else null,
    )
}
