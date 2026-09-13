package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import java.sql.DatabaseMetaData

/**
 * The resolution every table-ADDRESSED read passes through first (datasources.md §7A, 123 §A):
 * [SchemaIntrospector.columns], [SchemaIntrospector.tableStats] and the preview-rows surface
 * (`resolveTable` on the introspector). Split out of the introspector, which sits at the house
 * size ceiling — this class also owns [readTables], the getTables walk the tables listing and
 * the resolver share, so "is the table there" can never drift from "what the listing shows".
 *
 * ## The three states
 *
 * 1. **Present** — the namespace's listing (the SAME catalog call `tables` uses: same
 *    [Namespaces.route] routing, the dialect's full [DialectAdapter.introspectionTableTypes],
 *    exact-match identifiers) contains the table → the read proceeds exactly as before. An
 *    existing table with zero readable columns, or an engine with no catalog stats, stays an
 *    EMPTY result — empty is valid when the table exists.
 * 2. **Absent** → [DatasourceErrorCodes.TABLE_NOT_FOUND] (404). The message says which of the
 *    two absence kinds applies, per the dialect's [DialectAdapter.introspectionCatalogIsComplete]:
 *    a complete catalog means the table does not exist; a privilege-filtered one means it may
 *    only be invisible to the datasource's credentials. When a listed name is close —
 *    case-folded equality first, then an edit distance of at most [MAX_SUGGESTION_DISTANCE] —
 *    the message names it ("Did you mean …?") and `details` carries it as `suggestion`.
 * 3. **Present but unreadable** — the read itself fails with a permission SQLSTATE →
 *    [DatasourceErrorCodes.TABLE_FORBIDDEN] (403), classified by [isPermissionDenied] at the
 *    statement-executing boundaries (preview rows, `sql_probe`). A permission error on an
 *    absent table cannot happen, so translating only after resolution said present is sound.
 *
 * The LAKE dialect is deliberately out of scope: its registry branches (`lakeColumns`,
 * `lakeTableStats`) keep their own semantics and `datasource.lake_table_not_found`.
 */
internal class TableResolver {
    /**
     * Resolves [table] in [namespace] (the caller's effective filter) against the live catalog.
     * Null when the filter names no real place (a namespace deeper than the dialect's shape —
     * the [Namespaces.route] rule, whose empty result the callers keep); the [ResolvedTable]
     * routing pair when present; throws the catalogued table-not-found when absent.
     */
    fun resolve(
        meta: DatabaseMetaData,
        adapter: DialectAdapter,
        datasource: Datasource,
        namespace: List<String>,
        table: String,
    ): ResolvedTable? {
        val routed = Namespaces.route(adapter.namespaceShape, namespace, meta) ?: return null
        val listed =
            readTables(
                meta,
                adapter,
                routed.first,
                routed.second,
                maxRows = null,
                exemptSchemas = datasource.introspectionIncludeSchemas.toSet(),
            )
        if (listed.tables.any { it.name == table }) return ResolvedTable(routed.first, routed.second)
        throw tableNotFound(adapter, datasource, namespace, table, listed.tables.map { it.name })
    }

    /**
     * The shared getTables walk. [maxRows] caps the iteration at cap+1 `next()` calls (the +1
     * proves truncation); `null` walks everything (the resolver — a truncated listing could
     * report a present table absent).
     */
    fun readTables(
        meta: DatabaseMetaData,
        adapter: DialectAdapter,
        catalog: String?,
        schemaPattern: String?,
        maxRows: Int? = null,
        exemptSchemas: Set<String> = emptySet(),
    ): TablesPage {
        val out = mutableListOf<TableInfo>()
        var truncated = false
        meta.getTables(catalog, schemaPattern, "%", adapter.introspectionTableTypes.toTypedArray()).use { rs ->
            // Two jumps on purpose: system-schema rows are skipped WITHOUT counting against
            // the cap, and the cap+1-th USER row is the truncation proof — checking the cap
            // before the system-row test would flag truncation on a trailing system row.
            @Suppress("LoopWithTooManyJumpStatements")
            while (rs.next()) {
                val namespace = rs.namespaceOf(adapter.namespaceShape)
                if (adapter.isSystemSchema(namespace, exemptSchemas)) continue
                if (maxRows != null && out.size == maxRows) {
                    truncated = true
                    break
                }
                out.add(
                    TableInfo(
                        namespace,
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE"),
                        // Blank remarks are absent (F8's rule): Connector/J reports REMARKS as
                        // "" for every uncommented table — the wire contract is omitted-when-none.
                        rs.getString("REMARKS").asNonBlankOrNull(),
                    ),
                )
            }
        }
        return TablesPage(out, truncated)
    }

