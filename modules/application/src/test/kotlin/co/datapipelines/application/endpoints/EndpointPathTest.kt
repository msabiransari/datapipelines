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
        val parsed = EndpointPath.parse("/nyc/revenue/{borough}").getOrThrow()
        assertAll(
            { parsed.pattern shouldBe "/nyc/revenue/{borough}" },
            { parsed.segments.size shouldBe 3 },
            { parsed.segments[0] shouldBe EndpointPath.Segment.Literal("nyc") },
            { parsed.segments[2] shouldBe EndpointPath.Segment.Variable("borough") },
            { parsed.variableNames shouldBe listOf("borough") },
        )
    }

    @Test
    fun `the literal alphabet is the template-name one — dots, dashes and underscores inside`() {
        assertAll(
            { EndpointPath.parse("/a.b-c_d").isSuccess shouldBe true },
            { EndpointPath.parse("/9lives").isSuccess shouldBe true },
            // Must START alphanumeric.
            { EndpointPath.parse("/-leading").isSuccess shouldBe false },
            { EndpointPath.parse("/_leading").isSuccess shouldBe false },
            { EndpointPath.parse("/.leading").isSuccess shouldBe false },
            // No uppercase, no spaces, no percent-encoding games.
            { EndpointPath.parse("/Borough").isSuccess shouldBe false },
            { EndpointPath.parse("/two words").isSuccess shouldBe false },
            { EndpointPath.parse("/a%2Fb").isSuccess shouldBe false },
        )
    }

    @Test
    fun `every structural rule of §4_1 is refused, and says which rule`() {
        assertAll(
            { reason("no-leading-slash") shouldContain "must start with '/'" },
            { reason("/") shouldContain "root path" },
            { reason("/trailing/") shouldContain "must not end with '/'" },
            { reason("/wild/**") shouldContain "Wildcards" },
            { reason("/" + (1..11).joinToString("/") { "s$it" }) shouldContain "11 segments" },
            { reason("/" + "a".repeat(201)) shouldContain "characters" },
            { reason("/{Borough}") shouldContain "malformed" },
            { reason("/{9bad}") shouldContain "malformed" },
        )
    }

    @Test
    fun `a segment of exactly 64 characters is legal and 65 is not`() {
        // The rule is [a-z0-9][a-z0-9_.-]{0,63} — one leading char plus up to 63 more.
        assertAll(
            { EndpointPath.parse("/" + "a".repeat(64)).isSuccess shouldBe true },
            { EndpointPath.parse("/" + "a".repeat(65)).isSuccess shouldBe false },
        )
    }

    @Test
    fun `the same variable name twice is refused — each variable names one parameter`() {
        reason("/a/{x}/b/{x}") shouldContain "appears twice"
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
        overlap("/a/{x}", "/a/b") shouldBe true
    }

    @Test
    fun `two different literals in one position do not overlap`() {
        overlap("/a/b", "/a/c") shouldBe false
    }

    @Test
    fun `patterns of different segment counts never overlap`() {
        assertAll(
            { overlap("/a/{x}", "/a") shouldBe false },
            { overlap("/a/{x}", "/a/b/c") shouldBe false },
            // A variable binds exactly ONE segment, so it cannot span '/b/c'.
            { overlap("/{x}", "/a/b") shouldBe false },
        )
    }

    @Test
    fun `overlap is symmetric and reflexive — it ranks nothing`() {
        assertAll(
            { overlap("/a/{x}", "/a/b") shouldBe overlap("/a/b", "/a/{x}") },
            { overlap("/a/b", "/a/b") shouldBe true },
            { overlap("/a/{x}", "/a/{y}") shouldBe true },
        )
    }

    @Test
    fun `two variables in different positions still overlap`() {
        // '/a/{x}/c' and '/a/b/{y}' both match '/a/b/c'. A check that only compared position by
        // position for EQUAL variable names would miss this.
        overlap("/a/{x}/c", "/a/b/{y}") shouldBe true
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
