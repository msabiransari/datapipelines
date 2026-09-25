package co.datapipelines.mcp

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.semantics.FactEnrichment
import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The semantic link's MCP surface (lane 7e, transform-nodes design §2.3/§8.2/§8.3; mcp-server.md
 * §6.2.6–§6.2.8, §6.2.36, §6.2.38) through the REAL dispatcher with a key principal: what each
 * tool hands the service layer, and what an agent reads back on the wire. The rules themselves
 * (the citation check, inherit, needs_review, the lens in SQL) are proven on Postgres by
 * `TemplateImplementsIntegrationTest`; this suite pins the transport half — that nothing is
 * dropped, defaulted or re-decided between the arguments and the services.
 */
class TemplateImplementsToolsTest {
    private val templates = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val drafts = mockk<TemplateDraftService>()
    private val ctx = McpFixtures.ctx()
    private val fact = UUID.randomUUID()
    private val successor = UUID.randomUUID()

    private fun dispatch(
        tool: McpTool,
        name: String,
        args: Map<String, Any?>,
    ) = McpFixtures.payloadOf(McpToolDispatcher(listOf(tool), RecordingSink()).call(McpFixtures.request(name, args), ctx))

    private fun transform(
        implements: List<String>? = listOf("$fact"),
        retired: List<RetiredFactCitation> = emptyList(),
    ) = Template(
        id = "test/rainy.jsonata",
        version = 1,
        engine = Template.NONE_ENGINE,
        type = TemplateType.JSONATA,
        dialect = null,
        displayName = "Rainy",
        description = "d",
        body = "rows",
        createdAt = Instant.EPOCH,
        createdBy = UUID.randomUUID(),
        status = PipelineVersionStatus.DRAFT,
        bodyHash = "h1",
        implements = implements,
        retiredFacts = retired,
    )

    private val transformArgs =
        mapOf(
            "id" to "test/rainy.jsonata",
            "type" to "jsonata",
            "display_name" to "Rainy",
            "description" to "d",
            "body" to "rows",
        )

    @Test
    fun `templates_create hands the stated implements to the draft service and returns the stored citations`() {
        val drafted = slot<TemplateDraft>()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { drafts.create(any(), capture(drafted), McpFixtures.USER, CreateLifecycle.DRAFT, WriteSurface.MCP) } returns transform()
        val tool = TemplatesCreateTool(templates, co.datapipelines.pipeline.AuthoringGuard(true), validator, drafts)

        val payload = dispatch(tool, "templates_create", transformArgs + ("implements" to listOf("$fact")))

        assertSoftly {
            drafted.captured.implements shouldBe listOf("$fact")
            payload["implements"].map { it.asText() } shouldBe listOf("$fact")
            payload["needs_review"].asBoolean() shouldBe false
        }
    }

    @Test
    fun `templates_update passes an omitted implements as null - inherit - and a stated one verbatim`() {
        val drafted = mutableListOf<TemplateDraft>()
        every { templates.findWorking(any(), "test/rainy.jsonata") } returns transform()
        every { validator.validateOrThrow(capture(drafted), any()) } answers { firstArg() }
        every { drafts.write(any(), "test/rainy.jsonata", any(), "h1", McpFixtures.USER, WriteSurface.MCP) } returns
            TemplateVersionDetail("test/rainy.jsonata", 1, PipelineVersionStatus.RELEASED, "h1", Instant.EPOCH, McpFixtures.USER)
        every { templates.findVersion(any(), "test/rainy.jsonata", 1) } returns transform()
        val tool = TemplatesUpdateTool(templates, drafts, validator)
        val base = transformArgs + ("expected_hash" to "h1")

        dispatch(tool, "templates_update", base)
        dispatch(tool, "templates_update", base + ("implements" to listOf("$successor")))
        dispatch(tool, "templates_update", base + ("implements" to emptyList<String>()))

        drafted.map { it.implements } shouldBe listOf(null, listOf("$successor"), emptyList())
    }

