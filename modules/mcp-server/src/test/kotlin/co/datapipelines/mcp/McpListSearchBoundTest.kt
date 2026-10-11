package co.datapipelines.mcp

import co.datapipelines.application.checks.PipelineCheckRunRepository
import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.ExclusiveDraftTemplates
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.PipelineVersionConsumers
import co.datapipelines.pipeline.SearchNeedle
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #504b on the tool surface — `pipelines_list` and `templates_list` adopt the shared search-needle
 * rule, and the DISCPATCHER is the MCP translator of the refusal (invalid params). The services
 * are REAL over mocked collaborators (the repositories the rules read: the pipeline repository in
 * the service, the templates JDBC template behind `searchPattern`), so the refusal that reaches
 * the dispatcher is the rule's own — the same shape [ParameterSetsToolsTest]'s dispatcher case
 * drives for the parameter-set listing (#490). A mocked service would prove nothing: it would
 * never throw the rule at all.
 */
class McpListSearchBoundTest {
    private val pipelineRepository = mockk<PipelineRepository>()
    private val jdbc = mockk<NamedParameterJdbcTemplate>()
    private val workspaceId = McpFixtures.WORKSPACE.id

    private val pipelines =
        PipelineService(
            pipelineRepository,
            mockk<PipelineValidator>(),
            mockk<PipelineDraftService>(),
            mockk<PipelineReleaseService>(),
            mockk<AuthoringGuard>(),
            mockk<ExclusiveDraftTemplates>(),
            mockk<PipelineVersionConsumers>(),
        )
    private val templates = TemplateService(TemplateRepository(jdbc))

    private val dispatcher =
        McpToolDispatcher(
            listOf(PipelinesListTool(pipelines, McpFixtures.EVERYTHING_LENS), TemplatesListTool(templates, McpFixtures.EVERYTHING_LENS)),
            object : AuditEventSink {
                override fun log(
                    event: String,
                    userId: UUID?,
                    keyId: String?,
                    sourceIp: String?,
                    userAgent: String?,
                    details: Map<String, Any?>,
                ) = Unit
            },
        )

    @BeforeEach
    fun stubReads() {
        every { pipelineRepository.findAll(workspaceId, any(), any(), any()) } returns emptyList()
        every { pipelineRepository.findDrafts(any(), any()) } returns emptyMap()
        every {
            jdbc.query(any<String>(), any<Map<String, Any?>>(), any<RowMapper<co.datapipelines.templates.Template>>())
        } returns emptyList()
    }

    @Test
    fun `pipelines_list refuses an over-long q as invalid params, naming the shared code and the two numbers`() {
        val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

        val fault =
            shouldThrow<McpError> {
                dispatcher.call(McpFixtures.request("pipelines_list", mapOf("q" to over)), McpFixtures.ctx())
            }

        fault.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
        fault.message shouldContain "request.query_too_long"
        fault.message shouldContain "at most ${SearchNeedle.MAX_LENGTH}"
        fault.message shouldNotContain over
    }

    @Test
    fun `templates_list refuses an over-long q the same way`() {
        val over = "r".repeat(SearchNeedle.MAX_LENGTH + 1)

        val fault =
            shouldThrow<McpError> {
                dispatcher.call(McpFixtures.request("templates_list", mapOf("q" to over)), McpFixtures.ctx())
            }

        fault.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
        fault.message shouldContain "request.query_too_long"
        fault.message shouldNotContain over
    }

    @Test
    fun `a q at the shared bound passes the gate into the ordinary listing`() {
        val bounded = "r".repeat(SearchNeedle.MAX_LENGTH)

        val pipelinesAnswer = dispatcher.call(McpFixtures.request("pipelines_list", mapOf("q" to bounded)), McpFixtures.ctx())
        val templatesAnswer = dispatcher.call(McpFixtures.request("templates_list", mapOf("q" to bounded)), McpFixtures.ctx())

        pipelinesAnswer.isError() shouldBe false
        templatesAnswer.isError() shouldBe false
    }

    @Test
    fun `a whitespace-only q is no search on the tool surface, not a refusal`() {
        val answer = dispatcher.call(McpFixtures.request("pipelines_list", mapOf("q" to "   ")), McpFixtures.ctx())

        answer.isError() shouldBe false
    }
}
