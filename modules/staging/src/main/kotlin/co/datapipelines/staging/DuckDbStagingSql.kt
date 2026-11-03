package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.DuckDbEgressMapper
import co.datapipelines.typesystem.MappedColumn
import co.datapipelines.typesystem.TypeMappers
import co.datapipelines.typesystem.TypeMappingWarning
import org.duckdb.DuckDBConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.util.UUID

/**
 * The SQL a DuckDB staging database issues on its own behalf (staging.md §8.5), over a connection
 * the CALLER already leases: the bare `CREATE TABLE`, the appender batch, the catalog count, the
 * memory reading and the partial-table rollback. Every identifier is validated (§4.5) and quoted.
 */
internal object DuckDbStagingSql {
    private val log = LoggerFactory.getLogger(DuckDbStagingSql::class.java)

    /**
     * A bare `CREATE TABLE` (never `IF NOT EXISTS`, §4.5); the column types come from
     * [DuckDbEgressMapper], which refuses an unstorable decimal before any DDL is sent.
     */
    fun createTable(
        connection: Connection,
        tableName: String,
        columns: List<ColumnSchema>,
    ) {
        val decls = columns.joinToString(", ") { c -> "${StagingIdentifiers.quote(c.name)} ${DuckDbEgressMapper.toDuckDbType(c)}" }
        connection.createStatement().use { it.execute("CREATE TABLE ${StagingIdentifiers.quote(tableName)} ($decls)") }
    }

    /**
     * Appends [batch] through the driver's appender and flushes it **explicitly**.
     *
     * The explicit flush is load-bearing: on the pinned driver `DuckDBAppender.close()` flushes
     * inside a catch that discards the failure, so an engine out-of-memory at close loses the batch
     * without an error (measured, lane #795 probes 14/15 — 1 000 of 100 000 rows kept, no exception).
     * `flush()` throws instead, and its count is checked against the batch.
     */
    fun appendBatch(
        connection: Connection,
        tableName: String,
        binders: List<DuckDbValueBinder>,
        batch: List<List<Any?>>,
    ) {
        val duck = connection.unwrap(DuckDBConnection::class.java)
        duck.createAppender(DuckDBConnection.DEFAULT_SCHEMA, tableName).use { appender ->
            batch.forEach { row ->
                appender.beginRow()
                row.forEachIndexed { i, value -> binders[i].append(appender, value) }
                appender.endRow()
            }
            val flushed = appender.flush()
            check(flushed == batch.size.toLong()) {
                "the DuckDB appender flushed $flushed of ${batch.size} rows into staged table '$tableName'"
            }
        }
    }

