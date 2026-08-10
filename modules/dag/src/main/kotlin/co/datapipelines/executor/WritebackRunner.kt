package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types

/** Streams a DQL node's ResultSet into an external datasource table (dag-executor.md §6.4.3). */
interface WritebackRunner {
    /**
     * Writes every row of [resultSet] into `output.table`, honouring `output.mode`, and returns
     * the row count.
     *
     * @param sourceDialect the dialect the cursor came from. §6.4.3's sketch omits it; it is
     *   required for the same reason `Staging.stage` takes it (staging §3.2): values must be read
     *   through the source dialect's canonical mapping, or a `getObject` on a driver-object column
     *   ships Java identity text into the target table. Reported as a spec clarification.
     */
    fun writeback(
        resultSet: ResultSet,
        output: NodeOutput.Datasource,
        sourceDialect: Dialect,
    ): Long
}

/**
 * The JDBC [WritebackRunner] (§6.4.3).
 *
 * The whole write is **one transaction**: `replace` truncates and inserts together, so a failure
 * never leaves the target table empty. The ResultSet is consumed entirely inside the connection's
 * `use` block — no cursor outlives it.
 *
 * Every identifier this class interpolates (the table name, and the column names taken from
 * result-set metadata) is validated and quoted through [SqlIdentifiers] before it reaches SQL.
 */
