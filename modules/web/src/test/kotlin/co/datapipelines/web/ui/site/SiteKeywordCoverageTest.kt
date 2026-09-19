package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.site.SiteKeywordCoverageTest.Surface.BODY
import co.datapipelines.web.ui.site.SiteKeywordCoverageTest.Surface.H1
import co.datapipelines.web.ui.site.SiteKeywordCoverageTest.Surface.H2
import co.datapipelines.web.ui.site.SiteKeywordCoverageTest.Surface.TITLE
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.web.util.HtmlUtils

/**
 * The measured search phrases, pinned to the page and the SURFACE each one lives on (173 §E).
 *
 * The site is indexed once (owner, 2026-09-18): every phrase the keyword study measured
 * (`notes/2026-09-04-seo-keywords.md` and its addendum 4, in the private store) has a named
 * home — a title, an H1, an H2 or a sentence in the body — and this table is that plan as a
 * build gate. A rewrite that drops a heading, a title edit that loses the phrase, or a
 * sentence trimmed by a later lane fails here naming the phrase, the page and the surface,
 * rather than being discovered by a rankings drop months later.
 *
 * Matching is EXACTLY the sweep's (`sweep.py` in the store's evidence): lower-case, every
 * dash variant folded to a space, whitespace collapsed, visible text only — and stricter on
 * one point: `BODY` is the `<main>` landmark, never the chrome, so a footer link that names
 * every engine page cannot satisfy a body row on an unrelated page. Stop-words are NOT
 * folded, so a phrase is written here as searched ("add an mcp server to claude code" is a
 * different row from "add mcp server to claude code", and both are pinned).
 *
 * Falsified at birth (the two outputs are in the lane's evidence): removing "PostgreSQL"
 * from the Postgres engine row named `postgresql mcp server` on `/mcp-server/postgres`
 * (`BODY`); moving `alternative to airflow` from `BODY` to `H2` named it on `/compare/airflow`.
 * Non-vacuity: the table is at least [MIN_ROWS] rows, every registry page renders.
 */
class SiteKeywordCoverageTest {
    /** Where a phrase must appear. `BODY` is the weakest claim: anywhere in the visible `<main>`. */
    enum class Surface { TITLE, H1, H2, BODY }

    private data class Row(
        val phrase: String,
        val page: SitePage,
        val surface: Surface,
    )

    /** The engine page for one slug — a registry lookup, so a renamed slug fails to compile here. */
    private fun engine(slug: String): SitePage = SitePages.enginePage(checkNotNull(SitePages.engine(slug)) { "no engine $slug" })

