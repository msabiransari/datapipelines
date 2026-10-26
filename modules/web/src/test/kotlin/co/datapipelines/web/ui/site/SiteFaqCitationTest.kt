package co.datapipelines.web.ui.site

import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils
import java.io.File

/** Guards the FAQ strings that render both as visible answers and FAQPage JSON-LD. */
class SiteFaqCitationTest {
    // Keep this test-side inventory independent of the controllers: an omitted list must be
    // caught by the rendered-page completeness check below.
    private val lists: List<Pair<String, List<FaqEntry>>> =
        SiteFaqs.ALL +
            listOf(
                "DP_LAKE" to SiteFaqs.DP_LAKE,
                "PILLAR" to SiteFaqsCluster.PILLAR,
                "ADD_TO_CLIENT" to SiteFaqsCluster.ADD_TO_CLIENT,
                "AI_DATA_PIPELINE" to SiteFaqsCluster.AI_DATA_PIPELINE,
                "TEXT_TO_SQL" to SiteFaqsCluster.TEXT_TO_SQL,
                "COMPARE_AIRFLOW" to SiteFaqsCluster.COMPARE_AIRFLOW,
                "COMPARE_DBT" to SiteFaqsCluster.COMPARE_DBT,
                "COMPARE_DAGSTER_AIRFLOW" to SiteFaqsCluster.COMPARE_DAGSTER_AIRFLOW,
                "FEDERATED_QUERY" to SiteFaqsCluster.FEDERATED_QUERY,
                "ENGINES" to SiteFaqsCluster.ENGINES,
                "COMPARE_FIVETRAN" to SiteFaqsBatch2.COMPARE_FIVETRAN,
                "COMPARE_POSTGRES_ONLY" to SiteFaqsBatch2.COMPARE_POSTGRES_ONLY,
                "TABLEAU_PREP" to SiteFaqsBatch2.TABLEAU_PREP,
                "TABLEAU_ROADMAP" to SiteFaqsBatch2.TABLEAU_ROADMAP,
                "FOR_AGENCIES" to SiteFaqsBatch2.FOR_AGENCIES,
                "FOR_SAAS_TEAMS" to SiteFaqsBatch2.FOR_SAAS_TEAMS,
                "FOR_ANALYSTS" to SiteFaqsBatch2.FOR_ANALYSTS,
                "HOW_IT_WORKS_PRACTICAL" to SiteFaqsHub.HOW_IT_WORKS_PRACTICAL,
                "USE_CASES" to SiteFaqsHub.USE_CASES,
                "SCHEDULING" to SiteCapabilitiesFaqs.SCHEDULING,
                "DASHBOARDS" to SiteCapabilitiesFaqs.DASHBOARDS,
                "PARAMETER_SETS" to SiteCapabilitiesFaqs.PARAMETER_SETS,
            )

    @Test
    fun `every FAQ reference exists, matches its answer and names its section`() {
        val entries = lists.flatMap { (name, rows) -> rows.map { name to it } }
        check(entries.size >= MIN_ENTRIES) { "only ${entries.size} registered FAQ entries" }
        val inventory = Inventory(repoRoot())
        entries.forEach { (name, entry) -> inventory.inspect(name, entry) }
        System.getenv(REPORT_VARIABLE)?.let { path -> File(path).writeText(inventory.report.toString()) }
        check(inventory.references >= MIN_REFERENCES) { "only ${inventory.references} FAQ references" }
        check(inventory.failures.isEmpty()) {
            "FAQ citation failures (${inventory.failures.size}):\n${inventory.failures.joinToString("\n")}"
        }
    }

