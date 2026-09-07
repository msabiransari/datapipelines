package co.datapipelines.datasources

import java.sql.DatabaseMetaData

/**
 * The §7A namespace vocabulary: how a caller's filter is spelled, and how it reaches JDBC.
 *
 * Split out of [SchemaIntrospector] because it is the one place three surfaces have to agree —
 * the REST endpoints, the MCP tools, and the `introspection_include_schemas` allowlist all parse
 * the same dotted form, and a second parser would be a second dialect of the same string.
 *
 * ## The dotted form
 *
 * `a.b` is the wire's shorthand for `["a", "b"]`: an agent writing a prompt, an operator typing a
 * query parameter, and a `datasources_get_tables` argument all say `analytics.sales` rather than
 * assembling a JSON array. It is a SHORTHAND and not the model — the array form is what the wire
 * carries back, because a segment containing a dot exists (BigQuery dataset names do not admit
 * one, Snowflake's quoted identifiers do) and only the array can express it.
 */
internal object Namespaces {
    /** The dotted-shorthand separator. */
    const val SEPARATOR = '.'

    /**
     * `"a.b"` → `["a", "b"]`. Blank segments are dropped, not preserved: `"a."`, `".b"` and
     * `"a..b"` are typing slips, and a blank segment can match no schema on any dialect, so
     * keeping one would turn a slip into a filter that silently matches nothing.
     */
    fun parse(dotted: String?): List<String> =
        dotted
            ?.split(SEPARATOR)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** `["a", "b"]` → `"a.b"`, the form the allowlist and error messages print. */
    fun join(namespace: List<String>): String = namespace.joinToString(SEPARATOR.toString())

    /**
     * The caller's effective namespace filter from the two accepted spellings.
     *
     * [namespace] (087) wins when present; [schema] is the pre-087 single-segment spelling and
     * keeps working forever (datasources.md §12.1). Both blank/absent means "no filter" — the
     * blank-sentinel rule the whole boundary uses, because Spring binds `?schema=` to a non-null
     * empty string and the JDBC `""` sentinel means "objects without a schema", never a filter.
     *
     * A [schema] that is itself dotted is parsed as a namespace too: an agent handed
     * `a1.sales` by a listing and passing it back through the old parameter gets the place it
     * asked for rather than a search for a schema literally named `a1.sales` (which exists
     * nowhere).
     */
    fun filterOf(
        namespace: List<String>?,
        schema: String?,
    ): List<String> {
        val fromList = namespace.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        return fromList.ifEmpty { parse(schema) }
    }

    /**
     * Where a caller's [filter] goes on this dialect: `(catalog, schemaPattern)`, ready for
     * `getTables`/`getColumns`.
     *
     * Three rules, and the third is the one 087 added:
     *
     * 1. A catalog-routing dialect ([NamespaceShape.innermostArrivesInCatalog]) takes its single
     *    level in the **catalog** argument and leaves the pattern null (Connector/J's default).
     * 2. Everyone else takes the innermost segment in `schemaPattern`, and — new — the
     *    NEXT-OUTER segment in the **catalog** argument. Passing `null` there is what merged two
     *    ATTACHed DuckDB catalogs' same-named schemas into one listing, and what made an
     *    unqualified `getColumns` return both tables' columns as one table's.
     * 3. A filter DEEPER than the dialect's namespace matches nothing — `null` is returned for
     *    the whole routing, and the caller reports an empty page. It cannot name a real place,
     *    and silently dropping its extra segments would answer a different question.
     *
     * The JDBC **catalog argument is a LITERAL** ("must match the catalog name as it is stored"),
     * so it is never escaped — an escaped value there matches nothing, including any MySQL
     * database named with `_`/`%`. Only true pattern arguments get [toExactMatch].
     */
    fun route(
        shape: NamespaceShape,
        filter: List<String>,
        meta: DatabaseMetaData,
    ): Pair<String?, String?>? {
        if (filter.isEmpty()) return null to null
        if (filter.size > shape.labels.size) return null
        val innermost = filter.last()
        if (shape.innermostArrivesInCatalog) return innermost to null
        val outer = filter.getOrNull(filter.size - 2)
        return outer to innermost.toExactMatch(meta.searchStringEscape)
    }

    /**
     * Escapes `_`, `%` and the escape character itself so the string matches **only itself**
     * as a JDBC metadata name pattern (the driver's `getSearchStringEscape` says how to escape).
     * An empty escape string means the driver defines none — the name passes through as-is.
     */
    fun String.toExactMatch(escape: String): String {
        if (escape.isEmpty()) return this
        val escapeChar = escape[0]
        return buildString {
            this@toExactMatch.forEach { ch ->
                if (ch == '_' || ch == '%' || ch == escapeChar) append(escape)
                append(ch)
            }
        }
    }
}
