package co.datapipelines.mcp

import co.datapipelines.mcp.docs.Doc
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * #116 — the two analytical rules the #111 acceptance runs broke are PRESENT in what the server
 * serves, and the worked case that teaches the first one still teaches it.
 *
 * The runs executed cleanly and reconciled their own arithmetic while answering the wrong
 * question: an unweighted sampled count was added to a census count to rank, with the claim
 * that scaling one component cannot change a ranking, and the "independent" verification
 * re-ran the same formula, so it agreed. The rules exist in prose (lane 147, moved by 242b); a
 * later edit that trims them would pass every other guard — the goldens regenerate, the budget
 * shrinks — so their presence is pinned here: the weighting rule and the derived-verification
 * rule, in the guide every author opens AND in the reference that carries each rule in full.
 *
 * The clauses are matched over the RENDERED document with its lines trimmed and joined by one
 * space, so re-wrapping the prose is free and removing the sentence is not. The worked case is
 * held to its PROPERTY rather than its numbers: every row's sums are what its columns say, and
 * the unweighted and the weighted totals crown different sites — an edit that keeps the table
 * but loses the lesson goes red. Falsified at birth: each clause removed by a reversible edit
 * turned its test red, and the table edited so the winners agree turned the last one red.
 */
class AnalyticalGuidanceRulesTest {
    private val docSet = DocSetTestSupport.renderedDocSet()

    @Test
    fun `the pipelines guide names the weighting rule and the derived-verification rule`() {
        val guide = joined(docSet.get("pipelines"))
        assertNames("pipelines", guide, GUIDE_WEIGHTING)
        assertNames("pipelines", guide, GUIDE_DERIVED)
    }

    @Test
    fun `pipelines-numbers weights every sampled count before combining, and says why one summand reorders`() {
        val numbers = joined(docSet.get("pipelines-numbers"))
        assertNames("pipelines-numbers", numbers, NUMBERS_WEIGHTING)
        assertNames("pipelines-numbers", numbers, NUMBERS_WHOLE_SCORE)
    }

    @Test
    fun `pipelines-verification derives the check from the question, never from the authored formula`() {
        val verification = joined(docSet.get("pipelines-verification"))
        assertNames("pipelines-verification", verification, VERIFICATION_DERIVED)
        assertNames("pipelines-verification", verification, VERIFICATION_NOT_RECOMPUTED)
    }

    @Test
    fun `the worked case adds up, and weighting changes its winner`() {
        val section =
            docSet.get("pipelines-numbers").sections.firstOrNull { it.id.startsWith(WORKED_CASE_ID) }
        withClue("pipelines-numbers lost its worked case (a section whose id starts '$WORKED_CASE_ID')") {
            (section != null) shouldBe true
        }
        val text = section!!.markdown
        val weight =
            WEIGHT_PHRASE.find(text)?.groupValues?.get(1)?.toLong()
                ?: error("the worked case no longer states its sampling weight as 'a stated 1 in N'")
        val rows = tableRows(text)
        withClue("the worked case's table has fewer than three sites — a ranking needs a field") {
            rows shouldHaveAtLeastSize 3
        }
        for (row in rows) {
            withClue("${row.site}: 'Counter + rows' is not counter + observed rows") {
                row.unweighted shouldBe row.census + row.sampled
            }
            withClue("${row.site}: the weighted total is not counter + $weight × observed rows") {
                row.weighted shouldBe row.census + weight * row.sampled
            }
        }
        val unweightedWinner = rows.maxBy { it.unweighted }.site
        val weightedWinner = rows.maxBy { it.weighted }.site
        withClue("the worked case no longer changes its winner under weighting — it teaches nothing") {
            weightedWinner shouldNotBe unweightedWinner
        }
    }

    private data class CaseRow(
        val site: String,
        val census: Long,
        val sampled: Long,
        val unweighted: Long,
        val weighted: Long,
    )

    /** The body rows of the section's table: a name, then four whole numbers (thousands commas allowed). */
    private fun tableRows(markdown: String): List<CaseRow> =
        markdown
            .lines()
            .filter { it.startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map { it.trim() } }
            .filter { cells -> cells.size == 5 && cells.drop(1).all { NUMBER.matches(it) } }
            .map { cells ->
                val n = cells.drop(1).map { it.replace(",", "").toLong() }
                CaseRow(cells[0], n[0], n[1], n[2], n[3])
            }

    /** The document's text with every line trimmed and joined by one space — wrapping is free. */
    private fun joined(doc: Doc): String = doc.markdown.lines().joinToString(" ") { it.trim() }

    private fun assertNames(
        document: String,
        text: String,
        clause: String,
    ) {
        withClue("the rendered $document lost the clause \"$clause\" — #116's rule, which the #111 runs broke") {
            text.contains(clause) shouldBe true
        }
    }

    private companion object {
        /** Step 1½: the weighting rule, stated where the calculation is written down. */
        const val GUIDE_WEIGHTING = "a sampled count is weighted to its population BEFORE it enters a total, a share or a ranking"

        /** Step 5: the check is derived again, not recomputed. */
        const val GUIDE_DERIVED =
            "derived again from the question and the source facts, weights included — never by re-running your own formula"

        const val NUMBERS_WEIGHTING = "apply the stated sampling weight to every sampled count BEFORE the sum, the share and the rank"
        const val NUMBERS_WHOLE_SCORE = "A constant multiplier preserves order only when it multiplies the whole score"

        const val VERIFICATION_DERIVED = "Independent means derived again from the question and the source facts"
        const val VERIFICATION_NOT_RECOMPUTED = "never by re-running the pipeline's own formula"

        /** The section id prefix — the heading's slug, whatever follows the dash. */
        const val WORKED_CASE_ID = "a-worked-case"

        val WEIGHT_PHRASE = Regex("a stated 1 in (\\d+)")
        val NUMBER = Regex("[0-9][0-9,]*")
    }
}