    private inner class Inventory(
        private val root: File,
    ) {
        val failures = mutableListOf<String>()
        val report = StringBuilder("| List | Question | Surface | Reference | Section | Probe | Verdict |\n|---|---|---|---|---|---|---|\n")
        var references = 0
        private val docs = mutableMapOf<String, List<String>?>()

        fun inspect(
            name: String,
            entry: FaqEntry,
        ) {
            checkKnownClaims(name, entry)
            val answerRefs = refs(entry.answer)
            (refs(entry.cites) - answerRefs).forEach { (doc, anchor) ->
                failures += "$name: ${entry.question}: cites $doc §$anchor but the answer does not"
            }
            inspectSurface(name, entry, "answer", entry.answer)
            inspectSurface(name, entry, "cites", entry.cites)
        }

        private fun checkKnownClaims(
            name: String,
            entry: FaqEntry,
        ) {
            if ("docs/mcp-server.md §6.1" in entry.answer &&
                listOf("tool list", "grounding tools", "tools are", "agent can author").any { it in entry.answer }
            ) {
                failures += "$name: ${entry.question}: tool behavior belongs to docs/mcp-server.md §6.2, not §6.1"
            }
            if (name == "FOR_AGENCIES" && entry.question == "Can each client be kept separate from the others?" &&
                "docs/auth.md §5.6" !in entry.answer
            ) {
                failures += "$name: ${entry.question}: workspace pinning must cite docs/auth.md §5.6"
            }
            if (name == "FOR_SAAS_TEAMS" && entry.question == "How does each customer see only their own rows?") {
                val required = listOf("docs/templates.md §4.5", "docs/auth.md §7.7", "docs/rest-api.md §19.3")
                if ("Row-level authorisation stays your backend's job" !in entry.answer || required.any { it !in entry.answer }) {
                    failures += "$name: ${entry.question}: missing backend authorisation boundary or its three contracts"
                }
            }
        }

        private fun inspectSurface(
            name: String,
            entry: FaqEntry,
            surface: String,
            value: String,
        ) {
            ClaimCitations.parse(value).forEach { citation ->
                if (citation.anchors.isEmpty()) failures += "$name: ${entry.question}: unanchored ${citation.doc} in $surface"
                citation.anchors.forEach { anchor -> inspectReference(name, entry, surface, citation.doc, anchor) }
            }
        }

        private fun inspectReference(
            name: String,
            entry: FaqEntry,
            surface: String,
            doc: String,
            anchor: String,
        ) {
            references++
            val lines = docs.getOrPut(doc) { File(root, doc).takeIf { it.isFile }?.readLines() }
            val section = lines?.let { ClaimCitations.sectionText(it, anchor) }
            val probe = if (surface == "answer") probe(entry.answer, doc, anchor, section) else null
            val verdict =
                when {
                    section == null -> "missing section"
                    probe == false -> "probe miss"
                    else -> "ok"
                }
            if (verdict != "ok") failures += "$name: ${entry.question}: $surface $doc §$anchor: $verdict"
            report.append("| $name | ${entry.question} | $surface | $doc §$anchor | ${section != null} | ${probe ?: "—"} | $verdict |\n")
        }

        private fun refs(value: String): Set<Pair<String, String>> =
            ClaimCitations.parse(value).flatMap { citation -> citation.anchors.map { citation.doc to it } }.toSet()
    }

    @Test
    fun `every rendered FAQ question belongs to a registered list`() {
        val registered = lists.flatMap { it.second }.map { it.question }.toSet()
        check(lists.sumOf { it.second.size } >= MIN_ENTRIES) { "FAQ registry lost entries" }
        var renderedQuestions = 0
        val unregistered =
            SitePages.ALL.flatMap { page ->
                DETAILS.findAll(SitePageRenderer.render(page)).mapNotNull { match ->
                    renderedQuestions++
                    val question = HtmlUtils.htmlUnescape(match.groupValues[1]).trim()
                    if (question in registered) null else "${page.path}: $question"
                }
            }
        check(renderedQuestions >= MIN_ENTRIES) { "only $renderedQuestions rendered FAQ questions — the sweep must not be empty" }
        check(unregistered.isEmpty()) { "rendered FAQ questions missing from registry:\n${unregistered.joinToString("\n")}" }
    }

    @Test
    fun `an unrelated earlier statement cannot satisfy a later citation`() {
        val answer =
            "Parameters belong to an earlier statement. Frobnication is unrelated. " +
                "Widgets wobble (docs/templates.md §4.5)."
        check(probe(answer, "docs/templates.md", "4.5", "Parameters are bound scalars.") == false) {
            "an unrelated earlier statement must not satisfy this citation"
        }
    }

    private fun probe(
        answer: String,
        doc: String,
        anchor: String,
        section: String?,
    ): Boolean? {
        if (section == null) return null
        val marker = Regex("${Regex.escape(doc)}\\s*§?\\s*${Regex.escape(anchor)}(?=\\W|$)")
        // In a same-document chain only the first anchor repeats the path.
        val start = marker.find(answer)?.range?.first ?: answer.indexOf(doc)
        if (start < 0) return false
        // A citation-only sentence can follow its claim. Include that adjacent sentence,
        // never unrelated claims from the beginning of the answer.
        val nearbyClaim =
            answer
                .substring(0, start)
                .split(SENTENCE_BREAK)
                .filter { it.isNotBlank() }
                .takeLast(2)
                .joinToString(" ")
        val tokens = ClaimCitations.probeTokens(nearbyClaim)
        return tokens.any { ClaimCitations.sectionMentions(section.lowercase(), it) }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no docs directory above ${File("").absolutePath}" }
    }

    private companion object {
        const val MIN_ENTRIES = 120
        const val MIN_REFERENCES = 250
        const val REPORT_VARIABLE = "FAQ_CITATION_REPORT"
        val SENTENCE_BREAK = Regex("[.!?]\\s+")
        val DETAILS = Regex("""<details[^>]*>\s*<summary>([^<]*)</summary>\s*<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    }
}
