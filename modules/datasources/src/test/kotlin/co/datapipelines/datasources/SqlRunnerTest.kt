package co.datapipelines.datasources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger

/**
 * §7B query behavior over a **real** in-memory H2 — rows come from a live JDBC driver, so the
 * caps, quoting, decoding and wire encoding are exercised against actual driver behavior (the
 * same rationale as [SchemaIntrospectorH2Test]; the dialect-syntax table itself lives in
 * [DialectAdaptersTest], and the live demo-stack evidence covers Postgres/MySQL).
 */
class SqlRunnerTest {
    private val h2 = DriverManager.getConnection("jdbc:h2:mem:sqlrunner;DB_CLOSE_DELAY=-1")
    private val registry = mockk<DatasourceRegistry>()
    private val runner = SqlRunner(registry)

    /** A pool that hands out fresh connections to the same named in-memory DB. */
    private val pool = JdbcUrlPool("jdbc:h2:mem:sqlrunner;DB_CLOSE_DELAY=-1", "h2-run")

    private fun wireDatasource(datasource: Datasource = Fixtures.h2(name = "h2-run", jdbcUrl = "jdbc:h2:mem:sqlrunner;DB_CLOSE_DELAY=-1")): Datasource {
        every { registry.poolFor(datasource) } returns pool
        return datasource
    }

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `previewTable returns capped wire-encoded rows with the canonical schema`() {
        h2.createStatement().use { st ->
            st.execute("""CREATE TABLE trips (id INT PRIMARY KEY, city VARCHAR(40), fare DECIMAL(10,2))""")
            (1..8).forEach { i -> st.execute("INSERT INTO trips VALUES ($i, 'c$i', $i.50)") }
        }
        val ds = wireDatasource()

        val result = runner.previewTable(ds, "TRIPS", orderBy = listOf(OrderByTerm("FARE", descending = true)), limit = 3)

        assertAll(
            { result.rows.size shouldBe 3 },
            { result.truncated shouldBe true },
            // DESC actually ordered — the highest fares first, proving ORDER BY reached the engine.
            // DECIMAL keeps its declared scale on the wire (§3.5): "8.50", not 8.5.
            { result.rows.map { (it["FARE"] as BigDecimal).toPlainString() } shouldBe listOf("8.50", "7.50", "6.50") },
            // H2 stores unquoted identifiers uppercased; the canonical schema names match the driver's labels.
            { result.schema.columns.map { it.name } shouldBe listOf("ID", "CITY", "FARE") },
            { result.rows.first().keys shouldBe setOf("ID", "CITY", "FARE") },
        )
    }

    @Test
    fun `previewTable qualifies the schema it was given - same-named tables do not merge`() {
        h2.createStatement().use { st ->
            st.execute("CREATE SCHEMA other")
            st.execute("CREATE TABLE other.orders (id INT)")
            st.execute("INSERT INTO other.orders VALUES (7)")
            st.execute("CREATE TABLE orders (id INT)")
            st.execute("INSERT INTO orders VALUES (1)")
        }
        val ds = wireDatasource()

        val result = runner.previewTable(ds, "ORDERS", schema = "OTHER", limit = 5)

        result.rows.single()["ID"] shouldBe 7L
    }

