package co.datapipelines.templates

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * templates.md §12.3 — "validation of a body at `max-body-chars` completes within a bounded
 * time" — asserted as **bounds**, not as stopwatch readings (083 §B).
 *
 * ## Why this file exists next to [TemplateValidatorTest]
 *
 * The §12.3 cases were pinned with `elapsed < 5_000`, and a duration is not the property: it is a
 * claim about one machine's load at one moment. It measured 5,833 ms during a loaded full gate,
 * turned five of the last seven gates red, and was green in isolation every time anyone checked.
 * The cases themselves keep their homes in [TemplateValidatorTest]; what lives here is the
 * *shape* of the bound — that the work grows linearly in the input, and that the two regexes
 * which run over untrusted source before any parse cannot backtrack.
 *
 * ## The two halves
 *
 *  - **Counted growth.** [ValidationTrace] publishes the validator's own steps, so "linear in the
 *    body" is a comparison between two measured runs at two sizes rather than a hope.
 *  - **Linear by construction.** [ForbiddenConstructScanner]'s source-level patterns run on the
 *    request thread BEFORE the length-bounded parse, so a nested quantifier there would be a
 *    catastrophic-backtracking denial of service the parse bounds cannot reach. That is a
 *    property of the pattern text, and it is checked as one.
 */
class AdversarialBoundsTest {
    private val workspaceId = java.util.UUID.randomUUID()

    private fun validator() = TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })

    @Test
    fun `validation work grows linearly with the body, not quadratically`() {
        // Two sizes an order of magnitude apart. Quadratic work would show as ~100x the steps;
        // linear shows as ~10x. The assertion is deliberately loose (a 2x allowance) because the
        // claim being defended is the exponent, not the constant.
        val small = bodyOf(SMALL_LINES)
        val large = bodyOf(SMALL_LINES * GROWTH)

        val smallTrace = ValidationTrace()
        val largeTrace = ValidationTrace()
        validator().validate(TemplateFixtures.draft(body = small), workspaceId, smallTrace).isValid.shouldBeTrue()
        validator().validate(TemplateFixtures.draft(body = large), workspaceId, largeTrace).isValid.shouldBeTrue()

        val bound = smallTrace.steps.toLong() * GROWTH * LINEAR_ALLOWANCE
        withClue("small=$smallTrace large=$largeTrace — ${GROWTH}x the input must not cost more than ${LINEAR_ALLOWANCE}x linear") {
            (largeTrace.steps <= bound).shouldBeTrue()
        }
        // And the parse count does not grow at all: one body, one parse, at any size (§4.2).
        largeTrace.parseAttempts shouldBe smallTrace.parseAttempts
        largeTrace.parseAttempts shouldBe 1
    }

    @Test
    fun `the pre-parse source patterns are linear by construction - no nested quantifiers`() {
        // These two run over an UNBOUNDED-until-checked body on the request thread, before the
        // parse the length cap and the bounded stack protect. A nested quantifier — `(a+)+`,
        // `(a*)*`, `(a|a)+` — is the classic catastrophic-backtracking shape, and no downstream
        // bound can save a caller from it, because it happens first.
        //
        // Read out of the source rather than asserted about behaviour: "this pattern cannot
        // backtrack" is a property of the text, and a timing test for it would be exactly the
        // flake this round removed.
        val source = TemplateFixtures.repoFile(SCANNER_PATH).readText()
        val patterns = PATTERN_LITERAL.findAll(source).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

        withClue("no regex literals found in $SCANNER_PATH — the parse, not the patterns, changed") {
            (patterns.size >= EXPECTED_PATTERNS).shouldBeTrue()
        }
        val nested = patterns.filter { NESTED_QUANTIFIER.containsMatchIn(it) }
        withClue("patterns with a quantified group that is itself quantified") { nested.shouldBeEmpty() }
    }

    /** A legal, boring body of [lines] interpolated SQL lines. */
    private fun bodyOf(lines: Int): String = "SELECT \${a} FROM t WHERE b='\${c}'\n".repeat(lines)

    private companion object {
        const val SMALL_LINES = 200
        const val GROWTH = 10
        const val LINEAR_ALLOWANCE = 2

        const val SCANNER_PATH = "modules/templates/src/main/kotlin/co/datapipelines/templates/ForbiddenConstructScanner.kt"

        /** The count today is 4; the floor guards a parse that silently stopped finding them. */
        const val EXPECTED_PATTERNS = 4

        /** `Regex("""…""")` and `Regex("…")` literals. */
        val PATTERN_LITERAL = Regex("""Regex\(\s*(?:"{3}([\s\S]*?)"{3}|"((?:[^"\\]|\\.)*)")""")

        /** A quantified group immediately quantified again — the backtracking shape. */
        val NESTED_QUANTIFIER = Regex("""\([^()]*[+*][^()]*\)\s*[+*]""")
    }
}
