package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContain
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
     * §7A against the real Connector/J, with the database named `my_app` — the underscore is
     * the point (R1): the database arrives in TABLE_CAT (TABLE_SCHEM is null), so introspection
     * routes through the **catalog** argument, which JDBC defines as a LITERAL. If the code
     * ever escapes the catalog value (`my\_app`), the filter matches nothing and this test
     * fails with an empty tables listing — the exact zero-match defect class.
     *
     * It also pins the unfiltered system-schema exclusion (Connector/J reports `sys` /
     * `performance_schema` views as plain `VIEW`/`TABLE` rows) and the schemas listing's
     * catalog vocabulary (`databaseTerm=CATALOG` → `getCatalogs()`).
     */
    @Test
    fun `mysql introspection routes an underscore-named database through the literal catalog`() {
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

        // The schema filter (the underscore-named database itself) must match THROUGH the
        // literal catalog argument, and a columns read under the same filter must find the
        // table — the two reads the escaped-catalog defect silently emptied.
        val columns = introspector.columns("mysql_intro", "routed_orders", schemaFilter = mysql.databaseName)
        assertAll(
            { routed.schema shouldBe mysql.databaseName },
            { columns.map { it.column.name.lowercase() } shouldContain "id" },
            { tables.none { it.schema.equals("information_schema", ignoreCase = true) } shouldBe true },
        )

        // The unfiltered listing must not leak the engine's own schemas — sys views arrive as
        // plain VIEWs, invisible to the table-type vocabulary.
        val unfilteredSchemas = introspector.tables("mysql_intro").tables.mapNotNull { it.schema?.lowercase() }
        unfilteredSchemas.forEach { schema ->
            (schema in setOf("mysql", "performance_schema", "sys", "information_schema")) shouldBe false
        }

        // The schemas listing reads the DATABASES (catalogs) under Connector/J defaults, with
        // the engine's own databases excluded the same way.
        val schemas = introspector.schemas("mysql_intro")
        assertAll(
            { schemas.map { it.lowercase() } shouldContain mysql.databaseName.lowercase() },
            {
                schemas.none { it.lowercase() in setOf("mysql", "performance_schema", "sys", "information_schema") } shouldBe true
            },
        )
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        /** `my_app` on purpose: the underscore-named database is what catches an escaped
         *  catalog argument (a literal must match the stored name exactly). */
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.4").withDatabaseName("my_app")
    }
}
