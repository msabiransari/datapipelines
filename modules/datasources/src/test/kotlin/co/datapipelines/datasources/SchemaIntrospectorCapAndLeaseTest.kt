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
    fun `schemas stops iterating at cap plus one row and flags truncation`() {
        // schemas() walks getCatalogs()/getSchemas() under the pooled lease — on MySQL
        // catalog routing that is EVERY database the server grants. The same cap+1
        // early-exit as tables() bounds the walk and the payload, and the page flags the
        // drop so the caller knows the listing is partial.
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true
        every { schemasRs.getString("TABLE_SCHEM") } returns "s"
        val (introspector, name) = introspectorOver(Dialect.H2, meta)

        val page = introspector.schemas(name, maxSchemas = 2)

        assertAll(
            { verify(exactly = 3) { schemasRs.next() } },
            { page.schemas.size shouldBe 2 },
            { page.truncated shouldBe true },
        )
    }

    @Test
    fun `system-schema rows do not count against the schemas cap`() {
        // Same jump discipline as tables(): a trailing system row is skipped WITHOUT
        // counting, and truncation is only flagged when the cap+1-th USER row exists.
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true andThen true andThen true andThen false
        every { schemasRs.getString("TABLE_SCHEM") } returns "pg_catalog" andThen "public" andThen "sales"
        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        val page = introspector.schemas(name, maxSchemas = 2)

        assertAll(
            { page.schemas shouldBe listOf("public", "sales") },
            { page.truncated shouldBe false },
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
    fun `the connection-failure SQLState family is exactly the named set`() {
        // The SET is the contract now (034 C2): five rounds each widened this classifier for
        // the shapes they enumerated and missed the adjacent shape in the same predicate. The
        // family is pinned as a visible list — the next adjacent shape is a one-line addition
        // HERE plus the same line in SchemaIntrospector's CONNECTION_FAILURE_SQLSTATE_CLASSES /
        // CONNECTION_FAILURE_SQLSTATES, and this test is red until both exist.
        // 08 = SQL-standard connection exception (whole class); 57P01-04 = the PostgreSQL
        // operator-intervention SHUTDOWN states (raised by pgjdbc as PLAIN PSQLExceptions, so
        // a plain SQLException is the faithful carrier). Class 57 is NOT wholly in the
        // family — see the non-member test for 57014.
        val family =
            listOf(
                // class 08 representative
                "08001",
                // the named shutdown states
                "57P01",
                "57P02",
                "57P03",
                "57P04",
            )
        assertAll(
            family.map { representative ->
                {
                    val meta = mockk<DatabaseMetaData>()
                    every { meta.searchStringEscape } returns "\\"
                    every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                        SQLException("operator intervention", representative)
                    val (introspector, name) = introspectorOver(Dialect.H2, meta)

                    shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
                }
            },
        )
    }

    @Test
    fun `every postgres class-57 shutdown state after the lease is the module's unreachable`() {
        // The 034 instance of the recurrence: pg_terminate_backend / server shutdown during
        // the query-backed getSchema() used to surface as 400 parameter_required "reports no
        // current schema" instead of the 502 the unreachable translation exists to produce.
        assertAll(
            listOf("57P01", "57P02", "57P03", "57P04").map { shutdownState ->
                {
                    val meta = mockk<DatabaseMetaData>()
                    every { meta.searchStringEscape } returns "\\"
                    every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                        SQLException("terminating connection due to administrator command", shutdownState)
                    val (introspector, name) = introspectorOver(Dialect.H2, meta)

                    shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
                }
            },
        )
    }

    @Test
    fun `SQLState classes OUTSIDE the named family after the lease propagate as defects`() {
        // The other half of the set contract: widening is deliberate, never a side effect —
        // adjacent non-member classes stay defects (0A feature-not-supported, 23 integrity,
        // 42 syntax/undefined). "42" is the one a careless "any server error means down"
        // would swallow: 42P01 (undefined table) is a REAL Postgres server error that must
        // keep propagating. 57014 (query_canceled) is the class-57 sibling the shutdown
        // states were nearly widened over: the STATEMENT died, the connection is alive —
        // SchemaIntrospectorRoutingTest pins it to CurrentSchemaUnknownException at the
        // getSchema() boundary, so here it must NOT read as a connection failure.
        assertAll(
            listOf("0A000", "23505", "42P01", "57014").map { state ->
                {
                    val meta = mockk<DatabaseMetaData>()
                    every { meta.searchStringEscape } returns "\\"
                    every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                        SQLException("server error", state)
                    val (introspector, name) = introspectorOver(Dialect.H2, meta)

                    shouldThrow<SQLException> { introspector.tables(name) }
                }
            },
        )
    }

    @Test
    fun `a SQLTimeoutException after the lease is the module's unreachable`() {
        // SQLTimeoutException extends SQLTransientException — NOT the connection-exception
        // family — yet a dead network surfaces as exactly this shape. Round 3: the timeout
        // is part of the connection family for this boundary's purposes.
        val meta = mockk<DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
            java.sql.SQLTimeoutException("timeout: network is dead")
        val (introspector, name) = introspectorOver(Dialect.H2, meta)

        shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
    }

    @Test
    fun `a SQLite connection-loss code after the lease is the module's unreachable`() {
        // The vendored sqlite-jdbc's SQLiteException extends plain SQLException with a NULL
        // SQLState (verified in the 3.49.1.0 bytecode: the sole constructor passes null as
        // the SQLState and code&0xFF as the vendor code) — a db file deleted or locked
        // mid-metadata-walk fails every SQLState-based branch and used to escape as a raw
        // 500 / -32603. Classified by SQLite's own primary result codes: the connection-loss
        // family BUSY(5), IOERR(10), CANTOPEN(14), NOTADB(26) — extended codes (IOERR_READ,
        // CANTOPEN_NOTEMPDIR, BUSY_TIMEOUT, ...) fold to their primary under the driver's
        // own &0xFF masking.
        val connectionLossCodes =
            listOf(
                org.sqlite.SQLiteErrorCode.SQLITE_CANTOPEN,
                org.sqlite.SQLiteErrorCode.SQLITE_IOERR,
                org.sqlite.SQLiteErrorCode.SQLITE_BUSY,
                org.sqlite.SQLiteErrorCode.SQLITE_NOTADB,
            )
        assertAll(
            connectionLossCodes.map { code ->
                {
                    val meta = mockk<DatabaseMetaData>()
                    every { meta.searchStringEscape } returns "\\"
                    every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
                        org.sqlite.SQLiteException("sqlite walk failure", code)
                    val (introspector, name) = introspectorOver(Dialect.H2, meta)

                    shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
                }
            },
        )
    }

    @Test
    fun `a SQLite NON-connection exception after the lease propagates as a defect`() {
        // The SQLite classification is by primary code, never "null SQLState means down" —
        // a driver-level defect (here SQLITE_CONSTRAINT, code 19) must stay what it is,
        // preserving round-2's R5 narrowing for every other null-SQLState shape.
        val meta = mockk<DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } throws
            org.sqlite.SQLiteException("driver defect", org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT)
        val (introspector, name) = introspectorOver(Dialect.H2, meta)

        shouldThrow<org.sqlite.SQLiteException> { introspector.tables(name) }
    }

    @Test
    fun `an 08 SQLState buried on the cause or nextException chain is the module's unreachable`() {
        // Some drivers put the connection state only on a wrapped/next exception — the
        // check must walk BOTH chains rather than inspect the top-level SQLException alone.
        val onCause =
            SQLException("wrapper without a state of its own").apply {
                initCause(SQLException("connection exception", "08006"))
            }
        val onNext = SQLException("wrapper").apply { nextException = SQLException("connection is closed", "08001") }
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws onCause
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } throws onNext
                val (introspector, name) = introspectorOver(Dialect.H2, meta)

                shouldThrow<DatasourceUnreachableException> { introspector.tables(name) }
            },
        )
    }

    @Test
    fun `a cyclic exception chain terminates and classifies without looping`() {
        // Cycle-safety of the chain walk: a driver (or wrapper) that produces a cause cycle
        // must get a bounded classification, not an infinite walk. The cycle carries no
        // connection state, so the verdict is "defect" — propagate.
        val a = SQLException("cycle a", "S1000")
        val b = SQLException("cycle b", "S1001")
        a.initCause(b)
        b.initCause(a)
        val meta = mockk<DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } throws a
        val (introspector, name) = introspectorOver(Dialect.H2, meta)

        shouldThrow<SQLException> { introspector.tables(name) }
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
