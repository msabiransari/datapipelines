package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateImport
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant

class TemplateToolsTest {
    private val templates = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val engine = mockk<TemplateEngine>()
    private val engines = mockk<WorkspaceTemplateEngines> { every { engineFor(any()) } returns engine }
    private val readCtx = McpFixtures.ctx(Scope.READ)
    private val authorCtx = McpFixtures.ctx(Scope.AUTHOR)

    @Test
    fun `list projects the documented metadata and filters libraries`() {
        every { templates.list(any(), any(), any(), any(), any()) } returns
            listOf(McpFixtures.template(), McpFixtures.template(id = "dates.ftl", isLibrary = true))

        val all = TemplatesListTool(templates).call(McpArguments(emptyMap()), readCtx) as List<*>
        val libraries = TemplatesListTool(templates).call(McpArguments(mapOf("is_library" to true)), readCtx) as List<*>

        assertAll(
            {
                (all.first() as Map<*, *>).keys shouldContainExactly
                    setOf("id", "version", "dialect", "display_name", "description", "is_library")
            },
            { libraries.map { (it as Map<*, *>)["id"] } shouldContainExactly listOf("dates.ftl") },
        )
    }

    @Test
    fun `list pushes dialect and q down to the repository`() {
        val dialect = slot<Dialect>()
        val q = slot<String>()
        every { templates.list(any(), capture(dialect), capture(q), any(), any()) } returns emptyList()

        TemplatesListTool(templates).call(McpArguments(mapOf("dialect" to "MYSQL", "q" to "revenue")), readCtx)

        assertAll(
            { dialect.captured shouldBe Dialect.MYSQL },
            { q.captured shouldBe "revenue" },
        )
    }

