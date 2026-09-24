package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils

/**
 * #209 (2026-09-24) — the register guard. The owner's ruling: the site's language should
 * read like a person wrote it, not like model output. One idea per sentence, about twenty
 * words, plain verbs, no em-dashes, no parentheses in body copy, product terms used
 * consistently, and the stock model constructions ("X — that is the whole Y", "one X, every
 * Y", "not X but Y") gone. The rewrite landed in the #209 commits; this test keeps it there.
 *
 * What is scanned: every page in [SitePages.ALL] rendered through [SitePageRenderer] (the
 * real handlers), the reader-visible text of `<p>`, `<li>`, `<h1>`–`<h3>` and `<summary>`
 * (the FAQ questions). HTML comments, `<code>` (transcripts, code literals, wire captures)
 * and tag attributes are stripped first — a claim comment may say anything, and a curl
 * transcript is a fact, not prose.
 *
 * The rules, each falsified at birth (the red runs are in the lane's evidence):
 *  1. **No em-dash in body copy** — a full stop or a comma instead. Reintroducing one into
 *     any scanned element names the page and the sentence.
 *  2. **No tells** — the regexes in [TELLS], each with its reason. They are the model-prose
 *     constructions the rewrite removed; a new one fails naming the match.
 *  3. **No parentheses in `<p>` text**, except the allowlist below. An allowlist entry
 *     carries a reason; the count per page is capped so the allowance cannot quietly grow.
 *  4. **Non-vacuity** — the sweep asserts it scanned at least [MIN_PAGES] pages and at
 *     least [MIN_ELEMENTS] scanned elements, so a changed extraction shape that scanned
 *     nothing would fail loudly instead of passing by checking air.
 */
class SiteProseHygieneTest {

    private data class Scanned(val page: String, val tag: String, val text: String)

