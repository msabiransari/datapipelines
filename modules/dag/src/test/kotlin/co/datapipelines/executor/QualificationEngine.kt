package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.typesystem.Dialect
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The three engines #1036 qualifies on a real server with the pinned driver, and the per-engine
 * facts `LocalTransactionDialectQualificationTest` needs: where the database is, the dialect's
 * DDL for the fixture tables, and how a second connection observes a row the family has not
 * committed (PostgreSQL and MySQL/InnoDB read the committed version without waiting; SQL Server's
 * default READ COMMITTED isolation blocks on the writer's lock, which is the same "not visible"
 * fact reported as a lock timeout).
 *
 * Every fixture value is synthetic; no credential leaves the container's own test account.
 */
enum class QualificationEngine(
    val dialect: Dialect,
    /** The server version prefix the evidence was recorded against (the matrix's server column). */
    val serverVersionPrefix: String,
) {
    POSTGRES(Dialect.POSTGRES, "16."),
    MYSQL(Dialect.MYSQL, "8.4."),
    MSSQL(Dialect.MSSQL, "16.00.4275"),
    ;

    /** Where the engine's qualification database is (started on first touch). */
    internal val database: SharedPostgres.DatabaseRef by lazy {
        when (this) {
            POSTGRES -> SharedPostgres.scratchDatabase("q1036")
            MYSQL -> SharedMysql.mysql.let { SharedPostgres.DatabaseRef(it.jdbcUrl, it.username, it.password) }
            MSSQL -> SharedMssql.mssql.let { SharedPostgres.DatabaseRef(SharedMssql.jdbcUrl(), it.username, it.password) }
        }
    }

    /** Skips the caller where the engine's image cannot run (SQL Server is amd64-only). */
    fun assumeRunnable() {
        if (this == MSSQL) SharedMssql.assumeRunnable()
    }

    /** A registration of this engine; [jdbcUrl] may route through a [FaultProxy]. */
    internal fun registration(
        name: String,
        jdbcUrl: String = database.jdbcUrl,
    ): Datasource =
        Datasource(
            name = name,
            displayName = name,
            dialect = dialect,
            jdbcUrl = jdbcUrl,
            username = database.username,
            secret = database.password,
        )

    /** A plain connection the executor never touches — every verdict is read through one. */
    fun secondConnection(): Connection = DriverManager.getConnection(database.jdbcUrl, database.username, database.password)

    /** Drops and recreates the fixture tables with their seed rows. */
    fun reset() =
        secondConnection().use { connection ->
            connection.createStatement().use { statement ->
                FIXTURE_TABLES.forEach { statement.execute("DROP TABLE IF EXISTS $it") }
                statement.execute("CREATE TABLE acct (id INT PRIMARY KEY, bal INT NOT NULL)$storage")
                statement.execute("CREATE TABLE log_t (id INT NOT NULL)$storage")
                statement.execute("CREATE TABLE wb_target (id INT NOT NULL)$storage")
                statement.execute("CREATE TABLE k_gen (id $generatedKey PRIMARY KEY, note VARCHAR(20) NOT NULL)$storage")
                statement.execute("CREATE TABLE k_sup (id INT PRIMARY KEY, note VARCHAR(20) NOT NULL)$storage")
                statement.execute("INSERT INTO acct (id, bal) VALUES (1, 0), (2, 0), (3, 0), (4, 0), (5, 0)")
                statement.execute("INSERT INTO wb_target (id) VALUES (100), (101)")
            }
        }

    /** MySQL only: a non-transactional table, the recorded storage limitation. */
    fun createMyisamTable() =
        secondConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS m_t")
                statement.execute("CREATE TABLE m_t (id INT NOT NULL) ENGINE=MyISAM")
            }
        }

    /** One long value read on a second connection. */
    fun long(sql: String): Long =
        secondConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    check(rs.next()) { "no row" }
                    rs.getLong(1)
                }
            }
        }

    /** Every value of one integer column, ordered, read on a second connection. */
    fun ints(sql: String): List<Int> =
        secondConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getInt(1) else null }.toList() }
            }
        }

    /**
     * What a second connection sees of [table] while the family holds uncommitted writes to it:
     * `count=<n>` when the engine answers from committed data, or `blocked(<vendor code>)` when
     * the reader waits on the writer's lock beyond a short lock timeout (SQL Server's 1222).
     */
    fun midRunView(table: String): String =
        secondConnection().use { connection ->
            connection.createStatement().use { statement ->
                if (this == MSSQL) statement.execute("SET LOCK_TIMEOUT $MID_RUN_LOCK_TIMEOUT_MS")
                runCatching { "count=${singleLong(statement.executeQuery("SELECT COUNT(*) FROM $table"))}" }
                    .getOrElse { e -> if (e is SQLException) "blocked(${e.errorCode})" else throw e }
            }
        }

    /** The storage engine of [table] as the server reports it (MySQL), or the engine's fixed table kind. */
    fun storageOf(table: String): String =
        when (this) {
            MYSQL -> {
                secondConnection().use { connection ->
                    val query = connection.prepareStatement(STORAGE_ENGINE_QUERY)
                    query.use {
                        it.setString(1, table)
                        singleString(it.executeQuery())
                    }
                }
            }

            POSTGRES -> {
                "heap table"
            }

            MSSQL -> {
                "rowstore table"
            }
        }

    private val storage: String get() = if (this == MYSQL) " ENGINE=InnoDB" else ""

    private val generatedKey: String
        get() =
            when (this) {
                POSTGRES -> "INT GENERATED BY DEFAULT AS IDENTITY"
                MYSQL -> "INT AUTO_INCREMENT"
                MSSQL -> "INT IDENTITY(1,1)"
            }

    private companion object {
        const val STORAGE_ENGINE_QUERY = "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"

        fun singleLong(rs: java.sql.ResultSet): Long =
            rs.use {
                check(it.next()) { "no row" }
                it.getLong(1)
            }

        fun singleString(rs: java.sql.ResultSet): String =
            rs.use {
                check(it.next()) { "no row" }
                it.getString(1)
            }

        val FIXTURE_TABLES = listOf("acct", "log_t", "wb_target", "k_gen", "k_sup")
        const val MID_RUN_LOCK_TIMEOUT_MS = 500
    }
}