    @Test
    fun `an unsupported dialect is a protocol error`() {
        shouldThrow<McpError> {
            TemplatesListTool(templates).call(McpArguments(mapOf("dialect" to "SNOWFLAKE")), readCtx)
        }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `get returns the latest version by default, including body and imports`() {
        every { templates.findDraftDetail(any(), any()) } returns null
        every { templates.findLatest(any(), "revenue.sql") } returns McpFixtures.template()

        every { templates.findLatest(any(), "with_imports.sql") } returns
            McpFixtures.template(id = "with_imports.sql").copy(imports = listOf(TemplateImport("dates.ftl", 2, "dates")))

        val template = TemplatesGetTool(templates).call(McpArguments(mapOf("id" to "revenue.sql")), readCtx) as Template
        val imported = TemplatesGetTool(templates).call(McpArguments(mapOf("id" to "with_imports.sql")), readCtx) as Template

        assertAll(
            { template.body shouldBe "SELECT 1" },
            { template.version shouldBe 1 },
            { template.imports shouldContainExactly emptyList() },
            { imported.imports shouldContainExactly listOf(TemplateImport("dates.ftl", 2, "dates")) },
        )
    }

    @Test
    fun `get defaults to the working version - the draft when one exists`() {
        // §7.1's template mirror: with a draft open, the DEFAULT read returns the draft's
        // version (never the released body an agent would silently rebase over).
        every { templates.findDraftDetail(any(), "revenue.sql") } returns
            TemplateVersionDetail(
                templateId = "revenue.sql",
                version = 2,
                status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = java.time.Instant.EPOCH,
                createdBy = McpFixtures.USER,
            )
        every { templates.findVersion(any(), "revenue.sql", 2) } returns
            McpFixtures
                .template()
                .copy(
                    version = 2,
                    body = "SELECT 2",
                    status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                    bodyHash = "hash-v2",
                )

        val template = TemplatesGetTool(templates).call(McpArguments(mapOf("id" to "revenue.sql")), readCtx) as Template

        template.version shouldBe 2
        template.body shouldBe "SELECT 2"
        template.status shouldBe co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
    }

    @Test
    fun `get distinguishes an unknown template from an unknown version`() {
        every { templates.findDraftDetail(any(), "nope") } returns null
        every { templates.findLatest(any(), "nope") } returns null
        every { templates.findDraftDetail(any(), "revenue.sql") } returns null
        every { templates.findVersion(any(), "revenue.sql", 9) } returns null
        every { templates.existsId(any(), "revenue.sql") } returns true

        assertAll(
            {
                shouldThrow<DatapipelinesException> {
                    TemplatesGetTool(templates).call(McpArguments(mapOf("id" to "nope")), readCtx)
                }.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> {
                    TemplatesGetTool(templates).call(McpArguments(mapOf("id" to "revenue.sql", "version" to 9)), readCtx)
                }.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
            },
        )
    }

    @Test
    fun `create validates the draft before storing it and records the caller as author`() {
        val draft = slot<TemplateDraft>()
        every { validator.validateOrThrow(capture(draft), any()) } answers { firstArg() }
        every { templates.create(any(), any(), McpFixtures.USER) } returns McpFixtures.template()

        TemplatesCreateTool(templates, validator).call(
            McpArguments(
                mapOf(
                    "dialect" to "POSTGRES",
                    "display_name" to "Revenue",
                    "description" to "Expects: month (STRING).",
                    "body" to "SELECT 1",
                    "imports" to listOf(mapOf("id" to "dates.ftl", "version" to 2, "alias" to "dates")),
                ),
            ),
            authorCtx,
        )

        assertAll(
            { draft.captured.dialect shouldBe Dialect.POSTGRES },
            { draft.captured.engine shouldBe Template.FREEMARKER_ENGINE },
            { draft.captured.isLibrary shouldBe false },
            { draft.captured.imports shouldContainExactly listOf(TemplateImport("dates.ftl", 2, "dates")) },
        )
    }

    @Test
    fun `create rejects a malformed imports entry as a protocol error`() {
        shouldThrow<McpError> {
            TemplatesCreateTool(templates, validator).call(
                McpArguments(
                    mapOf(
                        "dialect" to "POSTGRES",
                        "display_name" to "Revenue",
                        "description" to "d",
                        "body" to "SELECT 1",
                        "imports" to listOf(mapOf("id" to "dates.ftl", "alias" to "dates")),
                    ),
                ),
                authorCtx,
            )
        }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `render previews the SQL of the latest version without storing anything`() {
        every { templates.findLatest(any(), "revenue.sql") } returns McpFixtures.template(version = 3)
        val ref = slot<TemplateRef>()
        every { engine.render(capture(ref), any(), any()) } returns "SELECT 1 WHERE month = '2026-07'"

        val payload =
            TemplatesRenderTool(templates, engines).call(
                McpArguments(mapOf("id" to "revenue.sql", "context" to mapOf("month" to "2026-07"))),
                authorCtx,
            )

        assertAll(
            { ref.captured shouldBe TemplateRef("revenue.sql", 3) },
            // §6.2.9 pins the return as the rendered SQL string — not an object wrapping it.
            { payload shouldBe "SELECT 1 WHERE month = '2026-07'" },
        )
    }

    @Test
    fun `render of an explicit version checks the version exists first`() {
        every { templates.lookupVersion(any(), "revenue.sql", 2) } returns
            TemplateVersion(
                id = "revenue.sql",
                version = 2,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT 1",
                createdAt = Instant.parse("2026-08-01T00:00:00Z"),
                createdBy = McpFixtures.USER,
            )
        every { engine.render(any(), any(), any()) } returns "SELECT 1"

        TemplatesRenderTool(templates, engines).call(
            McpArguments(mapOf("id" to "revenue.sql", "version" to 2, "context" to emptyMap<String, Any?>())),
            authorCtx,
        )
    }

    @Test
    fun `a version below 1 is refused, never silently read as version 1`() {
        assertAll(
            {
                shouldThrow<McpError> {
                    TemplatesGetTool(templates).call(McpArguments(mapOf("id" to "revenue.sql", "version" to 0)), readCtx)
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
            {
                shouldThrow<McpError> {
                    TemplatesRenderTool(templates, engines).call(
                        McpArguments(mapOf("id" to "revenue.sql", "version" to -3, "context" to emptyMap<String, Any?>())),
                        authorCtx,
                    )
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
        )
        // Nothing was read: a refused version must not reach the repository at all.
        verify(exactly = 0) { templates.findVersion(any(), any(), any()) }
        verify(exactly = 0) { templates.findLatest(any(), any()) }
    }

    @Test
    fun `list clamps limit into the repository's page bounds`() {
        val limit = slot<Int>()
        every { templates.list(any(), any(), any(), any(), capture(limit)) } returns emptyList()

        TemplatesListTool(templates).call(McpArguments(mapOf("limit" to 10_000)), readCtx)

        limit.captured shouldBe TemplateRepository.MAX_PAGE_LIMIT
    }

    @Test
    fun `create passes is_library through and rejects an unsupported engine`() {
        val draft = slot<TemplateDraft>()
        every { validator.validateOrThrow(capture(draft), any()) } answers { firstArg() }
        every { templates.create(any(), any(), McpFixtures.USER) } returns McpFixtures.template(isLibrary = true)

        val library =
            mapOf(
                "dialect" to "POSTGRES",
                "display_name" to "Dates",
                "description" to "Date macros.",
                "body" to "<#macro d></#macro>",
                "is_library" to true,
            )
        TemplatesCreateTool(templates, validator).call(McpArguments(library), authorCtx)

        assertAll(
            { draft.captured.isLibrary shouldBe true },
            {
                shouldThrow<McpError> {
                    TemplatesCreateTool(templates, validator).call(McpArguments(library + mapOf("engine" to "jinja2")), authorCtx)
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
        )
    }

    @Test
    fun `render of an unknown version is a catalogued not-found`() {
        every { templates.lookupVersion(any(), "revenue.sql", 9) } returns null
        every { templates.existsId(any(), "revenue.sql") } returns true

        shouldThrow<DatapipelinesException> {
            TemplatesRenderTool(templates, engines).call(
                McpArguments(mapOf("id" to "revenue.sql", "version" to 9, "context" to emptyMap<String, Any?>())),
                authorCtx,
            )
        }.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
    }
}