    private val scanned: List<Scanned> by lazy {
        SitePages.ALL.flatMap { page ->
            val html = SitePageRenderer.render(page)
            val withoutComments = COMMENT.replace(html, " ")
            val withoutCode = CODE.replace(withoutComments, " ")
            SCANNED_TAGS.flatMap { tag ->
                Regex("<$tag\\b[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(withoutCode)
                    .map { m ->
                        val text = HtmlUtils
                            .htmlUnescape(TAG.replace(m.groupValues[1], " "))
                            .replace(WHITESPACE, " ")
                            .trim()
                        when {
                            text.isEmpty() -> null
                            // A directory row (one arrow link, no sentence): /explore renders
                            // the DOCS' OWN titles there, and the docs are outside this
                            // lane's fence. Their wording is the docs' to fix.
                            tag == "li" && text.endsWith(ARROW) && ARROW_LINK.containsMatchIn(m.groupValues[1]) -> null
                            else -> Scanned(page.path, tag, text)
                        }
                    }
            }.filterNotNull()
        }
    }

    @Test
    fun `every scanned element counts toward the non-vacuity floor`() {
        withClue("pages rendered and scanned") { SitePages.ALL.size shouldBeGreaterThanOrEqual MIN_PAGES }
        withClue("scanned <p>/<li>/<h1>-<h3>/<summary> elements across the site") {
            scanned.size shouldBeGreaterThanOrEqual MIN_ELEMENTS
        }
    }

    @Test
    fun `no em-dash in body copy`() {
        val offenders =
            scanned.mapNotNull { s ->
                val idx = s.text.indexOf(EM_DASH)
                if (idx < 0) {
                    null
                } else {
                    "…${s.text.coerceSentence(idx)}… [${s.tag} on ${s.page}]"
                }
            }
        withClue("em-dashes in body copy (use a full stop or a comma):\n${offenders.joinToString("\n")}") {
            offenders shouldBe emptyList()
        }
    }

    @Test
    fun `no model-prose tells`() {
        val offenders =
            scanned.flatMap { s ->
                TELLS.flatMap { (regex, reason) ->
                    regex.findAll(s.text).map { "\"${it.value}\" on ${s.page} ($reason)" }
                }
            }
        withClue("model-prose tells (each regex in TELLS carries its reason):\n${offenders.joinToString("\n")}") {
            offenders shouldBe emptyList()
        }
    }

    @Test
    fun `no parentheses in paragraphs outside the allowlist`() {
        val offenders = mutableListOf<String>()
        var allowlisted = 0
        scanned
            .filter { it.tag == "p" }
            .forEach { s ->
                PAREN.findAll(s.text).forEach { m ->
                    val inner = m.groupValues[1]
                    val allowed = ALLOWED_PARENS.firstOrNull { it.first.matches(inner) }
                    if (allowed == null) {
                        offenders += "\"($inner)\" on ${s.page}"
                    } else {
                        allowlisted++
                    }
                }
            }
        withClue("parentheses in <p> text outside the allowlist:\n${offenders.joinToString("\n")}") {
            offenders shouldBe emptyList()
        }
        // The allowlist is a lease, not a licence: cap the total so it cannot quietly grow.
        withClue("allowlisted parentheses across the site (ceiling $ALLOWED_PARENS_CEILING)") {
            allowlisted shouldBeGreaterThanOrEqual 1
            allowlisted shouldBeLessThanOrEqual ALLOWED_PARENS_CEILING
        }
    }

    /** The sentence an offender sits in, for the failure message. */
    private fun String.coerceSentence(idx: Int): String {
        val start = maxOf(0, lastIndexOf('.', idx).let { if (it < 0) 0 else it + 1 })
        val end = minOf(length, indexOf('.', idx).let { if (it < 0) length else it + 1 })
        return substring(start, end).trim()
    }

    private companion object {
        const val EM_DASH = '\u2014'

        /** 37 registry pages (the owner's floor: 35 marketing pages plus engines and hubs). */
        const val MIN_PAGES = 35

        /**
         * Measured on the post-rewrite tree (2026-09-24): the sweep scans 2,614 non-empty
         * elements. The floor sits well under that so a template edit cannot trip it, but
         * high enough that a broken extraction (zero elements) fails loudly.
         */
        const val MIN_ELEMENTS = 2_400

        /**
         * The tells, each with the reason it is banned. Entries are added when the
         * construction is met in the wild, never speculatively.
         */
        val TELLS: List<Pair<Regex, String>> =
            listOf(
                // The model flourish that turns a claim into a pronouncement.
                Regex("""[—,] that is the whole\b""", RegexOption.IGNORE_CASE) to "the 'X, that is the whole Y' pronouncement",
                // The parallel-promise couplet ("one key, every workspace").
                Regex("""\bone \w+, every \w+\b""", RegexOption.IGNORE_CASE) to "the 'one X, every Y' couplet",
                // The false-balance contrast that no person says out loud.
                Regex("""\bnot \w+ but \w+\b""", RegexOption.IGNORE_CASE) to "the 'not X but Y' false balance",
                // The deprecation-flavoured framing the rewrite removed.
                Regex("""\b(?:is not|isn't) just\b""", RegexOption.IGNORE_CASE) to "the 'is not just X' minimiser",
                // The eager adverb no engineer uses in a spec review.
                Regex("""\bseamlessly\b""", RegexOption.IGNORE_CASE) to "the 'seamlessly' adverb",
            )

        /** A parenthesised group inside a `<p>`. */
        val PAREN = Regex("""\(([^()]*)\)""")

        /**
         * The allowlist. One entry today: a visible doc citation. The FAQ answers cite the
         * spec they rest on in parentheses (`SiteJsonLdTest` requires the citation to be in
         * the answer text at all) — the citation is audit trail, not aside. Matched on the
         * inner text; anything else in parentheses is a rewrite.
         */
        val ALLOWED_PARENS: List<Pair<Regex, String>> =
            listOf(
                Regex("""[^()]*docs/[A-Za-z0-9 ._/,§-]+""") to
                    "a visible spec citation, required in FAQ answers by SiteJsonLdTest",
            )

        /** The lease ceiling for allowlisted parentheses: 62 measured on the rewritten tree, capped with headroom. */
        const val ALLOWED_PARENS_CEILING = 80

        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val CODE = Regex("""<code\b.*?</code>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")
        val WHITESPACE = Regex("""\s+""")
        val SCANNED_TAGS = listOf("p", "li", "h1", "h2", "h3", "summary")
        const val ARROW = "\u2192"
        val ARROW_LINK = Regex("""<a\b""")
    }
}
