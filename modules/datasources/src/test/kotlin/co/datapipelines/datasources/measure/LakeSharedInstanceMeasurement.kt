package co.datapipelines.datasources.measure

import co.datapipelines.datasources.CountingParquetServer
import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 152 (#128) — **what does a LAKE pool's read cost across the lifecycle events that used to
 * discard its cache?** Same SQL, same data, same resource limits on every arm; the arms are the
 * investigation's (cold/warm × sequential/parallel) plus the two 152 adds: a PHYSICAL
 * replacement (`softEvict`, what maxLifetime/idleTimeout/validation do) and a WHOLE-POOL
 * rebuild (retire → reap → rebuild, what a registry change does).
 *
 * The remote object is a loopback [CountingParquetServer], so "file bytes read" is a counted
 * fact, not an inference from timing; `duckdb_external_file_cache()` is the engine-side half of
 * the same witness. Output rows are checksummed so every arm is shown to compute the same
 * answer. A failing arm is REPORTED as a row, not skipped — the failed arm is data.
 *
 * Runs on the base commit and on 152 by copying this file (and the server) beside each tree's
 * sources; the evidence directory holds both outputs. Reports, never asserts — gated on
 * `DP_MEASURE=1` like the executor's measurements.
 */
@EnabledIfEnvironmentVariable(named = "DP_MEASURE", matches = "1")
class LakeSharedInstanceMeasurement {
    @TempDir
    lateinit var tempDir: File

    private data class Arm(
        val name: String,
        val queries: Int,
        val millis: Long,
        val head: Int,
        val get: Int,
        val bytes: Long,
        val cacheBytes: Long,
        val checksum: String,
        val failure: String? = null,
    )

    @Test
    fun `read cost across cold, warm, parallel, physical replacement and whole-pool rebuild`() {
        val file = parquet()
        CountingParquetServer(file).use { server ->
            val sql =
                "SELECT pickup_zone, count(*) AS trips, round(sum(fare), 2) AS fare, round(avg(distance), 3) AS dist " +
                    "FROM read_parquet('${server.url}') GROUP BY pickup_zone ORDER BY pickup_zone"
            val ds = datasource()
            val arms = mutableListOf<Arm>()
            ConnectionPoolManager(poolFactory = { ConnectionPoolManager.buildHikariPool(it) }).use { manager ->
                val pool = manager.poolFor(ds)
                arms += arm("cold sequential (fresh pool, 1 lease)", server) { sequential(pool, sql, 1) }
                arms += arm("warm sequential (same pool, 3 leases)", server) { sequential(pool, sql, WARM_REPEATS) }
                arms += arm("warm parallel ($PARALLEL leases at once)", server) { parallel(pool, sql) }
                arms +=
                    arm("physical replacement (softEvict, then 1 lease)", server) {
                        pool.softEvict()
                        sequential(pool, sql, 1)
                    }
                arms += arm("after replacement, parallel ($PARALLEL leases)", server) { parallel(pool, sql) }
                arms +=
                    arm("whole-pool rebuild (retire, reap, 1 lease)", server) {
                        manager.retire(ds.name)
                        manager.reapRetiring()
                        sequential(manager.poolFor(ds), sql, 1)
                    }
                arms +=
                    arm("cold parallel (fresh pool, $PARALLEL leases at once)", server) {
                        manager.retire(ds.name)
                        manager.reapRetiring()
                        parallel(manager.poolFor(ds), sql)
                    }
                val budgets = manager.poolFor(ds).leaseConnection().use { c -> settings(c, "memory_limit", "threads") }
                println("### LAKE read cost across the pool lifecycle — ${file.length() / KB} KB Parquet, $ROWS rows, over loopback HTTP")
                println()
                println("Resource limits (instance-global): $budgets; pool `maximumPoolSize` default; unsigned, httpfs loaded.")
                println()
                println("| arm | queries | wall ms | HEAD | range GET | file bytes read | engine cache bytes after | checksum | failure |")
                println("|---|---|---|---|---|---|---|---|---|")
                arms.forEach { a ->
                    println(
                        "| ${a.name} | ${a.queries} | ${a.millis} | ${a.head} | ${a.get} | ${a.bytes} | ${a.cacheBytes} | " +
                            "${a.checksum} | ${a.failure ?: ""} |",
                    )
                }
                println()
                println("`file bytes read` is what the loopback server SERVED during the arm; 0 means the engine's")
                println("cache answered the scan. `checksum` is SHA-256 over the ordered result rows, truncated.")
            }
        }
    }

    // ------------------------------------------------------------------ arms

    private fun arm(
        name: String,
        server: CountingParquetServer,
        run: () -> Pair<Int, Pair<String, Long>>,
    ): Arm {
        server.reset()
        val started = System.nanoTime()
        return try {
            val (queries, result) = run()
            val (checksum, cacheBytes) = result
            val (head, get, bytes) = server.counts()
            Arm(name, queries, elapsedMs(started), head, get, bytes, cacheBytes, checksum)
        } catch (e: Exception) {
            val (head, get, bytes) = server.counts()
            Arm(name, 0, elapsedMs(started), head, get, bytes, -1, "-", failure = e.message?.lineSequence()?.first())
        }
    }

    /** [n] leases in a row, one query each; returns the query count, the last checksum and the cache size. */
    private fun sequential(
        pool: ConnectionPool,
        sql: String,
        n: Int,
    ): Pair<Int, Pair<String, Long>> {
        var last = "" to 0L
        repeat(n) { pool.leaseConnection().use { c -> last = checksum(c, sql) to cacheBytes(c) } }
        return n to last
    }

    /** [PARALLEL] leases held at once, each running the query; every checksum must agree. */
    private fun parallel(
        pool: ConnectionPool,
        sql: String,
    ): Pair<Int, Pair<String, Long>> {
        val executor = Executors.newFixedThreadPool(PARALLEL)
        try {
            val results =
                (1..PARALLEL)
                    .map { executor.submit<Pair<String, Long>> { pool.leaseConnection().use { c -> checksum(c, sql) to cacheBytes(c) } } }
                    .map { it.get(ARM_TIMEOUT_S, TimeUnit.SECONDS) }
            val checksums = results.map { it.first }.distinct()
            val checksum = if (checksums.size == 1) checksums.single() else "DISAGREE ${checksums.joinToString("/")}"
            return PARALLEL to (checksum to results.maxOf { it.second })
        } finally {
            executor.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ fixture

    /** Deterministic rows: a seeded generator, so base and 152 measure the same bytes. */
    private fun parquet(): File {
        val target = tempDir.resolve("trips.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use {
                it.execute(
                    "COPY (SELECT i AS id, i % 263 AS pickup_zone, (i * 7919 % 10007) / 100.0 AS fare, " +
                        "(i * 104729 % 5003) / 1000.0 AS distance, '2025-' || lpad(((i % 12) + 1)::VARCHAR, 2, '0') AS month " +
                        "FROM range(0, $ROWS) tbl(i)) TO '${target.absolutePath}' (FORMAT PARQUET, ROW_GROUP_SIZE 100000)",
                )
            }
        }
        return target
    }

    /** The public-bucket demo's shape: `catalog.kind=s3` loads httpfs, `unsigned` creates no secret. */
    private fun datasource() =
        Datasource(
            name = "lake_measure",
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            credentialKind = CredentialKind.NONE,
            properties =
                DatasourceProperties(
                    dialect =
                        mapOf(
                            "catalog.kind" to "s3",
                            "region" to "us-east-1",
                            "unsigned" to "true",
                            "memory_limit" to MEMORY_LIMIT,
                            "threads" to THREADS,
                        ),
                ),
        )

    private fun checksum(
        connection: Connection,
        sql: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = rs.metaData.columnCount
                while (rs.next()) {
                    digest.update((1..columns).joinToString("|") { rs.getString(it) }.toByteArray())
                    digest.update('\n'.code.toByte())
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(CHECKSUM_CHARS)
    }

    private fun cacheBytes(connection: Connection): Long =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT coalesce(sum(nr_bytes), 0) FROM duckdb_external_file_cache()").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun settings(
        connection: Connection,
        vararg names: String,
    ): String =
        names.joinToString(", ") { name ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT current_setting('$name')").use { rs ->
                    rs.next()
                    "$name=${rs.getString(1)}"
                }
            }
        }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MS

    private companion object {
        const val ROWS = 3_000_000L
        const val PARALLEL = 4
        const val WARM_REPEATS = 3
        const val ARM_TIMEOUT_S = 120L
        const val MEMORY_LIMIT = "512MB"
        const val THREADS = "4"
        const val CHECKSUM_CHARS = 12
        const val KB = 1024L
        const val NANOS_PER_MS = 1_000_000L
    }
}
