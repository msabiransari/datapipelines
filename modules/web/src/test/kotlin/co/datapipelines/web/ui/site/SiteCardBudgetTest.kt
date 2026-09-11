package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 119 §A.3 — a card is as tall as its text, and its text has a budget: **a card body is
 * ≤ 90 words**. Measured on the live page (2026-09-11, 1440×1000), the tallest card in a
 * "What's in the box" row stretched to 611px while holding 262px of content — the empty
 * space is the surplus of the wordiest cards, and `align-items: start` (the CSS half of
 * the rule) only removes the stretch, not the surplus.
 *
 * The budget is on the card BODY: every `<p>` plus any trailing spec link inside the
 * "What's in the box" cards, with the claim comments and the heading excluded — the claims
 * are audit trail, not reading text. A card that wants to say more links the cited docs
 * section ("read the spec →"), which is what the card already promises by citing it.
 *
 * Falsified at birth: on the page as it shipped, this ran RED naming eleven cards (the
 * wordiest, "Promotion between environments", carried 164 body words); the trims in the
 * same commit brought every card under the ceiling. The near-ceiling non-vacuity floor
 * keeps the sweep honest: a guard over cards that are all ten words long checks nothing.
 */
class SiteCardBudgetTest {
    @Test
    fun `every card body on the engineering page fits the word budget`() {
        val cards = CARD.findAll(featuresSection()).map { it.value }.toList()
        // Non-vacuity: the grid must still be the twenty-seven-card section this guard
        // was written for — a changed markup shape would otherwise pass by scanning air.
        withClue("cards found in the features section") { cards.size shouldBeGreaterThanOrEqual MIN_CARDS }

        val over =
            cards
                .map { card -> card to bodyWords(card) }
                .filter { (_, words) -> words > MAX_BODY_WORDS }
                .map { (card, words) -> "${heading(card)}: $words body words (budget $MAX_BODY_WORDS)" }
        withClue("cards over the body-word budget") { over shouldBe emptyList() }

        // Non-vacuity on the ceiling itself: at least one card lives NEAR it, so the
        // assertion above would notice the budget moving.
        val nearCeiling = cards.count { bodyWords(it) in NEAR_CEILING }
        withClue("cards in the $NEAR_CEILING word band (want the budget to be reachable)") {
            nearCeiling shouldBeGreaterThanOrEqual 1
        }
    }

    /** The "What's in the box" section body — the same slice the claim guard reads. */
    private fun featuresSection(): String {
        val source = howItWorksSource()
        val from = source.indexOf("id=\"cap-title\"")
        val to = source.indexOf("<!-- ============================ SECURITY", from)
        check(from > 0 && to > from) { "the features section markers moved — fix this guard, do not delete it" }
        return source.substring(from, to)
    }

    /** The card's reading text: everything except its heading, its icon and its claims. */
    private fun bodyWords(card: String): Int =
        card
            .replace(COMMENT, " ")
            .replace(H3, " ")
            .replace(TAG, " ")
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .size

    private fun heading(card: String): String = H3.find(card)?.groupValues?.get(1)?.trim() ?: card.take(60)

    private fun howItWorksSource(): String =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResource("classpath:templates/site/how-it-works.html")
            .inputStream
            .readBytes()
            .decodeToString()

    private companion object {
        const val MIN_CARDS = 20
        const val MAX_BODY_WORDS = 90
        val NEAR_CEILING = 50..MAX_BODY_WORDS

        val CARD = Regex("""<article class="card[^"]*">.*?</article>""", RegexOption.DOT_MATCHES_ALL)
        val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val H3 = Regex("""<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")
        val WHITESPACE = Regex("""\s+""")
    }
}
