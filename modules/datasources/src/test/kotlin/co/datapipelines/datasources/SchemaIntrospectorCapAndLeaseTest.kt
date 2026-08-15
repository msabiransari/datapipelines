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
 * §7A's work-protocol seam: the cap+1 early-exit (a huge catalog costs cap+1 rows, not a walk)
 * and the [withMetaData][SchemaIntrospector] lease boundary — not-found and both unreachable
 * exception families (the Hikari pool-build path included), plus the post-lease SQLException
 * split: only the connection family translates to unreachable, anything else is a defect.
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

        shouldThrow<DatasourceUnreachableException> { SchemaIntrospector(registry).tables("h2-test") }
            .cause
            ?.message shouldBe "Connection refused"
    }

    @Test
    fun `a CONNECTION-family SQLException after the lease is the module's unreachable`() {
        // Post-lease, only the connection family means "the database went away": SQLState
        // class 08, the JDBC connection-exception subclasses, or SQLRecoverableException.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                    SQLException("connection exception", "08001")
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                    java.sql.SQLTransientConnectionException("connection is closed")
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                    java.sql.SQLRecoverableException("recoverable")
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
            },
        )
    }

    @Test
    fun `any OTHER SQLException after the lease propagates as a defect`() {
        // A metadata read failing with a non-connection SQLException (vendor error, bad
        // state) is a defect in this module or a driver bug — masking it as "the caller's
        // database is unreachable" would hide it, exactly like the RuntimeException policy
        // at the same site.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                    SQLException("vendor error", "S1000")
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<SQLException> { introspector.tables(name) }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                    SQLException("no sqlstate at all")
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<SQLException> { introspector.tables(name) }
            },
        )
    }
}
