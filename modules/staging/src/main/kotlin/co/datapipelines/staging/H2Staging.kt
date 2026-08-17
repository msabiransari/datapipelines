package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.H2EgressMapper
import co.datapipelines.typesystem.LogicalTypeMapping
import co.datapipelines.typesystem.MappedColumn
import co.datapipelines.typesystem.TypeMappers
import co.datapipelines.typesystem.TypeMappingWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.util.UUID

/**
 * The H2 implementation of [Staging] (staging.md §3, §4, §8, §9).
 *
 * One instance owns one JDBC connection to a per-execution in-memory H2 database and
 * serializes every access to it through a single [Mutex] (§9.2 — a JDBC `Connection` is not
 * safe for concurrent callers, and the executor runs nodes concurrently). The connection is
 * opened by [H2StagingFactory], held open for the whole execution (that is what keeps the
 * in-memory database alive, §3.5), and released by [close].
 *
 * Both the connection and the mutex are **private**. Callers that need raw SQL go through
 * [withConnection], which takes the lock around their block; there is no way to obtain the
 * connection without the lock, and no way to observe or acquire the lock separately. [close]
 * is the one path that touches the connection outside the mutex — by contract it runs in the
 * executor's `finally` after every staging operation has returned (§3.4, §3.5).
 *
 * ## Source type mapping
 *
 * [stage] resolves each source column's canonical type through the **source dialect's** mapper
 * ([TypeMappers.forDialect]) — never H2's. A Postgres/Oracle/MySQL source's JDBC codes and type
 * names do not mean what H2's mean (Oracle `DATE` is a timestamp, MySQL `bit(n>1)` is binary),
 * so mapping source metadata through the H2 table picks the wrong storage type and loses data
 * *before* egress re-derivation can see it (§3.2). `mapColumn` (not `map`) is used so an
 * unknown source type's §8.2 warning can name its column; those warnings ride out on
 * [StageResult.warnings] and never fail the node.
 */
