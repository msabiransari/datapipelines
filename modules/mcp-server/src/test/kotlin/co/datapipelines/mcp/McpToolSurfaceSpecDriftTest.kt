package co.datapipelines.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
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
 * the part this module hand-transcribes: §6.2's tool blocks — each tool's top-level `description`
 * (the sentence an agent reads first in `tools/list`) and its `inputSchema` (every property, type,
 * enum, default, `required` list and description) — plus §7.1's URI forms and §8's prompt names.
 * `McpTools.tool()` takes the schema as a raw string, so without this test a dropped `enum` or a
 * reworded description disagrees with the frozen contract silently, which is exactly how the two
 * Gate C findings (`templates_render`'s return shape, `datasources_list`'s stray enum) happened.
 *
 * Two **deep equalities**, one per half of a block, not structural subsets: an agent reads both
 * the description and the schema's property descriptions to decide what to send, so both are
 * contract. Until #235 only the `inputSchema` half was compared — a tool's own `description` could
 * be reworded in code, or in the doc, with this test green.
 *
 * What this test does NOT check: the served manual's tool references are generated from the
 * shipped descriptions (`DocSetStructureTest`), so they cannot drift from code; and the BODY prose
 * around each block (the "Returns:" paragraphs) is not parsed at all.
 */
class McpToolSurfaceSpecDriftTest {
    private val spec = SpecFiles.read(SpecFiles.MCP_SPEC_PATH)

    // 033/C3: the REAL bean method's output (see RealShippedTools.kt) — not the hand-built
    // fixture this class used to keep, whose "built exactly as the autoconfiguration builds
    // it" comment was a claim, not a constraint.
    private val tools = realShippedTools().associateBy { it.name }

    /**
     * The one tool whose INPUT §6.2 documents in prose ("Same input as `pipelines_create` plus
     * required `id`") instead of an `inputSchema`, so its block carries `name` and `description`
     * only and the schema comparison skips it (the description comparison does not). Named here so
     * a second tool cannot lose its schema unnoticed: every other block must carry one.
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
        val documented = documentedBlocks()

        // Row-count guard: a tool added to §6.2 must be implemented, not silently skipped.
        documented.keys + documentedInProse shouldContainExactlyInAnyOrder tools.keys

        assertAll(
            documented.map { (name, block) ->
                {
                    if (name in documentedInProse) {
                        withClue("§6.2 block `$name` is documented in prose: it carries no inputSchema") {
                            block.has("inputSchema") shouldBe false
                        }
                    } else {
                        val shipped = McpJsonDefaults.getMapper().writeValueAsString(tools.getValue(name).definition.inputSchema())
                        McpTools.readTree(shipped) shouldBe block["inputSchema"]
                    }
                }
            },
        )
    }

    @Test
    fun `every §6_2 tool description matches the shipped tool exactly`() {
        val documented = documentedBlocks()

        // Same row-count guard as the schema test: a block that LOSES its description is a
        // named failure below, never a silently skipped row.
        documented.keys + documentedInProse shouldContainExactlyInAnyOrder tools.keys

        assertAll(
            documented.map { (name, block) ->
                {
                    val description = block["description"]
                    withClue("§6.2 block `$name` has no top-level string `description`") {
                        (description != null && description.isTextual) shouldBe true
                    }
                    // The whole string, whitespace as written — an agent reads it first in tools/list.
                    withClue("`$name` description: shipped (actual) vs §6.2 block (expected)") {
                        tools.getValue(name).definition.description() shouldBe description?.asText()
                    }
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
            // 9 entity forms + the two `docs/skill` forms 095 added.
            { forms.size shouldBe 11 },
            { parsed.filterValues { it == null }.keys shouldContainExactly emptyList() },
            {
                parsed.values
                    .mapNotNull { it }
                    .map { it::class.simpleName }
                    .toSet()
                    .size shouldBe 11
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

    /** Every §6.2 fenced JSON block (`name`, `description`, `inputSchema`), keyed by the tool it defines. */
    private fun documentedBlocks(): Map<String, JsonNode> =
        Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .findAll(section("### 6.2.1 `pipelines_list`", "### 6.3 Tool result schema"))
            .map { McpTools.readTree(it.groupValues[1]) }
            .associateBy { it["name"].asText() }

    private fun section(
        from: String,
        to: String,
    ): String = spec.substring(spec.indexOf(from), spec.indexOf(to))

    /** §7.1 writes forms with `{id}` placeholders; substitute values of the right shape. */
    private fun String.substituteExamples(): String =
        replace("{reference}", "templates")
            .replace("{id}", McpFixtures.PIPELINE_ID.toString())
            .replace("{execution_id}", McpFixtures.EXECUTION_ID.toString())
            .replace("{version}", "2")
            .replace("{name}", "pg-prod")
}
