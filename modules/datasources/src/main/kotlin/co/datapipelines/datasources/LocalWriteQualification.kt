package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType

/**
 * The runtime qualification matrix of the single-database pipeline transaction
 * (pipeline-write-transactions §3.2, §5, §7; dag-executor.md §16.6): for each transactional engine,
 * the pinned driver, the storage mode, the key mode and the operation, and whether that combination
 * was QUALIFIED by real-driver evidence, REFUSED because the evidence showed it cannot keep the
 * contract, or is UNAVAILABLE because no qualifying environment could be provisioned.
 *
 * It sits beside the dialect capabilities ([DialectAdapter]) because it is a per-dialect fact, and
 * the write-set planner reads it at run admission — before the first business statement — so an
 * engine the transaction covers but that is not qualified is REFUSED, never silently run as
 * partial commits. The evidence is the lane-specific suite `LocalTransactionDialectQualificationTest`
 * (modules/dag), which re-reads every QUALIFIED row from a second connection with the pinned driver;
 * changing a row's status without that suite's evidence is a defect.
 *
 * An exhaustive `when` keyed by the dialect: a new dialect cannot compile without a decision here.
 * The embedded engines (H2, SQLite, DuckDB) and LAKE are outside the feature (record §3.2) and have
 * no rows — [inScope] is false for them and the planner keeps them on the ordinary path.
 */
object LocalWriteQualification {
    /** Every row of the matrix, in dialect order — the source of the dag-executor.md §16.6 table. */
    val ROWS: List<LocalWriteCapability> by lazy { Dialect.entries.flatMap(::rowsFor) }

    /** True when the single-database transaction covers [dialect] at all (qualified or not). */
    fun inScope(dialect: Dialect): Boolean = rowsFor(dialect).isNotEmpty()

    /**
     * The status of [operation] on [dialect] under the engine's DEFAULT storage mode: the worst
     * status over the operation's key modes (an insert is qualified only when both a generated and
     * a supplied key were measured, because which one a run takes is decided per input row).
     * An out-of-scope dialect answers [QualificationStatus.REFUSED].
     */
    fun status(
        dialect: Dialect,
        operation: LocalWriteOperation,
    ): QualificationStatus =
        rowsFor(dialect)
            .filter { it.operation == operation && it.defaultStorage }
            .map { it.status }
            .maxByOrNull { it.ordinal }
            ?: QualificationStatus.REFUSED

    /**
     * The status of an insert on [dialect] whose declared key is [keyType] (#1036 R2): the worst of the
     * generated and supplied key modes among the rows that cover that type. A row with no key type covers
     * EVERY type (a refusal or an unavailable engine does not depend on the key's type); a qualified row
     * covers only the type it was measured with. A type no row covers was never measured, so it is
     * REFUSED — an unmeasured key type is never admitted under another type's evidence.
     */
    fun insertStatus(
        dialect: Dialect,
        keyType: LogicalType,
    ): QualificationStatus {
        val covering =
            rowsFor(dialect).filter {
                it.operation == LocalWriteOperation.INSERT && it.defaultStorage && (it.keyType == null || it.keyType == keyType)
            }
        val modes = covering.map { it.keyMode }.toSet()
        val complete = KeyMode.GENERATED in modes && KeyMode.SUPPLIED in modes
        return if (!complete) QualificationStatus.REFUSED else covering.maxOf { it.status }
    }

    /** The key types measured QUALIFIED for an insert on [dialect], both key modes each, in declaration order. */
    fun qualifiedKeyTypes(dialect: Dialect): List<LogicalType> =
        LogicalType.entries.filter { insertStatus(dialect, it) == QualificationStatus.QUALIFIED }

    /** The rows of [operation] on [dialect] under its default storage mode — the reasons a refusal names. */
    fun rows(
        dialect: Dialect,
        operation: LocalWriteOperation,
    ): List<LocalWriteCapability> = rowsFor(dialect).filter { it.operation == operation && it.defaultStorage }

    /** The engine's own commit, rollback, statement timeout and lost-acknowledgement behaviour is qualified. */
    fun transactionQualified(dialect: Dialect): Boolean = status(dialect, LocalWriteOperation.TRANSACTION) == QualificationStatus.QUALIFIED

