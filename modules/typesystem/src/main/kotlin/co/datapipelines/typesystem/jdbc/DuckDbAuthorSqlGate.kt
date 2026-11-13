package co.datapipelines.typesystem.jdbc

import java.sql.SQLException

/**
 * The raw-text gate every piece of author SQL passes on an in-process DuckDB engine before the
 * driver sees it: every leased connection of a `DUCKDB` or `LAKE` datasource (datasources.md §5.8)
 * and the DuckDB staging database's author leases (staging.md §8.5). **TEMPORARY** — an
 * accommodation of a defect in the pinned DuckDB (owner rulings 2026-11-03, lanes #795 and #816), to
 * be removed when the upstream fix ships.
 *
 * ## Why it lives in `typesystem`
 *
 * Its two callers are `staging` and `datasources`, and module-structure.md §5.4 gives them exactly
 * one shared module: this one (the same reasoning as [co.datapipelines.typesystem.H2RestrictedSession]).
 * The code is pure JDK — `java.sql` and `kotlin.text` — no driver import.
 *
 * ## Why it exists
 *
 * The locked configuration (`enable_external_access = false`, `lock_configuration = true`) refuses
 * every `SET` and every file access from an author connection, but on the pinned 1.5.6.0 (re-probed 2026-11-13) the
 * functions named below are not settings, and the lock does not stop them. The defect is reported
 * to DuckDB through its private channel; its details stay out of this repository until the fix is
 * public. A parse-based refusal is not possible — DuckDB's `json_serialize_sql` parses SELECT-shaped
 * text only, and every tempdb output node is a `CREATE TABLE … AS` — so the gate reads the
 * **raw text**, case-insensitively, strings and comments included, and refuses:
 *
 *  - any occurrence of [REFUSED_NAMES] (the engine functions that reach the defect, directly or
 *    through serialized SQL);
 *  - a call to either of the engine's two SQL-text table functions ([CARRIER_CALL]): the name,
 *    optionally closed by a double quote, followed by any run of [SEPARATOR] characters and then `(`
 *    or a comment opener. They run SQL **text**, so without this rule a statement could assemble a
 *    refused name by concatenation that its raw text never contains.
 *
 * ## The separator class
 *
 * The pinned engine's tokenizer accepts 23 code points between a function name and its argument
 * list: the five ASCII ones (TAB, LF, FF, CR, SPACE) and eighteen non-ASCII ones (no-break space,
 * the general-punctuation spaces and zero-width characters, the ideographic space and the byte-order
 * mark). Java's `\s` is ASCII-only, so [SEPARATOR] is the union `\s`, every Unicode separator
 * (`Z`), every format character (`Cf`) and every control character (`Cc`) — a superset of the 23;
 * a character the engine does not accept there can only make the gate refuse more, never less.
 * `DuckDbAuthorSqlGuardSweepTest` (module `datasources`) asks the pinned engine itself, for every
 * code point up to U+2FFFF, whether it runs the call form, and requires the gate to refuse every
 * form it runs.
 *
 * Of the pinned driver's 943 functions, three execute SQL text — the two carriers and one of
 * [REFUSED_NAMES]; `DuckDbCarrierCatalogTest` pins that set and goes red if a driver bump adds
 * another.
 *
 * Known false positive, accepted by the ruling: a string or identifier that merely contains a
 * refused name (a column so named) is refused too.
 *
 * The refusal is a [DuckDbAuthorSqlRefusal]: an [SQLException] with SQLState `42501`, so it travels
 * every boundary a driver refusal travels and maps to `pipeline.node.query_execution_failed`
 * carrying the refusal's text. Its own type lets a privilege classifier (the datasources
 * module's `isPermissionDenied`) tell it from an engine's `insufficient_privilege`, which would
 * otherwise answer `datasource.table_forbidden` — a credentials diagnosis for a statement the
 * author must rewrite (lane #816).
 */
object DuckDbAuthorSqlGate {
    /** Refused anywhere in the text, case-insensitively. */
    val REFUSED_NAMES: List<String> =
        listOf("enable_logging", "disable_logging", "truncate_duckdb_logs", "json_execute_serialized_sql")

    /**
     * One character that may separate a carrier's name from its argument list: ASCII whitespace,
     * or any Unicode separator, format or control character (see the class KDoc).
     */
    const val SEPARATOR: String = """[\s\p{Z}\p{Cf}\p{Cc}]"""

    /** The two SQL-text carriers, when called: the name, optionally quoted, then `(` or a comment. */
    val CARRIER_CALL: Regex = Regex("""\bquery(?:_table)?"?$SEPARATOR*(?:\(|/\*|--)""", RegexOption.IGNORE_CASE)

    /** SQLState for the refusal: class 42, insufficient privilege. */
    const val SQL_STATE = "42501"

    /**
     * Passes [sql] or throws the refusal. Never echoes the text: it is the author's own SQL and the
     * refusal names only what was found.
     */
    fun check(sql: String) {
        val lowered = sql.lowercase()
        REFUSED_NAMES.firstOrNull { it in lowered }?.let { throw refusal("`$it`") }
        // The literal pre-filter keeps the regex off the common statement: text whose lower-cased
        // form never spells the carrier word cannot match it. Lower-casing first is safe in this
        // direction: it can only turn more text into the word, never less.
        if (CARRIER_WORD in lowered && CARRIER_CALL.containsMatchIn(lowered)) throw refusal("a call to `query` or `query_table`")
    }

    private const val CARRIER_WORD = "query"

    private fun refusal(what: String): DuckDbAuthorSqlRefusal =
        DuckDbAuthorSqlRefusal(
            "DuckDB refuses $what in author SQL. This is a temporary guard against a defect in the " +
                "pinned DuckDB; rewrite the statement without it.",
        )
}

/**
 * [DuckDbAuthorSqlGate]'s refusal, SQLState [DuckDbAuthorSqlGate.SQL_STATE]. Only the gate builds
 * one; a classifier recognises it by type, never by message.
 */
class DuckDbAuthorSqlRefusal internal constructor(
    message: String,
) : SQLException(message, DuckDbAuthorSqlGate.SQL_STATE)
