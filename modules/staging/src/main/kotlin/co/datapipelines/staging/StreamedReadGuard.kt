package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID

/*
 * TEMPORARY (#994): a guard against a defect of the pinned DuckDB JDBC driver (1.5.6.0). A staging database is opened
 * with streamed results (DuckDbStagingFactory, decision 1 of #994), and with streaming on an engine error raised after
 * the first streamed chunk ENDS the cursor: next() returns false and nothing is thrown, so a failed read looks like a
 * finished, shorter one. The driver offers no public signal that tells the two apart (#994 A1). The report for the
 * owner to file is the store's notes/evidence/994-duckdb-streamed-short-result/upstream-report.md.
 *
 * The guard (rule G2 of #994): every production read of a staging cursor is compared with a count that is not a
 * streamed drain — a one-row aggregate from the engine, whose own failure throws — taken in the same snapshot as the
 * read, and a read that ends with a different number of rows fails as `pipeline.staging.result_incomplete`.
 *
 * REMOVE when the driver throws mid-stream: StreamedResultTripwireTest goes red on that driver and names this file.
 */

/**
 * A staging read ended before the rows the engine counted for it (#994, staging.md §8.3): the pinned driver ended a
 * streamed cursor without the engine's error. Details are counts and the reader's name; never a row value, never SQL.
 */
class StagingResultIncompleteException(
    val rowsRead: Long,
    val rowsExpected: Long,
    val reader: String,
) : DatapipelinesException(
        code = StagingErrorCodes.RESULT_INCOMPLETE,
        message =
            "A staging read ($reader) ended after $rowsRead of the $rowsExpected rows the engine counted for it: the " +
                "engine failed while the result was streamed and the driver did not report the failure (#994). Run " +
                "the pipeline again; if it recurs, lower the data the step reads or raise the staging memory limit.",
        details = mapOf("rows_read" to rowsRead, "rows_expected" to rowsExpected, "reader" to reader),
    )

/**
 * The cursor a guarded reader is handed: [delegate] with its rows counted. The read is judged at one moment only —
 * when `next()` returns false: rows taken ≠ [expected] → [StagingResultIncompleteException]. A reader that stops or
 * closes before the end was told nothing false and is not judged.
 *
 * [labels], when given, are the column labels and names the reader's ORIGINAL statement reports (a materialised
 * result renames duplicate labels); every other metadata value is the delegate's. `findColumn` resolves against them.
 */
class CountedResultSet internal constructor(
    private val delegate: ResultSet,
    private val expected: Long,
    private val reader: String,
    private val labels: OriginalLabels? = null,
) : ResultSet by delegate {
    /** Rows the reader has taken so far. */
    var rowsTaken: Long = 0L
        private set

    override fun next(): Boolean {
        if (delegate.next()) {
            rowsTaken++
            return true
        }
        if (rowsTaken != expected) throw StagingResultIncompleteException(rowsTaken, expected, reader)
        return false
    }

    override fun getMetaData(): ResultSetMetaData = labels?.over(delegate.metaData) ?: delegate.metaData

    override fun findColumn(columnLabel: String): Int = labels?.find(columnLabel) ?: delegate.findColumn(columnLabel)
}

/** The labels and names an original statement reports, by position (1-based in JDBC, 0-based here). */
class OriginalLabels(
    private val labels: List<String>,
    private val names: List<String>,
) {
    internal fun over(metadata: ResultSetMetaData): ResultSetMetaData =
        object : ResultSetMetaData by metadata {
            override fun getColumnLabel(column: Int): String = labels[column - 1]

            override fun getColumnName(column: Int): String = names[column - 1]
        }

    internal fun find(label: String): Int {
        val index = labels.indexOfFirst { it.equals(label, ignoreCase = true) }
        if (index < 0) throw SQLException("No column labelled '$label' in the result")
        return index + 1
    }

    companion object {
        /** Reads labels and names from [metadata] (a prepared original's). */
        fun of(metadata: ResultSetMetaData): OriginalLabels {
            val range = 1..metadata.columnCount
            return OriginalLabels(range.map { metadata.getColumnLabel(it) }, range.map { metadata.getColumnName(it) })
        }
    }
}

/** The guard's building blocks (file header). Every statement it builds is made of quoted identifiers and fixed text. */
object StreamedReadGuard {
    /** Prefix of the relations the guard writes a result into (#994 C5); dropped before the lease returns. */
    const val RELATION_PREFIX = "dp_guard_"

    /** Whether reads of [staging] are guarded: only a DuckDB staging database streams its results. */
    fun appliesTo(staging: Staging): Boolean = staging is DuckDbStaging

    /** The count beside a reader's own [sql] (C1–C4): `count(*)` over it. [sql] is DP's statement, never author SQL. */
    fun countOf(sql: String): String = "SELECT count(*) FROM (\n$sql\n) AS dp_guard_count"