    /** Every row recorded for [dialect], default storage first. */
    fun rowsFor(dialect: Dialect): List<LocalWriteCapability> =
        when (dialect) {
            Dialect.POSTGRES -> postgres()
            Dialect.MYSQL -> mysql()
            Dialect.MSSQL -> mssql()
            Dialect.ORACLE -> oracle()
            Dialect.H2, Dialect.SQLITE, Dialect.DUCKDB, Dialect.LAKE -> emptyList()
        }

    private const val PGJDBC = "org.postgresql:postgresql 42.7.13"
    private const val CONNECTOR_J = "com.mysql:mysql-connector-j 9.7.0"
    private const val MSSQL_JDBC = "com.microsoft.sqlserver:mssql-jdbc 12.10.2.jre11"
    private const val OJDBC = "com.oracle.database.jdbc:ojdbc11 23.7.0.25.01"

    /** The fixed facts every row of one engine shares: dialect, pinned driver, server, default storage. */
    private data class Engine(
        val dialect: Dialect,
        val driver: String,
        val server: String,
        val storage: String,
    ) {
        fun row(
            operation: LocalWriteOperation,
            keyMode: KeyMode,
            reason: String,
            status: QualificationStatus = QualificationStatus.QUALIFIED,
            keyType: LogicalType? = null,
        ) = LocalWriteCapability(dialect, driver, server, storage, operation, keyMode, status, reason, keyType = keyType)

        fun refusedInsert(
            keyMode: KeyMode,
            reason: String,
        ) = row(LocalWriteOperation.INSERT, keyMode, reason, QualificationStatus.REFUSED)
    }

    private val PG = Engine(Dialect.POSTGRES, PGJDBC, "PostgreSQL 16 (postgres:16-alpine)", "heap table")
    private val MY = Engine(Dialect.MYSQL, CONNECTOR_J, "MySQL 8.4 (mysql:8.4)", "InnoDB")
    private val MS = Engine(Dialect.MSSQL, MSSQL_JDBC, "SQL Server 2022 16.0.4275 (pinned digest)", "rowstore table")
    private val ORA = Engine(Dialect.ORACLE, OJDBC, "Oracle Database (no environment)", "heap table")

    /** The key types an insert on PostgreSQL was measured with, each in both key modes (dag-executor.md §16.6). */
    private val PG_KEY_TYPES = listOf(LogicalType.INTEGER, LogicalType.BIGINTEGER, LogicalType.STRING)

    private fun postgres(): List<LocalWriteCapability> =
        qualifiedEngine(PG) +
            PG_KEY_TYPES.flatMap { type ->
                listOf(KeyMode.GENERATED, KeyMode.SUPPLIED).map { mode ->
                    PG.row(LocalWriteOperation.INSERT, mode, QUALIFIED_KEY, keyType = type)
                }
            }

    private fun mysql(): List<LocalWriteCapability> =
        qualifiedEngine(MY) +
            listOf(
                MY.refusedInsert(
                    KeyMode.GENERATED,
                    "refused: the driver labels the key GENERATED_KEY (the session's LAST_INSERT_ID), not the declared column",
                ),
                MY.refusedInsert(
                    KeyMode.SUPPLIED,
                    "refused: the driver labels the key GENERATED_KEY, not the declared column, and returns no key row " +
                        "for a supplied key on a column without AUTO_INCREMENT",
                ),
                MY
                    .copy(storage = "MyISAM")
                    .row(
                        LocalWriteOperation.TRANSACTION,
                        KeyMode.NONE,
                        "refused: a non-transactional table keeps its rows after the rollback; " +
                            "transactional tables (InnoDB) are a prerequisite",
                        QualificationStatus.REFUSED,
                    ).copy(defaultStorage = false),
            )

    private fun mssql(): List<LocalWriteCapability> =
        qualifiedEngine(MS) +
            listOf(
                MS.refusedInsert(
                    KeyMode.GENERATED,
                    "refused: the driver labels the key GENERATED_KEYS (SCOPE_IDENTITY()), not the declared column",
                ),
                MS.refusedInsert(
                    KeyMode.SUPPLIED,
                    "refused: the driver labels the key GENERATED_KEYS, not the declared column, and returns a NULL key " +
                        "for a supplied key on a column without IDENTITY",
                ),
            )

