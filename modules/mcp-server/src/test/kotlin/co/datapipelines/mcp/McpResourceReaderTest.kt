package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRecord
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class McpResourceReaderTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val datasources = mockk<DatasourceRegistry>()
    private val executions = mockk<ExecutionRepository>()
    private val events = mockk<ExecutionEventRepository>()
    private val ctx = McpFixtures.ctx(Scope.READ)

    private val reader = McpResourceReader(McpFixtures.pipelineService(pipelines), templates, datasources, executions, events)

    private fun contents(uri: String): McpSchema.TextResourceContents =
        reader.read(uri, ctx).contents().single() as McpSchema.TextResourceContents

    @Test
    fun `a pipeline reads as its JSON body`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        // D56: no version in the URI ⇒ the WORKING version, so the reader asks for a draft first.
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()

        val contents = contents(McpResourceUri.pipeline(McpFixtures.PIPELINE_ID))

        assertAll(
            { contents.mimeType() shouldBe McpResourceCatalog.MIME_JSON },
            { McpTools.readTree(contents.text())["name"].asText() shouldBe "monthly_revenue" },
        )
    }

    @Test
    fun `the skill reads as the packaged Markdown, and so does each reference`() {
        val core = contents(McpResourceUri.skill())
        val reference = contents(McpResourceUri.skillReference("templates"))

        assertAll(
            { core.mimeType() shouldBe McpResourceCatalog.MIME_MARKDOWN },
            // The BYTES, not a rendering of them: this is the same file the checkout holds and
            // the same one `GET /skill.md` serves.
            { core.text() shouldBe SkillDocs.skill },
            { core.text() shouldContain "name: datapipelines" },
            { reference.mimeType() shouldBe McpResourceCatalog.MIME_MARKDOWN },
            { reference.text() shouldBe SkillDocs.references.getValue("templates") },
            // The file name someone copies out of the map works too.
            { contents(McpResourceUri.skillReference("templates.md")).text() shouldBe reference.text() },
            // Reading the manual touches no repository — it is workspace-independent content.
            { verify(exactly = 0) { pipelines.findById(any(), any()) } },
        )
    }

    @Test
    fun `an unknown reference is not-found, in the same shape as an unknown entity`() {
        assertAll(
            { shouldThrow<McpError> { reader.read(McpResourceUri.skillReference("nope"), ctx) } },
            // A path is not a name: nothing here concatenates caller input into a file path.
            { shouldThrow<McpError> { reader.read("datapipelines://docs/skill/../../etc/passwd", ctx) } },
            { shouldThrow<McpError> { reader.read("datapipelines://docs/other", ctx) } },
        )
    }

    @Test
    fun `a specific pipeline version reads that version`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord(version = 5)
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 2) } returns McpFixtures.pipelineBody(name = "older")

        McpTools.readTree(contents("datapipelines://pipelines/${McpFixtures.PIPELINE_ID}/versions/2").text())["name"].asText() shouldBe
            "older"
    }

    @Test
    fun `the parameters resource carries only the parameter declarations`() {
        every { pipelines.findById(any(), McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { pipelines.findDraftDetail(any(), McpFixtures.PIPELINE_ID) } returns null
        every { pipelines.findVersionBody(any(), McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()

        val text = contents("datapipelines://pipelines/${McpFixtures.PIPELINE_ID}/parameters").text()

        assertAll(
            { text shouldNotContain "nodes" },
            { McpTools.readTree(text).isObject shouldBe true },
        )
    }

    @Test
    fun `a template reads as its Freemarker body`() {
        every { templates.findLatest(any(), "test/revenue.sql") } returns McpFixtures.template()

        val contents = contents(McpResourceUri.template("test/revenue.sql"))

        assertAll(
            { contents.mimeType() shouldBe McpResourceCatalog.MIME_FREEMARKER_SQL },
            { contents.text() shouldBe "SELECT 1" },
        )
    }

    @Test
    fun `a datasource reads without its password`() {
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns McpFixtures.datasource()

        val text = contents(McpResourceUri.datasource("pg-prod")).text()

        assertAll(
            { text shouldContain "jdbc:postgresql" },
            { text shouldNotContain "super-secret-password" },
            { text shouldNotContain "\"password\":" },
            // The FIELD, not the word: `"credential":{"kind":"password"}` legitimately names the
            // kind, and asserting on the bare quoted word made the kind's own value look like a
            // leak (087). What must never appear is a `"password":` KEY carrying a value.
            { text shouldNotContain "\"secret\":" },
        )
    }

    @Test
    fun `the datasource collection lists every datasource without credentials`() {
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns listOf(McpFixtures.datasource())

        val text = contents(McpResourceUri.datasources()).text()

        assertAll(
            { McpTools.readTree(text).size() shouldBe 1 },
            { text shouldNotContain "super-secret-password" },
        )
    }

    @Test
    fun `an execution reads as its metadata`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()

        McpTools.readTree(contents(McpResourceUri.execution(McpFixtures.EXECUTION_ID)).text())["status"].asText() shouldBe "SUCCESS"
    }

    @Test
    fun `execution events replay in SSE framing`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
        every { events.findByExecution(McpFixtures.EXECUTION_ID) } returns
            listOf(
                ExecutionEventRecord(McpFixtures.EXECUTION_ID, 1, "execution_started", Instant.parse("2026-08-09T12:00:00Z"), "{}"),
                ExecutionEventRecord(McpFixtures.EXECUTION_ID, 2, "pipeline_completed", Instant.parse("2026-08-09T12:00:02Z"), "{}"),
            )

        val contents = contents("datapipelines://executions/${McpFixtures.EXECUTION_ID}/events")

        assertAll(
            { contents.mimeType() shouldBe "text/event-stream" },
            { contents.text() shouldContain "id: 1\nevent: execution_started\ndata: {}" },
            { contents.text() shouldContain "event: pipeline_completed" },
        )
    }

    @Test
    fun `another user's execution and its events are not readable`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(triggeredBy = McpFixtures.OTHER_USER)

        assertAll(
            { shouldThrow<McpError> { reader.read(McpResourceUri.execution(McpFixtures.EXECUTION_ID), ctx) } },
            { shouldThrow<McpError> { reader.read("datapipelines://executions/${McpFixtures.EXECUTION_ID}/events", ctx) } },
        )
    }

    @Test
    fun `a specific template version reads that version's body`() {
        every { templates.lookupVersion(any(), "test/revenue.sql", 2) } returns
            TemplateVersion(
                id = "test/revenue.sql",
                version = 2,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT 2",
                createdAt = Instant.parse("2026-08-01T00:00:00Z"),
                createdBy = McpFixtures.USER,
            )

        val contents = contents("datapipelines://templates/test/revenue.sql/versions/2")

        assertAll(
            { contents.text() shouldBe "SELECT 2" },
            { contents.mimeType() shouldBe McpResourceCatalog.MIME_FREEMARKER_SQL },
        )
    }

    /** F3: the resource read path asserts the `read` floor rather than assuming it. */
    @Test
    fun `a key holding no scope cannot read a resource`() {
        val error =
            shouldThrow<McpError> {
                reader.read(
                    McpResourceUri.pipeline(McpFixtures.PIPELINE_ID),
                    McpToolContext(McpFixtures.principal(), McpFixtures.CORRELATION_ID),
                )
            }

        assertAll(
            { error.jsonRpcError.code() shouldBe McpArguments.FORBIDDEN },
            { verify(exactly = 0) { pipelines.findById(any(), any()) } },
        )
    }

    @Test
    fun `an unknown uri is a resource-not-found protocol error`() {
        shouldThrow<McpError> { reader.read("datapipelines://users/1", ctx) }
    }

    @Test
    fun `an unknown entity is a resource-not-found protocol error`() {
        every { templates.findLatest(any(), "nope") } returns null

        shouldThrow<McpError> { reader.read(McpResourceUri.template("nope"), ctx) }
    }

    @Test
    fun `every advertised template uri parses and reads - listed is never unreadable`() {
        // A2 / 077: a template id is a PATH (mandatory folder since 077), so the catalog's
        // advertised URIs carry slashes — including the trap shape, a folder named `versions`.
        // The pre-077 parser answered every one of these with not-found. Paging the catalog
        // and reading back each URI through the production parser is what keeps "listed"
        // and "readable" from drifting apart again.
        val advertised =
            listOf(
                McpFixtures.template(id = "nyc/mobility/daily_by_zone.sql"),
                McpFixtures.template(id = "acme/versions/report.sql"),
                McpFixtures.template(id = "a/b/c/d/e/deep.sql"),
            )
        advertised.forEach { template -> every { templates.findLatest(any(), template.id) } returns template }
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns advertised
        every { pipelines.findAll(any(), null) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val catalog =
            McpResourceCatalog(
                pipelines,
                templates,
                datasources,
                executions,
                Clock.fixed(Instant.parse("2026-08-09T12:00:00Z"), ZoneOffset.UTC),
            )
        val templateUris =
            catalog
                .list(ctx, null)
                .resources
                .map { it.uri() }
                .filter { it.startsWith("datapipelines://templates/") }

        templateUris.size shouldBe advertised.size
        templateUris.forEach { uri ->
            withClue(uri) {
                McpResourceUri.parse(uri).shouldNotBeNull()
                contents(uri).text() shouldBe "SELECT 1"
            }
        }
    }
}