class H2Staging internal constructor(
    override val executionId: UUID,
    private val connection: Connection,
    private val config: H2StagingProperties,
) : Staging {
    private val mutex = Mutex()

    /** Table names staged this execution — the deterministic guard for `table_already_exists`. */
    private val stagedTables = linkedSetOf<String>()

    /** Observability counter (§8.2): total rows staged, reported by [stats], never a limit input. */
    private var stagedRowTotal = 0L

    override suspend fun <T> withConnection(block: suspend (Connection) -> T): T = mutex.withLock { block(connection) }

    override suspend fun stage(
        resultSet: ResultSet,
        tableName: String,
        sourceDialect: Dialect,
    ): StageResult =
        mutex.withLock {
            val metadata = resultSet.metaData
            val indices = 1..metadata.columnCount

            // Column labels come from user SQL — validate before they touch generated DDL (§4.5).
            val columnNames = StagingIdentifiers.validateColumnNames(indices.map { metadata.getColumnLabel(it) })
            val mapped = columnNames.mapIndexed { i, name -> mapSourceColumn(sourceDialect, name, metadata, i + 1) }
            val columns = mapped.map { it.column }
            // Flattened in column order, one per affected column; never fatal (§8.2).
            val warnings = mapped.flatMap { it.warnings }.map { it.withBoundedSourceType() }
            val mappings = columns.map { LogicalTypeMapping(it.type, it.precision, it.scale) }

            createTable(tableName, columns)
            // Any failure past CREATE TABLE leaves a partial table and a claimed name behind;
            // undo both so a P4 retry of this node is not poisoned by its own first attempt.
            val rowsStaged =
                try {
                    batchInsert(tableName, columns, mappings, resultSet).also { checkMemoryBudget() }
                } catch (e: SQLException) {
                    // A driver fault the value-overflow mapping did not claim (§4.3).
                    rollbackStagedTable(tableName)
                    throw e
                } catch (e: DatapipelinesException) {
                    // value_overflow (§4.3) or memory_limit_exceeded (§8.2) — both leave a table.
                    rollbackStagedTable(tableName)
                    throw e
                }
            // Counted only once the whole operation succeeded — a failed stage must not inflate
            // the observability total it reports through stats().
            stagedRowTotal += rowsStaged

            StageResult(tableName, rowsStaged, columns, warnings)
        }

    override suspend fun stageRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    ): StageResult =
        mutex.withLock {
            createTable(tableName, columns)
            // Same partial-table rollback as stage(): a failure mid-stream leaves no table and
            // no claimed name behind, so the parent node never treats a half-written tempdb
            // table as success and a retry of the node starts clean.
            val rowsStaged =
                try {
                    batchInsertRows(tableName, columns, rows).also { checkMemoryBudget() }
                } catch (e: CancellationException) {
                    // Cancellation is not a staging failure — but it must not strand a partial
                    // table either; roll back, then let it propagate untouched.
                    rollbackStagedTable(tableName)
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // Wider than stage()'s SQLException/DatapipelinesException pair on purpose:
                    // the row sequence is lazy over the CHILD's cursor, so a child-side fault of
                    // any shape surfaces here mid-insert — and a partial table must never survive
                    // it (a half-written tempdb table read as success is the failure mode this
                    // rollback exists to prevent).
                    rollbackStagedTable(tableName)
                    throw e
                }
            stagedRowTotal += rowsStaged
            StageResult(tableName, rowsStaged, columns)
        }

    override suspend fun <T> withQuery(
        sql: String,
        block: suspend (ResultSet) -> T,
    ): T =
        mutex.withLock {
            connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
                stmt.queryTimeout = config.queryTimeoutSeconds
                stmt.fetchSize = config.resultBatchSize
                // Retained from the returning-cursor shape: a cursor closed inside the block
                // takes its statement with it, and `use` closes the statement regardless (§3.3).
                stmt.closeOnCompletion()
                block(stmt.executeQuery(sql))
            }
        }

    override suspend fun execute(sql: String): Long =
        mutex.withLock {
            val affected = connection.createStatement().use { it.executeUpdate(sql) }.toLong()
            // execute() may write to staging; measure the footprint after it (§8.2).
            checkMemoryBudget()
            affected
        }

    override suspend fun stats(): StagingStats =
        mutex.withLock {
            StagingStats(
                // Read from the catalog, not from `stagedTables`: §10 says "current tables", and
                // SQL nodes create tables through execute()/withConnection() that the in-process
                // set never sees. The set stays the deterministic duplicate-name guard (§4.5).
                tableCount = measureTableCount(),
                totalRows = stagedRowTotal,
                memoryUsedBytes = measureUsedHeapKb() * BYTES_PER_KB,
            )
        }

    override fun close() {
        try {
            // Belt: release every staged table's memory immediately and deterministically.
            dropStagedTables()
        } catch (e: SQLException) {
            // pipeline.staging.cleanup_failed — logged, never rethrown from close() (§3.4).
            log.warn("tempdb table cleanup failed for execution {}: {}", executionId, e.message)
        } finally {
            try {
                // Braces: closing the only connection destroys the in-memory database (§3.4).
                connection.close()
            } catch (e: SQLException) {
                log.warn("tempdb connection close failed for execution {}: {}", executionId, e.message)
            }
        }
    }

    /**
     * Resolves one source column through [sourceDialect]'s mapper (§3.2). `mapColumn` carries
     * the column name so an unknown source type's §8.2 warning can name it — `map` cannot.
     */
    private fun mapSourceColumn(
        sourceDialect: Dialect,
        name: String,
        metadata: ResultSetMetaData,
        index: Int,
    ): MappedColumn =
        TypeMappers.forDialect(sourceDialect).mapColumn(
            name = name,
            sqlType = metadata.getColumnType(index),
            precision = metadata.getPrecision(index),
            scale = metadata.getScale(index),
            typeName = metadata.getColumnTypeName(index),
        )

    /**
     * Issues a **bare** `CREATE TABLE` (never `IF NOT EXISTS`, never an implicit `DROP`, §4.5).
     * A name already staged this execution fails loudly rather than overwriting a table another
     * node may be about to read.
     *
     * Two guards, because the in-process set alone is not sufficient: SQL nodes create tables
     * through `execute()` / `withConnection()`, which `stagedTables` never sees, so the
     * database's own duplicate-object rejection is mapped to the same catalogued error rather
     * than escaping as a raw driver `SQLException` the executor cannot classify.
     */
    private fun createTable(
        tableName: String,
        columns: List<ColumnSchema>,
    ) {
        if (!stagedTables.add(tableName)) throw StagingTableAlreadyExistsException(tableName)
        val decls = columns.joinToString(", ") { c -> "${StagingIdentifiers.quote(c.name)} ${H2EgressMapper.toH2Type(c)}" }
        val ddl = "CREATE TABLE ${StagingIdentifiers.quote(tableName)} ($decls)"
        try {
            connection.createStatement().use { it.execute(ddl) }
        } catch (e: SQLException) {
            // Roll back the bookkeeping so the failure is not double-counted as "staged", and
            // never drop the pre-existing table — it may be one another node is about to read.
            stagedTables.remove(tableName)
            throw asStagingFailure(e, tableName)
        }
    }

    /**
     * Translates a `CREATE TABLE` fault into the catalogued duplicate-table error when the
     * database says the object already exists, and leaves anything else exactly as it was.
     */
    private fun asStagingFailure(
        e: SQLException,
        tableName: String,
    ): Exception = if (e.sqlState == DUPLICATE_OBJECT_STATE) StagingTableAlreadyExistsException(tableName) else e

    /**
     * Undoes a partially staged table after a post-`CREATE TABLE` failure (§4.5 duplicate guard
     * stays honest, and a retry of the node starts from a clean name). Best effort: a failure to
     * drop is logged, never allowed to replace the original exception the caller is about to see.
     */
    private fun rollbackStagedTable(tableName: String) {
        stagedTables.remove(tableName)
        try {
            connection.createStatement().use { it.execute("DROP TABLE IF EXISTS ${StagingIdentifiers.quote(tableName)}") }
        } catch (e: SQLException) {
            log.warn("tempdb rollback of partial table '{}' failed for execution {}: {}", tableName, executionId, e.message)
        }
    }

    private fun batchInsert(
        tableName: String,
        columns: List<ColumnSchema>,
        mappings: List<LogicalTypeMapping>,
        rs: ResultSet,
    ): Long {
        val columnList = columns.joinToString(",") { StagingIdentifiers.quote(it.name) }
        val placeholders = columns.joinToString(",") { "?" }
        val sql = "INSERT INTO ${StagingIdentifiers.quote(tableName)} ($columnList) VALUES ($placeholders)"
        val sqlTypes = columns.map { H2EgressMapper.h2SqlType(it) }

        return try {
            connection.prepareStatement(sql).use { stmt -> streamInto(stmt, mappings, sqlTypes, rs) }
        } catch (e: SQLException) {
            // SQL class 22 = "data exception" (numeric out of range, value too long): a source
            // value overflowing the staged column's capacity (§4.3). Anything else is a real fault.
            if (e.sqlState?.startsWith(DATA_EXCEPTION_CLASS) == true) {
                throw StagingValueOverflowException(
                    "A source value exceeds the staged column capacity in table '$tableName': ${e.message}",
                    e,
                )
            }
            throw e
        }
    }

    /** Streams every source row into [stmt] in batches of `insert-batch-size`; returns the count. */
    private fun streamInto(
        stmt: PreparedStatement,
        mappings: List<LogicalTypeMapping>,
        sqlTypes: List<Int>,
        rs: ResultSet,
    ): Long {
        val batchSize = config.insertBatchSize
        var rowCount = 0L
        while (rs.next()) {
            bindRow(stmt, mappings, sqlTypes, rs)
            stmt.addBatch()
            if (++rowCount % batchSize == 0L) stmt.executeBatch()
        }
        // Flush the trailing partial batch — and always issue one executeBatch for an empty source.
        if (rowCount == 0L || rowCount % batchSize != 0L) stmt.executeBatch()
        return rowCount
    }

    /**
     * The [batchInsert] twin for already-decoded canonical values (§10 `stageRows`): the values
     * were read through the source dialect's mapper by the child's executor, so they bind
     * directly — there is no ResultSet left and no second mapping to apply.
     */
    private fun batchInsertRows(
        tableName: String,
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    ): Long {
        val columnList = columns.joinToString(",") { StagingIdentifiers.quote(it.name) }
        val placeholders = columns.joinToString(",") { "?" }
        val sql = "INSERT INTO ${StagingIdentifiers.quote(tableName)} ($columnList) VALUES ($placeholders)"
        val sqlTypes = columns.map { H2EgressMapper.h2SqlType(it) }

        return try {
            connection.prepareStatement(sql).use { stmt -> streamRowsInto(stmt, tableName, columns, sqlTypes, rows) }
        } catch (e: SQLException) {
            if (e.sqlState?.startsWith(DATA_EXCEPTION_CLASS) == true) {
                throw StagingValueOverflowException(
                    "A source value exceeds the staged column capacity in table '$tableName': ${e.message}",
                    e,
                )
            }
            throw e
        }
    }

    /** Streams canonical [rows] into [stmt] in batches of `insert-batch-size`; returns the count. */
    private fun streamRowsInto(
        stmt: PreparedStatement,
        tableName: String,
        columns: List<ColumnSchema>,
        sqlTypes: List<Int>,
        rows: Sequence<List<Any?>>,
    ): Long {
        val batchSize = config.insertBatchSize
        var rowCount = 0L
        rows.forEach { row ->
            require(row.size == columns.size) {
                "Row has ${row.size} values for ${columns.size} columns of table '$tableName'"
            }
            row.forEachIndexed { i, value -> stmt.setObject(i + 1, value, sqlTypes[i]) }
            stmt.addBatch()
            if (++rowCount % batchSize == 0L) stmt.executeBatch()
        }
        if (rowCount == 0L || rowCount % batchSize != 0L) stmt.executeBatch()
        return rowCount
    }

    /** Binds one source row into [stmt], reading each value per the canonical mapping (§4.4). */
    private fun bindRow(
        stmt: PreparedStatement,
        mappings: List<LogicalTypeMapping>,
        sqlTypes: List<Int>,
        rs: ResultSet,
    ) {
        mappings.forEachIndexed { i, m ->
            stmt.setObject(i + 1, SourceValueReader.readValue(rs, i + 1, m), sqlTypes[i])
        }
    }

    /**
     * Compares the **measured** footprint against the budget (§8.2), polled once here after a
     * staging operation completes — not per batch. The reading is kilobytes; the budget is
     * megabytes.
     */
    private fun checkMemoryBudget() {
        val usedKb = measureUsedHeapKb()
        if (usedKb > config.maxMemoryMb * KB_PER_MB) {
            throw StagingMemoryLimitException(memoryUsedBytes = usedKb * BYTES_PER_KB, maxMemoryMb = config.maxMemoryMb)
        }
    }

    /**
     * The measured footprint (§8.2): the JVM's used heap, read **in-process**.
     *
     * H2's `SELECT MEMORY_USED()` is deliberately not used — it requires admin rights (SQLState
     * 90040) in the pinned driver, and this connection is a non-admin user by §9.5. Nothing is
     * lost by the substitution: the two are the *same quantity*, verified against 2.3.232 —
     * `MEMORY_USED()` is itself "collect garbage, then report used heap", not a measure of the
     * database's own allocation, and both readings returned 14271 KB at the same instant after an
     * identical fill.
     *
     * Known limit, stated in §8.2: this is heap for the whole JVM, so with concurrent executions
     * every instance reads the same number and `max_memory_mb` acts as a shared ceiling rather
     * than an isolated per-execution budget. That was equally true of `MEMORY_USED()`; real
     * isolation is a v1.1+ item, and §8.4's JVM limits are the hard backstop underneath.
     */
    private fun measureUsedHeapKb(): Long {
        // Matches MEMORY_USED()'s post-GC semantics — without it the reading counts garbage the
        // budget should not be charged for. Called once per staging operation (§8.2), never per
        // batch, so the cost lands on a coarse guard and not on the insert path.
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB
    }

    /** Base tables currently in the staging catalog — every one, whoever created it (§10). */
    private fun measureTableCount(): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $STAGED_TABLES").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    /**
     * Releases every staged table immediately, ahead of the connection close (§3.4 belt).
     *
     * `DROP ALL OBJECTS` — the one-statement version — requires admin rights in the pinned driver
     * (SQLState 90040) and this connection is non-admin by §9.5, so the catalog is enumerated and
     * each base table dropped instead; both are operations the restricted user holds.
     *
     * `CASCADE` because author SQL may have built views over a staged table, and each drop is
     * independent so one failure cannot cost the remaining tables their release. Enumeration
     * failure propagates to [close]'s handler, which logs it as `cleanup_failed`; the connection
     * close that follows destroys the database regardless (§3.4 braces).
     */
    private fun dropStagedTables() {
        val tables =
            connection.createStatement().use { st ->
                st.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME FROM $STAGED_TABLES").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                }
            }
        connection.createStatement().use { st ->
            tables.forEach { (schema, table) ->
                // Schema-qualified: a table parked outside PUBLIC would not resolve against the
                // session's current schema, and quoting both parts keeps a hostile object name
                // inside the identifier (the catalog is the source of these strings, not the user,
                // but the same rule applies wherever a name reaches DDL — §4.5).
                val qualified = "${StagingIdentifiers.quote(schema)}.${StagingIdentifiers.quote(table)}"
                try {
                    st.execute("DROP TABLE $qualified CASCADE")
                } catch (e: SQLException) {
                    log.warn("tempdb drop of staged table {} failed for execution {}: {}", qualified, executionId, e.message)
                }
            }
        }
    }

    /**
     * Bounds the reflected source-DB type name carried by a §8.2 warning (ST-SEC-2). The value
     * is unvalidated text from a foreign driver's metadata and rides to the UI; an unbounded
     * one is a payload budget nobody set.
     *
     * Applied here, at staging's own boundary, because the mapper that builds the warning lives
     * in `typesystem` — outside this module. Warnings produced by other consumers of
     * `TypeMappers` are therefore still unbounded; the single-point fix belongs in the mapper
     * and is reported as a carry-forward.
     */
    private fun TypeMappingWarning.withBoundedSourceType(): TypeMappingWarning {
        val raw = sourceType ?: return this
        if (raw.length <= MAX_SOURCE_TYPE_CHARS) return this
        return copy(sourceType = raw.take(MAX_SOURCE_TYPE_CHARS) + "…")
    }

    private companion object {
        val log = LoggerFactory.getLogger(H2Staging::class.java)
        const val KB_PER_MB = 1024L
        const val BYTES_PER_KB = 1024L
        const val DATA_EXCEPTION_CLASS = "22"

        /**
         * The catalog projection both "current tables" (§10) and the §3.4 cleanup sweep read, so
         * the two can never disagree about what a staged table is. `BASE TABLE` excludes views an
         * author may have created — a view is not a table to count, and `DROP TABLE` cannot drop
         * one (its `CASCADE`d owner takes it instead).
         *
         * **Every schema, not just `PUBLIC`.** The restricted user holds `ALTER ANY SCHEMA`, so
         * author SQL can `CREATE SCHEMA` and `SET SCHEMA` (verified against 2.3.232) and park a
         * table outside `PUBLIC`. A `PUBLIC`-only projection would leave that table uncounted by
         * §10 and unreleased by the §3.4 belt — memory held for the rest of the JVM's life if the
         * database ever outlives its connection.
         *
         * H2's own catalog tables report `BASE TABLE` too, so the system schemas are excluded by
         * name. That list is driver- and mode-specific (`PG_CATALOG` exists because of
         * `MODE=PostgreSQL`); a mode that added another catalog schema would show up as extra
         * `tableCount` and as drops that fail — which is why each drop is caught individually
         * rather than aborting the sweep.
         *
         * **The comparison is case-folded on purpose.** With `DATABASE_TO_LOWER=TRUE` in the URL
         * (see [H2StagingFactory]) H2 names its own schemas `information_schema` / `pg_catalog`,
         * so the bare upper-case literals match nothing: measured against the pinned driver
         * (2.3.232), this projection returned **36** rows instead of 2 — §10 would over-report
         * `tableCount` by every catalog table, and the §3.4 belt would issue 34 `DROP TABLE`s
         * against H2's own catalog. `UPPER(...)` keeps the filter correct under either folding,
         * so the projection does not silently depend on a URL parameter set in another class.
         */
        const val STAGED_TABLES =
            "INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' " +
                "AND UPPER(TABLE_SCHEMA) NOT IN ('INFORMATION_SCHEMA', 'PG_CATALOG')"

        /**
         * H2's SQLState for "object already exists". Read off the pinned driver (2.3.232) from a
         * real duplicate `CREATE TABLE` — `42S01`, vendor code 42101 — not recalled; the drift
         * test re-derives it the same way rather than trusting this constant.
         */
        const val DUPLICATE_OBJECT_STATE = "42S01"

        /** Ceiling for a reflected source type name in a warning (ST-SEC-2). */
        const val MAX_SOURCE_TYPE_CHARS = 64
    }
}
