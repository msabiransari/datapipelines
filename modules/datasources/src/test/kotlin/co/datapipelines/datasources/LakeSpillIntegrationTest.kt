package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** Real spilling through LAKE's production pool, including overlapping generations (#133). */
class LakeSpillIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `default spill paths are absolute and isolated across overlapping pool generations`() {
        val datasource = lake()
        val first = ConnectionPoolManager.buildHikariPool(datasource)
        val second = ConnectionPoolManager.buildHikariPool(datasource)
        lateinit var firstPath: Path
        lateinit var secondPath: Path
        try {
            first.leaseConnection().use { connection ->
                firstPath = spillPath(connection)
                firstPath.isAbsolute shouldBe true
                firstPath.parent shouldBe Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                spill(connection)
            }
            second.leaseConnection().use { connection ->
                secondPath = spillPath(connection)
                secondPath shouldNotBe firstPath
                spill(connection)
            }
            // Hikari replaces the physical connection; the engine and its spilled table survive.
            (first as HikariConnectionPool).softEvict()
            first.leaseConnection().use { connection ->
                spillPath(connection) shouldBe firstPath
                verifyRows(connection)
            }
            first.close()
            Files.exists(firstPath) shouldBe false
            Files.exists(secondPath) shouldBe true
            second.leaseConnection().use(::verifyRows)
        } finally {
            first.close()
            second.close()
        }
        Files.exists(secondPath) shouldBe false
    }

    @Test
    fun `an explicit spill directory is honored and cleaned by the engine`() {
        val configured = temporaryDirectory.resolve("operator-spill")
        ConnectionPoolManager.buildHikariPool(lake(mapOf("temp_directory" to configured.toString()))).use { pool ->
            pool.leaseConnection().use { connection ->
                spillPath(connection) shouldBe configured
                spill(connection)
            }
        }
        Files.exists(configured) shouldBe false
    }

    private fun lake(overrides: Map<String, String> = emptyMap()) =
        Datasource(
            name = "spill_test",
            displayName = "Spill test",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            credentialKind = CredentialKind.NONE,
            properties = DatasourceProperties(dialect = mapOf("memory_limit" to "16MB", "threads" to "1") + overrides),
        )

    private fun spillPath(connection: Connection): Path =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_setting('temp_directory')").use { rows ->
                rows.next() shouldBe true
                Path.of(rows.getString(1))
            }
        }

    private fun spill(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE spilling AS SELECT i, md5(i::VARCHAR) payload FROM range(1000000) t(i)")
            statement.executeQuery("SELECT coalesce(sum(size), 0) FROM duckdb_temporary_files()").use { rows ->
                rows.next() shouldBe true
                rows.getLong(1).shouldBeGreaterThan(0L)
            }
        }
        Files.exists(spillPath(connection)) shouldBe true
        verifyRows(connection)
    }

    private fun verifyRows(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*), sum(i) FROM spilling").use { rows ->
                rows.next() shouldBe true
                rows.getLong(1) shouldBe 1_000_000L
                rows.getLong(2) shouldBe 499_999_500_000L
            }
        }
    }
}
