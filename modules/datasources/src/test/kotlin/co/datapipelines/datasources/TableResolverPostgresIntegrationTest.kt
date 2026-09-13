package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The 123 §A three-state rule against the module's shared Postgres container (the
 * [SqlProbePostgresIntegrationTest] pattern: one fresh connection pool per lease, task-named
 * fixtures dropped after the class).
 *
 * Postgres is the COMPLETE-catalog dialect — pgjdbc 42.7.13's `getTables` appends a
 * `has_table_privilege` filter only under `hideUnprivilegedObjects` (default false, set
 * nowhere in this repo) — so this suite owns the shapes H2 cannot produce: real roles whose
 * grants differ, where the listing shows a table the SELECT then cannot read (the
 * `datasource.table_forbidden` arm).
 *
 * One premise did NOT survive bytecode verification: pgjdbc's `getColumns` applies NO
 * privilege filter at all (verified 2026-09-13 in the pinned 42.7.13 jar — no
 * `getHideUnprivilegedObjects`/`has_*_privilege` in the method), so a "zero visible columns"
 * table does not exist on Postgres: a role with only column-level grants still receives the
 * FULL column listing. The `a table with only column-level grants still resolves and reads`
 * test pins exactly that, and the "empty when the table exists" state stays the
 * [SchemaIntrospectorH2Test]-level property it always was.
 */
class TableResolverPostgresIntegrationTest {
    private val postgres = SharedPostgres.postgres

    private fun datasource(
        name: String,
        username: String,
        secret: String,
    ): Datasource =
        Datasource(
            name = name,
            displayName = "PG",
            dialect = Dialect.POSTGRES,
            jdbcUrl = postgres.jdbcUrl,
            username = username,
            secret = secret,
        )

    private val admin = datasource("pg123_admin", postgres.username, postgres.password)
    private val columnOnly = datasource("pg123_colonly", "task123_colonly", "task123")
    private val noSelect = datasource("pg123_noselect", "task123_noselect", "task123")

    private val registry = mockk<DatasourceRegistry>()
    private val introspector = SchemaIntrospector(registry)
    private val probe = SqlProbe(registry)
    private val runner = SqlRunner(registry)

    /** Every datasource this suite uses gets a fresh-connection anonymous pool. */
    private fun wire(ds: Datasource) {
        every { registry.get(ds.name) } returns ds
        every { registry.poolFor(ds) } returns
            object : ConnectionPool {
                override val name: String = ds.name

                override fun leaseConnection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, ds.username, ds.secret)

                override fun close() = Unit
            }
    }

    @BeforeEach
    fun seed() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP VIEW IF EXISTS task123_companies_v")
                st.execute("DROP TABLE IF EXISTS task123_companies")
                // The roles' only grants live on the fixture table, so with it dropped the
                // roles go cleanly (a previous failed run leaves no dependency behind).
                st.execute("DROP ROLE IF EXISTS task123_colonly")
                st.execute("DROP ROLE IF EXISTS task123_noselect")
                st.execute("CREATE ROLE task123_colonly LOGIN PASSWORD 'task123'")
                st.execute("CREATE ROLE task123_noselect LOGIN PASSWORD 'task123'")
                st.execute("CREATE TABLE task123_companies (id BIGINT PRIMARY KEY, note VARCHAR(50))")
                st.execute("INSERT INTO task123_companies SELECT i, 'note-' || i FROM generate_series(1, 5) i")
                st.execute("CREATE VIEW task123_companies_v AS SELECT id FROM task123_companies")
                st.execute("GRANT SELECT (id) ON task123_companies TO task123_colonly")
            }
        }
        wire(admin)
        wire(columnOnly)
        wire(noSelect)
    }

    @AfterEach
    fun cleanup() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP VIEW IF EXISTS task123_companies_v")
                st.execute("DROP TABLE IF EXISTS task123_companies")
                st.execute("DROP ROLE IF EXISTS task123_colonly")
                st.execute("DROP ROLE IF EXISTS task123_noselect")
            }
        }
    }

    @Test
    fun `an unknown table is table_not_found naming the nearest listed table - complete-catalog wording`() {
        val thrown = shouldThrow<DatapipelinesException> { introspector.columns(admin.name, "task123_companie") }

        assertAll(
            { thrown.code shouldBe DatasourceErrorCodes.TABLE_NOT_FOUND },
            // pg_catalog is complete: absence means the table does not exist — no filtered wording.
            { thrown.message shouldContain "does not exist" },
            { (thrown.message ?: "") shouldNotContain "cannot see it" },
            { thrown.message shouldContain "Did you mean 'task123_companies'?" },
            { thrown.details["table"] shouldBe "task123_companie" },
            { thrown.details["suggestion"] shouldBe "task123_companies" },
        )
    }

    @Test
    fun `a VIEW resolves as present - the introspection table types are the resolver's vocabulary`() {
        introspector.columns(admin.name, "task123_companies_v").map { it.column.name } shouldContainExactly listOf("id")
    }

    @Test
    fun `a table with only column-level grants still resolves and reads - getColumns is not privilege-filtered`() {
        // The role holds SELECT (id) only. pgjdbc's getColumns applies no privilege filter
        // (bytecode-verified, class KDoc), so the read answers the FULL listing — the point
        // under test is the resolution: a granted-at-any-level table is PRESENT, never 404.
        introspector.columns(columnOnly.name, "task123_companies").map { it.column.name } shouldContainExactly listOf("id", "note")
    }

    @Test
    fun `a table with no SELECT grant resolves PRESENT - the listing proves it exists`() {
        // The complete catalog lists the table for a role that cannot read a byte of it —
        // which is exactly what makes the 403 translation sound: the refusal comes after
        // resolution said present, so it can only mean "exists, not for you".
        // The unfiltered read resolves the connection's current NAMESPACE — the database as
        // catalog beside the schema (pgjdbc ignores the catalog argument; the outer segment
        // travels as context, exactly like the columns read).
        introspector.resolveTable(noSelect, "task123_companies") shouldBe
            ResolvedTable(postgres.jdbcUrl.substringAfterLast('/').substringBefore('?'), "public")
    }

    @Test
    fun `a SELECT a role cannot read fails the probe and the preview with the permission shape`() {
        val probeFailure =
            shouldThrow<SqlProbeExecutionException> {
                probe.probe(noSelect, "SELECT id FROM task123_companies")
            }
        val previewFailure =
            shouldThrow<SqlExecutionException> {
                runner.previewTable(noSelect, "task123_companies", null, emptyList(), 5)
            }

        assertAll(
            { (probeFailure.cause as? SQLException)?.isPermissionDenied() shouldBe true },
            { (previewFailure.cause as? SQLException)?.isPermissionDenied() shouldBe true },
        )
    }
}
