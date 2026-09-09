package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

/**
 * §7C catalog statistics against the real Connector/J (the [DialectConnectivityIntegrationTest]
 * container pattern): `information_schema.tables.table_rows` is InnoDB's estimate — refreshed
 * by ANALYZE TABLE — and `information_schema.statistics` orders a composite key by
 * `seq_in_index`. MySQL's catalog routing means the schema bind is the DATABASE.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableStatsMysqlIntegrationTest {
    /** Built lazily — the container's JDBC URL exists only after it starts. */
    private val ds
        get() =
            Datasource(
                name = "mysql_stats",
                displayName = "MySQL",
                dialect = Dialect.MYSQL,
                jdbcUrl = mysql.jdbcUrl,
                username = mysql.username,
                secret = mysql.password,
                properties = DatasourceProperties(jdbc = mapOf("sslMode" to "DISABLED")),
            )

    private val registry = mockk<DatasourceRegistry>()
    private val introspector = SchemaIntrospector(registry)

    @BeforeEach
    fun wire() {
        every { registry.get(ds.name) } returns ds
        every { registry.poolFor(ds) } returns
            object : ConnectionPool {
                override val name: String = ds.name

                override fun leaseConnection(): Connection = DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password)

                override fun close() = Unit
            }
    }

    @BeforeEach
    fun seed() {
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS stats_probe")
                st.execute("CREATE TABLE stats_probe (id INT PRIMARY KEY, grp INT, note VARCHAR(50))")
                st.execute("CREATE UNIQUE INDEX idx_grp_note ON stats_probe(grp, note)")
                st.execute(
                    "INSERT INTO stats_probe " +
                        "WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 500) " +
                        "SELECT n, n % 10, CONCAT('note-', n) FROM seq",
                )
                st.execute("ANALYZE TABLE stats_probe")
            }
        }
    }

    @AfterEach
    fun cleanup() {
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE IF EXISTS stats_probe") }
        }
    }

    @Test
    fun `table_rows estimate and composite index order come from information_schema`() {
        val stats = introspector.tableStats(ds.name, "stats_probe")

        val primary = stats.indexes.single { it.primary }
        val composite = stats.indexes.single { it.name == "idx_grp_note" }
        assertAll(
            { stats.statsSource shouldBe "information_schema.tables" },
            // InnoDB's estimate — ANALYZE TABLE refreshes it, but it remains an estimate.
            { stats.rowEstimate!!.toLong() shouldBeGreaterThan 0L },
            { stats.statsAsOf shouldBe null },
            { primary.name shouldBe "PRIMARY" },
            { primary.unique shouldBe true },
            { primary.columns shouldContainExactly listOf("id") },
            { composite.unique shouldBe true },
            { composite.primary shouldBe false },
            { composite.columns shouldContainExactly listOf("grp", "note") },
            // information_schema.column_statistics is deliberately not read (the plan's KDoc).
            { stats.columns shouldBe emptyList<ColumnStats>() },
        )
    }

    @Test
    fun `an unknown table answers empty stats against the named catalog, not an error`() {
        val stats = introspector.tableStats(ds.name, "no_such_table")

        assertAll(
            { stats.statsSource shouldBe "information_schema.tables" },
            { stats.rowEstimate shouldBe null },
            { stats.indexes shouldBe emptyList<IndexStats>() },
        )
    }

    private companion object {
        /** One container, one suite — the DialectConnectivityIntegrationTest rationale applies. */
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer("mysql:8.4")
                .withDatabaseName("my_app")
                .withStartupTimeout(java.time.Duration.ofMinutes(5))
    }
}
