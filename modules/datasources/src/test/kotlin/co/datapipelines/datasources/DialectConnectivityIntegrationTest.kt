package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Each dialect adapter against its **real** database (datasources.md §13.2): the adapter builds
 * a pool, leases a live connection, runs a query, and the adapter's `typeMapper` (the
 * type-system.md §5 table for that dialect) maps a real `ResultSetMetaData` column. Proves the
 * adapter → HikariCP → driver → typesystem wiring end to end.
 *
 * Postgres and MySQL live here because both publish arm64 images and start natively everywhere.
 * **MSSQL is in [MssqlConnectivityIntegrationTest]**, which carries the same probe behind a host
 * architecture gate — see that class for why it cannot share this one's `@Container` lifecycle.
 * Oracle needs `-Poracle`; its absence is covered by the `driver_not_loaded` unit case instead.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DialectConnectivityIntegrationTest {
    @Test
    fun `postgres connects, queries, and maps an integer column`() {
        DialectProbe.verifyIntegerColumn(
            datasource =
                Datasource(
                    name = "pg_it",
                    displayName = "PG",
                    dialect = Dialect.POSTGRES,
                    jdbcUrl = postgres.jdbcUrl,
                    username = postgres.username,
                    password = postgres.password,
                ),
            integerQuery = "SELECT CAST(1 AS INTEGER) AS n",
        )
    }

    @Test
    fun `mysql connects, queries, and maps an integer column`() {
        DialectProbe.verifyIntegerColumn(
            datasource =
                Datasource(
                    name = "mysql_it",
                    displayName = "MySQL",
                    dialect = Dialect.MYSQL,
                    jdbcUrl = mysql.jdbcUrl,
                    username = mysql.username,
                    password = mysql.password,
                    properties = DatasourceProperties(jdbc = mapOf("sslMode" to "DISABLED")),
                ),
            integerQuery = "SELECT CAST(1 AS SIGNED) AS n",
        )
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4")
    }
}
