package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File

/**
 * The 024b rule as a GUARD, extended from the homepage to every page the site serves (073 §B),
 * and from file existence to section existence (674).
 *
 * Four obligations, all mechanical:
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
 *     question stays out of scope (674's brief).
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
        // Non-vacuity: the homepage plus the cluster templates and the two 133 demo
        // partials. A resolver that stopped matching would pass every check below by
        // having nothing to check.
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

        // Non-vacuity on both axes: the site carries dozens of claim comments naming a
        // dozen-plus distinct specs. A changed comment shape empties one or both, and the
        // "missing" check below would then pass by looking at nothing.
        check(comments >= MIN_COMMENTS) { "the claim sweep found only $comments claim comments" }
        check(cited.size >= MIN_DISTINCT_DOCS) { "the claim sweep found only ${cited.size} distinct cited docs" }

        val root = repoRoot()
        cited.filterNot { File(root, it).isFile } shouldBe emptyList()
    }

    @Test
    fun `the parser reads every citation grammar shape`() {
        // One literal comment carrying every shape the grammar accepts (674 A.1): plain and
        // dotted and lettered numbers, Appendix and Part with and without the § glyph, all
        // three range spellings, a file-only citation, and two citations of different docs.
        val comment =
            "docs/auth.md §3, docs/rest-api.md §6.2.14 (the row), docs/auth.md §5A.2 and §11A, " +
                "docs/datasources.md §8C.1, docs/rest-api.md §9.7A, docs/deployment.md §Appendix B, " +
                "docs/deployment.md Appendix B (no glyph), docs/semantic-layer-research.md Part five, " +
                "docs/auth.md §7.1–7.6 (the walk), docs/scheduler.md §4.2–§6, docs/enums.md §4-§5, " +
                "docs/mcp-server.md (no anchor at all), docs/auth.md §10.1"
        val expected =
            listOf(
                ClaimCitations.Citation("docs/auth.md", listOf("3"), ""),
                ClaimCitations.Citation("docs/rest-api.md", listOf("6.2.14"), "(the row)"),
                ClaimCitations.Citation("docs/auth.md", listOf("5A.2"), "and §11A"),
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
            )
        ClaimCitations.parse(comment) shouldBe expected
    }

    @Test
    fun `every cited section exists`() {
        val refs = citedSectionRefs()
        // Non-vacuity: 834 anchored citations today. A parser that stopped seeing anchors
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
        // for a range, the upper end — the end nearest the prose). Assertions cover what
        // passes today; the misses at promotion time carry one reason each in KNOWN_UNPROBED
        // and the follow-up work. The report that filled that list is in the lane evidence.
        val docSections = mutableMapOf<Pair<String, String>, String?>()
        val root = repoRoot()
        val misses =
            templates.flatMap { (template, source) ->
                CLAIM_COMMENT.findAll(source).map { it.value }.flatMap { comment ->
                    ClaimCitations.parse(comment).mapNotNull { citation ->
                        probeMiss(template, citation, root, docSections)
                    }
                }
            }
        check(misses.isEmpty()) {
            "citations whose prose shares no word with their section:\n${misses.joinToString("\n")}"
        }
    }

    /** The probe's miss line for one citation, or null when the citation passes (or is excused). */
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
        val key = "$template|${citation.doc}|${citation.anchors.joinToString("–")}"
        if (KNOWN_UNPROBED.containsKey(key)) return null
        val sectionKey = citation.doc to anchor
        if (!docSections.containsKey(sectionKey)) {
            val lines = File(root, citation.doc).takeIf { it.isFile }?.readLines()
            docSections[sectionKey] = lines?.let { ClaimCitations.sectionText(it, anchor) }
        }
        val section = docSections[sectionKey]
        val hit = section != null && tokens.any { ClaimCitations.sectionMentions(section.lowercase(), it) }
        return if (hit) null else "$template: ${citation.doc} §$anchor — tokens $tokens in ${citation.prose.take(80)}"
    }

    /** Every anchored citation as `(template, doc, anchor)`, ranges flattened to both ends. */
    private fun citedSectionRefs(): List<Triple<String, String, String>> =
        templates.flatMap { (name, source) ->
            CLAIM_COMMENT.findAll(source).flatMap { comment ->
                ClaimCitations.parse(comment.value).flatMap { citation ->
                    citation.anchors.map { anchor -> Triple(name, citation.doc, anchor) }
                }
            }
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

        /**
         * Anchored citations at the 674 base (92003a21f): 834. The floor is the brief's 600 —
         * deliberately coarser than the count, because the sharper parser guard is the grammar
         * test above; this one only has to catch catastrophic parser blindness.
         */
        const val MIN_ANCHORED = 600

        val CARD = Regex("""<article class="card[^"]*">.*?</article>""", RegexOption.DOT_MATCHES_ALL)
        val CLAIM = Regex("""<!--\s*claim:""")
        val CLAIM_COMMENT = Regex("""<!--\s*claim:.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val DOC_PATH = Regex("""docs/[A-Za-z0-9._/-]+\.md""")
        val HEADING = Regex("""<h3>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Citations whose prose shares no word with their section, promoted with the probe
         * rather than repaired (674 A.3's branch 2: 45 misses at promotion time, over the
         * ~15 threshold; 43 keys — some cover two or three comments in one template). One
         * reason each, named by kind — precision (the prose describes a different section
         * than the anchor names), vague (the prose is a marketing question the section never
         * answers literally), synonym (the section says it in other words), chain (further
         * bare `§n` citations ride in the prose). The follow-up issue owns the sharpening; a
         * comment fixed here leaves this list. Key: `template|doc|anchor(s)`.
         */
        val KNOWN_UNPROBED: Map<String, String> =
            mapOf(
                "add-mcp-server.html|docs/mcp-server.md|4.2" to
                    "precision: prose names the transport (§3); §4.2 is unauthorized behavior",
                "ai-data-pipeline.html|docs/mcp-server.md|6.1" to
                    "precision: the agent-authoring claims belong at §2/§6.2; §6.1 is the naming convention (two comments)",
                "compare-airflow.html|docs/scheduler.md|4" to
                    "vague: 'replace Airflow's scheduler' is the §1/§2 pitch; §4 is missed occurrences and Run now",
                "compare-airflow.html|docs/mcp-server.md|6.1" to
                    "precision: agent-authoring is §2/§6.2; §6.1 is the naming convention",
                "compare-airflow.html|docs/versioning.md|3.1" to
                    "precision: reruns/reproducibility is §3 lifecycle; §3.1 is statuses and verbs",
                "compare-dagster-vs-airflow.html|docs/dag-executor.md|5" to
                    "vague: 'need an orchestrator' is the §1 pitch; §5 is the execution lifecycle",
                "compare-dagster-vs-airflow.html|docs/rest-api.md|6" to
                    "precision: the three triggers are §4/§5; §6 is SSE streaming of a run",
                "compare-dagster-vs-airflow.html|docs/mcp-server.md|6.1" to
                    "precision: the MCP-or-REST comparison is §2; §6.1 is the naming convention",
                "compare-dbt.html|docs/staging.md|1" to
                    "vague: 'need a warehouse' is answered by the whole doc; §1 Purpose never says warehouse",
                "compare-dbt.html|docs/datasources.md|4.1" to
                    "synonym: 'sources' vs §4.1's dialect catalog vocabulary",
                "compare-dbt.html|docs/mcp-server.md|6.1" to
                    "precision: agent-authoring is §2/§6.2; §6.1 is the naming convention",
                "compare-fivetran-airbyte.html|docs/scheduler.md|1" to
                    "vague: '(schedules)' restates §1's own title words in different form",
                "compare-fivetran-airbyte.html|docs/ROADMAP.md|3" to
                    "vague: '(extracts later)' is a roadmap promise §3 does not word that way",
                "compare-fivetran-airbyte.html|docs/rest-api.md|19" to
                    "vague: 'replace a nightly sync' is the pitch; §19 is the published-endpoint contract",
                "compare-postgres-only.html|docs/rest-api.md|19" to
                    "vague: 'API partners' is the audience; §19 is the published-endpoint contract",
                "faq.html|docs/staging.md|3" to
                    "vague: 'no warehouse' is the §1 pitch; §3 is the staging lifecycle",
                "faq.html|docs/dag-executor.md|5" to
                    "precision: timeouts are §4's limits; §5 is the execution lifecycle",
                "for-agencies.html|docs/auth.md|7.5" to
                    "vague: 'each client separate' is workspace scoping; §7.5 is key roles",
                "for-agencies.html|docs/versioning.md|3" to
                    "vague: 'who signs it off' is promotion governance §3 never words that way",
                "for-saas-teams.html|docs/rest-api.md|19" to
                    "vague: 'embedded analytics' is the pitch; §19 is the published-endpoint contract",
                "for-saas-teams.html|docs/templates.md|4.5" to
                    "precision: per-customer row scoping is parameter binding only indirectly; §4.5 is the binding rule",
                "for-saas-teams.html|docs/versioning.md|3" to
                    "vague: 'who controls what customers see' is governance; §3 is the version lifecycle",
                "how-it-works.html|docs/mcp-server.md|6.1" to
                    "precision: the catalogue size renders from §6.2's definitions; §6.1 is the naming convention",
                "how-it-works.html|docs/datasources.md|4.1" to
                    "synonym: 'engines' vs §4.1's dialect-catalog vocabulary",
                "index.html|docs/datasources.md|4" to
                    "synonym: 'engines/databases' vs §4's supported-dialects vocabulary (three comments)",
                "index.html|docs/deployment.md|10" to
                    "vague: '(free, AGPL-3.0, self-hosted)' is license + hosting; §10 is the license only",
                "index.html|docs/scheduler.md|1" to
                    "vague: '(schedules)' restates §1's own title words in different form",
                "index.html|docs/versioning.md|3" to
                    "vague: the data-engineer FAQ is the §1 pitch; §3 is the version lifecycle",
                "index.html|docs/pipeline-contract.md|12.12" to
                    "vague: 'numbers are right' is the release-check FAQ; §12.12 words it as checks, not numbers",
                "index.html|docs/auth.md|7.7" to
                    "precision: per-customer row scoping is §6 workspaces; §7.7 is key kinds and bindings",
                "pillar.html|docs/auth.md|11.4" to
                    "precision: revoking an agent key is §7.3/§7.4; §11.4 is the validation cache",
                "pricing.html|docs/deployment.md|4" to
                    "chain: prose targets §7/§8 (what you operate); the anchor is §4 required infrastructure",
                "pricing.html|docs/deployment.md|10" to
                    "vague: 'every feature, no seat count' is the license pitch; §10 words the license only",
                "pricing.html|docs/datasources.md|4" to
                    "synonym: 'engines' vs §4's supported-dialects vocabulary",
                "tableau-extracts-alerts-dashboards.html|docs/scheduler.md|1" to
                    "chain: prose targets §9 (not in slice 1); the anchor is §1 purpose",
                "tableau-extracts-alerts-dashboards.html|docs/templates.md|4.5" to
                    "precision: per-viewer filtering is parameter binding only indirectly; §4.5 is the binding rule",
                "tableau.html|docs/staging.md|3" to
                    "vague: 'no warehouse' is the §1 pitch; §3 is the staging lifecycle",
                "tableau.html|docs/auth.md|7.7" to
                    "precision: 'path-scoped keys' is §7.7's bindings, but the section never uses the word 'scoped'",
                "tableau.html|docs/scheduler.md|1" to
                    "vague: 'schedules that run pipelines' paraphrases §1's purpose sentence",
                "text-to-sql-agent.html|docs/mcp-server.md|6.1" to
                    "precision: SQL generation is the §6.2 tool surface; §6.1 is the naming convention",
                "text-to-sql-agent.html|docs/pipeline-contract.md|3.1" to
                    "vague: 'what happens to the query' is the §1/§2 story; §3.1 is the JSON structure",
                "text-to-sql-agent.html|docs/staging.md|1" to
                    "vague: the join-across-databases FAQ is the §1 pitch, which never says join",
                "use-cases.html|docs/ROADMAP.md|3" to
                    "vague: '(no hosted plan)' is the hosting stance; §3 is the v2 feature list",
            )
    }
}
