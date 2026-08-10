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
import java.time.Instant

class McpResourceReaderTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val datasources = mockk<DatasourceRegistry>()
    private val executions = mockk<ExecutionRepository>()
    private val events = mockk<ExecutionEventRepository>()
    private val ctx = McpFixtures.ctx(Scope.READ)

    private val reader = McpResourceReader(pipelines, templates, datasources, executions, events)

    private fun contents(uri: String): McpSchema.TextResourceContents =
        reader.read(uri, ctx).contents().single() as McpSchema.TextResourceContents

    @Test
    fun `a pipeline reads as its JSON body`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()

        val contents = contents(McpResourceUri.pipeline(McpFixtures.PIPELINE_ID))

        assertAll(
            { contents.mimeType() shouldBe McpResourceCatalog.MIME_JSON },
            { McpTools.readTree(contents.text())["name"].asText() shouldBe "monthly_revenue" },
        )
    }

    @Test
    fun `a specific pipeline version reads that version`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord(version = 5)
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 2) } returns McpFixtures.pipelineBody(name = "older")

        McpTools.readTree(contents("datapipelines://pipelines/${McpFixtures.PIPELINE_ID}/versions/2").text())["name"].asText() shouldBe
            "older"
    }

    @Test
    fun `the parameters resource carries only the parameter declarations`() {
        every { pipelines.findById(McpFixtures.PIPELINE_ID) } returns McpFixtures.pipelineRecord()
        every { pipelines.findVersionBody(McpFixtures.PIPELINE_ID, 1) } returns McpFixtures.pipelineBody()

        val text = contents("datapipelines://pipelines/${McpFixtures.PIPELINE_ID}/parameters").text()

        assertAll(
            { text shouldNotContain "nodes" },
            { McpTools.readTree(text).isObject shouldBe true },
        )
    }

    @Test
    fun `a template reads as its Freemarker body`() {
        every { templates.findLatest("revenue.sql") } returns McpFixtures.template()

        val contents = contents(McpResourceUri.template("revenue.sql"))

        assertAll(
            { contents.mimeType() shouldBe McpResourceCatalog.MIME_FREEMARKER_SQL },
            { contents.text() shouldBe "SELECT 1" },
        )
    }

    @Test
    fun `a datasource reads without its password`() {
        every { datasources.get("pg-prod") } returns McpFixtures.datasource()

        val text = contents(McpResourceUri.datasource("pg-prod")).text()

        assertAll(
            { text shouldContain "jdbc:postgresql" },
            { text shouldNotContain "super-secret-password" },
            { text shouldNotContain "\"password\"" },
        )
    }

    @Test
    fun `the datasource collection lists every datasource without credentials`() {
        every { datasources.list(null) } returns listOf(McpFixtures.datasource())

        val text = contents(McpResourceUri.datasources()).text()

        assertAll(
            { McpTools.readTree(text).size() shouldBe 1 },
            { text shouldNotContain "super-secret-password" },
        )
    }

    @Test
    fun `an execution reads as its metadata`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()

        McpTools.readTree(contents(McpResourceUri.execution(McpFixtures.EXECUTION_ID)).text())["status"].asText() shouldBe "SUCCESS"
    }

    @Test
    fun `execution events replay in SSE framing`() {
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()
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
        every { executions.findById(McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(triggeredBy = McpFixtures.OTHER_USER)

        assertAll(
            { shouldThrow<McpError> { reader.read(McpResourceUri.execution(McpFixtures.EXECUTION_ID), ctx) } },
            { shouldThrow<McpError> { reader.read("datapipelines://executions/${McpFixtures.EXECUTION_ID}/events", ctx) } },
        )
    }

    @Test
    fun `a specific template version reads that version's body`() {
        every { templates.lookupVersion("revenue.sql", 2) } returns
            TemplateVersion(
                id = "revenue.sql",
                version = 2,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT 2",
                createdAt = Instant.parse("2026-08-01T00:00:00Z"),
                createdBy = McpFixtures.USER,
            )

        val contents = contents("datapipelines://templates/revenue.sql/versions/2")

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
            { verify(exactly = 0) { pipelines.findById(any()) } },
        )
    }

    @Test
    fun `an unknown uri is a resource-not-found protocol error`() {
        shouldThrow<McpError> { reader.read("datapipelines://users/1", ctx) }
    }

    @Test
    fun `an unknown entity is a resource-not-found protocol error`() {
        every { templates.findLatest("nope") } returns null

        shouldThrow<McpError> { reader.read(McpResourceUri.template("nope"), ctx) }
    }
}