    /** Base tables in the staging database — every one, whoever created it (§10); views and temp tables excluded. */
    fun countTables(connection: Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery(COUNT_TABLES).use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    /** The engine's buffer-manager reading: the sum of `duckdb_memory()` (staging.md §8.5). */
    fun memoryUsedBytes(connection: Connection): Long =
        connection.createStatement().use { st ->
            st.executeQuery(MEMORY_USED).use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }

    /** Drops a partially staged table; best effort — false (and logged) when the drop refused. */
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

    /** A source column's canonical descriptor, by the SOURCE dialect's ingress mapper (as H2Staging). */
    fun mapSourceColumn(
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

    /** Bounds a reflected source type name carried by a warning (ST-SEC-2, as H2Staging). */
    fun boundedSourceType(warning: TypeMappingWarning): TypeMappingWarning {
        val raw = warning.sourceType ?: return warning
        if (raw.length <= MAX_SOURCE_TYPE_CHARS) return warning
        return warning.copy(sourceType = raw.take(MAX_SOURCE_TYPE_CHARS) + "…")
    }

    private const val MAX_SOURCE_TYPE_CHARS = 64

    private const val COUNT_TABLES =
        "SELECT count(*) FROM duckdb_tables() WHERE NOT temporary AND database_name = current_database()"
    private const val MEMORY_USED = "SELECT coalesce(sum(memory_usage_bytes), 0) FROM duckdb_memory()"
}

/**
 * Turns the engine's own failures into the catalogued staging codes (staging.md §8.5, §7.2). The
 * pinned driver reports every engine error with SQLState `null`, so the classification reads the
 * error class at the START of the engine's message — text measured on 1.5.5.1 (lane #795 probes
 * 13, 15, 16) and pinned by `DuckDbEngineFailuresTest`:
 *
 *  - an `Out of Memory Error` whose text says the limit "was set by the 'max_temp_directory_size'
 *    setting" (a spill past the reservation) → `scratch_limit_exceeded`;
 *  - any other `Out of Memory Error`, or the appender's commit failure for want of a block →
 *    `memory_limit_exceeded`, with the engine's `(used/limit used)` reading in the details;
 *  - `Catalog Error: Table with name … already exists` on a staged create → `table_already_exists`.
 *
 * Anchored, not a substring search: an author's own `error('Out of Memory Error …')` arrives as an
 * `Invalid Input Error`, and a refused `SET max_temp_directory_size` names the setting too — both
 * stay the driver's exception. Anything unmatched is returned unchanged.
 */
internal class DuckDbEngineFailures(
    private val executionId: UUID,
    private val memoryLimitBytes: Long,
    private val reservationBytes: Long,
) {
    fun classify(e: SQLException): Exception {
        val message = e.message.orEmpty()
        val outOfMemory = ENGINE_OUT_OF_MEMORY.containsMatchIn(message) || APPENDER_OUT_OF_MEMORY.containsMatchIn(message)
        return when {
            outOfMemory && SPILL_LIMIT_SENTENCE in message -> {
                StagingScratchLimitException(
                    executionId,
                    reservationBytes,
                    measuredBytes = null,
                    engineReading = reading(message),
                    cause = e,
                )
            }

            outOfMemory -> {
                StagingEngineMemoryLimitException(executionId, memoryLimitBytes, reading(message), e)
            }

            else -> {
                e
            }
        }
    }

    /** A staged create's refusal: `table_already_exists` when the engine says so, else [classify]. */
    fun classifyCreate(
        e: SQLException,
        tableName: String,
    ): Exception = if (ALREADY_EXISTS.containsMatchIn(e.message.orEmpty())) StagingTableAlreadyExistsException(tableName) else classify(e)

    /** The engine's `(used/limit used)` figure when it gave one, else its first sentence, bounded. */
    private fun reading(message: String): String =
        USED_READING.find(message)?.groupValues?.get(1) ?: message.lineSequence().first().take(MAX_READING_CHARS)

    private companion object {
        /** The engine's own error, bare or behind the driver's pending-result wrapper. */
        val ENGINE_OUT_OF_MEMORY =
            Regex(
                """^(?:Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result Error: )?""" +
                    """Out of Memory Error: """,
            )

        /** The appender's commit failing for want of a buffer block (probe 15). */
        val APPENDER_OUT_OF_MEMORY =
            Regex(
                """^Appender error, [^\n]*message: Failed to append: Failed to commit: """ +
                    """(?:failed to pin block|could not allocate block|failed to allocate data)""",
            )
        const val SPILL_LIMIT_SENTENCE = "This limit was set by the 'max_temp_directory_size' setting"
        val ALREADY_EXISTS = Regex("""Catalog Error: Table with name "[^"]*" already exists""")
        val USED_READING = Regex("""\(([0-9.]+ [KMGTP]?i?B/[0-9.]+ [KMGTP]?i?B) used\)""")
        const val MAX_READING_CHARS = 200
    }
}

/**
 * The DuckDB engine refused an allocation past its `memory_limit` (staging.md §8.5). The limit is the
 * buffer manager's bound only (dp-lake design §11), so [engineReading] is the engine's own figure —
 * `used/limit` — never a JVM heap reading.
 */
class StagingEngineMemoryLimitException(
    val executionId: UUID,
    val memoryLimitBytes: Long,
    val engineReading: String,
    cause: Throwable? = null,
) : DatapipelinesException(
        code = StagingErrorCodes.MEMORY_LIMIT_EXCEEDED,
        message =
            "The DuckDB staging database for execution $executionId reached its $memoryLimitBytes-byte memory " +
                "limit ($engineReading). Stage fewer rows or columns, or split the statement.",
        details =
            mapOf(
                "execution_id" to executionId.toString(),
                "memory_limit_bytes" to memoryLimitBytes,
                "engine_reading" to engineReading,
            ),
        cause = cause,
    )
