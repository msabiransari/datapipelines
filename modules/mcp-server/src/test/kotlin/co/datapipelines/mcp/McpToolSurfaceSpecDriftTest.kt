package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.modelcontextprotocol.json.McpJsonDefaults
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The shipped MCP surface, asserted against **mcp-server.md itself** — the house spec-drift
 * pattern six sibling modules already follow.
 *
 * `ScopeMatrixSpecDriftTest` (in `auth`) guards the tool *names* and their scopes. Nothing guarded
 * the part this module hand-transcribes: §6.2's input schemas — every property, type, enum,
 * default, `required` list and description — plus §7.1's URI forms and §8's prompt names.
 * `McpTools.tool()` takes the schema as a raw string, so without this test a reworded description
 * or a dropped `enum` disagrees with the frozen contract silently, which is exactly how the two
 * Gate C findings (`templates_render`'s return shape, `datasources_list`'s stray enum) happened.
 *
 * Deliberately a **deep equality** on the whole `inputSchema`, not a structural subset: an agent
 * reads those descriptions to decide what to send, so they are contract too.
 */
class McpToolSurfaceSpecDriftTest {
    private val spec = SpecFiles.read(SpecFiles.MCP_SPEC_PATH)
    private val tools = shippedTools().associateBy { it.name }

    /**
     * The one tool §6.2 documents in prose instead of a JSON block ("Same input as
     * `pipelines_create` plus required `id`"), so it has no block to compare against. Named here so
     * the count guard below still covers all 15.
     */
    private val documentedInProse = setOf("pipelines_update")

    @Test
    fun `§6_1 lists exactly the tools this server ships`() {
        val listed =
            Regex("^- `([a-z_]+)`$", RegexOption.MULTILINE)
                .findAll(section("### 6.1 Tool naming convention", "### 6.2 Tool definitions"))
                .map { it.groupValues[1] }
                .toList()

        assertAll(
            { listed.size shouldBe 15 },
            { tools.keys shouldContainExactlyInAnyOrder listed },
        )
    }

    @Test
    fun `every §6_2 input schema matches the shipped tool exactly`() {
        val documented = documentedSchemas()

        // Row-count guard: a tool added to §6.2 must be implemented, not silently skipped.
        documented.keys + documentedInProse shouldContainExactlyInAnyOrder tools.keys

        assertAll(
            documented.map { (name, schema) ->
                {
                    val shipped = McpJsonDefaults.getMapper().writeValueAsString(tools.getValue(name).definition.inputSchema())
                    McpTools.readTree(shipped) shouldBe schema
                }
            },
        )
    }

    @Test
    fun `every §7_1 resource URI form parses to a distinct resource type`() {
        val forms =
            Regex("^datapipelines://\\S+", RegexOption.MULTILINE)
                .findAll(section("### 7.1 Resource URI scheme", "### 7.2 Resource examples"))
                .map { it.value }
                .toList()

        val parsed = forms.associateWith { McpResourceUri.parse(it.substituteExamples()) }

        assertAll(
            { forms.size shouldBe 9 },
            { parsed.filterValues { it == null }.keys shouldContainExactly emptyList() },
            {
                parsed.values
                    .mapNotNull { it }
                    .map { it::class.simpleName }
                    .toSet()
                    .size shouldBe 9
            },
        )
    }

    @Test
    fun `the prompt surface is exactly §8's two admissible prompts`() {
        val promptSection = section("## 8. Prompt Surface", "## 9. Error Handling")
        val declared =
            Regex("^### 8\\.\\d+ `([a-z_]+)`", RegexOption.MULTILINE)
                .findAll(promptSection)
                .map { it.groupValues[1] }
                .toList()
        val notInV1 =
            Regex("^### 8\\.\\d+ `([a-z_]+)` — \\*\\*not in v1\\*\\*", RegexOption.MULTILINE)
                .findAll(promptSection)
                .map { it.groupValues[1] }
                .toSet()

        assertAll(
            { declared.size shouldBe 3 },
            { notInV1 shouldBe setOf("create_pipeline_for_question") },
            { McpPromptCatalog().prompts.map { it.name() } shouldContainExactlyInAnyOrder (declared - notInV1) },
        )
    }

    /** Every §6.2 fenced JSON block, keyed by the tool it defines. */
    private fun documentedSchemas(): Map<String, JsonNode> =
        Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .findAll(section("### 6.2.1 `pipelines_list`", "### 6.3 Tool result schema"))
            .map { McpTools.readTree(it.groupValues[1]) }
            .associate { it["name"].asText() to it["inputSchema"] }

    private fun section(
        from: String,
        to: String,
    ): String = spec.substring(spec.indexOf(from), spec.indexOf(to))

    /** §7.1 writes forms with `{id}` placeholders; substitute values of the right shape. */
    private fun String.substituteExamples(): String =
        replace("{id}", McpFixtures.PIPELINE_ID.toString())
            .replace("{execution_id}", McpFixtures.EXECUTION_ID.toString())
            .replace("{version}", "2")
            .replace("{name}", "pg-prod")

    /** The production tool list, built exactly as the autoconfiguration builds it. */
    private fun shippedTools(): List<McpTool> {
        val pipelines = mockk<PipelineRepository>()
        val templates = mockk<TemplateRepository>()
        val datasources = mockk<DatasourceRegistry>()
        val executions = mockk<ExecutionRepository>()
        val resultStore = mockk<ResultStore>()
        val urls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" }
        val deserializer = PipelineDeserializer()
        return listOf(
            PipelinesListTool(pipelines, deserializer),
            PipelinesGetTool(pipelines),
            PipelineExecuteTool(pipelines, mockk<PipelineExecutor>(), resultStore, urls, deserializer),
            PipelinesCreateTool(pipelines, deserializer, mockk<PipelineValidator>(), PipelineSerializer()),
            PipelinesUpdateTool(pipelines, deserializer, mockk<PipelineValidator>(), PipelineSerializer()),
            TemplatesListTool(templates),
            TemplatesGetTool(templates),
            TemplatesCreateTool(templates, mockk<TemplateValidator>()),
            TemplatesRenderTool(templates, mockk<TemplateEngine>()),
            DatasourcesListTool(datasources),
            DatasourcesGetTool(datasources),
            DatasourcesTestTool(datasources),
            ExecutionsListTool(executions),
            ExecutionsGetTool(executions),
            ExecutionsGetResultTool(executions, resultStore, urls),
        ).also { mockk<AuditLogger>(relaxed = true) }
    }
}
