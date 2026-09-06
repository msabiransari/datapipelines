package co.datapipelines.templates

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: [TEMPLATE_PATH] versus the **written** §4.1 grammar
 * (`docs/template-hierarchy-design.md`).
 *
 * `PipelineNameGrammarSpecDriftTest` has anchored the pipeline copy to the document since 067,
 * and its KDoc names the gap this class closes: "`modules/templates`'s own copy is not
 * anchored to the doc today — that is pre-existing and out of this round's fence." 077 moved
 * the rule for the first time since, which is exactly when an unanchored second copy is
 * dangerous: two `Regex` literals in two modules, both under the same hand, and only one of
 * them held to the written rule.
 *
 * The derivation is deliberately the pipeline test's, twice over, so the two guards cannot
 * disagree about what §4.1 says:
 *
 * 1. **Assembly** from §4.1's grammar block — the `segment` production and the repetition
 *    bound are parsed out and composed, which catches a change to the segment character class
 *    or to the segment-count bounds (the 077 change is the lower bound: a folder is
 *    mandatory).
 * 2. **Literal identity** with §4.6's migration-gate SQL, which spells the whole regex out for
 *    Postgres. That block is what a deployment enforces against stored template names, so a
 *    save-time rule that disagreed with it would let a template take a name the next
 *    deployment would abort on.
 *
 * The length cap is read from §4.1's prose the same way.
 */
class TemplateNameGrammarSpecDriftTest {
    private val spec = TemplateFixtures.repoFile(SPEC_PATH).readText()

    @Test
    fun `the shipped regex is the grammar §4_1 writes down`() {
        val block =
            requireNotNull(Regex("```\\n(path\\s+:=.*?)```", RegexOption.DOT_MATCHES_ALL).find(spec)) {
                "§4.1's grammar block not found in $SPEC_PATH"
            }.groupValues[1]

        val segment =
            requireNotNull(Regex("""segment\s+:=\s+(\S+)""").find(block)) { "no `segment :=` production in §4.1" }
                .groupValues[1]
        val repetition =
            requireNotNull(Regex("""path\s+:=\s+segment\s+\("/"\s+segment\)(\{\d+,\d+})""").find(block)) {
                "no `path := segment (\"/\" segment){n,m}` production in §4.1"
            }.groupValues[1]

        withClue("§4.1 assembled: segment=$segment repetition=$repetition") {
            TEMPLATE_PATH.pattern shouldBe "^$segment(/$segment)$repetition$"
        }
    }

    @Test
    fun `the shipped regex is byte-identical to the one §4_6's deploy gate enforces`() {
        // The gate's SQL is a single-quoted Postgres regex on the `name !~ '…'` line.
        val gate =
            requireNotNull(Regex("""name !~ '(\^[^']+\$)'""").find(spec)) {
                "§4.6's `name !~ '…'` legacy-name gate not found in $SPEC_PATH"
            }.groupValues[1]

        TEMPLATE_PATH.pattern shouldBe gate
    }

    @Test
    fun `the shipped length cap is the one §4_1 writes down`() {
        val cap =
            requireNotNull(Regex("""Total path length: \*\*≤ (\d+) chars\*\*""").find(spec)) {
                "§4.1's total-length sentence not found in $SPEC_PATH"
            }.groupValues[1].toInt()

        MAX_TEMPLATE_PATH_CHARS shouldBe cap
    }

    private companion object {
        const val SPEC_PATH = "docs/template-hierarchy-design.md"
    }
}
