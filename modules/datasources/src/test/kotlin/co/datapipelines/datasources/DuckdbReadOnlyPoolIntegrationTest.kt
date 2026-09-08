package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * The `--demo trade` shape, replayed: a DuckDB FILE opened `access_mode=READ_ONLY` with the
 * datasource flagged `readonly` — exactly `deploy/sample-data/bootstrap-datasources-census.yml`.
 *
 * HikariCP 6.3.3's `setupConnection` calls `setReadOnly(pool.readOnly)` whenever the connection's
 * own `isReadOnly()` differs from the pool's flag, and the DuckDB driver refuses to CHANGE the
 * flag ("Can't change read-only status on connection level"). Two rounds each fixed one half:
 * 0cda450 forced the pool flag `true` (fine for a READ_ONLY file, fatal for the `:memory:` lake),
 * 089 forced it off for DuckDB (fine for the lake, fatal for every trade datasource — found by
 * 093 photographing a red `failed` badge). The pool flag now MIRRORS the open state, so Hikari
 * never calls `setReadOnly` on this driver at all. Both halves are asserted here; the first
 * case is red on either previous rule.
 */
class DuckdbReadOnlyPoolIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private fun duckdbFile(name: String): File {
        val file = tempDir.resolve(name)
        DriverManager.getConnection("jdbc:duckdb:${file.absolutePath}").use { writer ->
            writer.createStatement().use { it.execute("CREATE TABLE t AS SELECT 1 AS id UNION ALL SELECT 2") }
        }
        return file
    }

    private fun datasource(
        name: String,
        jdbcUrl: String,
        jdbc: Map<String, Any?>,
    ) = Datasource(
        name = name,
        displayName = name,
        dialect = Dialect.DUCKDB,
        jdbcUrl = jdbcUrl,
        credentialKind = CredentialKind.NONE,
        isReadonly = true,
        properties = DatasourceProperties(jdbc = jdbc),
    )

    @Test
    fun `a readonly datasource over a file opened READ_ONLY builds its pool and answers a query`() {
        val file = duckdbFile("trade.duckdb")
        val ds = datasource("trade", "jdbc:duckdb:${file.absolutePath}", mapOf("access_mode" to "READ_ONLY"))

        ConnectionPoolManager(poolFactory = { ConnectionPoolManager.buildHikariPool(it) }).use { manager ->
            manager.poolFor(ds).leaseConnection().use { connection ->
                assertAll(
                    { connection.isReadOnly shouldBe true },
                    {
                        connection.createStatement().use { st ->
                            st.executeQuery("SELECT count(*) FROM t").use { rs ->
                                rs.next()
                                rs.getLong(1) shouldBe 2
                            }
                        }
                    },
                )
            }
        }
    }

    @Test
    fun `a readonly datasource over a writable connection still builds - the pool flag mirrors the open state`() {
        // The lake's shape (`:memory:`, no access_mode): the connection opens writable, so the
        // pool flag stays false and Hikari has nothing to change. The 0cda450 rule failed here.
        val ds = datasource("mem", "jdbc:duckdb:", emptyMap())

        ConnectionPoolManager(poolFactory = { ConnectionPoolManager.buildHikariPool(it) }).use { manager ->
            manager.poolFor(ds).leaseConnection().use { connection ->
                connection.isReadOnly shouldBe false
            }
        }
    }

    @Test
    fun `the pool flag is derived from access_mode, case-insensitively, never from the readonly flag alone`() {
        val adapter = DialectAdapters.forDialect(Dialect.DUCKDB)
        assertAll(
            { adapter.buildHikariConfig(datasource("a", "jdbc:duckdb:", mapOf("access_mode" to "read_only"))).isReadOnly shouldBe true },
            { adapter.buildHikariConfig(datasource("b", "jdbc:duckdb:", mapOf("access_mode" to "READ_WRITE"))).isReadOnly shouldBe false },
            { adapter.buildHikariConfig(datasource("c", "jdbc:duckdb:", emptyMap())).isReadOnly shouldBe false },
        )
    }
}
