package co.datapipelines.web.ui.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * One FAQ entry, rendered TWICE from ONE value: as a visible `<details>` in the page and as a
 * `Question` inside the page's `FAQPage` JSON-LD. Site v2's rule (mock 2026-09-09): the
 * structured data can never say more, less, or other than what the reader sees — a snippet
 * Google shows must be a sentence on the page. `SiteFaqParityTest` reads both back and
 * compares them.
 *
 * @property question ends with `?` — SiteJsonLdTest's shape rule for a `Question.name`.
 * @property answer plain prose, at least 120 characters, and it NAMES the doc it rests on
 *   (`docs/<file>.md §n`), which is both the claim's citation (SiteClaimCitationTest reads the
 *   `<!-- claim: … -->` the fragment emits from [cites]) and the "contains `docs/`" rule the
 *   JSON-LD test applies to every answer.
 * @property cites the `docs/<file>.md §section` the answer rests on — the claim comment's text.
 */
data class FaqEntry(
    val question: String,
    val answer: String,
    val cites: String,
) {
    init {
        require(question.endsWith("?")) { "A FAQ question ends with '?': $question" }
        require(answer.length >= MIN_ANSWER_CHARS) { "A FAQ answer is a paragraph, not a line (${answer.length} chars): $question" }
        require(DOC_REF.containsMatchIn(answer)) { "A FAQ answer names the doc it rests on (docs/<file>.md): $question" }
        require(DOC_REF.containsMatchIn(cites)) { "A FAQ citation names a docs/*.md: $cites" }
    }

    companion object {
        const val MIN_ANSWER_CHARS = 120
        val DOC_REF = Regex("""docs/[A-Za-z0-9._/-]+\.md""")
    }
}

/** The `FAQPage` JSON-LD for a list of entries — the only writer of that block on the site. */
object FaqJsonLd {
    private val mapper: ObjectMapper = JsonMapper.builder().build()

    fun render(entries: List<FaqEntry>): String {
        require(entries.isNotEmpty()) { "A FAQ block needs at least one entry" }
        val payload =
            linkedMapOf(
                "@context" to "https://schema.org",
                "@type" to "FAQPage",
                "mainEntity" to
                    entries.map { e ->
                        linkedMapOf(
                            "@type" to "Question",
                            "name" to e.question,
                            "acceptedAnswer" to linkedMapOf("@type" to "Answer", "text" to e.answer),
                        )
                    },
            )
        // A closing-tag sequence (less-than, slash) cannot appear inside a script body; Jackson
        // does not escape it, so it is done here — the one place the block is written.
        return mapper.writeValueAsString(payload).replace("</", "<\\/")
    }
}
