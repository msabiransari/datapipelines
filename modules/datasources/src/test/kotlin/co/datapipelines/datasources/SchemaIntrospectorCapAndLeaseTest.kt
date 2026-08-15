package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException

/**
 * §7A's work-protocol seam: the cap+1 early-exit (a huge catalog costs cap+1 rows, not a walk),
 * the one-lease snapshot rule, and the [withMetaData][SchemaIntrospector] lease boundary —
 * not-found and both unreachable exception families (the Hikari pool-build path included).
 */
class SchemaIntrospectorCapAndLeaseTest {
    private val h2 = java.sql.DriverManager.getConnection("jdbc:h2:mem:introspect-cap;DB_CLOSE_DELAY=-1")
    private val registry = mockk<DatasourceRegistry>()

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `tables stops iterating at cap plus one row`() {
        // The listing must not walk a huge catalog to its end: after cap+1 `next()` calls the
        // iteration stops (cap rows kept, the +1 proves truncation).
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true
        val (introspector, name) = introspectorOver(Dialect.H2, meta)

        val page = introspector.tables(name, maxTables = 2)

        assertAll(
            { verify(exactly = 3) { tablesRs.next() } },
            { page.tables.size shouldBe 2 },
            { page.truncated shouldBe true },
        )
    }

    @Test
    fun `snapshot stops the tables walk at cap plus one and never issues the server-wide bulk getColumns`() {
        // A huge catalog must cost cap+1 `next()` calls, not a full walk — and the old
        // server-wide `getColumns(null, null, "%", "%")` (every column of every schema,
        // including system schemas filtered only afterwards) must be gone entirely.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true
        every { tablesRs.getString("TABLE_SCHEM") } returns "public"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.getColumns(null, "public", "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        val snapshot = introspector.snapshot(name, maxTables = 2)

        assertAll(
            { verify(exactly = 3) { tablesRs.next() } },
            { snapshot.truncated shouldBe true },
            { verify(exactly = 0) { meta.getColumns(null, null, "%", "%") } },
            { verify(exactly = 2) { meta.getColumns(null, "public", "orders", "%") } },
        )
    }

    @Test
    fun `snapshot leases ONE connection - tables and per-table columns on a single consistent lease`() {
        // One lease per table (up to 201 for a full snapshot) starves the pool and reads the
        // schema across connections that may disagree mid-flight; the per-table column reads
        // must all ride the SAME lease as the tables walk.
        h2.createStatement().use { st -> (1..3).forEach { st.execute("CREATE TABLE t$it (id INT, note VARCHAR(10))") } }
        val pool = CountingPool("jdbc:h2:mem:introspect-cap;DB_CLOSE_DELAY=-1", "h2-test")
        val ds = Fixtures.h2(name = "h2-test")
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns pool

        val snapshot = SchemaIntrospector(registry).snapshot("h2-test", maxTables = 2)

        assertAll(
            { pool.leases shouldBe 1 },
            { snapshot.tables.size shouldBe 2 },
            { snapshot.truncated shouldBe true },
            { snapshot.tables.all { it.columns.size == 2 } shouldBe true },
        )
    }

    @Test
    fun `a pool-build failure is the module's unreachable - not an escaping RuntimeException`() {
        // The true Hikari path for a down database: `poolFor`'s `computeIfAbsent` builds the
        // pool and `HikariDataSource` construction throws PoolInitializationException — a
        // RuntimeException, NOT an SQLException. Round 1 caught only SQLException at the
        // surfaces, so this escaped as a raw 500 / -32603.
        val ds = Fixtures.h2(name = "h2-test")
        val poolFailure =
            com.zaxxer.hikari.pool.HikariPool.PoolInitializationException(
                RuntimeException("Failed to get driver instance for jdbcUrl"),
            )
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } throws poolFailure

        val thrown =
            shouldThrow<DatasourceUnreachableException> {
                SchemaIntrospector(registry).tables("h2-test")
            }

        assertAll(
            { thrown.datasourceName shouldBe "h2-test" },
            { thrown.cause shouldBe poolFailure },
        )
    }

    @Test
    fun `a lease failure SQLException is the module's unreachable`() {
        val ds = Fixtures.h2(name = "h2-test")
        every { registry.get("h2-test") } returns ds
        every { registry.poolFor(ds) } returns
            object : co.datapipelines.datasources.pooling.ConnectionPool {
                override val name: String = "h2-test"

                override fun leaseConnection(): java.sql.Connection = throw SQLException("Connection refused")

                override fun close() = Unit
            }

        shouldThrow<DatasourceUnreachableException> { SchemaIntrospector(registry).snapshot("h2-test") }
            .cause
            ?.message shouldBe "Connection refused"
    }
}