    private fun oracle(): List<LocalWriteCapability> =
        LocalWriteOperation.entries.flatMap { operation ->
            keyModesOf(operation).map { keyMode ->
                ORA.row(operation, keyMode, UNAVAILABLE_ORACLE, QualificationStatus.UNAVAILABLE)
            }
        }

    /** The transaction and the count/write-back operations, measured qualified on the engine's default storage. */
    private fun qualifiedEngine(engine: Engine): List<LocalWriteCapability> =
        listOf(
            engine.row(LocalWriteOperation.TRANSACTION, KeyMode.NONE, QUALIFIED_TRANSACTION),
            engine.row(LocalWriteOperation.UPDATE, KeyMode.NONE, QUALIFIED_COUNT),
            engine.row(LocalWriteOperation.DELETE, KeyMode.NONE, QUALIFIED_COUNT),
            engine.row(LocalWriteOperation.WRITEBACK_APPEND, KeyMode.NONE, QUALIFIED_WRITEBACK),
            engine.row(LocalWriteOperation.WRITEBACK_REPLACE, KeyMode.NONE, QUALIFIED_REPLACE),
        )

    private fun keyModesOf(operation: LocalWriteOperation): List<KeyMode> =
        if (operation == LocalWriteOperation.INSERT) listOf(KeyMode.GENERATED, KeyMode.SUPPLIED) else listOf(KeyMode.NONE)

    private const val UNAVAILABLE_ORACLE =
        "unavailable: no project-approved licensed Oracle test environment; qualification is unfinished"
    private const val QUALIFIED_TRANSACTION =
        "qualified: uncommitted rows hidden from a second connection, commit and rollback observed, " +
            "statement timeout and lost commit acknowledgement end without a replay"
    private const val QUALIFIED_COUNT = "qualified: affected_rows observed for zero and several matches, rolled back with the family"
    private const val QUALIFIED_WRITEBACK = "qualified: appended rows committed with the family and rolled back with it"
    private const val QUALIFIED_REPLACE = "qualified: cleared with a transactional DELETE; old rows survive a later failure"
    private const val QUALIFIED_KEY =
        "qualified: the driver returns the declared column under its own label, correlated per row, for the key type of the row"
}

/** One cell of the matrix — see [LocalWriteQualification]. */
data class LocalWriteCapability(
    val dialect: Dialect,
    /** The pinned driver: Maven coordinates and the version the build resolves. */
    val driver: String,
    /** The server the evidence ran against, or the reason none ran. */
    val server: String,
    /** The table storage the row covers. */
    val storage: String,
    val operation: LocalWriteOperation,
    val keyMode: KeyMode,
    val status: QualificationStatus,
    /** Why the row has its status — names no business value, SQL or credential. */
    val reason: String,
    /** False for a storage mode recorded as a limitation; the runtime reads only the default storage. */
    val defaultStorage: Boolean = true,
    /**
     * The declared canonical key type an insert row was measured with (#1036 R2), or null when the row
     * covers every type or has no key. A qualified insert row covers its own type only.
     */
    val keyType: LogicalType? = null,
)

/** What the transaction does on the engine: the transaction itself, a DML operation, or a write-back mode. */
enum class LocalWriteOperation(
    val wire: String,
) {
    TRANSACTION("transaction"),
    INSERT("insert"),
    UPDATE("update"),
    DELETE("delete"),
    WRITEBACK_APPEND("writeback_append"),
    WRITEBACK_REPLACE("writeback_replace"),
}

/** How an insert's key is obtained; NONE for operations without a key. */
enum class KeyMode(
    val wire: String,
) {
    GENERATED("generated"),
    SUPPLIED("supplied"),
    NONE("none"),
}

/** Ordered from best to worst: a combined status is the worst of its parts. */
enum class QualificationStatus(
    val wire: String,
) {
    QUALIFIED("qualified"),
    REFUSED("refused"),
    UNAVAILABLE("unavailable"),
}