    /** The rows the engine counts for [countSql], read from a one-row result: no row is a failure, never 0. */
    fun expectedRows(
        statement: Statement,
        countSql: String,
        reader: String,
    ): Long = statement.executeQuery(countSql).use { rs -> oneRow(rs, "$reader count") { it.getLong(1) } }

    /** A one-row read (C3): [read] on the single row; no row → [StagingResultIncompleteException] (0 of 1). */
    inline fun <T> oneRow(
        rs: ResultSet,
        reader: String,
        read: (ResultSet) -> T,
    ): T {
        if (!rs.next()) throw StagingResultIncompleteException(0, 1, reader)
        return read(rs)
    }

    /** Wraps [rs] so that its end is judged against [expected]. */
    fun counted(
        rs: ResultSet,
        expected: Long,
        reader: String,
        labels: OriginalLabels? = null,
    ): CountedResultSet = CountedResultSet(rs, expected, reader, labels)

    /**
     * Runs [body] — a count and the read it guards — in ONE transaction on [connection], so both see the same snapshot
     * (a staged table changed by another connection in between raises no false alarm). On a connection in auto-commit
     * the transaction is opened here and ended on every exit (commit on success, rollback otherwise) and auto-commit is
     * restored; on a connection that already has a transaction nothing is begun, committed or rolled back.
     */
    inline fun <T> inOneSnapshot(
        connection: Connection,
        body: () -> T,
    ): T {
        if (!connection.autoCommit) return body()
        connection.autoCommit = false
        var committed = false
        try {
            val result = body()
            connection.commit()
            committed = true
            return result
        } finally {
            endSnapshot(connection, committed)
        }
    }

    /** The exit half of [inOneSnapshot]: roll back unless committed, then restore auto-commit; never masks a failure. */
    @PublishedApi
    internal fun endSnapshot(
        connection: Connection,
        committed: Boolean,
    ) {
        try {
            if (!committed) connection.rollback()
        } catch (e: SQLException) {
            log.debug("staging snapshot rollback failed after a failed guarded read: {}", e.message)
        } finally {
            try {
                connection.autoCommit = true
            } catch (e: SQLException) {
                log.debug("staging snapshot could not restore auto-commit: {}", e.message)
            }
        }
    }

    // ------------------------------------------------------------ C5: an author's query over tempdb

    /**
     * The engine's own parse of an author's statement, bound as a value (never concatenated): true when it is exactly
     * one SELECT-shaped statement that is not a SHOW/DESCRIBE/SUMMARIZE — the statements `CREATE TABLE … AS` accepts and
     * whose preparation runs nothing. Anything else (several statements, PRAGMA, EXPLAIN, PIVOT, SHOW…) → false.
     */
    const val MATERIALIZABLE_SQL =
        "SELECT coalesce(json_extract_string(j, '$.error') = 'false' " +
            "AND json_array_length(j, '$.statements') = 1 " +
            "AND coalesce(json_extract_string(j, '$.statements[0].node.from_table.type'), '') <> 'SHOW_REF', false) " +
            "FROM (SELECT json_serialize_sql(?::VARCHAR) AS j)"

    /** Asks the engine whether [authorSql] can be written into a relation ([MATERIALIZABLE_SQL]), on [statement]. */
    fun materializable(
        statement: java.sql.PreparedStatement,
        authorSql: String,
    ): Boolean {
        statement.setString(1, authorSql)
        return statement.executeQuery().use { rs -> oneRow(rs, "materializable") { it.getBoolean(1) } }
    }

    /** A fresh, quoted relation name with [RELATION_PREFIX]. */
    fun newRelation(): String = StagingIdentifiers.quote(RELATION_PREFIX + UUID.randomUUID().toString().replace("-", ""))

    /** `CREATE TABLE <relation> AS <author sql>`: the statement that contains the author's SQL (run on the gated lease). */
    fun materializeSql(
        quotedRelation: String,
        authorSql: String,
    ): String = "CREATE TABLE $quotedRelation AS $authorSql"

    /** The count of the written relation. */
    fun countSqlOf(quotedRelation: String): String = "SELECT count(*) FROM $quotedRelation"

    /**
     * The read of the written relation: a single-table FROM, which the engine reads in insertion order, and the
     * `CREATE TABLE … AS` inserted in the author's order (DuckDB 1.5 "Order Preservation": `preserve_insertion_order`,
     * default true and never set by staging, governs order into a sink, a `CREATE TABLE … AS SELECT` included).
     */
    fun readSqlOf(quotedRelation: String): String = "SELECT * FROM $quotedRelation"

    /** The drop of the written relation. */
    fun dropSqlOf(quotedRelation: String): String = "DROP TABLE IF EXISTS $quotedRelation"

    @PublishedApi
    internal val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(StreamedReadGuard::class.java)
}
