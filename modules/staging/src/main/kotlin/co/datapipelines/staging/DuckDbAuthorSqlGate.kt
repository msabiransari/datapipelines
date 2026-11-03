package co.datapipelines.staging

import java.sql.SQLException

/**
 * The raw-text gate every piece of author SQL passes on a DuckDB staging database before the driver
 * sees it (staging.md §8.5). **TEMPORARY** — an accommodation of a defect in the pinned DuckDB
 * (owner ruling 2026-11-03, lane #795), to be removed when the upstream fix ships.
 *
 * ## Why it exists
 *
 * The locked configuration (`enable_external_access = false`, `lock_configuration = true`) refuses
 * every `SET` and every file access from an author connection, but on the pinned 1.5.5.1 the
 * functions named below are not settings, and the lock does not stop them. The defect is reported
 * to DuckDB through its private channel; its details stay out of this repository until the fix is
 * public. A parse-based refusal is not possible — DuckDB's `json_serialize_sql` parses SELECT-shaped
 * text only, and every tempdb output node is a `CREATE TABLE … AS` — so the gate reads the
 * **raw text**, case-insensitively, strings and comments included, and refuses:
 *
 *  - any occurrence of [REFUSED_NAMES] (the three logging functions and the serialized-SQL executor);
 *  - the word `query` or `query_table` followed by `(` or a comment opener: the two table functions
 *    that run SQL **text**, so `query('SELECT * FROM enable_' || 'logging(…)')` cannot assemble a
 *    refused name the raw text never contains.
 *
 * Of the pinned driver's 943 functions, `query`, `query_table` and `json_execute_serialized_sql` are
 * the only ones that execute SQL text; `DuckDbCarrierCatalogTest` pins that set and goes red if a
 * driver bump adds another.
 *
 * Known false positive, accepted by the ruling: a string or identifier that merely contains a
 * refused name (a column called `enable_logging`) is refused too.
 *
 * The refusal is an [SQLException] with SQLState `42501` (insufficient privilege), so it travels
 * exactly as the engine's own permission refusals do and maps to `query_execution_failed`.
 */
object DuckDbAuthorSqlGate {
    /** Refused anywhere in the text, case-insensitively. */
    val REFUSED_NAMES: List<String> =
        listOf("enable_logging", "disable_logging", "truncate_duckdb_logs", "json_execute_serialized_sql")

    /** The two SQL-text carriers, when called: the name, optionally quoted, then `(` or a comment. */
    val CARRIER_CALL: Regex = Regex("""\bquery(?:_table)?"?\s*(?:\(|/\*|--)""", RegexOption.IGNORE_CASE)

    /** SQLState for the refusal: class 42, insufficient privilege. */
    const val SQL_STATE = "42501"

    /**
     * Passes [sql] or throws the refusal. Never echoes the text: it is the author's own SQL and the
     * refusal names only what was found.
     */
    fun check(sql: String) {
        val lowered = sql.lowercase()
        REFUSED_NAMES.firstOrNull { it in lowered }?.let { throw refusal("`$it`") }
        if (CARRIER_CALL.containsMatchIn(sql)) throw refusal("a call to `query` or `query_table`")
    }

    private fun refusal(what: String): SQLException =
        SQLException(
            "DuckDB staging refuses $what in tempdb SQL. This is a temporary guard against a defect in the " +
                "pinned DuckDB (staging.md §8.5); rewrite the statement without it.",
            SQL_STATE,
        )
}
