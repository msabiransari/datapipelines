package co.datapipelines.datasources

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

/*
 * The connection's current-namespace read, shared by [SchemaIntrospector.columns] and
 * [TableStatsReader] — the one place a driver-reported current schema/catalog is classified,
 * so two readers cannot drift on what "the driver reports none" versus "the database died"
 * means. Extracted verbatim from SchemaIntrospector (whose KDoc on the classification the
 * comments below carry) when the §7C stats read needed the identical default.
 */

/** SQLState "feature not supported" — the untyped sibling of [SQLFeatureNotSupportedException]. */
internal const val FEATURE_UNSUPPORTED_STATE = "0A000"

/**
 * The ONE blank-sentinel rule at the ResultSet boundary: a value that is null, empty, or
 * whitespace-only means "absent", never a name — drivers report the JDBC `""` sentinel
 * ("objects without a catalog/schema") and some report `" "` just as vacuously. Every
 * site that reads a schema or remark (driver-reported or caller-supplied) routes through
 * this rule so the boundary cannot spell it differently per site.
 */
internal fun String?.asNonBlankOrNull(): String? = this?.takeUnless { it.isBlank() }

/**
 * The connection's current NAMESPACE, in this dialect's own vocabulary: the **catalog** for
 * catalog-routing drivers (Connector/J keeps the current database there and leaves
 * `getSchema()` null), `getSchema()` for everyone else. Null when the driver reports none
 * — and the JDBC blank sentinel counts as none: `""` means "objects without a
 * catalog/schema", not a schema named `""`, so the caller reads unfiltered rather than
 * filtering on a name that matches nothing.
 *
 * Every [SQLException] this read can produce is classified HERE, in ONE place — extending
 * the lease boundary's own [isConnectionFailure] classification, never
 * forking a second one. Three families (R5 F1; the shapes are live-pinned per driver in
 * `EmbeddedDialectBehaviorTest`):
 *
 * 1. **Feature-unsupported** — [SQLFeatureNotSupportedException], or a driver signaling
 *    the same via plain `SQLException` with SQLState `0A000` — reads as null: a legitimate
 *    capability statement ("driver reports none").
 * 2. **Connection loss** — the [isConnectionFailure] family, INCLUDING the
 *    per-driver knowledge the classifier carries (SQLState class 08 + the typed connection
 *    exceptions + SQLite's null-state result codes + H2's closed-object codes + the
 *    DuckDB/SQLite closed-connection lifecycle messages) — becomes
 *    [DatasourceUnreachableException]: the catalogued 502 path, whose recommended
 *    recovery fails honestly on the same dead connection.
 * 3. **Anything left** — a NON-connection failure of the current-schema read itself
 *    (pgjdbc's `getSchema()` executes `select current_schema()` on the server —
 *    bytecode-verified for 42.7.13 — so a statement cancel 57014 or a permission error
 *    arrives here) — is [CurrentSchemaUnknownException] with the driver exception
 *    attached as cause: a catalogued failure whose recovery (pass an explicit schema
 *    filter, which never consults the current schema) works on the live connection.
 *    NEVER a raw rethrow to the surface: the surfaces catch only the two module
 *    exceptions, and a raw driver exception is a 500 / JSON-RPC -32603.
 */
internal fun Connection.currentNamespace(
    adapter: DialectAdapter,
    datasourceName: String,
): List<String> {
    val shape = adapter.namespaceShape
    val innermost = currentInnermost(shape, datasourceName) ?: return emptyList()
    // The outer segment matters as much as the inner one on a multi-catalog connection: a
    // current schema of `sales` with no catalog is what merged two ATTACHed catalogs' tables.
    // `getCatalog()` failing is not fatal here — the one-catalog dialects behave as before.
    val outer = if (shape.hasOuterCatalog) runCatching { catalog }.getOrNull()?.takeUnless { it.isBlank() } else null
    return listOfNotNull(outer, innermost)
}

/** The innermost current level, classified — see [currentNamespace]'s three families. */
private fun Connection.currentInnermost(
    shape: NamespaceShape,
    datasourceName: String,
): String? =
    try {
        if (shape.innermostArrivesInCatalog) catalog else schema
    } catch (_: SQLFeatureNotSupportedException) {
        // The typed capability statement: the driver reports none. Deliberately
        // discarded — the exception type itself is the entire signal.
        null
    } catch (e: SQLException) {
        when {
            e.sqlState == FEATURE_UNSUPPORTED_STATE -> null
            e.isConnectionFailure() -> ConnectionLease.unreachable(datasourceName, e)
            else -> throw CurrentSchemaUnknownException(datasourceName, e)
        }
    }?.asNonBlankOrNull()
