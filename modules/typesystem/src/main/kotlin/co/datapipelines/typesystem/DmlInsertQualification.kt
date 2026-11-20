package co.datapipelines.typesystem

/**
 * Which dialects a DML `insert` node may run against (pipeline-contract §4.4, write-transactions
 * record §5): "an unqualified combination is refused".
 *
 * An insert returns each input row with its logical `id`, read from the driver's generated-key
 * cursor for the ONE declared key column (`prepareStatement(sql, arrayOf(column))`). Only a
 * dialect whose pinned driver was MEASURED to return that column — under its own label, with
 * both a database-generated and an author-supplied value — is qualified; every other dialect is
 * refused before any statement is prepared, never guessed at by row order or `MAX(key)`.
 *
 * Measured (lane #1034, 2026-11-20): H2 2.3.232 returns the named column (label upper-cased);
 * DuckDB 1.5.6.0 returns it, including a value past 2^53; PostgreSQL (pgjdbc, `RETURNING`)
 * returns it in `DmlOperationResultsIntegrationTest`. SQLite 3.49.1.0 returns
 * `last_insert_rowid()` — the rowid, not the named column — so it stays refused. MySQL, SQL
 * Server and Oracle are qualified by #1036's real-driver lane, which flips their rows here with
 * its evidence. LAKE grants no write capability (record §3.4).
 *
 * An exhaustive `when`, keyed by the dialect alone, for the reason [Dialect]'s KDoc gives for
 * every per-dialect total map: a new dialect cannot compile without a decision here.
 */
object DmlInsertQualification {
    fun isQualified(dialect: Dialect): Boolean =
        when (dialect) {
            Dialect.H2, Dialect.DUCKDB, Dialect.POSTGRES -> true
            Dialect.MYSQL, Dialect.MSSQL, Dialect.ORACLE, Dialect.SQLITE, Dialect.LAKE -> false
        }
}
