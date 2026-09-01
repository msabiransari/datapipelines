package co.datapipelines.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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

    // 033/C3: the REAL bean method's output (see RealShippedTools.kt) — not the hand-built
    // fixture this class used to keep, whose "built exactly as the autoconfiguration builds
    // it" comment was a claim, not a constraint.
    private val tools = realShippedTools().associateBy { it.name }

    /**
     * The one tool §6.2 documents in prose instead of a JSON block ("Same input as
     * `pipelines_create` plus required `id`"), so it has no block to compare against. Named here so
     * the count guard below still covers all 18.
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
            // 033/C3: the count comes from the catalog, not a hardcoded 18 — a 19th tool
            // shipped without a spec row turns the names assertion red, and a spec row
            // without a tool turns the catalog binding red (McpToolCatalogBindingTest).
            { listed.size shouldBe McpToolCatalog.NAMES.size },
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
            { notInV1 shouldBe emptySet() },
            { McpPromptCatalog().prompts.map { it.name() } shouldContainExactlyInAnyOrder declared },
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
}
