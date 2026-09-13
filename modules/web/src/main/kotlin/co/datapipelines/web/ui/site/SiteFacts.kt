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
