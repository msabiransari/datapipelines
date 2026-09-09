package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.Connection
import java.sql.DriverManager

/**
 * §7D probes against the module's shared Postgres container (the [TableStatsPostgresIntegrationTest]
 * pattern: one fresh connection pool per lease, a task-named fixture table dropped after the
 * class). This suite owns the shapes H2 cannot produce: pgjdbc's queryTimeout arriving as a
 * server-side cancel (SQLState 57014), and a real `Seq Scan` plan with a row estimate.
 */
class SqlProbePostgresIntegrationTest {
    private val postgres = SharedPostgres.postgres

    private val ds =
        Datasource(
            name = "pg_probe",
            displayName = "PG",
            dialect = Dialect.POSTGRES,
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            secret = postgres.password,
        )

    private val registry = mockk<DatasourceRegistry>()
    private val probe = SqlProbe(registry)

    @BeforeEach
    fun wire() {
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
                st.execute("DROP TABLE IF EXISTS task107_probe")
                st.execute("CREATE TABLE task107_probe (id BIGINT PRIMARY KEY, note VARCHAR(50))")
                st.execute("INSERT INTO task107_probe SELECT i, 'note-' || i FROM generate_series(1, 20) i")
            }
        }
    }

    @AfterEach
    fun cleanup() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("DROP TABLE IF EXISTS task107_probe") }
        }
    }

    @Test
    fun `a probe returns capped rows, wall time, and the seq-scan plan with its estimate`() {
        val result = probe.probe(ds, "SELECT id, note FROM task107_probe", limit = 5)

        assertAll(
            { result.rows.rows.size shouldBe 5 },
            { result.rows.truncated shouldBe true },
            {
                result.rows.schema.columns
                    .map { it.name } shouldBe listOf("id", "note")
            },
            { result.plan shouldNotBe null },
            { result.plan!!.scan shouldBe "seq" },
            { result.plan!!.estimatedRows shouldNotBe null },
            { result.plan!!.raw shouldContain "Seq Scan on task107_probe" },
        )
    }

    @Test
    fun `a BIGINT parameter arrives as its wire string and binds against the bigint column`() {
        val result =
            probe.probe(
                ds,
                "SELECT id, note FROM task107_probe WHERE id = :id",
                parameters = mapOf("id" to SqlProbeParameter(LogicalType.BIGINTEGER, "7")),
            )

        assertAll(
            { result.rows.rows.size shouldBe 1 },
            { result.rows.rows.single()["id"] shouldBe "7" },
            { result.rows.rows.single()["note"] shouldBe "note-7" },
        )
    }

    @Test
    fun `a timed-out probe is SqlProbeTimeoutException with wall time and the pre-executed plan attached`() {
        val thrown =
            shouldThrow<SqlProbeTimeoutException> {
                probe.probe(ds, "SELECT pg_sleep(20)", timeoutSeconds = 1)
            }

        assertAll(
            // The 1s timebox fired; generous headroom under the 10s budget keeps a loaded CI box honest.
            { thrown.wallMs shouldBeLessThan 10_000L },
            // The plan was read BEFORE the query ran — the whole point of reading it first.
            // Plain EXPLAIN reports the `Result` node and its estimate; the expression itself
            // (pg_sleep) appears only in EXPLAIN ANALYZE / VERBOSE shapes.
            { thrown.plan shouldNotBe null },
            { thrown.plan!!.estimatedRows shouldBe "1" },
            { thrown.plan!!.raw shouldContain "Result" },
            // pgjdbc delivers the timeout as a server cancel (57014) — not the lease's
            // connection-failure family, and never DatasourceUnreachableException.
            { thrown.message shouldBe "The probe statement exceeded its timeout against datasource 'pg_probe'." },
        )
    }

    @Test
    fun `a refused probe executes nothing against the fixture`() {
        shouldThrow<SqlProbeRefusalException> { probe.probe(ds, "DELETE FROM task107_probe") }

        val count =
            probe.probe(ds, "SELECT COUNT(*) AS n FROM task107_probe")
        count.rows.rows.single()["n"] shouldBe "20"
    }
}
