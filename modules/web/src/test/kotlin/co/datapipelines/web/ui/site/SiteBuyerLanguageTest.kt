package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeAtLeast
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * 115 §B — the fold guard. The home page now speaks to the BUYER (owner + an outside review,
 * 2026-09-10): above the fold it sells the outcome — the sentence, the result, the API — and
 * every engineering term moved one hop away to `/how-it-works`. Two rules, both mechanical:
 *
 *  1. **The fold is buyer language.** Everything from `<main` to the END of `section#artifact`
 *     (hero, before/after, artifact) contains no case-insensitive, word-bounded match of the
 *     vocabulary the page is not allowed to lead with: pipeline(s), DAG, federated, Iceberg,
 *     DuckDB, Parquet, MCP, JDBC, staging. HTML comments and tag attributes are stripped
 *     first — a claim comment may cite `docs/rest-api.md`, and a `data-shot` attribute may
 *     name a file, without tripping the guard; the READER's text may not.
 *  2. **The vocabulary moved; it did not vanish.** `/how-it-works` must contain at least six
 *     of those words — the guard would be satisfied by deleting the engineering story, and
 *     that is not what happened (the owner ruled: nothing is hidden, the mechanism moves).
 *
 * The matcher itself is pinned: `datapipelines` must NOT match (the brand is one word, and a
 * regex without the boundaries would ban the site's own name), and a bare banned word DOES
 * match — the positive control that keeps both assertions above from passing vacuously.
 *
 * Falsified at birth (115 §B): with "pipeline" inserted into the hero sub, assertion 1 names
 * the word and the section; reverted by the inverse edit.
 */
class SiteBuyerLanguageTest {
    @Test
    fun `the fold speaks the buyer's language`() {
        val fold = fold(SitePageRenderer.render(SitePages.HOME))
        val offenders =
            sections(fold).flatMap { (id, text) ->
                BANNED.findAll(text).map { word -> "section#$id says \"${word.value}\"" }
            }
        withClue("engineering vocabulary above the fold (hero + before/after + artifact)") {
            offenders shouldBe emptyList()
        }
        // Non-vacuity: the fold actually rendered sections to scan. A changed template shape
        // that emptied `sections` would pass the assertion above by checking nothing.
        sections(fold).size shouldBeAtLeast MIN_FOLD_SECTIONS
    }

    @Test
    fun `the engineering vocabulary moved to how-it-works, it did not vanish`() {
        val html = SitePageRenderer.render(SitePages.HOW_IT_WORKS)
        val found = BANNED.findAll(strip(html)).map { it.value.lowercase() }.toSet()
        withClue("/how-it-works banned-vocabulary hits (want >= $MIN_MOVED_WORDS distinct): $found") {
            found.size shouldBeAtLeast MIN_MOVED_WORDS
        }
    }

    @Test
    fun `the matcher hears the difference between the brand and the banned word`() {
        // Positive control, both directions: the word boundary is what keeps the site's own
        // name legal, and the same matcher must still fire on a bare banned word.
        BANNED.findAll("Customer-facing data at datapipelines.co, no data team required").count() shouldBe 0
        BANNED.find("the pipeline runs where the data lives")?.value shouldBe "pipeline"
    }

    /**
     * 119 §C — the POSITIVE half of the open-source rule. The owner's measurement
     * (`marketing/site-open-source-signals.md`, 2026-09-11): the home page said "open
     * source" four times and "AGPL" twice, but "free" ZERO times, and the first screen did
     * not answer "is this something I buy". So the fold must SAY it: "free" and "open
     * source" above the fold, and "AGPL" somewhere on the page. Falsified at birth: on the
     * page as 115 shipped it, this ran RED — the fold carried neither word.
     */
    @Test
    fun `the fold says free and open source, and the page says AGPL`() {
        val html = SitePageRenderer.render(SitePages.HOME)
        val foldText = strip(fold(html)).lowercase()
        withClue("the fold must contain the word \"free\" — a visitor decides \"is this something I buy\" on the first screen") {
            ("free" in foldText.split(WORD_BOUNDARY)) shouldBe true
        }
        withClue("the fold must contain the phrase \"open source\"") {
            foldText.contains("open source") shouldBe true
        }
        withClue("the page must name the licence: \"AGPL\" at least once") {
            strip(html).contains("AGPL") shouldBe true
        }
    }

    /** From `<main` to the closing tag of `section#artifact` — the fold, as the buyer reads it. */
    private fun fold(html: String): String {
        val from = html.indexOf("<main")
        val artifact = html.indexOf("id=\"artifact\"")
        check(from >= 0 && artifact > from) { "the home template lost its main/artifact markers — fix this guard, do not delete it" }
        val to = html.indexOf("</section>", artifact) + "</section>".length
        return html.substring(from, to)
    }

    /** The fold's `<section>`s as (id, reader-visible text): comments and tags stripped. */
    private fun sections(fold: String): List<Pair<String, String>> =
        fold
            .split("<section")
            .drop(1)
            .map { chunk ->
                val id = ID.find(chunk)?.groupValues?.get(1) ?: "(no id)"
                id to strip(chunk)
            }

    private fun strip(html: String): String = TAG.replace(COMMENT.replace(html, " "), " ")

    private companion object {
        /** Word-boundary split for the "free" check — "freedom" must not count. */
        val WORD_BOUNDARY = Regex("""\b""")
        /** Hero, before/after, artifact — the fold must always render at least these three. */
        const val MIN_FOLD_SECTIONS = 3

        /** §B: at least six of the moved words must survive on the engineering page. */
        const val MIN_MOVED_WORDS = 6

        val BANNED = Regex("""\b(pipeline|pipelines|DAG|federated|Iceberg|DuckDB|Parquet|MCP|JDBC|staging)\b""", RegexOption.IGNORE_CASE)
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]*>")
        val ID = Regex("""id="([^"]+)"""")
    }
}