    /**
     * phrase → page → surface. Grouped as the study groups them; the volume is in the study,
     * not here — this table states WHERE, the study states WHY.
     */
    private val rows: List<Row> =
        listOf(
            // ---- Cluster 1: the engine MCP servers (the pillar, the engines, the client how-to).
            Row("claude code mcp server", SitePages.ADD_TO_CLAUDE_CODE, H2),
            Row("cursor mcp server", SitePages.ADD_TO_CLAUDE_CODE, H2),
            Row("add an mcp server to claude code", SitePages.ADD_TO_CLAUDE_CODE, TITLE),
            Row("add an mcp server to claude code", SitePages.ADD_TO_CLAUDE_CODE, H1),
            Row("add mcp server to claude code", SitePages.ADD_TO_CLAUDE_CODE, BODY),
            Row("sql mcp server", SitePages.PILLAR, TITLE),
            Row("database mcp server", SitePages.PILLAR, TITLE),
            Row("mcp database server", SitePages.PILLAR, BODY),
            Row("postgres mcp server", engine("postgres"), TITLE),
            Row("postgres mcp server", engine("postgres"), H1),
            Row("postgresql mcp server", engine("postgres"), BODY),
            Row("sql server mcp", engine("sql-server"), TITLE),
            Row("sql server mcp server", engine("sql-server"), TITLE),
            Row("mssql mcp server", engine("sql-server"), BODY),
            Row("mysql mcp server", engine("mysql"), TITLE),
            Row("oracle mcp server", engine("oracle"), TITLE),
            Row("sqlite mcp server", engine("sqlite"), TITLE),
            Row("duckdb mcp server", engine("duckdb"), TITLE),
            // ---- Cluster 2: federated query.
            Row("federated query", SitePages.FEDERATED_QUERY, TITLE),
            Row("data virtualization", SitePages.FEDERATED_QUERY, TITLE),
            Row("cross-database join", SitePages.FEDERATED_QUERY, TITLE),
            Row("cross-database query", SitePages.FEDERATED_QUERY, BODY),
            Row("federated query engine", SitePages.FEDERATED_QUERY, BODY),
            Row("join tables from different databases", SitePages.FEDERATED_QUERY, BODY),
            // ---- Cluster 3: text-to-SQL and the AI pages.
            Row("text to sql", SitePages.TEXT_TO_SQL_AGENT, TITLE),
            Row("text-to-sql agent", SitePages.TEXT_TO_SQL_AGENT, TITLE),
            Row("natural language to sql", SitePages.TEXT_TO_SQL_AGENT, H2),
            Row("text to sql llm", SitePages.TEXT_TO_SQL_AGENT, BODY),
            Row("ai sql query generator", SitePages.TEXT_TO_SQL_AGENT, BODY),
            Row("ai data analyst", SitePages.FOR_ANALYSTS, TITLE),
            Row("ai data analyst", SitePages.FOR_ANALYSTS, H1),
            Row("sql assistant", SitePages.FOR_ANALYSTS, BODY),
            Row("ai data pipeline", SitePages.AI_DATA_PIPELINE, TITLE),
            // ---- Cluster 4: comparisons.
            Row("dagster vs airflow", SitePages.COMPARE_DAGSTER_AIRFLOW, TITLE),
            Row("dagster vs airflow", SitePages.COMPARE_DAGSTER_AIRFLOW, H1),
            Row("airflow vs dagster", SitePages.COMPARE_DAGSTER_AIRFLOW, H2),
            Row("prefect vs airflow", SitePages.COMPARE_DAGSTER_AIRFLOW, H2),
            Row("dagster alternative", SitePages.COMPARE_DAGSTER_AIRFLOW, H2),
            Row("airflow alternative", SitePages.COMPARE_AIRFLOW, TITLE),
            Row("apache airflow alternative", SitePages.COMPARE_AIRFLOW, TITLE),
            Row("alternative to airflow", SitePages.COMPARE_AIRFLOW, BODY),
            Row("dbt alternative", SitePages.COMPARE_DBT, TITLE),
            Row("alternative to dbt", SitePages.COMPARE_DBT, BODY),
            Row("fivetran alternative", SitePages.COMPARE_FIVETRAN, H1),
            Row("airbyte alternative", SitePages.COMPARE_FIVETRAN, H2),
            Row("fivetran vs airbyte", SitePages.COMPARE_FIVETRAN, BODY),
            Row("postgres as data warehouse", SitePages.COMPARE_POSTGRES_ONLY, H2),
            Row("postgres analytics", SitePages.COMPARE_POSTGRES_ONLY, BODY),
            Row("postgres for analytics", SitePages.COMPARE_POSTGRES_ONLY, BODY),
            // ---- Clusters 5/6: the category terms.
            Row("data pipeline", SitePages.AI_DATA_PIPELINE, TITLE),
            Row("data pipeline tools", SitePages.AI_DATA_PIPELINE, H2),
            Row("agentic data engineering", SitePages.AI_DATA_PIPELINE, BODY),
            Row("ai-native data platform", SitePages.AI_DATA_PIPELINE, BODY),
            Row("agent-native", SitePages.AI_DATA_PIPELINE, BODY),
            Row("pipelines as code", SitePages.TABLEAU_PREP, TITLE),
            Row("pipeline as code", SitePages.TABLEAU_PREP, BODY),
            Row("open-source etl", SitePages.PRICING, BODY),
            Row("open-source data pipeline", SitePages.PRICING, BODY),
            Row("self-hosted analytics", SitePages.FOR_SAAS_TEAMS, BODY),
            Row("embedded analytics", SitePages.FOR_SAAS_TEAMS, TITLE),
            Row("customer facing analytics", SitePages.FOR_SAAS_TEAMS, BODY),
            // ---- The semantic layer.
            Row("semantic layer", SitePages.SEMANTIC_LAYER, TITLE),
            Row("semantic model", SitePages.SEMANTIC_LAYER, H2),
            Row("semantic data model", SitePages.SEMANTIC_LAYER, H2),
            Row("dbt semantic layer", SitePages.SEMANTIC_LAYER, H2),
            Row("cube semantic layer", SitePages.SEMANTIC_LAYER, H2),
            Row("schema drift", SitePages.SEMANTIC_LAYER, H2),
            Row("business glossary", SitePages.SEMANTIC_LAYER, H2),
            // ---- Addendum 4 (2026-09-18): the pages measured after the study.
            Row("white label reporting", SitePages.FOR_AGENCIES, H2),
            Row("client reporting tool", SitePages.FOR_AGENCIES, BODY),
            Row("sample sql database", SitePages.DEMO_DATA, H2),
            Row("nyc taxi dataset", SitePages.DEMO_DATA, BODY),
            Row("mcp server security", SitePages.SECURITY, H2),
            Row("tableau extracts", SitePages.TABLEAU_ROADMAP, H2),
            Row("duckdb s3", SitePages.DP_LAKE, H2),
            Row("sql on s3", SitePages.DP_LAKE, BODY),
            Row("iceberg sql", SitePages.DP_LAKE, BODY),
        )