class JdbcWritebackRunner(
    private val registry: DatasourceRegistry,
    /**
     * Rows per JDBC batch. Deliberately **not** a config key: configuration.md §3 defines none for
     * write-back, and inventing one would put a second definition of a setting outside its
     * authority (D8).
     */
    private val batchSize: Int = DEFAULT_BATCH_ROWS,
) : WritebackRunner {
    override fun writeback(
        resultSet: ResultSet,
        output: NodeOutput.Datasource,
        sourceDialect: Dialect,
    ): Long {
        val datasource =
            registry.get(output.datasource)
                ?: throw datasourceNotFound(output.datasource)
        // Both identifier guards report the WRITE-BACK phase code: a bad generated identifier here
        // is a write-back failure, never the save-time `pipeline.validation.invalid_identifier`,
        // which is an HTTP-400 code and must not surface mid-execution (§8.2).
        val table = SqlIdentifiers.requireValidTable(output.table, PipelineErrorCodes.Node.WRITEBACK_FAILED)
        val columns = ResultRowReader.schemaOf(resultSet.metaData, sourceDialect).columns
        SqlIdentifiers.validateColumnNames(columns.map { it.name }, PipelineErrorCodes.Node.WRITEBACK_FAILED)

        return registry.poolFor(datasource).leaseConnection().use { connection ->
            connection.autoCommit = false
            try {
                if (output.mode == WriteMode.REPLACE) clearTarget(connection, table)
                val written = streamInsert(connection, table, columns, resultSet)
                connection.commit()
                written
            } catch (e: SQLException) {
                rollbackQuietly(connection)
                throw mapWriteFailure(e, output)
            }
        }
    }

    /**
     * `TRUNCATE`, falling back to `DELETE` for a dialect that does not support it (§6.4.3).
     *
     * The attempt is wrapped in a savepoint: on Postgres a failed statement poisons the whole
     * transaction, so a bare try/catch fallback would commit nothing and report a confusing
     * "current transaction is aborted" instead of the real outcome.
     */
    private fun clearTarget(
        connection: Connection,
        table: String,
    ) {
        val savepoint = connection.setSavepoint("dp_writeback_replace")
        try {
            connection.createStatement().use { it.execute("TRUNCATE TABLE ${SqlIdentifiers.quote(table)}") }
            connection.releaseSavepoint(savepoint)
        } catch (e: SQLException) {
            if (isMissingTable(e)) throw e
            connection.rollback(savepoint)
            connection.createStatement().use { it.execute("DELETE FROM ${SqlIdentifiers.quote(table)}") }
        }
    }

    private fun streamInsert(
        connection: Connection,
        table: String,
        columns: List<ColumnSchema>,
        resultSet: ResultSet,
    ): Long {
        val quoted = columns.joinToString(", ") { SqlIdentifiers.quote(it.name) }
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${SqlIdentifiers.quote(table)} ($quoted) VALUES ($placeholders)"
        return connection.prepareStatement(sql).use { statement ->
            var pending = 0
            var written = 0L
            while (resultSet.next()) {
                bindRow(statement, columns, resultSet)
                statement.addBatch()
                pending++
                if (pending >= batchSize) {
                    written += rowsWritten(statement.executeBatch(), pending)
                    pending = 0
                }
            }
            if (pending > 0) written += rowsWritten(statement.executeBatch(), pending)
            written
        }
    }

    /**
     * Rows actually written by one batch — the **sum of the per-statement counts**, not the number
     * of statements (F16).
     *
     * `executeBatch().size` is the length of the update-count array, i.e. how many statements were
     * submitted. It happens to equal the row count for single-row `INSERT`s, which is why the bug
     * was invisible here, but it is not what the JDBC contract says and it is wrong the moment a
     * trigger or a multi-row form changes the ratio. `SUCCESS_NO_INFO` means "succeeded, count
     * unknown" and is counted as one row; `EXECUTE_FAILED` contributes nothing (and the driver has
     * normally already thrown a `BatchUpdateException` by then, which the caller maps).
     *
     * @param submitted statements in this batch — the fallback when a driver reports no counts.
     */
    private fun rowsWritten(
        counts: IntArray,
        submitted: Int,
    ): Long =
        if (counts.isEmpty()) {
            0L
        } else {
            counts
                .sumOf { count ->
                    when {
                        count >= 0 -> count.toLong()
                        count == Statement.SUCCESS_NO_INFO -> 1L
                        else -> 0L
                    }
                }.coerceAtMost(submitted.toLong() * MAX_ROWS_PER_STATEMENT)
        }

    private fun bindRow(
        statement: PreparedStatement,
        columns: List<ColumnSchema>,
        resultSet: ResultSet,
    ) {
        columns.forEachIndexed { index, column ->
            val value = ResultRowReader.readValue(resultSet, index + 1, column)
            if (value == null) statement.setNull(index + 1, Types.NULL) else statement.setObject(index + 1, value)
        }
    }

    /**
     * The original write failure is what the caller must see. A rollback that also fails is
     * logged and dropped: letting it escape would *replace* the real cause with a secondary one
     * the author cannot act on.
     */
    @Suppress("SwallowedException")
    private fun rollbackQuietly(connection: Connection) {
        try {
            connection.rollback()
        } catch (e: SQLException) {
            LOG.warn("Write-back rollback failed (SQLState {}): {}", e.sqlState, e.message)
        }
    }

    private fun mapWriteFailure(
        cause: SQLException,
        output: NodeOutput.Datasource,
    ): DatapipelinesException =
        if (isMissingTable(cause)) {
            DatapipelinesException(
                code = PipelineErrorCodes.Node.WRITEBACK_TARGET_MISSING,
                message =
                    "Write-back target '${output.table}' does not exist in datasource " +
                        "'${output.datasource}'. Create it with a preceding DDL node, or pre-create it.",
                details = mapOf("datasource" to output.datasource, "table" to output.table),
                cause = cause,
            )
        } else {
            DatapipelinesException(
                code = PipelineErrorCodes.Node.WRITEBACK_FAILED,
                message = "Write-back to '${output.datasource}.${output.table}' failed: ${cause.message}",
                details =
                    mapOf(
                        "datasource" to output.datasource,
                        "table" to output.table,
                        "sql_state" to cause.sqlState,
                    ),
                cause = cause,
            )
        }

    private fun datasourceNotFound(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND,
            message = "Write-back target datasource '$name' is not registered.",
            details = mapOf("datasource" to name),
        )

    /**
     * "Relation does not exist", across the dialects v1 supports.
     *
     * SQLState is the portable signal: `42S02` is the XOPEN/ODBC spelling (MySQL, MSSQL, H2 also
     * report `42S02`), `42P01` is Postgres' own `undefined_table`. Anything else in class 42 is a
     * different syntax/access error and must not be reported as a missing table.
     *
     * (Driver citations in this file refer to the BOM-resolved H2 **2.3.232**.)
     *
     * `42S03` is H2's **table-not-found-with-candidates** state (vendor code 42103), raised when a
     * name resolves to nothing but a case-variant of it exists — exactly what a quoted lowercase
     * `output.table` hits against a target whose author DDL was upper-folded. Verified against the
     * pinned 2.3.232 driver, not recalled. Omitting it reported the single most likely write-back
     * misconfiguration as the generic `writeback_failed`, which tells the author nothing about the
     * one thing they can fix.
     */
    private fun isMissingTable(e: SQLException): Boolean = e.sqlState in MISSING_TABLE_SQL_STATES

    private companion object {
        const val DEFAULT_BATCH_ROWS = 1000

        /** Sanity bound on a driver's self-reported per-statement count. */
        const val MAX_ROWS_PER_STATEMENT = 1_000_000L
        val MISSING_TABLE_SQL_STATES = setOf("42S02", "42S03", "42P01")
        val LOG: Logger = LoggerFactory.getLogger(JdbcWritebackRunner::class.java)
    }
}
