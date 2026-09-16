package co.datapipelines.datasources

import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DriverManager
import java.sql.SQLException

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

    // ------------------------------------------------------------------ the tempdb scratch check (2026-09-11; #119 2026-09-16)

    /**
     * A sibling H2 opened with the scratch probe's OWN url shape (staging mode, lower-folding)
     * but kept alive and pre-populated with [ddl] — the counterexample engine: what the same
     * driver says about the same statement once the tables exist.
     */
    private fun scratchModeDatasourceWith(vararg ddl: String): Datasource {
        val url =
            "jdbc:h2:mem:scratch_ce_${System.nanoTime()};MODE=${SqlProbe.DEFAULT_SCRATCH_MODE};" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url).use { c -> c.createStatement().use { st -> ddl.forEach { st.execute(it) } } }
        return wireDatasource(Fixtures.h2(name = "scratch-counterexample", jdbcUrl = url))
    }

    private fun h2ErrorCode(thrown: SqlProbeExecutionException): Int = (thrown.cause as SQLException).errorCode

    /**
     * #119 — the engine premise, proven on the pinned driver, not mocked: H2 stops PREPARING at
     * the first table it cannot find, so a syntax error AFTER that table is invisible to the
     * empty-scratch check. The same statement against a sibling H2 in the same mode where the
     * tables exist is a `42000` syntax error at `OUTER` — which is what a real staged execution
     * then reports. The pre-#119 outcome called this "Parsed; every self-defined name resolved".
     * It is [ScratchProbeOutcome.Incomplete], naming the table and claiming nothing after it.
     */
    @Test
    fun `scratch - a missing table masks a later syntax error, so the outcome is Incomplete, not a pass`() {
        val outcome = probe.probeScratch(MASKED_FULL_OUTER_JOIN)

        outcome.shouldBeInstanceOf<ScratchProbeOutcome.Incomplete>()
        outcome.missingTable shouldBe "absent_a"

        // The counterexample, same driver, same mode: with both tables present the engine
        // reaches the join keyword and refuses it. Had the scratch outcome meant "the SQL is
        // sound", this statement would run here.
        val withTables = scratchModeDatasourceWith("CREATE TABLE absent_a (id INT)", "CREATE TABLE absent_b (id INT)")
        val thrown = shouldThrow<SqlProbeExecutionException> { probe.probe(withTables, MASKED_FULL_OUTER_JOIN) }
        assertAll(
            { h2ErrorCode(thrown) shouldBe H2_SYNTAX_ERROR },
            { thrown.driverMessage shouldContain "OUTER" },
        )
    }

    /** #119 — the same masking hides a misspelt clause and an unknown column, not only a join form. */
    @Test
    fun `scratch - a missing table also masks a misspelt clause and a bad column after it`() {
        val misspelt = "SELECT a.id FROM absent_a a WHERE a.id > 0 GROUPP BY a.id"
        val badColumn = "SELECT a.no_such_col FROM absent_a a"

        probe.probeScratch(misspelt).shouldBeInstanceOf<ScratchProbeOutcome.Incomplete>()
        probe.probeScratch(badColumn).shouldBeInstanceOf<ScratchProbeOutcome.Incomplete>()

        val withTable = scratchModeDatasourceWith("CREATE TABLE absent_a (id INT)")
        assertAll(
            { h2ErrorCode(shouldThrow<SqlProbeExecutionException> { probe.probe(withTable, misspelt) }) shouldBe H2_SYNTAX_ERROR },
            { shouldThrow<SqlProbeExecutionException> { probe.probe(withTable, badColumn) }.driverMessage shouldContain "no_such_col" },
        )
    }

    /**
     * The boundary of the masking, so the note claims no more than the engine does: H2 resolves
     * a FUNCTION name while parsing the select list, BEFORE it resolves the FROM tables — so an
     * unknown function is a real error even over a missing table, while a column (resolved after
     * the tables) is masked (the test above).
     */
    @Test
    fun `scratch - an unknown function is NOT masked by a missing table`() {
        val thrown = shouldThrow<SqlProbeExecutionException> { probe.probeScratch("SELECT no_such_fn(a.id) FROM absent_a a") }

        thrown.driverMessage shouldContain "no_such_fn"
    }

    /**
     * A VALID statement over a staged table that does not exist is the SAME outcome — incomplete
     * is not "invalid". It keeps the missing-table evidence, its binds still go through the
     * house grammar, and the wall time is measured.
     */
    @Test
    fun `scratch - a valid statement over a missing staged table is Incomplete with the table named`() {
        val outcome =
            probe.probeScratch(
                "SELECT t.hr, s.n FROM (VALUES (0),(1)) AS t(hr) LEFT JOIN stg_orders s ON s.hr = t.hr WHERE s.n > :min",
                parameters = mapOf("min" to SqlProbeParameter(LogicalType.INTEGER, "1")),
            )

        outcome.shouldBeInstanceOf<ScratchProbeOutcome.Incomplete>()
        assertAll(
            { outcome.missingTable shouldBe "stg_orders" },
            { outcome.wallMs shouldBeGreaterThanOrEqualTo 0L },
        )
    }

    /** A self-contained statement that H2 itself refuses is the structured error, never Incomplete. */
    @Test
    fun `scratch - an invalid self-contained statement is the execution exception, not Incomplete`() {
        val thrown = shouldThrow<SqlProbeExecutionException> { probe.probeScratch("SELECT t.x FORM (VALUES (1)) AS t(x)") }

        h2ErrorCode(thrown) shouldBe H2_SYNTAX_ERROR
    }

    /** The defect that cost a full DAG run: H2 names VALUES columns C1, not column1. */
    @Test
    fun `scratch - an H2 name error is the execution exception with H2's message`() {
        val thrown =
            shouldThrow<SqlProbeExecutionException> {
                probe.probeScratch("SELECT CAST(column1 AS INTEGER) AS hr FROM (VALUES (0),(1),(2))")
            }
        thrown.driverMessage shouldContain "column1"
    }

    /** A self-contained statement runs and returns rows, exactly as a real probe would. */
    @Test
    fun `scratch - a self-contained statement returns rows`() {
        val outcome = probe.probeScratch("SELECT t.hr FROM (VALUES (0),(1),(2)) AS t(hr) ORDER BY t.hr")

        outcome.shouldBeInstanceOf<ScratchProbeOutcome.Rows>()
        outcome.result.rows.rows.size shouldBe 3
        outcome.result.rows.schema.columns
            .single()
            .name shouldBe "hr"
    }

    /** A self-contained statement with a bound parameter runs too — the binder is the pipeline's. */
    @Test
    fun `scratch - a bound self-contained statement returns the rows the bind selects`() {
        val outcome =
            probe.probeScratch(
                "SELECT t.hr FROM (VALUES (0),(1),(2)) AS t(hr) WHERE t.hr >= :min ORDER BY t.hr",
                parameters = mapOf("min" to SqlProbeParameter(LogicalType.INTEGER, "1")),
            )

        outcome.shouldBeInstanceOf<ScratchProbeOutcome.Rows>()
        val selected =
            outcome.result.rows.rows
                .map { it["hr"].toString() }
        selected shouldBe listOf("1", "2")
    }

    /** The classifier still guards the scratch engine: nothing but one SELECT/WITH runs. */
    @Test
    fun `scratch - a non-SELECT is refused before any engine opens`() {
        shouldThrow<SqlProbeRefusalException> { probe.probeScratch("DROP TABLE stg_orders") }
    }

    private companion object {
        /**
         * #119's witness (spec §2): on an empty scratch the missing table is reported; with both
         * tables present H2 2.3.232 refuses `FULL OUTER JOIN` at `OUTER`. An acceptance audit
         * matched this shape's SQL hash between a "passing" probe and the failing execution.
         */
        const val MASKED_FULL_OUTER_JOIN = "SELECT a.id FROM absent_a a FULL OUTER JOIN absent_b b ON a.id = b.id"

        /** H2's `SYNTAX_ERROR_1`/`_2` error code. */
        const val H2_SYNTAX_ERROR = 42000
    }
}
