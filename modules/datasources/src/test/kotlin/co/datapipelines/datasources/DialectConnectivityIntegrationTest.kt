package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

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

    /**
     * §7A against the real Connector/J: the database arrives in TABLE_CAT (TABLE_SCHEM is
     * null), so introspection must route through the catalog — the schema filter selects the
     * database and each table reports the database as its schema. And an UNFILTERED listing
     * must drop MySQL's system schemas: Connector/J reports `sys` / `performance_schema`
     * views as plain `VIEW`/`TABLE` rows, so only the system-schema list can keep them out.
     */
    @Test
    fun `mysql introspection reads the database from the catalog as the schema`() {
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE routed_orders (id INT PRIMARY KEY)") }
        }

        val ds =
            Datasource(
                name = "mysql_intro",
                displayName = "MySQL",
                dialect = Dialect.MYSQL,
                jdbcUrl = mysql.jdbcUrl,
                username = mysql.username,
                password = mysql.password,
                properties = DatasourceProperties(jdbc = mapOf("sslMode" to "DISABLED")),
            )
        val registry = mockk<DatasourceRegistry>()
        every { registry.get("mysql_intro") } returns ds
        every { registry.poolFor(ds) } returns
            object : ConnectionPool {
                override val name: String = "mysql_intro"

                override fun leaseConnection(): Connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)

                override fun close() = Unit
            }

        val introspector = SchemaIntrospector(registry)
        val tables = introspector.tables("mysql_intro", schemaFilter = mysql.databaseName).tables

        val routed = tables.first { it.name.equals("routed_orders", ignoreCase = true) }
        assertAll(
            { routed.schema shouldBe mysql.databaseName },
            { tables.none { it.schema.equals("information_schema", ignoreCase = true) } shouldBe true },
        )

        // The unfiltered listing must not leak the engine's own schemas — sys views arrive as
        // plain VIEWs, invisible to the table-type vocabulary.
        val unfilteredSchemas = introspector.tables("mysql_intro").tables.mapNotNull { it.schema?.lowercase() }
        unfilteredSchemas.forEach { schema ->
            (schema in setOf("mysql", "performance_schema", "sys", "information_schema")) shouldBe false
        }
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
