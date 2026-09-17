package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 152 (#128) — one DuckDB instance per LAKE pool generation, shared by every physical
 * connection HikariCP opens, so the engine's external-file cache SURVIVES routine physical
 * connection replacement (maxLifetime, idle eviction, validation failure).
 *
 * The investigation record proved the defect with a real Hikari witness: every physical
 * connection on `jdbc:duckdb::memory:` was its own engine, and the replacement Hikari handed
 * out 23 s later had an EMPTY cache — the same annual scan went from 6 s to a 62 s timeout.
 * This suite is the controlled, network-free form of that witness: a loopback HTTP server that
 * counts requests and bytes ([CountingParquetServer]) stands in for S3, and the assertions read
 * the counters — a marker table only proves a shared CATALOG; a request count proves the CACHE.
 */
class LakeSharedInstanceIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the external file cache survives a physical connection replacement - the second scan reads no file bytes`() {
        val parquet = parquet("trips.parquet", "SELECT i AS id, i * 2 AS twice FROM range(0, 5000) tbl(i)")
        CountingParquetServer(parquet).use { server ->
            val sql = "SELECT count(*), sum(twice) FROM read_parquet('${server.url}')"
            ConnectionPoolManager.buildHikariPool(tablelessLake("lake_cache")).use { pool ->
                val firstPhysical: Int
                val cold: Triple<Int, Int, Long>
                val cachedAfterCold: Long
                pool.leaseConnection().use { connection ->
                    firstPhysical = physicalId(connection)
                    cacheBytes(connection) shouldBe 0L
                    server.reset()
                    rowOf(connection, sql) shouldBe listOf(5000L, 24_995_000L)
                    cold = server.counts()
                    // The engine caches the RANGES it read (row groups + footer), not the
                    // whole file — the measured value is the witness, not the file length.
                    cachedAfterCold = cacheBytes(connection)
                }
                withClue("the cold scan must have actually read the file from the server") {
                    cold.third shouldBeGreaterThan 0L
                    cachedAfterCold shouldBeGreaterThan 0L
                }

                // Routine replacement: the idle physical connection is evicted (what maxLifetime,
                // idleTimeout and a failed validation all do), so the next lease is a NEW one.
                (pool as HikariConnectionPool).softEvict()
                server.reset()
                pool.leaseConnection().use { connection ->
                    physicalId(connection) shouldNotBe firstPhysical
                    withClue("the replacement physical connection must see the cache the first one filled") {
                        cacheBytes(connection) shouldBe cachedAfterCold
                    }
                    rowOf(connection, sql) shouldBe listOf(5000L, 24_995_000L)
                }
                val warm = server.counts()
                assertAll(
                    { withClue("warm scan file bytes served") { warm.third shouldBe 0L } },
                    { withClue("warm scan range GETs") { warm.second shouldBe 0 } },
                )
            }
        }
    }

    @Test
    fun `a whole-pool rebuild is a NEW generation - its cache starts cold, by design`() {
        val parquet = parquet("zones.parquet", "SELECT i AS id FROM range(0, 3000) tbl(i)")
        CountingParquetServer(parquet).use { server ->
            val sql = "SELECT count(*) FROM read_parquet('${server.url}')"
            val ds = tablelessLake("lake_rebuild")
            ConnectionPoolManager(poolFactory = { ConnectionPoolManager.buildHikariPool(it) }).use { manager ->
                manager.poolFor(ds).leaseConnection().use { rowOf(it, sql) shouldBe listOf(3000L) }
                server.counts().third shouldBeGreaterThan 0L

                // The generation boundary: retire + reap + rebuild (what a registry change, a
                // credential rotation or reconcile does) opens a NEW instance — the cache is
                // the generation's, not the datasource's, and it does not carry across.
                manager.retire(ds.name) shouldBe true
                manager.reapRetiring().drained shouldBe 1
                server.reset()
                manager.poolFor(ds).leaseConnection().use { connection ->
                    cacheBytes(connection) shouldBe 0L
                    rowOf(connection, sql) shouldBe listOf(3000L)
                }
                withClue("a rebuilt generation reads the file again") { server.counts().third shouldBeGreaterThan 0L }
            }
        }
    }

    @Test
    fun `a kind-password lake builds through the credentialed overload - the secret is instance-level`() {
        // An S3 key pair puts the key id in Hikari's username, so HikariCP calls the
        // credentialed getConnection — which must join the owner, never refuse or re-login.
        val keyed =
            tablelessLake("lake_keyed").copy(
                credentialKind = CredentialKind.PASSWORD,
                username = "AKIAEXAMPLEKEYID",
                secret = "example-secret-never-sent",
                properties = DatasourceProperties(dialect = mapOf("catalog.kind" to "s3", "region" to "us-east-1")),
            )
        ConnectionPoolManager.buildHikariPool(keyed).use { pool ->
            pool.leaseConnection().use { a ->
                pool.leaseConnection().use { b ->
                    assertAll(
                        { physicalId(a) shouldNotBe physicalId(b) },
                        // The secret was created ONCE, on the owner, and both duplicates see it.
                        { rowOf(a, "SELECT count(*) FROM duckdb_secrets() WHERE name = 'dp_lake'") shouldBe listOf(1L) },
                        { rowOf(b, "SELECT count(*) FROM duckdb_secrets() WHERE name = 'dp_lake'") shouldBe listOf(1L) },
                    )
                }
            }
        }
    }

    @Test
    fun `the registration pre-flight runs on its own scratch engine - it never joins the live instance`() {
        val candidate = parquet("candidate.parquet", "SELECT 1 AS id")
        val ds = tablelessLake("lake_preflight")
        ConnectionPoolManager.buildHikariPool(ds).use { pool ->
            pool.leaseConnection().use { live ->
                live.createStatement().use { it.execute("CREATE SCHEMA live_marker") }
                val refusal =
                    LakeTablePreflight.check(
                        ds,
                        LakeRegisteredTable(listOf("nyc", "mobility"), "candidate", "parquet", "file://${candidate.absolutePath}"),
                    )
                assertAll(
                    { withClue("the candidate is readable") { refusal shouldBe null } },
                    // The pre-flight's view was created on ITS engine, not the live one…
                    { runsSql(live, "SELECT count(*) FROM nyc.mobility.candidate") shouldBe false },
                    // …and the live engine's state stayed where it was.
                    { rowOf(live, "SELECT count(*) FROM duckdb_schemas() WHERE schema_name = 'live_marker'") shouldBe listOf(1L) },
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun runsSql(
        connection: Connection,
        sql: String,
    ): Boolean =
        try {
            connection.createStatement().use { it.executeQuery(sql).close() }
            true
        } catch (_: java.sql.SQLException) {
            false
        }

    private fun parquet(
        file: String,
        selectSql: String,
    ): File {
        val target = tempDir.resolve(file)
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use { it.execute("COPY ($selectSql) TO '${target.absolutePath}' (FORMAT PARQUET)") }
        }
        return target
    }

    /**
     * A tableless LAKE datasource whose `catalog.kind` makes the adapter load httpfs (so the
     * loopback URL is readable) and whose `unsigned` flag creates no S3 secret — the public
     * bucket demo's own shape, pointed at the witness server instead of a bucket.
     */
    private fun tablelessLake(name: String) =
        Datasource(
            name = name,
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            credentialKind = CredentialKind.NONE,
            properties =
                DatasourceProperties(
                    dialect = mapOf("catalog.kind" to "s3", "region" to "us-east-1", "unsigned" to "true"),
                ),
        )

    /** The raw driver connection behind Hikari's proxy — its identity IS the physical connection. */
    private fun physicalId(connection: Connection): Int = System.identityHashCode(connection.unwrap(Connection::class.java))

    private fun cacheBytes(connection: Connection): Long =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT coalesce(sum(nr_bytes), 0) FROM duckdb_external_file_cache()").use { rs ->
                rs.next() shouldBe true
                rs.getLong(1)
            }
        }

    private fun rowOf(
        connection: Connection,
        sql: String,
    ): List<Long> =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next() shouldBe true
                (1..rs.metaData.columnCount).map { rs.getLong(it) }
            }
        }
}
