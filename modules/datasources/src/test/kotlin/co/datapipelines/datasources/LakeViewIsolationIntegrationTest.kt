package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.LakeViewInit
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * 109 §A, proven over a REAL pool ([LakePoolViewsIntegrationTest]'s harness: Hikari + in-memory
 * DuckDB + `file://` parquet in [@TempDir]): the per-table-isolated view application.
 *
 * What this pins is the round's whole reason to exist: one registered table whose view cannot
 * be created (a file that is not Parquet; an unmappable namespace) no longer fails the pool —
 * the connect succeeds, the healthy tables query, the broken one is RECORDED through the
 * [LakeViewOutcomeRecorder] seam and skipped, the recording is transition-only, and a fixed
 * table's error clears on the next pool build. The last test is the falsification: the SAME
 * registry through the pre-109 strict composition (one joined `connectionInitSql`) fails the
 * pool build outright — the failure mode the isolation removes.
 */
class LakeViewIsolationIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private val adapter = DialectAdapters.forDialect(Dialect.LAKE)

    private fun lakeDatasource(name: String) =
        Datasource(
            name = name,
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            credentialKind = CredentialKind.NONE,
        )

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

    /** A file with a Parquet name and plain-text content — the classic broken registration. */
    private fun notParquet(file: String): File = tempDir.resolve(file).apply { writeText("this is not parquet") }

    /** A recorder over a mutable event list — (qualified name, recorded error) pairs, in order. */
    private class RecordingOutcomes {
        val events = mutableListOf<Pair<String, String?>>()

        val recorder =
            LakeViewOutcomeRecorder { datasourceName, namespace, table, error ->
                events += "$datasourceName/${(namespace + table).joinToString(".")}" to error
            }
    }

    private fun poolManager(
        rows: () -> List<LakeRegisteredTable>,
        outcomes: RecordingOutcomes,
    ) = ConnectionPoolManager(
        poolFactory = { datasource ->
            ConnectionPoolManager.buildHikariPool(
                datasource,
                lakeViews =
                    LakeViewInit(
                        datasourceName = datasource.name,
                        plan = LakeViewStatements.planForTables(rows(), adapter),
                        recorder = outcomes.recorder,
                    ),
            )
        },
    )

    @Test
    fun `a failing view is recorded and skipped - the healthy table stays reachable`() {
        val tripsFile = parquet("trips.parquet", "SELECT 1 AS id, 'a' AS label UNION ALL SELECT 2, 'b'")
        val brokenFile = notParquet("broken.parquet")
        val rows =
            listOf(
                LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_trips", "parquet", "file://${tripsFile.absolutePath}"),
                LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_broken", "parquet", "file://${brokenFile.absolutePath}"),
            )
        val outcomes = RecordingOutcomes()
        val ds = lakeDatasource("lake_isolation")

        poolManager({ rows }, outcomes).use { manager ->
            // The connect SUCCEEDS — the broken table no longer takes the pool down.
            manager.poolFor(ds).leaseConnection().use { connection ->
                assertAll(
                    { countOf(connection, "SELECT count(*) FROM hvfhv_trips") shouldBe 2 },
                    { runsSql(connection, "SELECT count(*) FROM hvfhv_broken") shouldBe false },
                )
            }
            assertAll(
                // Exactly one recorded outcome: the broken table's engine error…
                { outcomes.events.size shouldBe 1 },
                {
                    val (qualified, error) = outcomes.events.single()
                    qualified shouldBe "lake_isolation/nyc.mobility.hvfhv_broken"
                    error.shouldNotBeNull()
                },
                // …and nothing for the healthy one: null → null is not a transition.
                { outcomes.events.none { it.first.endsWith("hvfhv_trips") } shouldBe true },
            )
        }
    }

    @Test
    fun `the recording is transition-only - a second physical connection re-records nothing`() {
        val brokenFile = notParquet("broken.parquet")
        val rows = listOf(LakeRegisteredTable(listOf("nyc"), "broken", "parquet", "file://${brokenFile.absolutePath}"))
        val outcomes = RecordingOutcomes()
        val ds = lakeDatasource("lake_transitions")

        poolManager({ rows }, outcomes).use { manager ->
            val pool = manager.poolFor(ds)
            // Two connections held at once force two PHYSICAL connections, each applying the plan.
            pool.leaseConnection().use { first ->
                pool.leaseConnection().use { second ->
                    runsSql(first, "SELECT 1") shouldBe true
                    runsSql(second, "SELECT 1") shouldBe true
                }
            }
            outcomes.events.map { it.first } shouldContainExactly listOf("lake_transitions/nyc.broken")
        }
    }

    @Test
    fun `a fixed table clears the recorded error on the next pool build`() {
        val goodFile = parquet("fixed.parquet", "SELECT 7 AS id")
        val brokenFile = notParquet("broken.parquet")
        val rows =
            mutableListOf(
                LakeRegisteredTable(listOf("nyc"), "flaky", "parquet", "file://${brokenFile.absolutePath}"),
            )
        val outcomes = RecordingOutcomes()
        val ds = lakeDatasource("lake_recovers")

        poolManager({ rows.toList() }, outcomes).use { manager ->
            manager.poolFor(ds).leaseConnection().use { connection ->
                runsSql(connection, "SELECT count(*) FROM flaky") shouldBe false
            }
            outcomes.events.size shouldBe 1

            // The operator fixes the location; the registry mutation retires the pool and the
            // rebuild applies the fresh plan — success after error CLEARS the recording.
            rows[0] = rows[0].copy(location = "file://${goodFile.absolutePath}", lastError = outcomes.events.single().second)
            manager.retire(ds.name) shouldBe true
            manager.reapRetiring()
            manager.poolFor(ds).leaseConnection().use { connection ->
                withClue("the fixed table was not queryable after the rebuild") {
                    countOf(connection, "SELECT count(*) FROM flaky") shouldBe 1
                }
            }
            outcomes.events shouldContainExactly
                listOf(
                    "lake_recovers/nyc.flaky" to outcomes.events[0].second,
                    "lake_recovers/nyc.flaky" to null,
                )
        }
    }

    @Test
    fun `an emission-refused table - a 3-segment namespace - is recorded without touching the engine`() {
        val tripsFile = parquet("trips.parquet", "SELECT 1 AS id")
        val rows =
            listOf(
                LakeRegisteredTable(listOf("nyc"), "hvfhv_trips", "parquet", "file://${tripsFile.absolutePath}"),
                LakeRegisteredTable(listOf("a", "b", "c"), "unmappable", "parquet", "file://${tripsFile.absolutePath}"),
            )
        val outcomes = RecordingOutcomes()
        val ds = lakeDatasource("lake_unmappable")

        poolManager({ rows }, outcomes).use { manager ->
            manager.poolFor(ds).leaseConnection().use { connection ->
                // Two namespaces → no search-path rule; the healthy table answers qualified.
                countOf(connection, "SELECT count(*) FROM \"nyc\".\"hvfhv_trips\"") shouldBe 1
            }
            outcomes.events.size shouldBe 1
            val (qualified, error) = outcomes.events.single()
            qualified shouldBe "lake_unmappable/a.b.c.unmappable"
            error.shouldNotBeNull() shouldContain "3-segment namespace"
        }
    }

    @Test
    fun `FALSIFICATION - the pre-109 strict composition fails the whole pool build on one bad view`() {
        val tripsFile = parquet("trips.parquet", "SELECT 1 AS id")
        val brokenFile = notParquet("broken.parquet")
        val rows =
            listOf(
                LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_trips", "parquet", "file://${tripsFile.absolutePath}"),
                LakeRegisteredTable(listOf("nyc", "mobility"), "hvfhv_broken", "parquet", "file://${brokenFile.absolutePath}"),
            )
        val ds = lakeDatasource("lake_legacy")
        val manager =
            ConnectionPoolManager(
                poolFactory = { datasource ->
                    // The pre-109 shape: every statement in ONE connectionInitSql string — one
                    // failing view fails the connection, and the pool build with it.
                    ConnectionPoolManager.buildHikariPool(
                        datasource,
                        LakeViewStatements.forTables(rows, adapter),
                    )
                },
            )

        manager.use {
            shouldThrow<Exception> { manager.poolFor(ds) }
            withClue("the legacy pool build must fail before any healthy table can be read") {
                manager.hasPool(ds.name) shouldBe false
            }
        }
    }

    private fun countOf(
        connection: java.sql.Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }

    /** Whether [sql] runs at all — false on the engine's unknown-table failure. */
    private fun runsSql(
        connection: java.sql.Connection,
        sql: String,
    ): Boolean =
        runCatching {
            connection.createStatement().use { st ->
                st.executeQuery(sql).use { it.next() }
            }
        }.isSuccess
}
