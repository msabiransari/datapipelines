package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.Connection
import java.sql.DriverManager

/**
 * §7C catalog statistics against the real Postgres (the module's shared container): after an
 * ANALYZE, `pg_class.reltuples` is exact, `pg_stats` carries n_distinct/null_frac/histogram
 * bounds, and `pg_index` orders a composite key. The shared database is module-wide — the
 * fixture table carries a task-unique name and is dropped after the class.
 */
class TableStatsPostgresIntegrationTest {
    private val postgres = SharedPostgres.postgres

    private val ds =
        Datasource(
            name = "pg_stats",
            displayName = "PG",
            dialect = Dialect.POSTGRES,
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            secret = postgres.password,
        )

    private val registry = mockk<DatasourceRegistry>()
    private val introspector = SchemaIntrospector(registry)

    @BeforeEach
    fun wire() {
        every { registry.get(ds.name) } returns ds
        every { registry.poolFor(ds) } returns
            object : ConnectionPool {
                override val name: String = ds.name

                override fun leaseConnection(): Connection =
                    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

                override fun close() = Unit
            }
    }

    @BeforeEach
    fun seed() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS task107_stats_probe")
                st.execute("CREATE TABLE task107_stats_probe (id INTEGER PRIMARY KEY, grp INTEGER, note VARCHAR(50))")
                st.execute("CREATE UNIQUE INDEX idx_task107_grp_note ON task107_stats_probe(grp, note)")
                st.execute(
                    "INSERT INTO task107_stats_probe " +
                        "SELECT i, i % 10, CASE WHEN i % 10 = 0 THEN NULL ELSE 'note-' || i END " +
                        "FROM generate_series(1, 1000) i",
                )
                st.execute("ANALYZE task107_stats_probe")
            }
        }
    }

    @AfterEach
    fun cleanup() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE IF EXISTS task107_stats_probe") }
        }
    }

    @Test
    fun `an analyzed table reports reltuples, the analyze time, and ordered indexes`() {
        val stats = introspector.tableStats(ds.name, "task107_stats_probe")

        val pkey = stats.indexes.single { it.primary }
        val composite = stats.indexes.single { it.name == "idx_task107_grp_note" }
        assertAll(
            { stats.statsSource shouldBe "pg_class" },
            // reltuples is exact for a freshly analyzed small table.
            { stats.rowEstimate shouldBe "1000" },
            // greatest(last_analyze, last_autoanalyze) — set by the explicit ANALYZE.
            { stats.statsAsOf shouldNotBe null },
            { pkey.name shouldEndWith "_pkey" },
            { pkey.unique shouldBe true },
            { pkey.columns shouldContainExactly listOf("id") },
            { composite.unique shouldBe true },
            { composite.primary shouldBe false },
            { composite.columns shouldContainExactly listOf("grp", "note") },
        )
    }

    @Test
    fun `pg_stats carries distinct counts, null fractions, and histogram bounds`() {
        val stats = introspector.tableStats(ds.name, "task107_stats_probe")

        val id = stats.columns.single { it.name == "id" }
        val grp = stats.columns.single { it.name == "grp" }
        val note = stats.columns.single { it.name == "note" }
        assertAll(
            // A fully distinct column stores n_distinct = -1 (a fraction of the table) —
            // normalized against reltuples to the estimate, never reported as "-1".
            { id.nDistinct shouldBe "1000" },
            { id.nDistinct shouldNotStartWith "-" },
            { id.distinctIsRatio shouldBe false },
            { id.min shouldBe "1" },
            { id.max shouldBe "1000" },
            // Ten distinct groups: a positive, small estimate.
            { grp.nDistinct shouldNotBe null },
            { grp.nDistinct!!.toLong() shouldBeGreaterThan 0L },
            { grp.distinctIsRatio shouldBe false },
            // 100 of 1000 rows null.
            { note.nullFraction!! shouldBeGreaterThan 0.05 },
            { note.nullFraction!! shouldBeLessThan 0.15 },
        )
    }

    @Test
    fun `an unknown table answers empty stats against the named catalog, not an error`() {
        val stats = introspector.tableStats(ds.name, "task107_no_such_table")

        assertAll(
            { stats.statsSource shouldBe "pg_class" },
            { stats.rowEstimate shouldBe null },
            { stats.indexes shouldBe emptyList<IndexStats>() },
            { stats.columns shouldBe emptyList<ColumnStats>() },
        )
    }
}
