package co.datapipelines.application.endpoints

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The §4.1 path grammar and the ambiguity relation the whole matcher design rests on.
 *
 * The overlap tests are the load-bearing half: if [EndpointPath.overlaps] ever returned false for
 * a genuinely ambiguous pair, publishing would accept two patterns that match one URL and
 * `EndpointMatcher` — which does no ranking BECAUSE this refusal exists — would silently pick one
 * of two meanings. Every case below is a pair a careless implementation gets wrong.
 */
class EndpointPathTest {
    @Test
    fun `a legal path parses into its literal and variable segments`() {
        val parsed = EndpointPath.parse("/nyc/v1/revenue/{borough}").getOrThrow()
        assertAll(
            { parsed.pattern shouldBe "/nyc/v1/revenue/{borough}" },
            { parsed.segments.size shouldBe 4 },
            { parsed.segments[0] shouldBe EndpointPath.Segment.Literal("nyc") },
            { parsed.segments[3] shouldBe EndpointPath.Segment.Variable("borough") },
            { parsed.variableNames shouldBe listOf("borough") },
        )
    }

    @Test
    fun `the literal alphabet is the template-name one — dots, dashes and underscores inside`() {
        assertAll(
            { EndpointPath.parse("/a.b-c_d/v1/x").isSuccess shouldBe true },
            { EndpointPath.parse("/9lives/v1/x").isSuccess shouldBe true },
            // Must START alphanumeric.
            { EndpointPath.parse("/-leading/v1/x").isSuccess shouldBe false },
            { EndpointPath.parse("/_leading/v1/x").isSuccess shouldBe false },
            { EndpointPath.parse("/.leading/v1/x").isSuccess shouldBe false },
            // No uppercase, no spaces, no percent-encoding games.
            { EndpointPath.parse("/Borough/v1/x").isSuccess shouldBe false },
            { EndpointPath.parse("/two words/v1/x").isSuccess shouldBe false },
            { EndpointPath.parse("/a%2Fb/v1/x").isSuccess shouldBe false },
        )
    }

    @Test
    fun `every structural rule of §4_1 is refused, and says which rule`() {
        assertAll(
            { reason("no-leading-slash") shouldContain "must start with '/'" },
            { reason("/") shouldContain "root path" },
            { reason("/trailing/v1/x/") shouldContain "must not end with '/'" },
            { reason("/wild/v1/**") shouldContain "Wildcards" },
            { reason("/" + (1..11).joinToString("/") { "s$it" }) shouldContain "11 segments" },
            { reason("/a/v1/" + "a".repeat(195)) shouldContain "characters" },
            { reason("/nyc/v1/{Borough}") shouldContain "malformed" },
            { reason("/nyc/v1/{9bad}") shouldContain "malformed" },
        )
    }

    @Test
    fun `the R-EP5 shape — at least three segments, the category and version literal`() {
        assertAll(
            // Two segments is not an endpoint: no room for the path beneath category and version.
            { reason("/trade/v1") shouldContain "at least" },
            { reason("/nyc") shouldContain "at least" },
            // Variables live AFTER the version only.
            { reason("/{ns}/v1/x") shouldContain "category" },
            { reason("/nyc/{v}/x") shouldContain "version" },
            // The version is free-form — v1 by convention, anything literal in law.
            { EndpointPath.parse("/nyc/2025/revenue").isSuccess shouldBe true },
            { EndpointPath.parse("/nyc/blue/revenue").isSuccess shouldBe true },
        )
    }

    @Test
    fun `one api prefix is stripped, and the stored form never carries it`() {
        assertAll(
            { EndpointPath.parse("/api/trade/v1/x").getOrThrow().pattern shouldBe "/trade/v1/x" },
            { EndpointPath.parse("/trade/v1/x").getOrThrow().pattern shouldBe "/trade/v1/x" },
            // Exactly ONE prefix: /api/api/v1/x normalises to a pattern whose category is 'api'.
            { EndpointPath.parse("/api/api/v1/x").getOrThrow().pattern shouldBe "/api/v1/x" },
            { EndpointPath.reservedCategory(EndpointPath.parse("/api/api/v1/x").getOrThrow()) shouldBe "api" },
        )
    }

    @Test
    fun `the reserved categories are the product's version namespace and the prefix itself`() {
        // Reserved: every v<number> is the product's API namespace, today and tomorrow.
        listOf("v1", "v2", "v10").forEach { category ->
            withClue(category) {
                EndpointPath.reservedCategory(EndpointPath.parse("/$category/v1/x").getOrThrow()) shouldBe category
            }
        }
        // Legal: a category is the engineer's namespace — anything else in the segment alphabet.
        listOf("trade", "v", "v1a", "2025", "nyc").forEach { category ->
            withClue(category) {
                EndpointPath.reservedCategory(EndpointPath.parse("/$category/v1/x").getOrThrow()) shouldBe null
            }
        }
    }