    private val rendered: Map<String, String> by lazy {
        rows.map { it.page }.distinct().associate { it.path to SitePageRenderer.render(it) }
    }

    @Test
    fun `every measured phrase is on its page, on its surface`() {
        withClue("the table is non-vacuous") { rows.size shouldBeGreaterThanOrEqual MIN_ROWS }
        val missing =
            rows.mapNotNull { row ->
                val html = rendered.getValue(row.page.path)
                val haystack = surfaceText(html, row.surface)
                if (norm(row.phrase) in haystack) null else "'${row.phrase}' is not in the ${row.surface} of ${row.page.path}"
            }
        // The clue carries the WHOLE list: the failure names every phrase, not the first.
        withClue("phrases missing from their planned surface:\n" + missing.joinToString("\n")) { missing.shouldBeEmpty() }
    }

    @Test
    fun `no row is listed twice`() {
        val duplicates = rows.groupBy { it }.filterValues { it.size > 1 }.keys
        withClue("duplicate rows") { duplicates.shouldBeEmpty() }
    }

    /** The normalised text of one surface — what the sweep would read for it. */
    private fun surfaceText(
        html: String,
        surface: Surface,
    ): String {
        val main = main(html)
        val title = TITLE_TAG.find(html)?.groupValues?.get(1)
        return when (surface) {
            TITLE -> norm(visible(title.orEmpty()))
            H1 -> H1_TAG.findAll(main).joinToString(" | ") { norm(visible(it.groupValues[1])) }
            H2 -> H2_TAG.findAll(main).joinToString(" | ") { norm(visible(it.groupValues[1])) }
            BODY -> norm(visible(SCRIPT_OR_STYLE.replace(main, " ")))
        }
    }

    private fun main(html: String): String {
        val from = html.indexOf("<main")
        val to = html.lastIndexOf("</main>")
        check(from >= 0 && to > from) { "no <main> landmark in the render" }
        return html.substring(from, to)
    }

    private fun visible(fragment: String): String = HtmlUtils.htmlUnescape(TAG.replace(fragment, " "))

    /** The sweep's `norm`: lower-case, dash variants to spaces, whitespace collapsed. */
    private fun norm(text: String): String = WHITESPACE.replace(DASH.replace(text.lowercase(), " "), " ").trim()

    private companion object {
        /** 46 phrases in the study plus 18 in addendum 4 — the floor the brief set. */
        const val MIN_ROWS = 64

        val TITLE_TAG = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val H1_TAG = Regex("""<h1\b[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
        val H2_TAG = Regex("""<h2\b[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
        val SCRIPT_OR_STYLE = Regex("""<script\b.*?</script>|<style\b.*?</style>""", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")
        val DASH = Regex("[‑–—-]")
        val WHITESPACE = Regex("""\s+""")
    }
}
