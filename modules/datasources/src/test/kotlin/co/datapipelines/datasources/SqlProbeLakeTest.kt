package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * §7D against a LAKE datasource (the [LakeTableStatsTest] pattern: a real pool over
 * `jdbc:duckdb:`, the registry's table rows as the catalog, a hive-partitioned Parquet fixture
 * written by DuckDB itself). This is the T199 measurement as a test: the pruning signal is
 * DuckDB's static `Scanning Files: x/y` plan marker, live-probed against duckdb_jdbc 1.5.5.1
 * (2026-09-09) — plain `EXPLAIN` carries it, so the probe never runs the statement twice.
 *
 * One finding the probe KDoc records: DuckDB 1.5.5.1 evaluates a deterministic predicate on the
 * partition column per PARTITION VALUE, so `CAST`/`UPPER` on the partition column prune too —
 * the "unpruned" arm is a predicate the engine cannot fold onto partition values, which reports
 * no marker (null/null, the honest "no pruning information").
 */
class SqlProbeLakeTest {
    @TempDir
    lateinit var tempDir: File

    private val adapter = DialectAdapters.forDialect(Dialect.LAKE)

    private fun lakeDatasource() =
        Datasource(
            name = "lake_probe",
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            credentialKind = CredentialKind.NONE,
        )

    private fun probeOver(rows: List<LakeRegisteredTable>): Pair<SqlProbe, Datasource> {
        val ds = lakeDatasource()
        val pool = ConnectionPoolManager.buildHikariPool(ds, LakeViewStatements.forTables(rows, adapter))
        val registry = io.mockk.mockk<DatasourceRegistry>()
        io.mockk.every { registry.poolFor(ds) } returns pool
        return SqlProbe(registry) to ds
    }

    @Test
    fun `a partition-column equality prunes - the plan reports one file of three`() {
        val (probe, ds) = probeOver(registeredTrips())

        val result = probe.probe(ds, "SELECT id, pickup_date FROM trips WHERE pickup_date = 'd1'")

        assertAll(
            { result.rows.rows.size shouldBe 33 },
            { result.rows.truncated shouldBe false },
            { result.plan shouldNotBe null },
            { result.plan!!.partitionsScanned shouldBe 1 },
            { result.plan!!.partitionsTotal shouldBe 3 },
            { result.plan!!.scan shouldBe "partition_prune 1/3" },
        )
    }

    @Test
    fun `a predicate the engine cannot fold onto partition values reports no pruning marker`() {
        val (probe, ds) = probeOver(registeredTrips())

        // Verified 2026-09-09: a range/LIKE on the partition column emits no "File Filters" and
        // no "Scanning Files" marker at all — null/null, not "3/3".
        val result = probe.probe(ds, "SELECT id FROM trips WHERE pickup_date >= 'd0'", limit = 200)

        assertAll(
            { result.rows.rows.size shouldBe 99 },
            { result.plan shouldNotBe null },
            { result.plan!!.partitionsScanned shouldBe null },
            { result.plan!!.partitionsTotal shouldBe null },
        )
    }

    @Test
    fun `a zoneless DuckDB TIMESTAMP column decodes through the probe`() {
        // The demo lake's hvfhv_trips.pickup_at is exactly this shape: DuckDB cannot hand a
        // zoneless TIMESTAMP out as OffsetDateTime ("Can't convert value to OffsetDateTime"),
        // so the reader falls back to LocalDateTime and the wire shows the canonical UTC form.
        val (probe, ds) = probeOver(registeredTrips())

        val result = probe.probe(ds, "SELECT picked_at FROM trips WHERE pickup_date = 'd1' LIMIT 2")

        result.rows.rows.first()["picked_at"] shouldBe "2024-06-15T10:30:00.000000Z"
    }

    @Test
    fun `CAST on the partition column still prunes - the pinned 1_5_5_1 behavior`() {
        val (probe, ds) = probeOver(registeredTrips())

        // Counter-intuitive but verified: DuckDB folds the deterministic cast per partition
        // value, so the file filter applies. Pinned so a future engine bump that stops folding
        // fails loudly here rather than silently changing what the probe reports.
        val result = probe.probe(ds, "SELECT id FROM trips WHERE CAST(pickup_date AS VARCHAR) = 'd1'")

        assertAll(
            { result.plan shouldNotBe null },
            { result.plan!!.partitionsScanned shouldBe 1 },
            { result.plan!!.partitionsTotal shouldBe 3 },
        )
    }

    /** The registered catalog row for the hive-partitioned fixture (three `pickup_date` partitions). */
    private fun registeredTrips(): List<LakeRegisteredTable> =
        listOf(
            LakeRegisteredTable(
                listOf("nyc", "mobility"),
                "trips",
                "parquet",
                "file://${seedPartitionedParquet().absolutePath}",
                partitionColumn = "pickup_date",
            ),
        )

    /** Three hive partitions (`pickup_date=d0|d1|d2`), 99 rows, via DuckDB's own writer. */
    private fun seedPartitionedParquet(): File {
        val dir = tempDir.resolve("hive")
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use {
                it.execute(
                    "COPY (SELECT i AS id, 'd' || (i % 3) AS pickup_date, " +
                        "TIMESTAMP '2024-06-15 10:30:00' AS picked_at FROM range(1, 100) t(i)) " +
                        "TO '${dir.absolutePath}' (FORMAT PARQUET, PARTITION_BY (pickup_date))",
                )
            }
        }
        return dir
    }
}
