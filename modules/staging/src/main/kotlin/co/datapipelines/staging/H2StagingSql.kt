package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.H2EgressMapper
import co.datapipelines.typesystem.LogicalTypeMapping
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * The generated SQL behind [H2Staging] — DDL, batched inserts, catalog reads and the cleanup
 * sweep — as plain functions over a connection the CALLER already leases (staging.md §4, §3.4).
 *
 * Nothing here acquires a lease or touches pool state: every function takes the current
 * connection as an argument, which is what makes the no-nested-acquisition rule of §9.2
 * checkable by reading the signatures. [H2Staging] owns the leases and the bookkeeping.
 */
internal object H2StagingSql {
    private val log = LoggerFactory.getLogger(H2Staging::class.java)

    /** SQL class 22 = "data exception" — a source value overflowing the staged column (§4.3). */
    private const val DATA_EXCEPTION_CLASS = "22"

    /**
     * H2's SQLState for "object already exists". Read off the pinned driver (2.3.232) from a
     * real duplicate `CREATE TABLE` — `42S01`, vendor code 42101 — not recalled; the drift
     * test re-derives it the same way rather than trusting this constant.
     */
    const val DUPLICATE_OBJECT_STATE = "42S01"

    /**
     * The catalog projection both "current tables" (§10) and the §3.4 cleanup sweep read, so
     * the two can never disagree about what a staged table is. `BASE TABLE` excludes views an
     * author may have created — a view is not a table to count, and `DROP TABLE` cannot drop
     * one (its `CASCADE`d owner takes it instead).
     *
     * **Every schema, not just `PUBLIC`.** The restricted user holds `ALTER ANY SCHEMA`, so
     * author SQL can `CREATE SCHEMA` and `SET SCHEMA` (verified against 2.3.232) and park a
     * table outside `PUBLIC`. A `PUBLIC`-only projection would leave that table uncounted by
     * §10 and unreleased by the §3.4 belt.
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
     * (2.3.232), this projection returned **36** rows instead of 2. `UPPER(...)` keeps the
     * filter correct under either folding.
     */
    const val STAGED_TABLES =
        "INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' " +
            "AND UPPER(TABLE_SCHEMA) NOT IN ('INFORMATION_SCHEMA', 'PG_CATALOG')"

    /**
     * Issues a **bare** `CREATE TABLE` (never `IF NOT EXISTS`, never an implicit `DROP`, §4.5).
     * A name already present in the database fails as the catalogued duplicate-table error
     * rather than escaping as a raw driver `SQLException` the executor cannot classify —
     * SQL nodes create tables through `execute()`/`withConnection()` that the in-process
     * reservation set never sees.
     */
    fun createTable(
        connection: Connection,
        tableName: String,
        columns: List<ColumnSchema>,
    ) {
        val decls = columns.joinToString(", ") { c -> "${StagingIdentifiers.quote(c.name)} ${H2EgressMapper.toH2Type(c)}" }
        val ddl = "CREATE TABLE ${StagingIdentifiers.quote(tableName)} ($decls)"
        try {
            connection.createStatement().use { it.execute(ddl) }
        } catch (e: SQLException) {
            throw if (e.sqlState == DUPLICATE_OBJECT_STATE) StagingTableAlreadyExistsException(tableName) else e
        }
    }

    fun insertSql(
        tableName: String,
        columns: List<ColumnSchema>,
    ): String {
        val columnList = columns.joinToString(",") { StagingIdentifiers.quote(it.name) }
        val placeholders = columns.joinToString(",") { "?" }
        return "INSERT INTO ${StagingIdentifiers.quote(tableName)} ($columnList) VALUES ($placeholders)"
    }

    /**
     * Prepares, binds and flushes one batch on [connection], closing the statement before
     * returning — a statement never outlives the lease that created it (§9.2). Re-preparing
     * per batch is the price of that rule; H2's per-session query cache makes the re-parse a
     * lookup for every batch after the first on a given connection.
     */
    fun insertBatch(
        connection: Connection,
        tableName: String,
        columns: List<ColumnSchema>,
        sqlTypes: List<Int>,
        batch: List<List<Any?>>,
    ) {
        try {
            connection.prepareStatement(insertSql(tableName, columns)).use { stmt ->
                batch.forEach { row -> bindRow(stmt, row, sqlTypes) }
                stmt.executeBatch()
            }
        } catch (e: SQLException) {
            throw asInsertFailure(e, tableName)
        }
    }

    private fun bindRow(
        stmt: PreparedStatement,
        row: List<Any?>,
        sqlTypes: List<Int>,
    ) {
        row.forEachIndexed { i, value -> stmt.setObject(i + 1, value, sqlTypes[i]) }
        stmt.addBatch()
    }

    /** A source value overflowing the staged column's capacity is `value_overflow` (§4.3); anything else is a real fault. */
    private fun asInsertFailure(
        e: SQLException,
        tableName: String,
    ): Exception =
        if (e.sqlState?.startsWith(DATA_EXCEPTION_CLASS) == true) {
            StagingValueOverflowException("A source value exceeds the staged column capacity in table '$tableName': ${e.message}", e)
        } else {
            e
        }

    /**
     * Reads up to [batchSize] rows out of [rs], decoding each value through its canonical mapping
     * (§4.4). A short batch means the cursor is exhausted. Touches only the SOURCE cursor —
     * callers run it holding no lease, which is the whole point of batching (§9.2).
     */
    fun readBatch(
        rs: ResultSet,
        mappings: List<LogicalTypeMapping>,
        batchSize: Int,
    ): List<List<Any?>> {
        val batch = ArrayList<List<Any?>>(batchSize)
        while (batch.size < batchSize && rs.next()) {
            batch.add(mappings.mapIndexed { i, m -> SourceValueReader.readValue(rs, i + 1, m) })
        }
        return batch
    }

    /** Drops a partially staged table; best effort — returns false (and logs) when the drop refused. */
    fun dropPartialTable(
        connection: Connection,
        tableName: String,
        executionId: UUID,
    ): Boolean =
        try {
            connection.createStatement().use { it.execute("DROP TABLE IF EXISTS ${StagingIdentifiers.quote(tableName)}") }
            true
        } catch (e: SQLException) {
            log.warn("tempdb rollback of partial table '{}' failed for execution {}: {}", tableName, executionId, e.message)
            false
        }

    /** Base tables currently in the staging catalog — every one, whoever created it (§10). */
    fun countTables(connection: Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $STAGED_TABLES").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    /**
     * Releases every staged table immediately, ahead of the last connection close (§3.4 belt).
     *
     * `DROP ALL OBJECTS` — the one-statement version — requires admin rights in the pinned driver
     * (SQLState 90040) and the operational user is non-admin by §9.5, so the catalog is enumerated
     * and each base table dropped instead; both are operations the restricted user holds.
     *
     * `CASCADE` because author SQL may have built views over a staged table, and each drop is
     * independent so one failure cannot cost the remaining tables their release. Enumeration
     * failure propagates to the pool's close handler, which logs it as `cleanup_failed`; the
     * connection close that follows destroys the database regardless (§3.4 braces).
     */
    fun dropStagedTables(
        connection: Connection,
        executionId: UUID,
    ) {
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
                // inside the identifier (§4.5).
                val qualified = "${StagingIdentifiers.quote(schema)}.${StagingIdentifiers.quote(table)}"
                try {
                    st.execute("DROP TABLE $qualified CASCADE")
                } catch (e: SQLException) {
                    log.warn("tempdb drop of staged table {} failed for execution {}: {}", qualified, executionId, e.message)
                }
            }
        }
    }
}
