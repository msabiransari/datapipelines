package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.typesystem.Dialect
import java.sql.Connection
import java.sql.DriverManager

/**
 * #463 A.3 — the four [ReadOnlySourceCases] on MYSQL through the real executor (MySQL takes its own
 * streaming mode in `SourceStreaming.fetchSizeFor`, so the Postgres class does not cover it).
 *
 * ## Why the write is a function call
 *
 * A plain `INSERT` sent through `executeQuery` on a read-only Connector/J connection is refused by
 * the DRIVER ("Connection is read-only. Queries leading to data modification are not allowed"),
 * before the server sees it — which would prove nothing about the database. The write-shaped text
 * is therefore a `SELECT` of a stored function that inserts, created in setup: the driver passes a
 * `SELECT` through, and the SERVER refuses the insert inside it (error 1792, SQLState 25006, "Cannot
 * execute statement in a READ ONLY transaction"), measured by `ReadOnlyConnectionModeIntegrationTest`.
 */
internal class ReadOnlyMysqlSourceIntegrationTest : ReadOnlySourceCases() {
    private val mysql = SharedMysql.mysql

    override val datasource =
        Datasource(
            name = "my_ro",
            displayName = "my_ro",
            dialect = Dialect.MYSQL,
            jdbcUrl = mysql.jdbcUrl,
            username = mysql.username,
            secret = mysql.password,
        )

    override val writeSql = "SELECT ro_write(1) AS n"

    override fun connect(): Connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)

    override fun refusedByDatabase(details: Map<String, Any?>): Boolean = details["vendor_code"] == ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION

    /**
     * The writing function, created as root: binary logging is on in the stock image, so a function
     * that modifies data needs `log_bin_trust_function_creators` (declaring it DETERMINISTIC instead
     * would be a false statement about it).
     */
    override fun setup(connection: Connection) {
        val rootUrl = mysql.jdbcUrl.substringBeforeLast('/') + "/" + connection.catalog
        DriverManager.getConnection(rootUrl, "root", mysql.password).use { root ->
            root.createStatement().use { s ->
                s.execute("SET GLOBAL log_bin_trust_function_creators = 1")
                s.execute("DROP FUNCTION IF EXISTS ro_write")
                s.execute(
                    "CREATE FUNCTION ro_write(v INT) RETURNS INT MODIFIES SQL DATA " +
                        "BEGIN INSERT INTO ro_t VALUES (v); RETURN v; END",
                )
            }
        }
    }

    private companion object {
        /** MySQL's `ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION`. */
        const val ER_CANT_EXECUTE_IN_READ_ONLY_TRANSACTION = 1792
    }
}