    @Test
    fun `the category URL constraint and the reserved rule agree`() {
        // The catch-all mapping carries CATEGORY_URL_PATTERN; the reserved rule lives in
        // RESERVED_CATEGORY. Both are tested against one corpus so the two spellings of R-EP5
        // cannot drift — the mapping is what keeps a reserved category from reaching the handler.
        val constraint = Regex("^" + EndpointPath.CATEGORY_URL_PATTERN + "$")
        listOf("v1", "v2", "v10", "api", "trade", "v", "v1a", "2025", "nyc", "api-x", "x-api").forEach { category ->
            withClue(category) {
                constraint.matches(category) shouldBe !EndpointPath.RESERVED_CATEGORY.matches(category)
            }
        }
    }

    @Test
    fun `a tree node parses without the endpoint shape — a binding may stop at the category`() {
        assertAll(
            { EndpointPath.parseTreeNode("/nyc").getOrThrow().pattern shouldBe "/nyc" },
            { EndpointPath.parseTreeNode("/nyc/revenue").getOrThrow().pattern shouldBe "/nyc/revenue" },
            { EndpointPath.parseTreeNode("/{x}").isSuccess shouldBe true },
            // The grammar still holds: a node is segments, not free text.
            { EndpointPath.parseTreeNode("/nyc/Bad").isSuccess shouldBe false },
            // And the same normalisation: a binding written with the prefix names the same node.
            { EndpointPath.parseTreeNode("/api/nyc").getOrThrow().pattern shouldBe "/nyc" },
        )
    }

    @Test
    fun `a segment of exactly 64 characters is legal and 65 is not`() {
        // The rule is [a-z0-9][a-z0-9_.-]{0,63} — one leading char plus up to 63 more.
        assertAll(
            { EndpointPath.parse("/nyc/v1/" + "a".repeat(64)).isSuccess shouldBe true },
            { EndpointPath.parse("/nyc/v1/" + "a".repeat(65)).isSuccess shouldBe false },
        )
    }

    @Test
    fun `the same variable name twice is refused — each variable names one parameter`() {
        reason("/a/v1/{x}/b/{x}") shouldContain "appears twice"
    }

    @Test
    fun `ten segments is legal, eleven is not`() {
        assertAll(
            { EndpointPath.parse("/" + (1..10).joinToString("/") { "s$it" }).isSuccess shouldBe true },
            { EndpointPath.parse("/" + (1..11).joinToString("/") { "s$it" }).isSuccess shouldBe false },
        )
    }

    @Test
    fun `a variable overlaps a literal in the same position — the case §4_1 exists for`() {
        overlap("/a/v1/{x}", "/a/v1/b") shouldBe true
    }

    @Test
    fun `two different literals in one position do not overlap`() {
        overlap("/a/v1/b", "/a/v1/c") shouldBe false
    }

    @Test
    fun `patterns of different segment counts never overlap`() {
        assertAll(
            { overlap("/a/v1/{x}", "/a/v1/b/c") shouldBe false },
            // A variable binds exactly ONE segment, so it cannot span '/b/c'.
            { overlap("/a/v1/{x}/d", "/a/v1/b") shouldBe false },
        )
    }

    @Test
    fun `overlap is symmetric and reflexive — it ranks nothing`() {
        assertAll(
            { overlap("/a/v1/{x}", "/a/v1/b") shouldBe overlap("/a/v1/b", "/a/v1/{x}") },
            { overlap("/a/v1/b", "/a/v1/b") shouldBe true },
            { overlap("/a/v1/{x}", "/a/v1/{y}") shouldBe true },
        )
    }

    @Test
    fun `two variables in different positions still overlap`() {
        // '/a/v1/{x}/c' and '/a/v1/b/{y}' both match '/a/v1/b/c'. A check that only compared
        // position by position for EQUAL variable names would miss this.
        overlap("/a/v1/{x}/c", "/a/v1/b/{y}") shouldBe true
    }

    @Test
    fun `the ancestor walk is most-specific first and ends at the root`() {
        EndpointPath.ancestors("/lending/manhattan/home") shouldBe
            listOf("/lending/manhattan/home", "/lending/manhattan", "/lending", "/")
    }

    @Test
    fun `the ancestor walk of a one-segment path is the path then the root`() {
        assertAll(
            { EndpointPath.ancestors("/lending") shouldBe listOf("/lending", "/") },
            { EndpointPath.ancestors("/") shouldBe listOf("/") },
            // Normalisation: a trailing slash must not produce a phantom empty ancestor.
            { EndpointPath.ancestors("/lending/") shouldBe listOf("/lending", "/") },
        )
    }

    private fun overlap(
        a: String,
        b: String,
    ): Boolean = EndpointPath.overlaps(EndpointPath.parse(a).getOrThrow(), EndpointPath.parse(b).getOrThrow())

    private fun reason(pattern: String): String {
        val result = EndpointPath.parse(pattern)
        withClue("'$pattern' should have been refused") { result.isFailure shouldBe true }
        return result.exceptionOrNull()?.message.orEmpty()
    }
}
