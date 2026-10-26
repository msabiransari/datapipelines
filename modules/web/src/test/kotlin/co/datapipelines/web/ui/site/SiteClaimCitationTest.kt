package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File

/**
 * The 024b rule as a GUARD, extended from the homepage to every page the site serves (073 §B),
 * and from file existence to section existence (674).
 *
 * Five obligations, all mechanical:
 *  1. every feature card on every site template carries a `<!-- claim: docs/… -->` comment
 *     BEFORE its heading — a card asserts something, so it names where that is written down;
 *  2. every `docs/<file>.md` any claim on any site template cites EXISTS in the tree — a
 *     citation pointing at a deleted spec is worse than no citation, because it reads as
 *     verified;
 *  3. every cited SECTION exists — `§13.7` against a doc whose numbering stops at §12 is the
 *     file-level check passing while the citation lies;
 *  4. where a citation carries prose (the parenthetical or em-dash tail naming what the
 *     section is supposed to say), at least one content word of that prose occurs in the
 *     cited section's text — the cheap probe for "the section still says it"; the semantic
 *     question stays editorial;
 *  5. exemptions name existing citations that still miss the probe; repaired or deleted
 *     citations must leave the list, whose ceiling only goes down (722).
 *
 * Reads TEMPLATE SOURCE: the comments are the audit trail for a human reading view-source.
 * Card headings assert claims; section headings and group labels do not need citations.
 */