    private fun tableNotFound(
        adapter: DialectAdapter,
        datasource: Datasource,
        namespace: List<String>,
        table: String,
        candidates: List<String>,
    ): DatapipelinesException {
        val place = if (namespace.isEmpty()) "datasource '${datasource.name}'" else "namespace '${Namespaces.join(namespace)}'"
        val suggestion = nearestTableName(table, candidates)
        val message =
            buildString {
                if (adapter.introspectionCatalogIsComplete) {
                    append("Table '$table' does not exist in $place.")
                } else {
                    append(
                        "Table '$table' was not found in $place — it does not exist, " +
                            "or this datasource's credentials cannot see it.",
                    )
                }
                suggestion?.let { append(" Did you mean '$it'?") }
            }
        return DatapipelinesException(
            code = DatasourceErrorCodes.TABLE_NOT_FOUND,
            message = message,
            details =
                buildMap {
                    put("datasource", datasource.name)
                    put("table", table)
                    put("namespace", Namespaces.join(namespace))
                    suggestion?.let { put("suggestion", it) }
                },
        )
    }
}

/**
 * The outcome of a successful table resolution (123 §A): the routed JDBC arguments of the
 * namespace the table was found in, ready for `getColumns` — the same pair the tables listing
 * was routed to, so resolution and listing cannot disagree about where a namespace lives.
 */
data class ResolvedTable(
    /** The JDBC catalog argument (a LITERAL — never escaped; see [Namespaces.route]). */
    val catalog: String?,
    /** The JDBC schemaPattern argument (escaped exact-match, like every true pattern argument). */
    val schemaPattern: String?,
)

/**
 * The nearest listed name to [requested], or null when nothing is close: a case-folded equal
 * (the caller asked `Orders`, the engine stores `ORDERS`) beats any edit distance; otherwise
 * the smallest case-folded Levenshtein distance wins, capped at [MAX_SUGGESTION_DISTANCE] so a
 * wild guess offers no suggestion at all.
 */
internal fun nearestTableName(
    requested: String,
    candidates: List<String>,
): String? {
    candidates.firstOrNull { it != requested && it.equals(requested, ignoreCase = true) }?.let { return it }
    val nearest =
        candidates
            .filter { it != requested }
            .minByOrNull { levenshtein(requested.lowercase(), it.lowercase()) }
            ?: return null
    return if (levenshtein(requested.lowercase(), nearest.lowercase()) <= MAX_SUGGESTION_DISTANCE) nearest else null
}

/** The largest edit distance a did-you-mean suggestion may carry (123 §A). */
internal const val MAX_SUGGESTION_DISTANCE = 2

/** Classic two-row Levenshtein — table names are short, so no cutoff trickery. */
internal fun levenshtein(
    a: String,
    b: String,
): Int {
    if (a == b) return 0
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        previous = current
    }
    return previous[b.length]
}

/**
 * [DialectAdapter.introspectionSystemSchemas]: exact names match case-insensitively; an
 * entry ending in `*` matches by case-insensitive PREFIX (Oracle's versioned `apex_*`
 * schemas). Null is never a system schema.
 *
 * [exemptSchemas] is the datasource's `introspection_include_schemas` allowlist (§3.3):
 * a name listed there is NOT a system schema for this datasource, whatever the floor says —
 * the escape hatch for the floors' one blind spot (a prefix entry like `apex_*` hides a
 * customer's own APEX_REPORTING schema). Lowercase-exact, like the stored allowlist.
 */
internal fun DialectAdapter.isSystemSchema(
    namespace: List<String>,
    exemptSchemas: Set<String> = emptySet(),
): Boolean {
    val schema = namespace.lastOrNull() ?: return false
    val lower = schema.lowercase()
    if (exemptSchemas.isNotEmpty()) {
        // §3.3 (087): an allowlist entry may be the bare schema — today's spelling — or the
        // DOTTED namespace, which is the only way to exempt `a1.sales` while leaving
        // `a2.sales` excluded. Both forms are matched here so the two spellings cannot mean
        // different things on different code paths.
        if (lower in exemptSchemas) return false
        if (Namespaces.join(namespace).lowercase() in exemptSchemas) return false
    }
    val dotted = Namespaces.join(namespace).lowercase()
    return introspectionSystemSchemas.any { entry ->
        when {
            // A prefix entry (Oracle's versioned `apex_*`) matches the schema level only.
            entry.endsWith("*") -> lower.startsWith(entry.dropLast(1))

            // A DOTTED floor entry names a whole namespace (087): DuckDB's engine catalogs
            // hold a schema called `main`, and `main` alone is a perfectly ordinary user
            // schema everywhere else — only `system.main` identifies the engine's own.
            entry.contains(Namespaces.SEPARATOR) -> dotted == entry

            else -> lower == entry
        }
    }
}

/**
 * A result row's namespace, outermost first, in this dialect's own vocabulary: the owning
 * catalog (when the shape has a real outer level) then the innermost level from whichever
 * column carries it. Blank segments are dropped — the JDBC `""` sentinel means "objects
 * without a catalog/schema", never a name.
 */
internal fun java.sql.ResultSet.namespaceOf(shape: NamespaceShape): List<String> =
    listOfNotNull(
        if (shape.hasOuterCatalog) getString("TABLE_CAT").asNonBlankOrNull() else null,
        getString(shape.innermostResultColumn).asNonBlankOrNull(),
    )

/** `getSchemas()`'s owning-catalog column, blank-sentinel-filtered. */
internal fun java.sql.ResultSet.catalogOf(): String? = getString("TABLE_CATALOG").asNonBlankOrNull()
