package co.datapipelines.web.ui.site

import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.web.TestRepoFiles
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

/**
 * 119 §B.9 — the semantic-layer page's completeness pattern (the tools page's, applied to
 * the learned-facts kinds): the page renders a TWELVE-row kinds table, and the table is
 * copied — verbatim, meaning for meaning — from `docs/enums.md` §19, the one authority for
 * `LearnedFactKind`. A hand-copied table drifts the moment the enum grows, so this guard
 * parses BOTH sources and fails the build on any difference in value, scope or meaning,
 * in either direction.
 *
 * Also pinned here, because they are facts about the server this page must not exaggerate:
 * the three `semantics_*` tools the page names are names the catalogue actually ships; and
 * the `facts[]` wire example the page quotes as "one real response" really parses as the
 * introspection shape it claims to be — one column carrying one fact, kind `unit`, trust
 * `observed`, with an evidence summary (the non-vacuity that keeps the example honest).
 */
class SiteSemanticLayerGuardsTest {
    private val templateSource: String =
        TestRepoFiles.read("modules/web/src/main/resources/templates/site/semantic-layer.html")

    @Test
    fun `the page's kinds table agrees with enums section 19, row for row`() {
        val fromDoc =
            ENUM_ROW
                .findAll(enumsSection19())
                .map { it.destructured }
                .map { (kind, scope, meaning) -> Triple(kind, scope, meaning.trim()) }
                .toList()
        val fromPage =
            PAGE_ROW
                .findAll(templateSource)
                .map { it.destructured }
                .map { (kind, scope, meaning) -> Triple(kind, scope, meaning.trim()) }
                .toList()

        // Non-vacuity on both sides: a changed table shape parses to nothing and would
        // otherwise make the equality below pass by comparing two empty lists.
        withClue("kinds parsed from docs/enums.md §19") { fromDoc.size shouldBeGreaterThanOrEqual 12 }
        withClue("kinds parsed from the page's table") { fromPage.size shouldBeGreaterThanOrEqual 12 }

        withClue("kinds on the page but not in enums.md §19") {
            fromPage.filterNot { it in fromDoc }.shouldBeEmpty()
        }
        withClue("kinds in enums.md §19 but missing from the page") {
            fromDoc.filterNot { it in fromPage }.shouldBeEmpty()
        }
    }

    @Test
    fun `the tools the page names are tools the catalogue ships`() {
        // Non-vacuity: the catalogue really has the semantics family (a renamed tool would
        // otherwise make this check pass while naming nothing).
        withClue("the catalogue's semantics_* tools") {
            McpToolCatalog.NAMES.filter { it.startsWith("semantics_") }.size shouldBeGreaterThanOrEqual 3
        }
        val unknown = pageTools.filterNot { it in McpToolCatalog.NAMES }
        withClue("tools the page names that the catalogue does not ship") { unknown.shouldBeEmpty() }
        // All three family members are on the page — not just a cherry-picked one.
        val onPage = pageTools.filter { it.startsWith("semantics_") }
        withClue("semantics_* tools named on the page") {
            onPage.shouldContainAll(listOf("semantics_record", "semantics_list", "semantics_retire"))
        }
    }

    @Test
    fun `the facts example parses as the introspection shape it claims to be`() {
        val json = FACTS_EXAMPLE.find(templateSource)?.groupValues?.get(1) ?: throw AssertionError("the page lost its facts[] example")
        val columns = MAPPER.readTree(json)
        withClue("the example is a column array") { columns.isArray shouldBe true }

        val facts = columns.flatMap { col -> col["facts"]?.let { f -> 0.until(f.size()).map { f[it] } } ?: emptyList() }
        withClue("the example carries at least one fact") { facts.size shouldBeGreaterThanOrEqual 1 }
        facts.forEach { fact ->
            val kind = fact["kind"].asText()
            withClue("fact kind=$kind") {
                fact["scope"].asText() shouldBe "DATASOURCE"
                fact["kind"].asText() shouldBe "unit"
                fact["trust"].asText() shouldBe "observed"
                fact["evidence_summary"].asText().shouldNotBeBlank()
            }
        }
    }

    /** §19's slice of enums.md — from its heading to the next `## ` section. */
    private fun enumsSection19(): String {
        val doc = TestRepoFiles.read("docs/enums.md")
        val start = doc.indexOf("## 19.")
        require(start >= 0) { "docs/enums.md lost its §19 — fix this guard, do not delete it" }
        val end = doc.indexOf("\n## ", start + 1)
        return if (end > start) doc.substring(start, end) else doc
    }

    /** The `semantics_*` names the page quotes — checked against the real catalogue. */
    private val pageTools: List<String> =
        Regex("""\b(semantics_[a-z_]+)\b""")
            .findAll(templateSource)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    private companion object {
        /** One enums.md §19 row: `| `kind` | `SCOPE` | meaning | … |`. */
        val ENUM_ROW = Regex("""\|\s*`([a-z_]+)`\s*\|\s*`(DATASOURCE|WORKSPACE)`\s*\|\s*([^|]+)\|""")

        /** One page-table row: `<th scope="row"><code>kind</code></th><td>SCOPE</td><td>meaning</td>`. */
        val PAGE_ROW = Regex("""<th scope="row"><code>([a-z_]+)</code></th><td>(DATASOURCE|WORKSPACE)</td><td>([^<]+)</td>""")

        /** The page's verbatim `facts[]` example, inside its code block. */
        val FACTS_EXAMPLE = Regex("""<pre><code>(\[.*?\])</code></pre>""", RegexOption.DOT_MATCHES_ALL)

        val MAPPER = ObjectMapper()
    }
}
