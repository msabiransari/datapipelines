package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File

/**
 * The 024b rule as a GUARD, extended from the homepage to every page the site serves (073 §B).
 *
 * Two obligations, both mechanical:
 *  1. every feature card on every site template carries a `<!-- claim: docs/… -->` comment
 *     BEFORE its heading — a card asserts something, so it names where that is written down;
 *  2. every `docs/<file>.md` any claim on any site template cites EXISTS in the tree — a
 *     citation pointing at a deleted spec is worse than no citation, because it reads as
 *     verified.
 *
 * The round that introduced the rule was held because the claim table was missing; a reminder
 * is not a mechanism. This reads TEMPLATE SOURCE, not the render, because the comments are the
 * audit trail for a human reading view-source and the source is where a missing one is a defect.
 *
 * Scoped to cards rather than to every heading, deliberately: section headings and group labels
 * are headings too and cite nothing, because they assert nothing.
 */
class SiteClaimCitationTest {
    private val templates: Map<String, String> =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResources("classpath*:templates/site/*.html")
            .filter { it.filename != null && it.filename != "_layout.html" }
            .associate { it.filename!! to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `the sweep sees every site template`() {
        // Non-vacuity: the homepage plus eight cluster templates. A resolver that stopped
        // matching would pass every check below by having nothing to check.
        templates.keys.sorted() shouldBe
            listOf(
                "add-mcp-server.html",
                "ai-data-pipeline.html",
                "compare-airflow.html",
                "compare-dbt.html",
                "engine.html",
                "federated-query.html",
                "index.html",
                "pillar.html",
                "text-to-sql-agent.html",
            )
    }

    @Test
    fun `every feature card cites a claim`() {
        var cards = 0
        val uncited =
            templates.flatMap { (name, source) ->
                CARD.findAll(source).mapNotNull { match ->
                    cards++
                    val card = match.value
                    val cited =
                        CLAIM
                            .find(card)
                            ?.range
                            ?.first
                            ?.let { it < card.indexOf("<h3") } == true
                    if (cited) null else "$name: ${HEADING.find(card)?.groupValues?.get(1) ?: card.take(120)}"
                }
            }
        uncited shouldBe emptyList()
        check(cards >= MIN_CARDS) { "the card sweep found only $cards cards — has the card markup changed?" }
    }

    @Test
    fun `every cited doc exists`() {
        val comments = templates.values.sumOf { CLAIM_COMMENT.findAll(it).count() }
        val cited =
            templates.values
                .flatMap { source ->
                    CLAIM_COMMENT.findAll(source).flatMap { comment -> DOC_PATH.findAll(comment.value).map { it.value } }
                }.distinct()
                .sorted()

        // Non-vacuity on both axes: the site carries dozens of claim comments naming a
        // dozen-plus distinct specs. A changed comment shape empties one or both, and the
        // "missing" check below would then pass by looking at nothing.
        check(comments >= MIN_COMMENTS) { "the claim sweep found only $comments claim comments" }
        check(cited.size >= MIN_DISTINCT_DOCS) { "the claim sweep found only ${cited.size} distinct cited docs" }

        val root = repoRoot()
        cited.filterNot { File(root, it).isFile } shouldBe emptyList()
    }

    @Test
    fun `every cluster page cites at least one spec`() {
        val silent =
            templates
                .filterKeys { it != "index.html" }
                .filterValues { CLAIM_COMMENT.find(it) == null }
                .keys
                .sorted()
        silent shouldBe emptyList()
    }

    /**
     * The repo root: walk up from the test JVM's working directory until the `docs` tree the
     * claims cite is there. Derived, not assumed — a module move must not silently make this
     * guard check nothing (it would then find every doc "missing", which is the loud direction).
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds a docs/ directory" }
    }

    private companion object {
        const val MIN_CARDS = 30
        const val MIN_COMMENTS = 70
        const val MIN_DISTINCT_DOCS = 12

        val CARD = Regex("""<article class="card[^"]*">.*?</article>""", RegexOption.DOT_MATCHES_ALL)
        val CLAIM = Regex("""<!--\s*claim:""")
        val CLAIM_COMMENT = Regex("""<!--\s*claim:.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val DOC_PATH = Regex("""docs/[A-Za-z0-9._/-]+\.md""")
        val HEADING = Regex("""<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
    }
}
