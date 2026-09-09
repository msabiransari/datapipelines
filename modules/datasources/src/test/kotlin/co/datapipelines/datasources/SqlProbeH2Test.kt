package co.datapipelines.datasources

import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DriverManager

/**
 * §7D probes over a real in-memory H2 (the [SqlRunnerTest] rationale: caps, decoding and the
 * plan read exercised against actual driver behavior; the timeout path is pinned against the
 * real Postgres in [SqlProbePostgresIntegrationTest], and the LAKE pruning read in
 * [SqlProbeLakeTest]).
 */
class SqlProbeH2Test {
    private val h2 = DriverManager.getConnection("jdbc:h2:mem:sqlprobe;DB_CLOSE_DELAY=-1")
    private val registry = mockk<DatasourceRegistry>()
    private val probe = SqlProbe(registry)

    private fun wireDatasource(
        datasource: Datasource = Fixtures.h2(name = "h2-probe", jdbcUrl = "jdbc:h2:mem:sqlprobe;DB_CLOSE_DELAY=-1"),
    ): Datasource {
        every { registry.poolFor(datasource) } returns JdbcUrlPool(datasource.jdbcUrl, datasource.name)
        return datasource
    }

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `a probe returns capped wire-encoded rows, wall time, and the H2 plan summary`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE trips (id INT PRIMARY KEY, city VARCHAR(40))")
            (1..8).forEach { i -> st.execute("INSERT INTO trips VALUES ($i, 'c$i')") }
        }
        val ds = wireDatasource()

        val result = probe.probe(ds, "SELECT id, city FROM trips", limit = 3)

        assertAll(
            { result.rows.rows.size shouldBe 3 },
            { result.rows.truncated shouldBe true },
            {
                result.rows.schema.columns
                    .map { it.name } shouldBe listOf("ID", "CITY")
            },
            { result.wallMs shouldBeGreaterThanOrEqualTo 0L },
            // H2's plan is the rewritten statement with the access path in /* ... */ comments;
            // a table with a primary key may scan through it, so the summary is an index or a
            // table scan — both prove the marker parsed. The exact strings are pinned in
            // ExplainPlanParserTest.
            { result.plan shouldNotBe null },
            { result.plan!!.scan shouldNotBe null },
            { result.plan!!.raw shouldContain "TRIPS" },
        )
    }

    @Test
    fun `named parameters bind through the house grammar - a BIGINT arrives as its wire string`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE events (id BIGINT, payload VARCHAR(40))")
            st.execute("INSERT INTO events VALUES (9223372036854775806, 'big')")
            st.execute("INSERT INTO events VALUES (1, 'small')")
        }
        val ds = wireDatasource()

        val result =
            probe.probe(
                ds,
                "SELECT id, payload FROM events WHERE id = :id",
                parameters = mapOf("id" to SqlProbeParameter(LogicalType.BIGINTEGER, "9223372036854775806")),
            )

        assertAll(
            { result.rows.rows.size shouldBe 1 },
            // BIGINTEGER is string-on-wire (type-system §7.3) — out and back as the same string.
            { result.rows.rows.single()["ID"] shouldBe "9223372036854775806" },
            { result.rows.rows.single()["PAYLOAD"] shouldBe "big" },
        )
    }

    @Test
    fun `a missing parameter is refused before anything runs`() {
        h2.createStatement().use { it.execute("CREATE TABLE untouched (id INT)") }
        val ds = wireDatasource()

        val thrown =
            shouldThrow<SqlProbeParameterException> {
                probe.probe(ds, "SELECT * FROM untouched WHERE id = :id")
            }

        thrown.parameter shouldBe "id"
    }

    @Test
    fun `an uncoercible parameter value is refused with the parameter named`() {
        val ds = wireDatasource()

        val thrown =
            shouldThrow<SqlProbeParameterException> {
                probe.probe(ds, "SELECT 1 WHERE 1 = :n", parameters = mapOf("n" to SqlProbeParameter(LogicalType.INTEGER, "not-a-number")))
            }

        assertAll(
            { thrown.parameter shouldBe "n" },
            { thrown.declaredType shouldBe LogicalType.INTEGER },
        )
    }

    @Test
    fun `the row cap clamps rather than refuses, and truncation still reports`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE big (id INT)")
            (1..600).forEach { i -> st.execute("INSERT INTO big VALUES ($i)") }
        }
        val ds = wireDatasource()

        val result = probe.probe(ds, "SELECT id FROM big", limit = SqlProbe.MAX_LIMIT + 100)

        assertAll(
            { result.rows.rows.size shouldBe SqlProbe.MAX_LIMIT },
            { result.rows.truncated shouldBe true },
        )
    }

    @Test
    fun `a refused probe executes nothing`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE guarded (id INT)")
            st.execute("INSERT INTO guarded VALUES (1)")
        }
        val ds = wireDatasource()

        shouldThrow<SqlProbeRefusalException> { probe.probe(ds, "INSERT INTO guarded VALUES (2)") }
        shouldThrow<SqlProbeRefusalException> { probe.probe(ds, "SELECT id FROM guarded; DELETE FROM guarded") }

        h2.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM guarded").use { rs ->
                rs.next()
                rs.getInt(1) shouldBe 1
            }
        }
    }

    @Test
    fun `a statement the engine refuses reports a static message and the bounded driver text one level down`() {
        val ds = wireDatasource()
        val sql = "SELECT * FROM no_such_probe_table"

        val thrown = shouldThrow<SqlProbeExecutionException> { probe.probe(ds, sql) }

        assertAll(
            // The probe's own message is static — H2's driver text ECHOES the statement, so it
            // must live one level down, never in the probe's message.
            { thrown.message shouldBe "The probed statement failed against datasource 'h2-probe'." },
            { thrown.message.orEmpty() shouldNotContain sql },
            { thrown.driverMessage.length shouldBeLessThanOrEqualTo SqlExecutionException.MAX_MESSAGE_CHARS },
            { thrown.driverMessage shouldContain "NO_SUCH_PROBE_TABLE" },
        )
    }
}
