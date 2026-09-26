package co.datapipelines.mcp

import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.mcp.docs.DocArea
import co.datapipelines.mcp.docs.DocSet
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Structural assertions over the RENDERED set (the 242a record §5, first row): the generated
 * documents cannot name a world the catalogs do not ship, and the catalogs' contents cannot go
 * undocumented. Replaces `SkillToolsDocDriftTest` and the hand-written `error-codes.md`'s
 * drift: what is a catalog is generated (P5), and the guard runs over what the server SERVES.
 *
 * The two sides are deliberately independent renderings of the surface: the set comes from
 * [DocSetTestSupport.renderedDocSet], the expectations here from `McpToolCatalog`,
 * pipeline-contract §13's own parse (the audit's authority, never the generator's walk),
 * [NodeType] and [CalculatorRegistry].
 */
class DocSetStructureTest {
    private val docSet = DocSetTestSupport.renderedDocSet()

    @Test
    fun `the set is whole - core, eight areas, the narrative references`() {
        docSet.docs.size shouldBeGreaterThan 18
        docSet.core.name shouldBe DocSet.CORE_NAME
        for (area in DocArea.entries) {
            withClue("area ${area.wire} has no documents in the rendered set") {
                docSet.byArea(area).size shouldBeGreaterThan 0
            }
        }
    }

    @Test
    fun `every shipped tool appears exactly once, in its area's tools reference, with its shipped description`() {
        val occurrences = mutableMapOf<String, MutableList<String>>()
        for (tool in McpToolCatalog.NAMES) {
            for (doc in docSet.docs) {
                if (doc.markdown.lineSequence().any { it.trim() == "### `$tool`" }) {
                    occurrences.getOrPut(tool) { mutableListOf() }.add(doc.name)
                }
            }
        }
        val missing = McpToolCatalog.NAMES.filterNot { it in occurrences }
        withClue("tools with no ### heading anywhere in the set") { missing.shouldBeEmpty() }
        val duplicated = occurrences.filterValues { it.size != 1 }
        withClue("tools appearing in more than one document (or twice in one)") { duplicated.keys.shouldBeEmpty() }
        // Each tool lives in its OWN area's reference, and the section carries the shipped
        // description (one line, as the renderer spells it) and the catalogued permission.
        for (tool in McpToolCatalog.NAMES) {
            val area = DocArea.ofTool(tool)
            val docName = occurrences.getValue(tool).single()
            withClue("$tool lands in $docName, not ${area.wire}-tools") {
                docName shouldBe "${area.wire}-tools"
            }
            val doc = docSet.get(docName)
            val description =
                realShippedTools()
                    .first { it.name == tool }
                    .definition
                    .description()
                    .orEmpty()
            withClue("$tool's section does not carry its shipped description") {
                doc.markdown shouldContain oneLine(description)
            }
            val permission = McpToolCatalog.permissionOf(tool)!!.wire
            withClue("$tool's section does not carry its catalogued permission `$permission`") {
                doc.markdown shouldContain "`$permission`"
            }
        }
    }

    @Test
    fun `every catalogued error code appears once in core-error-codes`() {
        val documented = documentedCatalogCodes()
        withClue("the §13 parse yielded almost nothing — the locator or the doc changed") {
            documented.size shouldBeGreaterThan 200
        }
        val doc = docSet.get("core-error-codes")
        val missing = documented.filterNot { "`$it`" in doc.markdown }
        withClue("§13 codes missing from the rendered core-error-codes") { missing.shouldBeEmpty() }
        val duplicated = documented.filter { occurrences(doc.markdown, "`$it`") != 1 }
        withClue("codes appearing more than once in core-error-codes") { duplicated.shouldBeEmpty() }
    }

    @Test
    fun `every node type appears in the node-types narrative`() {
        val doc = docSet.get("pipelines-node-types")
        val missing =
            NodeType.entries
                .map { it.wire }
                .filterNot { wire -> doc.markdown.contains(Regex("(?<![A-Z])$wire(?![A-Z])")) }
        withClue("NodeType values the node-types document does not name — a type was added without prose") {
            missing.shouldBeEmpty()
        }
    }

    @Test
    fun `every calculator kind appears in the calculator catalog`() {
        val doc = docSet.get("pipelines-calculators")
        val missing = CalculatorRegistry.NAMES.filterNot { "## `$it`" in doc.markdown }
        withClue("calculator kinds with no catalog section") { missing.shouldBeEmpty() }
    }

    @Test
    fun `no shipped tool maps to a reserved area`() {
        for (tool in McpToolCatalog.NAMES) {
            val prefix = tool.substringBefore('_')
            withClue("tool $tool maps to reserved prefix '$prefix' — the area is added by the lane that ships it") {
                (prefix in DocArea.RESERVED_PREFIXES) shouldBe false
            }
        }
    }

    /**
     * The §13 (and §12) catalog, parsed from pipeline-contract.md with the audit's check-C
     * regex — the INDEPENDENT side of the error-code assertion: the generator walks the
     * constants, this reads the document. Restricted to codes that HAVE a
     * `PipelineErrorCodes` constant (the pipeline-contract drift test already holds that set
     * to §13), which drops the doc's method-call and prose false positives without weakening
     * the guard: a §13 code whose constant the generator's walk misses still has its constant
     * here, so it stays expected and the render goes red.
     */
    private fun documentedCatalogCodes(): Set<String> {
        val text = SpecFiles.read("docs/pipeline-contract.md")
        val parsed =
            Regex(
                "(?<![.\\w-])(?:pipeline|template|datasource|auth|workspace|result|rate_limit|" +
                    "idempotency|type_mapping|mcp|endpoint|mail|lake)\\.[a-z0-9_]+(?:\\.[a-z0-9_*]+)*(?![\\w-])",
            ).findAll(text).map { it.value }.toSet()
        return parsed intersect reflectedConstants()
    }

    /** Every `const val` string of [PipelineErrorCodes] and its nested objects — the walk, stated here. */
    private fun reflectedConstants(): Set<String> {
        val visited = mutableSetOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>(listOf(PipelineErrorCodes::class.java))
        val codes = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            for (field in current.fields) {
                if (field.type == String::class.java && Modifier.isStatic(field.modifiers) && Modifier.isPublic(field.modifiers)) {
                    codes.add(field.get(null) as String)
                }
            }
            for (nested in current.declaredClasses) queue.addLast(nested)
        }
        return codes
    }

    private fun occurrences(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