class SiteClaimCitationTest {
    private val templates: Map<String, String> =
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResources("classpath*:templates/site/*.html")
            .filter { it.filename != null && it.filename != "_layout.html" }
            .associate { it.filename!! to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `the sweep sees every site template`() {
        // Non-vacuity: a resolver that stopped matching must not pass by checking nothing.
        templates.keys.sorted() shouldBe
            listOf(
                "_demo-api.html",
                "_demo-console.html",
                "_demo-dag.html",
                "_demo-datasources.html",
                "add-mcp-server.html",
                "ai-data-pipeline.html",
                "compare-airflow.html",
                "compare-dagster-vs-airflow.html",
                "compare-dbt.html",
                "compare-fivetran-airbyte.html",
                "compare-postgres-only.html",
                "demo-data.html",
                "dp-lake.html",
                "engine.html",
                "explore.html",
                "faq.html",
                "federated-query.html",
                "for-agencies.html",
                "for-analysts.html",
                "for-saas-teams.html",
                "how-it-works.html",
                "index.html",
                "mcp-tools.html",
                "pillar.html",
                "pipeline-scheduling.html",
                "pricing.html",
                "published-api.html",
                "reusable-parameter-sets.html",
                "roadmap.html",
                "security.html",
                "semantic-layer.html",
                "sql-dashboards.html",
                "tableau-extracts-alerts-dashboards.html",
                "tableau-governed-dataset.html",
                "tableau-prep-vs-pipelines.html",
                "tableau.html",
                "text-to-sql-agent.html",
                "use-cases.html",
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

        // A changed comment shape must not empty the sweep and silently pass.
        check(comments >= MIN_COMMENTS) { "the claim sweep found only $comments claim comments" }
        check(cited.size >= MIN_DISTINCT_DOCS) { "the claim sweep found only ${cited.size} distinct cited docs" }

        val root = repoRoot()
        cited.filterNot { File(root, it).isFile } shouldBe emptyList()
    }

    @Test
    fun `the parser reads every citation grammar shape`() {
        // One literal covers numeric/Appendix/Part anchors, ranges, chains, prose boundaries,
        // a file-only citation and different documents; glyphs are optional throughout.
        val comment =
            "§99 is prose before a path; docs/auth.md §3, docs/rest-api.md §6.2.14 (the row), docs/auth.md §5A.2 and §11A, " +
                "docs/datasources.md §8C.1, docs/rest-api.md §9.7A, docs/deployment.md §Appendix B, " +
                "docs/deployment.md Appendix B (no glyph), docs/semantic-layer-research.md Part five, " +
                "docs/auth.md §7.1–7.6 (the walk), docs/scheduler.md §4.2–§6, docs/enums.md §4-§5, " +
                "docs/mcp-server.md (no anchor at all), docs/auth.md §10.1, " +
                "docs/scheduler.md §1, §4 and §9 (schedule runs), " +
                "docs/auth.md §3; 4 or §5 plus 7.1–§7.6 — prose with §99, " +
                "docs/auth.md §3, 4, and 5, docs/rest-api.md §6 (stream), " +
                "docs/auth.md §3 (prose), §99 and 100, docs/auth.md §4 — prose, §99"
        val expected =
            listOf(
                ClaimCitations.Citation("docs/auth.md", listOf("3"), ""),
                ClaimCitations.Citation("docs/rest-api.md", listOf("6.2.14"), "(the row)"),
                ClaimCitations.Citation("docs/auth.md", listOf("5A.2", "11A"), ""),
                ClaimCitations.Citation("docs/datasources.md", listOf("8C.1"), ""),
                ClaimCitations.Citation("docs/rest-api.md", listOf("9.7A"), ""),
                ClaimCitations.Citation("docs/deployment.md", listOf("Appendix B"), ""),
                ClaimCitations.Citation("docs/deployment.md", listOf("Appendix B"), "(no glyph)"),
                ClaimCitations.Citation("docs/semantic-layer-research.md", listOf("Part five"), ""),
                ClaimCitations.Citation("docs/auth.md", listOf("7.1", "7.6"), "(the walk)"),
                ClaimCitations.Citation("docs/scheduler.md", listOf("4.2", "6"), ""),
                ClaimCitations.Citation("docs/enums.md", listOf("4", "5"), ""),
                ClaimCitations.Citation("docs/mcp-server.md", emptyList(), "(no anchor at all)"),
                ClaimCitations.Citation("docs/auth.md", listOf("10.1"), ""),
                ClaimCitations.Citation("docs/scheduler.md", listOf("1", "4", "9"), "(schedule runs)"),
                ClaimCitations.Citation("docs/auth.md", listOf("3", "4", "5", "7.1", "7.6"), "prose with §99"),
                ClaimCitations.Citation("docs/auth.md", listOf("3", "4", "5"), ""),
                ClaimCitations.Citation("docs/rest-api.md", listOf("6"), "(stream)"),
                ClaimCitations.Citation("docs/auth.md", listOf("3"), "(prose), §99 and 100"),
                ClaimCitations.Citation("docs/auth.md", listOf("4"), "prose, §99"),
            )
        ClaimCitations.parse(comment) shouldBe expected
    }

    @Test
    fun `every cited section exists`() {
        val refs = citedSectionRefs()
        // Non-vacuity at 9d63e2d88: 843 anchored citations, 847 range ends; chains expose 904 ends.
        // After 722's repairs: 861 anchored citations, 922 ends. A parser that stops seeing anchors
        // (an ANCHOR regex regression, a comment-shape change) must fail loudly here, as
        // MIN_COMMENTS does above — not pass by looking at nothing.
        check(refs.size >= MIN_ANCHORED) { "the citation sweep found only ${refs.size} anchored citations" }

        val docLines = mutableMapOf<String, List<String>?>()
        val root = repoRoot()
        val missing =
            refs.mapNotNull { (template, doc, anchor) ->
                if (!docLines.containsKey(doc)) {
                    val file = File(root, doc)
                    docLines[doc] = if (file.isFile) file.readLines() else null
                }
                val carries = docLines[doc]?.let { ClaimCitations.sectionText(it, anchor) } != null
                if (carries) null else "$template: $doc §$anchor"
            }
        check(missing.isEmpty()) {
            "cited sections that no heading carries:\n${missing.joinToString("\n")}"
        }
    }

    @Test
    fun `every citation's prose names its section`() {
        // The 674 A.3 probe: where a citation carries prose naming what the section says, at
        // least one content word of the prose must occur in the cited section's text (the
        // section = its heading line through the line before the next same-or-higher heading;
        // for a range or chain, the last anchor — the end nearest the prose). The editorial
        // inventory and falsifications are in 722's evidence; no exemptions remain.
        val docSections = mutableMapOf<Pair<String, String>, String?>()
        val root = repoRoot()
        val misses =
            templates.flatMap { (template, source) ->
                CLAIM_COMMENT.findAll(source).map { it.value }.flatMap { comment ->
                    ClaimCitations.parse(comment).mapNotNull { citation ->
                        if (citationKey(template, citation) in KNOWN_UNPROBED) null else probeMiss(template, citation, root, docSections)
                    }
                }
            }
        check(misses.isEmpty()) {
            "citations whose prose shares no word with their section:\n${misses.joinToString("\n")}"
        }
    }

    @Test
    fun `every probe exemption names a current citation that still misses`() {
        check(KNOWN_UNPROBED.size <= MAX_UNPROBED) { "exemption ceiling exceeded: ${KNOWN_UNPROBED.size} > $MAX_UNPROBED" }
        val root = repoRoot()
        val docSections = mutableMapOf<Pair<String, String>, String?>()
        val citations =
            templates
                .flatMap { (template, source) ->
                    CLAIM_COMMENT.findAll(source).flatMap { comment ->
                        ClaimCitations.parse(comment.value).map { citation ->
                            citationKey(template, citation) to probeMiss(template, citation, root, docSections)
                        }
                    }
                }.groupBy({ it.first }, { it.second })
        val absent = KNOWN_UNPROBED.keys.filter { it !in citations }
        val passing = KNOWN_UNPROBED.keys.filter { key -> citations[key]?.any { it == null } == true }
        check(absent.isEmpty() && passing.isEmpty()) {
            "stale exemptions naming no citation:\n${absent.joinToString("\n")}\n" +
                "stale exemptions naming passing citations:\n${passing.joinToString("\n")}"
        }
    }

    private fun citationKey(
        template: String,
        citation: ClaimCitations.Citation,
    ): String = "$template|${citation.doc}|${citation.anchors.joinToString("–")}"

    /** The raw probe's miss line, without consulting exemptions, or null when it passes. */
    private fun probeMiss(
        template: String,
        citation: ClaimCitations.Citation,
        root: File,
        docSections: MutableMap<Pair<String, String>, String?>,
    ): String? {
        if (!citation.anchored) return null
        val tokens = ClaimCitations.probeTokens(citation.prose)
        if (tokens.isEmpty()) return null
        val anchor = citation.anchors.last()
        val sectionKey = citation.doc to anchor
        if (!docSections.containsKey(sectionKey)) {
            val lines = File(root, citation.doc).takeIf { it.isFile }?.readLines()
            docSections[sectionKey] = lines?.let { ClaimCitations.sectionText(it, anchor) }
        }
        val section = docSections[sectionKey]
        val hit = section != null && tokens.any { ClaimCitations.sectionMentions(section.lowercase(), it) }
        return if (hit) null else "${citationKey(template, citation)} — tokens $tokens in ${citation.prose.take(80)}"
    }

    /** Every anchored citation as `(template, doc, anchor)`, chains and range endpoints flattened. */
    private fun citedSectionRefs(): List<Triple<String, String, String>> =
        templates.flatMap { (name, source) ->
            CLAIM_COMMENT.findAll(source).flatMap { comment ->
                ClaimCitations.parse(comment.value).flatMap { citation ->
                    citation.anchors.map { anchor -> Triple(name, citation.doc, anchor) }
                }
            }
        }

    /**
     * Find the ancestor holding the cited docs; a moved module must fail loudly if absent.
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

        /**
         * Anchored citations at the 722 base (9d63e2d88): 843; 922 ends after repairs. The floor is 600 —
         * deliberately coarse: the grammar fixture catches subtler parser regressions.
         */
        const val MIN_ANCHORED = 600

        val CARD = Regex("""<article class="card[^"]*">.*?</article>""", RegexOption.DOT_MATCHES_ALL)
        val CLAIM = Regex("""<!--\s*claim:""")
        val CLAIM_COMMENT = Regex("""<!--\s*claim:.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val DOC_PATH = Regex("""docs/[A-Za-z0-9._/-]+\.md""")
        val HEADING = Regex("""<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Page rewrites still needed after 722's editorial review. Empty: all 43 former keys
         * were repaired. New exemptions require a page-rewrite reason and a tracked defect.
         * Key: `template|doc|anchor(s)`. The ceiling is a ratchet: it only goes down.
         */
        const val MAX_UNPROBED = 0
        val KNOWN_UNPROBED: Map<String, String> = emptyMap()
    }
}
