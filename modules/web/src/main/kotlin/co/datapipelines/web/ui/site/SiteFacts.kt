package co.datapipelines.web.ui.site

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.typesystem.Dialect

/**
 * The numbers the marketing site states (124 §A) — built from the code that OWNS each fact,
 * never from a literal, so a dialect, tool or kind that ships cannot leave the site claiming
 * yesterday's count. Every count on the site was once typed by hand, and the copies disagreed
 * with each other; this object is the one place the answer now comes from, and
 * `SiteHandTypedCountsGuardTest` fails the build on a typed count anywhere else.
 *
 * Everything here is a compile-time constant (the dialect enum, the tool catalog, the
 * registries), so [current] is cheap and deterministic: controllers call it once per request
 * and the page registry calls it once at init, and both get the same values. The spelled-out
 * forms ("eight") come from ONE [numberWord] helper so copy reads as prose.
 */
data class SiteFacts(
    /** Every dialect the product speaks — tempdb is implicit and is never counted or listed. */
    val engineCount: Int,
    /** The MCP tool surface, from the compile-time catalog (never an injected bean list). */
    val toolCount: Int,
    /** The tools that can write — the audit log's `mcp.tool.write` set. */
    val mutatingToolCount: Int,
    /** Every calculator kind the registry ships. */
    val calculatorKindCount: Int,
    /** The kinds an API key can be (user, endpoint, server). */
    val apiKeyKindCount: Int,
    /** The closed kind list of the learned semantic layer (enums.md §19). */
    val learnedFactKindCount: Int,
    /** The scopes a learned fact can live in (datasource, workspace). */
    val learnedFactScopeCount: Int,
    /** The semantics tools, counted off the catalog by the prefix the tools ship under. */
    val semanticsToolCount: Int,
    /**
     * The distinct engines each demo family's tables live on, keyed by family — read off the
     * published manifests by the demo-data page's handler, empty everywhere else (the other
     * pages never state the number).
     */
    val demoEngineCounts: Map<String, Int> = emptyMap(),
    /**
     * The demo showcase behind the hero console and the product slab's DAG (133 §B) — see
     * [DemoShowcase]. A constant, not a lookup: the run is a recorded acceptance result,
     * reproduced from the bucket, and the site presents it as exactly that.
     */
    val demo: DemoShowcase = DEMO_SHOWCASE,
) {
    /** The read-only majority of the tool surface. */
    val readToolCount: Int get() = toolCount - mutatingToolCount

    val engineCountWord: String get() = numberWord(engineCount)
    val apiKeyKindCountWord: String get() = numberWord(apiKeyKindCount)
    val learnedFactKindCountWord: String get() = numberWord(learnedFactKindCount)
    val learnedFactScopeCountWord: String get() = numberWord(learnedFactScopeCount)
    val semanticsToolCountWord: String get() = numberWord(semanticsToolCount)

    /** The engine count of one demo family as a prose word — the manifest's own fact, via the handler. */
    fun demoEngineCountWord(family: String): String =
        numberWord(checkNotNull(demoEngineCounts[family]) { "no demo engine count for family '$family'" })

    /**
     * The engines as copy names them, in [Dialect] declaration order:
     * "Postgres, Oracle, SQL Server, MySQL, H2, DuckDB, SQLite and dp-lake". The display names
     * come from [SitePages.ENGINES] — the one per-engine fact map — so a dialect without an
     * engine page cannot be named here at all (the equality guard in
     * `SiteEngineFactsGuardTest` makes that state unbuildable first).
     *
     * Lazy, deliberately: [SitePages] builds its registry rows from [current], so evaluating
     * this during [SitePages]' own init would read the half-built registry. By first use the
     * object is fully initialised.
     */
    val engines: String by lazy {
        val names =
            Dialect.entries.map { dialect ->
                SitePages.ENGINES.first { it.dialect == dialect.name }.displayName
            }
        names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    companion object {
        /** The facts as the code states them right now — the only constructor call site. */
        fun current(demoEngineCounts: Map<String, Int> = emptyMap()): SiteFacts =
            SiteFacts(
                engineCount = Dialect.entries.size,
                toolCount = McpToolCatalog.NAMES.size,
                mutatingToolCount = McpToolCatalog.MUTATING.size,
                calculatorKindCount = CalculatorRegistry.KINDS.size,
                apiKeyKindCount = ApiKeyKind.entries.size,
                learnedFactKindCount = LearnedFactKind.entries.size,
                learnedFactScopeCount = LearnedFactScope.entries.size,
                semanticsToolCount = McpToolCatalog.NAMES.count { it.startsWith("semantics_") },
                demoEngineCounts = demoEngineCounts,
            )
    }
}

/**
 * One through twelve as prose ("eight"), for copy that reads as a sentence rather than a
 * spec. Past twelve the digits are the better copy anyway, and refusing keeps a thirteenth
 * use-site from silently picking a style the rest of the site does not share.
 */
fun numberWord(n: Int): String = WORDS.getOrElse(n - 1) { error("numberWord covers 1..12; $n should render as digits") }

private val WORDS: List<String> =
    listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

/**
 * The demo showcase the home page shows instead of a screenshot (133 §B): the pipeline-0
 * acceptance result — `demo/top_company_by_borough`, executed 2026-09-14 on the published
 * sample set, reproduced from the bucket (ledger T272). It is a real run's record, marked
 * "from the demo workspace" wherever it renders — a result, never a promise.
 *
 * ONE constant block feeds the hero console, the product slab's DAG, and any page that
 * reuses those partials, so the site states one run; a number typed into a template
 * instead fails the demo arm of `SiteHandTypedCountsGuardTest`. EWR is omitted from the
 * console's table (the panel shows the five boroughs); the answer node counts every row.
 */
data class DemoShowcase(
    /** The sentence the visitor asked, verbatim. */
    val question: String,
    /** The agent's four steps; the LAST renders as the live ("left for a human") step. */
    val steps: List<String>,
    /** The result table the draft produced — the five boroughs, EWR omitted from the panel. */
    val rows: List<DemoRow>,
    /** The endpoint the pipeline answers on after release, with its example parameter. */
    val endpoint: String,
    /** The three source nodes of the DAG: what was read, with its own fact underneath. */
    val sources: List<DemoNode>,
    /** The two H2 staging nodes, with row counts and timings. */
    val stages: List<DemoNode>,
    /** The answer node: the rows the caller gets. */
    val answer: DemoNode,
    /** The bar under the DAG: the draft, its version, and what happens next. */
    val releaseLine: String,
    /** The pipeline's path — what a released version serves. */
    val pipelinePath: String,
    /** The released-so-far version label of the run of record. */
    val version: String,
    /** The reach of the endpoint key guarding the published path. */
    val keyReach: String,
    /** The demo workspace's datasource roster, as the datasources panel lists it. */
    val datasources: List<DemoDatasource>,
) {
    /** One console row: a borough, its top company, and the two counts with the share. */
    data class DemoRow(
        val borough: String,
        val topCompany: String,
        val trips: String,
        val boroughTrips: String,
        val share: String,
    )

    /** One DAG node: its name and the fact line under it (row count and timing where timed). */
    data class DemoNode(
        val name: String,
        val detail: String,
    )

    /** One datasource roster row: the name, what it is, and the access the agent gets. */
    data class DemoDatasource(
        val name: String,
        val kind: String,
        val access: String,
    )
}

/**
 * The run of record (133 §B.1): `demo/top_company_by_borough` v1, executed 2026-09-14 on the
 * published sample set, reproduced from the bucket — ledger T272.
 */
val DEMO_SHOWCASE =
    DemoShowcase(
        question = "Which rideshare company carried the most trips in each borough last quarter?",
        steps =
            listOf(
                "read the datasource facts, columns and stats. hvfhv_zone_day is a census at zone × day × company",
                "resolved \"last quarter\" from the data's last day, which gave Q4 2024",
                "rendered 3 templates, ran the draft: 4 nodes · 763 ms",
                "draft demo/top_company_by_borough v1, left for a human to release",
            ),
        rows =
            listOf(
                DemoShowcase.DemoRow("Manhattan", "Uber", "17,660,839", "23,669,163", "74.62%"),
                DemoShowcase.DemoRow("Brooklyn", "Uber", "11,946,362", "16,057,699", "74.40%"),
                DemoShowcase.DemoRow("Queens", "Uber", "9,786,602", "12,847,231", "76.18%"),
                DemoShowcase.DemoRow("Bronx", "Uber", "6,078,750", "7,572,899", "80.27%"),
                DemoShowcase.DemoRow("Staten Island", "Uber", "715,593", "918,360", "77.92%"),
            ),
        endpoint = "/api/demo/v1/top-company-by-borough?anchor_date=2025-01-01",
        sources =
            listOf(
                DemoShowcase.DemoNode("sample-lake · Parquet on S3", "hvfhv_zone_day · 377k rows"),
                DemoShowcase.DemoNode("sample-reference · SQLite", "zones · 265 rows"),
                DemoShowcase.DemoNode("calculator · trailing_periods", "anchor 2025-01-01 → Q4 2024"),
            ),
        stages =
            listOf(
                DemoShowcase.DemoNode("stage_company_zone", "523 rows · 482 ms"),
                DemoShowcase.DemoNode("stage_zones", "263 rows · 204 ms"),
            ),
        answer = DemoShowcase.DemoNode("answer", "6 rows · caller"),
        releaseLine = "demo/top_company_by_borough · v1 · awaiting a human release · then GET /api/demo/v1/…",
        pipelinePath = "demo/top_company_by_borough",
        version = "v1",
        keyReach = "/demo/**",
        datasources =
            listOf(
                DemoShowcase.DemoDatasource("sample-lake", "Parquet on S3, read in place", "read-only ✓"),
                DemoShowcase.DemoDatasource("sample-reference", "SQLite", "read-only ✓"),
            ),
    )