    @Test
    fun `a non-array implements, or a non-string entry, is a protocol fault - a malformed id is the validator's`() {
        every { templates.findWorking(any(), "test/rainy.jsonata") } returns transform()
        val tool = TemplatesUpdateTool(templates, drafts, validator)
        val base = transformArgs + ("expected_hash" to "h1")

        shouldThrow<McpError> { tool.call(McpArguments(base + ("implements" to "$fact")), ctx) }
            .message
            .orEmpty() shouldContain "must be an array"
        shouldThrow<McpError> { tool.call(McpArguments(base + ("implements" to listOf(7))), ctx) }
            .message
            .orEmpty() shouldContain "fact id string"
    }

    @Test
    fun `templates_list hands the implements filter to the read service as a fact id, and refuses a malformed one`() {
        val cited = slot<UUID>()
        val reads = mockk<TemplateService>()
        every { reads.list(any(), any(), any(), any(), any(), any(), any(), capture(cited)) } returns listOf(transform())
        val tool = TemplatesListTool(reads, McpFixtures.EVERYTHING_LENS)

        val rows = dispatch(tool, "templates_list", mapOf("implements" to "$fact"))

        assertSoftly {
            cited.captured shouldBe fact
            rows.single()["implements"].map { it.asText() } shouldBe listOf("$fact")
            rows.single()["needs_review"].asBoolean() shouldBe false
        }
        shouldThrow<McpError> { tool.call(McpArguments(mapOf("implements" to "not-a-fact")), ctx) }
            .message
            .orEmpty() shouldContain "must be a UUID"
    }

    @Test
    fun `templates_get serves needs_review with each retired fact and its successor`() {
        val reads = mockk<TemplateService>()
        every { reads.findDraftDetail(any(), any(), "test/rainy.jsonata") } returns null
        every { reads.findLatest(any(), any(), "test/rainy.jsonata") } returns
            transform(retired = listOf(RetiredFactCitation("$fact", "superseded", "$successor")))
        val payload = dispatch(TemplatesGetTool(reads, McpFixtures.EVERYTHING_LENS), "templates_get", mapOf("id" to "test/rainy.jsonata"))

        assertSoftly {
            payload["needs_review"].asBoolean() shouldBe true
            val retired = payload["retired_facts"].single()
            retired["fact_id"].asText() shouldBe "$fact"
            retired["retired_reason"].asText() shouldBe "superseded"
            retired["superseded_by"].asText() shouldBe "$successor"
        }
    }

    @Test
    fun `an sql template carries no citations and no retired_facts key, and reads needs_review false`() {
        val reads = mockk<TemplateService>()
        every { reads.findDraftDetail(any(), any(), "test/revenue.sql") } returns null
        every { reads.findLatest(any(), any(), "test/revenue.sql") } returns McpFixtures.template(dialect = Dialect.POSTGRES)
        val payload = dispatch(TemplatesGetTool(reads, McpFixtures.EVERYTHING_LENS), "templates_get", mapOf("id" to "test/revenue.sql"))

        assertSoftly {
            payload["implements"].isNull shouldBe true
            payload["needs_review"].asBoolean() shouldBe false
            payload.has("retired_facts") shouldBe false
        }
    }

    @Test
    fun `semantics_list and the datasource listing read implemented_by through the KEY's template lens`() {
        val promoterView = ReadLens.Only(setOf("test/rainy.jsonata"))
        val lens = PromoterLens { LensedView(ReadLens.Everything, promoterView) }
        val datasource = McpFixtures.datasource(name = "warehouse")
        val registry = mockk<DatasourceRegistry>()
        every { registry.getVisible("warehouse", any()) } returns datasource
        every { registry.listVisible(null, any()) } returns listOf(datasource)
        val service = mockk<SemanticsService>()
        val listed = slot<ReadLens>()
        every { service.list(any(), datasource, any(), capture(listed)) } returns emptyList()
        val enrichment = mockk<FactEnrichment>()
        val enriched = slot<ReadLens>()
        every { enrichment.forListing(any(), datasource, capture(enriched)) } returns FactEnrichment.DatasourceBlocks.EMPTY

        SemanticsListTool(registry, service, lens).call(McpArguments(mapOf("datasource" to "warehouse")), ctx)
        DatasourcesListTool(registry, enrichment, lens).call(McpArguments(emptyMap()), ctx)

        listed.captured shouldBe promoterView
        enriched.captured shouldBe promoterView
    }

    private class RecordingSink : co.datapipelines.auth.AuditEventSink {
        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) = Unit
    }
}