    @Test
    fun `previewTable without order_by returns engine-arbitrary top-N and reports truncation`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE t (id INT)")
            (1..5).forEach { i -> st.execute("INSERT INTO t VALUES ($i)") }
        }
        val ds = wireDatasource()

        val uncapped = runner.previewTable(ds, "T", limit = 10)
        val capped = runner.previewTable(ds, "T", limit = 2)

        assertAll(
            { uncapped.rows.size shouldBe 5 },
            { uncapped.truncated shouldBe false },
            { capped.rows.size shouldBe 2 },
            { capped.truncated shouldBe true },
        )
    }

    @Test
    fun `select binds positional values and decodes temporal and string types through the wire encoder`() {
        h2.createStatement().use { st ->
            st.execute("""CREATE TABLE events (id INT, event_day DATE, payload VARCHAR(200))""")
            st.execute("""INSERT INTO events VALUES (1, DATE '2026-09-01', 'hello')""")
        }
        val ds = wireDatasource()

        val result =
            runner.select(
                ds,
                "SELECT id, event_day, payload FROM events WHERE id = ?",
                bindValues = listOf(1),
                limit = 10,
            )

        val row = result.rows.single()
        assertAll(
            { row["ID"] shouldBe 1L },
            { row["EVENT_DAY"] shouldBe "2026-09-01" },
            { row["PAYLOAD"] shouldBe "hello" },
        )
    }

    @Test
    fun `executeUpdate runs DML for real and reports affected rows`() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE deletions (id INT)")
            (1..4).forEach { i -> st.execute("INSERT INTO deletions VALUES ($i)") }
        }
        val ds = wireDatasource()

        val affected = runner.executeUpdate(ds, "DELETE FROM deletions WHERE id > ?", bindValues = listOf(2))

        affected shouldBe 2L
        h2.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM deletions").use { rs ->
                rs.next()
                rs.getInt(1) shouldBe 2
            }
        }
    }

    @Test
    fun `a statement failure is SqlExecutionException with the bounded driver message`() {
        val ds = wireDatasource()

        val thrown = shouldThrow<SqlExecutionException> { runner.select(ds, "SELECT * FROM no_such_table", limit = 5) }

        thrown.message shouldBe thrown.message?.take(SqlExecutionException.MAX_MESSAGE_CHARS)
    }

    @Test
    fun `an unreachable datasource is DatasourceUnreachableException from the shared lease boundary`() {
        // ifexists=TRUE makes the lease FAIL on a database that was never created — the H2
        // SQLException family at lease time, translated by the shared boundary.
        val dead = Fixtures.h2(name = "dead", jdbcUrl = "jdbc:h2:mem:never-created;ifexists=TRUE")
        every { registry.poolFor(dead) } returns JdbcUrlPool(dead.jdbcUrl, "dead")

        shouldThrow<DatasourceUnreachableException> { runner.select(dead, "SELECT 1", limit = 1) }
    }

    @Test
    fun `queryTimeout from the datasource reaches the statement, and so do maxRows and fetchSize`() {
        h2.createStatement().use { it.execute("CREATE TABLE q (id INT)") }
        val ds = Fixtures.h2(name = "timeout-ds", jdbcUrl = "jdbc:h2:mem:sqlrunner;DB_CLOSE_DELAY=-1", queryTimeoutSeconds = 33)
        val timeout = AtomicInteger(-1)
        val maxRows = AtomicInteger(-1)
        val fetchSize = AtomicInteger(-1)
        val recording =
            object : co.datapipelines.datasources.pooling.ConnectionPool {
                override val name: String = "recording"

                override fun leaseConnection(): Connection = recordingConnection(timeout, maxRows, fetchSize)

                override fun close() = Unit
            }
        every { registry.poolFor(ds) } returns recording

        runner.select(ds, "SELECT * FROM q", limit = 12)

        assertAll(
            { timeout.get() shouldBe 33 },
            { maxRows.get() shouldBe 13 },
            { fetchSize.get() shouldBe 12 },
        )
    }

    /**
     * A JDK-proxy connection that records the statement knobs [SqlRunner.statement] is
     * documented to set (§7B discipline: timeout, maxRows = limit + 1, fetchSize = limit) and
     * delegates everything else to a real H2 connection.
     */
    private fun recordingConnection(
        timeout: AtomicInteger,
        maxRows: AtomicInteger,
        fetchSize: AtomicInteger,
    ): Connection {
        val real = DriverManager.getConnection("jdbc:h2:mem:sqlrunner;DB_CLOSE_DELAY=-1")
        val loader = Connection::class.java.classLoader
        val statementProxy: (Statement) -> Statement = { st ->
            Proxy.newProxyInstance(loader, arrayOf(java.sql.PreparedStatement::class.java)) { _, method, args ->
                when (method.name) {
                    "setQueryTimeout" -> timeout.set(args[0] as Int)
                    "setMaxRows" -> maxRows.set(args[0] as Int)
                    "setFetchSize" -> fetchSize.set(args[0] as Int)
                }
                method.invoke(st, *args.orEmpty())
            } as Statement
        }
        return Proxy.newProxyInstance(loader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") {
                statementProxy(method.invoke(real, *args.orEmpty()) as Statement)
            } else {
                method.invoke(real, *args.orEmpty())
            }
        } as Connection
    }
}
