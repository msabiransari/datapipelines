package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.persistence.FailureShape
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
import java.util.UUID

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
     * @param workspaceId the execution's workspace (025 A5): the target resolves through
     *   §5.3 visibility — a datasource the workspace cannot see is `datasource_not_found`,
     *   not a silent write.
     * @param transaction the execution family's local write transaction
     *   (pipeline-write-transactions §3.3). When the TARGET is its registration, the write runs
     *   as a participant span on that transaction's one connection — no own lease, no own commit,
     *   no own rollback: the ROOT executor settles the family's writes once. Any other target
     *   (or null) takes today's self-committing path unchanged. Suspend because a participant
     *   span serializes on the transaction's mutex.
     */
    suspend fun writeback(
        resultSet: ResultSet,
        output: NodeOutput.Datasource,
        sourceDialect: Dialect,
        workspaceId: UUID,
        /**
         * Told each measured boundary (149): the target checkout (waiting), each batch's fetch
         * and insert, the commit, and a rollback. [OperationObserver.NONE] by default.
         */
        observer: OperationObserver = OperationObserver.NONE,
        transaction: LocalWriteTransaction? = null,
    ): Long

    /**
     * The already-decoded twin of [writeback]: writes [rows] — canonical values under the
     * canonical [schema] — into `output.table`, honouring `output.mode`, and returns the row
     * count.
     *
     * This is the composition path (design 2026-08-13-pipeline-node-type §4.2): a parent PIPELINE
     * node with `output.target: "datasource"` lands the child's `direct`-streamed rows here. No
     * dialect parameter: decoding and source-dialect mapping happened in the child's executor, so
     * there is no cursor and no metadata left to map — the same relationship
     * `ResultStore.materializeRows` has to `materialize`.
     *
     * @param workspaceId as [writeback]: the execution's workspace, scoping target resolution.
     * @param transaction as [writeback]: the participant span when the target is the family's
     *   registration (the child-sink case — the span is reentrant with the child's own participant
     *   span on the same connection).
     */
    suspend fun writebackRows(
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        output: NodeOutput.Datasource,
        workspaceId: UUID,
        /** As [writeback]; the "fetch" is the pull from [rows]. */
        observer: OperationObserver = OperationObserver.NONE,
        transaction: LocalWriteTransaction? = null,
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
    /**
     * The byte ceiling for the same-registration source drain (§3.3): when a participant write-back
     * must drain its source cursor before reusing the connection, the drained rows are capped at
     * this many UTF-8 JSON bytes. It is the configured result budget
     * (`datapipelines.result.max-size-bytes`, via [co.datapipelines.executor.ResultConfig]) —
     * wired in [co.datapipelines.web.config.EngineConfiguration] — so the drain and the result
     * store cap are the SAME number for the same rows, and a source too large to retain refuses
     * the write before anything is written (§5's "roll back before commit if required rows cannot
     * be retained"). The default here mirrors `ResultConfig`'s own default for test convenience.
     */
    private val drainMaxBytes: Long = DEFAULT_DRAIN_MAX_BYTES,
) : WritebackRunner {
    override suspend fun writeback(
        resultSet: ResultSet,
        output: NodeOutput.Datasource,
        sourceDialect: Dialect,
        workspaceId: UUID,
        observer: OperationObserver,
        transaction: LocalWriteTransaction?,
    ): Long {
        val columns = ResultRowReader.schemaOf(resultSet.metaData, sourceDialect).columns
        if (drainOnParticipantConnection(transaction, output)) {
            val drained = drainResultSet(resultSet, columns, observer)
            return writeAll(output, workspaceId, columns, observer, transaction) { connection, table, dialect ->
                insertRows(connection, table, columns, drained.asSequence(), dialect, observer)
            }
        }
        return writeAll(output, workspaceId, columns, observer, transaction) { connection, table, dialect ->
            streamInsert(connection, table, columns, resultSet, dialect, observer)
        }
    }

    override suspend fun writebackRows(
        schema: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        output: NodeOutput.Datasource,
        workspaceId: UUID,
        observer: OperationObserver,
        transaction: LocalWriteTransaction?,
    ): Long {
        if (drainOnParticipantConnection(transaction, output)) {
            val drained = drainSequence(rows, schema, observer)
            return writeAll(output, workspaceId, schema, observer, transaction) { connection, table, dialect ->
                insertRows(connection, table, schema, drained.asSequence(), dialect, observer)
            }
        }
        return writeAll(output, workspaceId, schema, observer, transaction) { connection, table, dialect ->
            insertRows(connection, table, schema, rows, dialect, observer)
        }
    }

    /**
     * §3.3: "when source and destination are the same registration, stage or drain input under
     * existing limits before reusing the connection." True exactly when the write-back's TARGET is
     * the family's registration AND the calling coroutine already holds that registration's span —
     * which is the one way the source cursor can be on the same physical connection as the target
     * write. A tempdb source (or a source on another datasource) reaches [writeback] from OUTSIDE
     * any span, so it stays on the streaming path and needs no drain.
     */
    private suspend fun drainOnParticipantConnection(
        transaction: LocalWriteTransaction?,
        output: NodeOutput.Datasource,
    ): Boolean = transaction?.takeIf { it.registration == output.datasource }?.inParticipantSpan() == true

    /**
     * Drains [resultSet] into canonical rows under the [drainMaxBytes] ceiling, closing the cursor
     * once exhausted — so the caller can write on that same connection with no open cursor. Reads
     * through [ResultRowReader] exactly as [streamInsert]'s [bindRow] does, so the drained values
     * and the streamed values are identical byte-for-byte on the target.
     */
    private fun drainResultSet(
        resultSet: ResultSet,
        columns: List<ColumnSchema>,
        observer: OperationObserver,
    ): List<List<Any?>> {
        val drained = ArrayList<List<Any?>>()
        var bytes = 0L
        observer.phase(OperationPhase.FETCHING)
        while (resultSet.next()) {
            val row = columns.mapIndexed { index, column -> ResultRowReader.readValue(resultSet, index + 1, column) }
            bytes += ResultBytes.rowBytes(row, columns)
            if (bytes > drainMaxBytes) throw drainTooLarge()
            drained += row
        }
        resultSet.close()
        observer.fetched(drained.size.toLong())
        return drained
    }

    /** [drainResultSet]'s already-decoded twin for the composition path's lazy child sequence. */
    private fun drainSequence(
        rows: Sequence<List<Any?>>,
        columns: List<ColumnSchema>,
        observer: OperationObserver,
    ): List<List<Any?>> {
        val drained = ArrayList<List<Any?>>()
        var bytes = 0L
        observer.phase(OperationPhase.FETCHING)
        for (row in rows) {
            bytes += ResultBytes.rowBytes(row, columns)
            if (bytes > drainMaxBytes) throw drainTooLarge()
            drained += row
        }
        observer.fetched(drained.size.toLong())
        return drained
    }

    /** The drain ceiling refusal — raised before any write, so the target is untouched. */
    private fun drainTooLarge(): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.WRITEBACK_FAILED,
            message =
                "The same-registration write-back source exceeds $drainMaxBytes bytes and cannot " +
                    "be retained before writing; split it or stage it through tempdb",
            details = mapOf("max_bytes" to drainMaxBytes),
        )

    /**
     * The shared write-back shell (§6.4.3): datasource resolution, the identifier guards, and the
     * single-transaction write (`REPLACE` truncates and inserts together, so a failure never
     * leaves the target table empty) around whichever row source [insert] drains.
     *
     * Resolution is workspace-scoped and LIVE (025 A5, design §5.3/§6): the target must be
     * VISIBLE to the execution's workspace at write time, else `datasource_not_found`. The live
     * read (`getVisibleLive`, past the §6.3 cache) is what makes a grant revoked mid-run — after
     * the family already enlisted on an earlier write — refuse HERE rather than sail through on a
     * stale cached entry: "if authorization is withdrawn and a required write is refused, fail and
     * roll back" (§6). Distinct from [refuseIfReadonly]'s readonly-flag/soft-delete live read.
     *
     * ## The participant branch (write-transactions §3.3/§3.5)
     *
     * When [transaction] names THIS target as its registration, the write runs as a participant
     * span on the family's one connection: no own lease, `autoCommit` untouched (it is already
     * off), no commit and no rollback — the ROOT settles the family's writes once, after every
     * required node succeeds. `REPLACE` clears with a plain `DELETE`, never `TRUNCATE` (§5: an
     * implicit-commit TRUNCATE would end the transaction mid-run; the transactional DELETE is
     * the only legal clear there). No `observer.committed()` either: until the root confirms,
     * committed evidence stays withheld (#1035 publishes it after confirmation).
     *
     * `ThrowsCount` is suppressed for this function: each throw is its own catalogued refusal
     * (not-found, readonly, identifier, target) — the count is the contract.
     */
    @Suppress("ThrowsCount")
    private suspend fun writeAll(
        output: NodeOutput.Datasource,
        workspaceId: UUID,
        columns: List<ColumnSchema>,
        observer: OperationObserver,
        transaction: LocalWriteTransaction?,
        insert: (Connection, String, Dialect) -> Long,
    ): Long {
        val datasource =
            registry.getVisibleLive(output.datasource, workspaceId)
                ?: throw datasourceNotFound(output.datasource)
        refuseIfReadonly(output)
        // Both identifier guards report the WRITE-BACK phase code: a bad generated identifier here
        // is a write-back failure, never the save-time `pipeline.validation.invalid_identifier`,
        // which is an HTTP-400 code and must not surface mid-execution (§8.2).
        val table = SqlIdentifiers.requireValidTable(output.table, PipelineErrorCodes.Node.WRITEBACK_FAILED)
        SqlIdentifiers.validateColumnNames(columns.map { it.name }, PipelineErrorCodes.Node.WRITEBACK_FAILED)

        val participant = transaction?.takeIf { it.registration == output.datasource }
        if (participant != null) {
            return participant.withParticipant { connection ->
                // §6.4.3 (087) still applies on the participant path: identifiers are quoted in the
                // vocabulary of the connection the write lands on — the transaction's snapshot,
                // not a freshly resolved entry.
                val dialect = participant.dialect ?: datasource.dialect
                try {
                    if (output.mode == WriteMode.REPLACE) {
                        observer.phase(OperationPhase.WRITING)
                        clearTarget(connection, table, dialect, transactionalOnly = true)
                    }
                    val written = insert(connection, table, dialect)
                    observer.phase(OperationPhase.FINALIZING)
                    written
                } catch (e: SQLException) {
                    // No rollback attempt and no rolledBack report: the ROOT decides the family's
                    // outcome, and a failed node is a rollback the root performs (§4 row 3).
                    throw mapWriteFailure(e, output)
                }
            }
        }

        // §6.4.3 (087): identifiers are quoted in the TARGET dialect's vocabulary, not the SQL
        // standard's — MySQL needs backticks unless the server runs with ANSI_QUOTES, which the
        // adapter cannot know, and MSSQL needs brackets. Resolved from the datasource the gate
        // above returned, so the quoting and the connection can never describe different engines.
        val dialect = datasource.dialect
        // 149 §3: requesting the target connection is `waiting_output`; holding it and clearing
        // the table is `writing`; the commit is `finalizing`. Reported around the JDBC calls,
        // never inside a pool's own lock.
        observer.phase(OperationPhase.WAITING_OUTPUT)
        return registry.poolFor(datasource).leaseConnection().use { connection ->
            connection.autoCommit = false
            var commitAttempted = false
            try {
                if (output.mode == WriteMode.REPLACE) {
                    observer.phase(OperationPhase.WRITING)
                    clearTarget(connection, table, dialect, transactionalOnly = false)
                }
                val written = insert(connection, table, dialect)
                observer.phase(OperationPhase.FINALIZING)
                commitAttempted = true
                connection.commit()
                observer.committed()
                written
            } catch (e: SQLException) {
                // The rollback is attempted on every failure (transaction policy unchanged), but it
                // is EVIDENCE of an undo only when the failure came BEFORE the commit attempt. A
                // `commit()` that throws is ambiguous — the transaction may be durable and only its
                // acknowledgement lost — and a `rollback()` that "succeeds" afterwards undoes
                // nothing; reporting it would tell the user to retry a write that landed (R149-4).
                // With no report either way the tracker leaves the outcome UNKNOWN.
                val undone = rollbackQuietly(connection) && !commitAttempted
                if (undone) observer.rolledBack()
                throw mapWriteFailure(e, output)
            }
        }
    }

    /**
     * Workspaces design §6 layer 2a (D10): the live-entry readonly re-check, in the one shell
     * BOTH row sources funnel through — the cursor [writeback] path and the composition
     * [writebackRows] path — so every `output.target: "datasource"` write is behind the
     * backstop regardless of who produced the rows. Reads the LIVE registry entry (past the
     * metadata cache), so a flip between save and execution fails here.
     *
     * Fail-closed (044 F2/F3): a null live read (the row was soft-deleted out of band — the
     * D10 channel) refuses as `datasource_not_found`, never "no signal"; a metadata-DB
     * failure during the read refuses naming the METADATA db — see [ReadonlyBackstop].
     */
    private fun refuseIfReadonly(output: NodeOutput.Datasource) {
        when (ReadonlyBackstop.signal(registry, output.datasource)) {
            ReadonlySignal.READONLY -> throw datasourceReadonly(output.datasource, output.table)
            ReadonlySignal.ABSENT -> throw datasourceNotFound(output.datasource)
            ReadonlySignal.WRITABLE -> Unit
        }
    }

    /**
     * `TRUNCATE`, falling back to `DELETE` for a dialect that does not support it (§6.4.3).
     *
     * The attempt is wrapped in a savepoint: on Postgres a failed statement poisons the whole
     * transaction, so a bare try/catch fallback would commit nothing and report a confusing
     * "current transaction is aborted" instead of the real outcome.
     *
     * [transactionalOnly] is the participant path (write-transactions §5): the clear runs inside
     * the family's still-open local transaction, where an implicit-commit `TRUNCATE` would END
     * that transaction mid-run — so it is a plain `DELETE` there, always, with no savepoint
     * dance around an attempt that must never be made.
     */
    private fun clearTarget(
        connection: Connection,
        table: String,
        dialect: Dialect,
        transactionalOnly: Boolean,
    ) {
        if (transactionalOnly) {
            connection.createStatement().use { it.execute("DELETE FROM ${SqlIdentifiers.quote(table, dialect)}") }
            return
        }
        val savepoint = connection.setSavepoint("dp_writeback_replace")
        try {
            connection.createStatement().use { it.execute("TRUNCATE TABLE ${SqlIdentifiers.quote(table, dialect)}") }
            connection.releaseSavepoint(savepoint)
        } catch (e: SQLException) {
            if (isMissingTable(e)) throw e
            connection.rollback(savepoint)
            connection.createStatement().use { it.execute("DELETE FROM ${SqlIdentifiers.quote(table, dialect)}") }
        }
    }

    private fun streamInsert(
        connection: Connection,
        table: String,
        columns: List<ColumnSchema>,
        resultSet: ResultSet,
        dialect: Dialect,
        observer: OperationObserver,
    ): Long {
        val quoted = columns.joinToString(", ") { SqlIdentifiers.quote(it.name, dialect) }
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${SqlIdentifiers.quote(table, dialect)} ($quoted) VALUES ($placeholders)"
        return connection.prepareStatement(sql).use { statement ->
            var pending = 0
            var written = 0L
            observer.phase(OperationPhase.FETCHING)
            while (resultSet.next()) {
                bindRow(statement, columns, resultSet)
                statement.addBatch()
                pending++
                if (pending >= batchSize) {
                    written += executeBatch(statement, pending, observer)
                    pending = 0
                }
            }
            if (pending > 0) written += executeBatch(statement, pending, observer)
            written
        }
    }

    /**
     * One batch's boundaries (149): the rows bound so far were FETCHED; the `executeBatch` is
     * the WRITE; the count the driver reports is what the target accepted. The observer is
     * returned to `fetching` afterwards because the next cursor read follows at once.
     */
    private fun executeBatch(
        statement: PreparedStatement,
        pending: Int,
        observer: OperationObserver,
    ): Long {
        observer.fetched(pending.toLong())
        observer.phase(OperationPhase.WRITING)
        val written = rowsWritten(statement.executeBatch(), pending)
        observer.written(written)
        observer.phase(OperationPhase.FETCHING)
        return written
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

    /**
     * The [streamInsert] twin for already-decoded canonical rows (design §4.2's composition
     * path): same statement shape, same batching, same per-statement-count summing — only the
     * value source differs (a sequence, not a cursor).
     */
    private fun insertRows(
        connection: Connection,
        table: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
        dialect: Dialect,
        observer: OperationObserver,
    ): Long {
        val quoted = columns.joinToString(", ") { SqlIdentifiers.quote(it.name, dialect) }
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${SqlIdentifiers.quote(table, dialect)} ($quoted) VALUES ($placeholders)"
        return connection.prepareStatement(sql).use { statement ->
            var pending = 0
            var written = 0L
            observer.phase(OperationPhase.FETCHING)
            rows.forEach { row ->
                require(row.size == columns.size) {
                    "Row has ${row.size} values for ${columns.size} columns of '$table'"
                }
                row.forEachIndexed { index, value ->
                    if (value == null) statement.setNull(index + 1, Types.NULL) else statement.setObject(index + 1, value)
                }
                statement.addBatch()
                pending++
                if (pending >= batchSize) {
                    written += executeBatch(statement, pending, observer)
                    pending = 0
                }
            }
            if (pending > 0) written += executeBatch(statement, pending, observer)
            written
        }
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
    private fun rollbackQuietly(connection: Connection): Boolean =
        try {
            connection.rollback()
            true
        } catch (e: SQLException) {
            LOG.warn("Write-back rollback failed error={} sql_state={}", FailureShape.cause(e), FailureShape.sqlState(e))
            false
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
     * §13.4 sibling of `datasource_not_found`: the write-back target resolved at write-time,
     * but its live entry is readonly now (D10 flip or a stored version that predates the flag).
     */
    private fun datasourceReadonly(
        name: String,
        table: String,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Node.DATASOURCE_READONLY,
        message =
            "Write-back target datasource '$name' is readonly — writing '$table' to it is forbidden " +
                "(the flag was set after this pipeline version was saved, or the version predates it).",
        details = mapOf("datasource" to name, "table" to table),
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
     *
     * `42S04` is H2's third spelling of the same condition — "table not found (**this database is
     * empty**)", vendor code 42104: a target schema with no tables at all, the commonest first-run
     * shape for a write-back target. Verified against the same pinned driver; the actionable code
     * applies to it exactly as much.
     */
    private fun isMissingTable(e: SQLException): Boolean = e.sqlState in MISSING_TABLE_SQL_STATES

    private companion object {
        const val DEFAULT_BATCH_ROWS = 1000

        /** The same-registration drain ceiling, matching `datapipelines.result.max-size-bytes` default. */
        const val DEFAULT_DRAIN_MAX_BYTES = 104_857_600L

        /** Sanity bound on a driver's self-reported per-statement count. */
        const val MAX_ROWS_PER_STATEMENT = 1_000_000L
        val MISSING_TABLE_SQL_STATES = setOf("42S02", "42S03", "42S04", "42P01")
        val LOG: Logger = LoggerFactory.getLogger(JdbcWritebackRunner::class.java)
    }
}
