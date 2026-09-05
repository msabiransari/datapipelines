package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: [PIPELINE_PATH] versus the **written** path grammar.
 *
 * 067 rules that a pipeline name obeys character-for-character the same grammar a template
 * name does (template-hierarchy-design §4.1). `modules/templates` depends on this module and
 * never the reverse, so the constant cannot be imported from there and the two live as
 * separate `Regex` literals in separate modules. Left alone, that is precisely the shape this
 * project has been bitten by: two copies of one rule, both under the same hand, drifting with
 * a green suite.
 *
 * So neither copy is the authority — the DOCUMENT is, and this test derives the rule from it
 * twice, by two independent routes:
 *
 * 1. **Assembly** from §4.1's grammar block: the segment production and the repetition bound
 *    are parsed out and composed into a path regex, which must equal the shipped pattern. This
 *    catches a change to the segment character class or to the 10-segment bound.
 * 2. **Literal identity** with §4.6's migration-gate SQL, which spells the whole regex out for
 *    Postgres. That block is what a deployment actually enforces against stored template names,
 *    so a pipeline rule that disagrees with it would let a pipeline take a name no template
 *    could ever take.
 *
 * The length cap is read from §4.1's prose the same way. `modules/templates`'s own copy is not
 * anchored to the doc today — that is pre-existing and out of this round's fence — but the
 * doc-side assertions here would fail on any edit that moved the rule, which is the coupling
 * that matters.
 */
class PipelineNameGrammarSpecDriftTest {
    private val spec = Fixtures.repoFile(SPEC_PATH).readText()

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
            PIPELINE_PATH.pattern shouldBe "^$segment(/$segment)$repetition$"
        }
    }

    @Test
    fun `the shipped regex is byte-identical to the one §4_6's deploy gate enforces`() {
        // The gate's SQL is a single-quoted Postgres regex on the `name !~ '…'` line.
        val gate =
            requireNotNull(Regex("""name !~ '(\^[^']+\$)'""").find(spec)) {
                "§4.6's `name !~ '…'` legacy-name gate not found in $SPEC_PATH"
            }.groupValues[1]

        PIPELINE_PATH.pattern shouldBe gate
    }

    @Test
    fun `the shipped length cap is the one §4_1 writes down`() {
        val cap =
            requireNotNull(Regex("""Total path length: \*\*≤ (\d+) chars\*\*""").find(spec)) {
                "§4.1's total-length sentence not found in $SPEC_PATH"
            }.groupValues[1].toInt()

        MAX_PIPELINE_PATH_CHARS shouldBe cap
    }

    private companion object {
        const val SPEC_PATH = "docs/template-hierarchy-design.md"
    }
}
