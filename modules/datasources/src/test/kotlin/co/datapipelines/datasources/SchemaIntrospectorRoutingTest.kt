package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLFeatureNotSupportedException

/**
 * §7A identifier ROUTING over mocked `DatabaseMetaData` — which argument of `getTables` /
 * `getColumns` carries the schema for each dialect family, where the current schema comes
 * from, and what the fallback is when a driver reports none. No live driver here: these tests
 * pin the arguments, not JDBC behavior ([SchemaIntrospectorH2Test] owns the live-driver seam).
 */
class SchemaIntrospectorRoutingTest {
    @Test
    fun `mysql routes the schema filter to the catalog argument and reads TABLE_CAT as the schema`() {
        // Connector/J defaults: the database arrives in TABLE_CAT, TABLE_SCHEM is null — a
        // schemaPattern selects nothing. The filter must land in the catalog argument.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables("app", null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_CAT") } returns "app"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false

        val (introspector, name) = introspectorOver(Dialect.MYSQL, meta)

        assertAll(
            {
                introspector
                    .tables(name, schemaFilter = "app")
                    .tables
                    .single()
                    .schema shouldBe "app"
            },
            { introspector.columns(name, "orders", schemaFilter = "app") shouldBe emptyList() },
        )
    }

    @Test
    fun `the catalog argument is a LITERAL - a mysql database named my_app arrives unescaped`() {
        // The JDBC catalog argument "must match the catalog name as it is stored" — it is a
        // LITERAL, never a LIKE pattern. Escaping it (the toExactMatch path) makes any
        // database whose name carries '_' or '%' match NOTHING: get_tables returns [] and
        // get_columns returns [] — the zero-columns defect. The stub answers ONLY the raw
        // "my_app", so an escaped call fails loudly.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables("my_app", null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_CAT") } returns "my_app"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.getColumns("my_app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false

        val (introspector, name) = introspectorOver(Dialect.MYSQL, meta)

        assertAll(
            {
                introspector
                    .tables(name, schemaFilter = "my_app")
                    .tables
                    .single()
                    .schema shouldBe "my_app"
            },
            { introspector.columns(name, "orders", schemaFilter = "my_app") shouldBe emptyList() },
        )

        verify(exactly = 1) { meta.getTables("my_app", null, "%", any<Array<String>>()) }
        verify(exactly = 0) { meta.getTables(match { it != "my_app" }, null, "%", any<Array<String>>()) }
        verify(exactly = 1) { meta.getColumns("my_app", null, "orders", "%") }
        verify(exactly = 0) { meta.getColumns(match { it != "my_app" }, null, "orders", "%") }
    }

    @Test
    fun `the mysql current-schema default is a LITERAL catalog too - my_app arrives unescaped`() {
        // The default comes from connection.getCatalog() — a stored name, routed to the
        // catalog argument; it must not go through toExactMatch any more than an explicit
        // filter does.
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns("my_app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOver(Dialect.MYSQL, meta) { connection ->
                every { connection.catalog } returns "my_app"
            }

        introspector.columns(name, "orders") shouldBe emptyList()

        verify(exactly = 1) { meta.getColumns("my_app", null, "orders", "%") }
        verify(exactly = 0) { meta.getColumns(match { it != "my_app" }, null, "orders", "%") }
    }

    @Test
    fun `the schemaPattern argument stays escaped - my_schema on postgres arrives escaped`() {
        // The OTHER side of the fix: schemaPattern (and tableNamePattern) are true LIKE
        // pattern arguments — `_`/`%` MUST be escaped there. Routing the raw filter into
        // schemaPattern without escaping would reintroduce the wildcard-sibling defect.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, "my\\_schema", "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns false

        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        introspector.tables(name, schemaFilter = "my_schema").tables shouldBe emptyList()

        verify(exactly = 1) { meta.getTables(null, "my\\_schema", "%", any<Array<String>>()) }
    }

    @Test
    fun `schema-filtered dialects keep the filter in the schemaPattern argument`() {
        // The non-MySQL world: TABLE_SCHEM carries the schema and the filter stays in the
        // schemaPattern argument — routing must not move it for them.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, "public", "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_SCHEM") } returns "public"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"

        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        introspector
            .tables(name, schemaFilter = "public")
            .tables
            .single()
            .schema shouldBe "public"
    }

    @Test
    fun `columns reads the current schema from the schemaPattern argument for schema-filtered dialects`() {
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns(null, "sales", "deals", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOver(Dialect.POSTGRES, meta) { connection ->
                every { connection.schema } returns "sales"
            }

        introspector.columns(name, "deals") shouldBe emptyList()

        verify(exactly = 1) { meta.getColumns(null, "sales", "deals", "%") }
    }

    @Test
    fun `columns reads the current schema from the catalog argument for catalog-routing dialects`() {
        // Connector/J keeps the current database in the CATALOG (getSchema() returns null under
        // the default databaseTerm) — the default must route exactly like the schema filter does.
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOver(Dialect.MYSQL, meta) { connection ->
                every { connection.catalog } returns "app"
            }

        introspector.columns(name, "orders") shouldBe emptyList()

        verify(exactly = 1) { meta.getColumns("app", null, "orders", "%") }
    }

    @Test
    fun `a no-schema COLUMNS read on a datasource with no current schema FAILS instead of merging`() {
        // The cannot-merge theorem (§7A, rest-api §9.7, mcp-server §6.2.18), scoped where the
        // hazard is real: a driver that reports no current schema — null, or the blank
        // sentinel — leaves an unqualified COLUMNS read UNFILTERED, which on a database-less
        // MySQL registration merges db1.orders' columns with db2.orders' into one answer.
        // The theorem is enforced by failing with a dedicated exception the surfaces
        // translate to the catalogued invalid-argument code telling the caller to pass an
        // explicit schema. (tables() deliberately has NO such guard — see the next test.)
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "deals", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } returns null
                    }

                // R5 F5: the runtime MESSAGE is columns-scoped — the exception is raised
                // only by columns(), and agents read the message, not the KDoc. The old
                // tables-flavored wording reinstated the tables/columns confusion F6 of
                // round 4 spent its cycle removing.
                val thrown = shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "deals") }
                thrown.message shouldContain "columns read"
                thrown.message shouldContain "merge the columns"
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "orders", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.MYSQL, meta) { connection ->
                        every { connection.catalog } returns ""
                    }

                shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "orders") }
            },
        )
    }

    @Test
    fun `an unfiltered TABLES listing works on a datasource reporting no current schema`() {
        // R4 F6: a tables listing CANNOT merge — every row carries its own schema — and when
        // the current schema IS known the old guard passed while routeAndEscape(null) still
        // spanned every schema/catalog the server grants, so the guard prevented nothing it
        // claimed to prevent on tables() and regressed previously-working unfiltered
        // listings (drivers that throw SQLFeatureNotSupportedException from
        // getSchema()/getCatalog() tripped it through no fault of the listing). tables() no
        // longer consults the current schema at all; the cannot-merge guard lives only in
        // columns(). Both no-current-schema shapes must keep the listing working: null, and
        // the unsupported-operation throw.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val tablesRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
                every { tablesRs.next() } returns true andThen false
                every { tablesRs.getString("TABLE_CAT") } returns "db1"
                every { tablesRs.getString("TABLE_NAME") } returns "orders"
                every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
                val (introspector, name) =
                    introspectorOver(Dialect.MYSQL, meta) { connection ->
                        every { connection.catalog } returns null
                    }

                introspector
                    .tables(name)
                    .tables
                    .single()
                    .schema shouldBe "db1"
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val tablesRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
                every { tablesRs.next() } returns true andThen false
                every { tablesRs.getString("TABLE_CAT") } returns "db2"
                every { tablesRs.getString("TABLE_NAME") } returns "orders"
                every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
                val (introspector, name) =
                    introspectorOver(Dialect.MYSQL, meta) { connection ->
                        every { connection.catalog } throws SQLFeatureNotSupportedException("getCatalog unsupported")
                    }

                introspector
                    .tables(name)
                    .tables
                    .single()
                    .schema shouldBe "db2"
            },
        )
    }

    @Test
    fun `an EXPLICIT schema still works on a datasource with no current schema`() {
        // The failure above directs the caller to pass a schema from the schemas listing —
        // that recovery path must keep working on the very datasource that failed.
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns("db1", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOver(Dialect.MYSQL, meta) { connection ->
                every { connection.catalog } returns null
            }

        introspector.columns(name, "orders", schemaFilter = "db1") shouldBe emptyList()

        verify(exactly = 1) { meta.getColumns("db1", null, "orders", "%") }
    }

    @Test
    fun `a schemaless dialect never fails the unknown-current-schema guard`() {
        // SQLite has NO JDBC schema dimension at all (getSchemas() is empty, every object
        // reports a null schema — verified against the vendored 3.49.1.0), so an unqualified
        // read cannot merge same-named tables and the caller has no schema to pass anyway:
        // the schemas() listing is empty. The guard is for schema-capable dialects only.
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns(null, null, "t", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOver(Dialect.SQLITE, meta) { connection ->
                every { connection.schema } returns null
            }

        introspector.columns(name, "t") shouldBe emptyList()
    }

    @Test
    fun `a schemaless dialect NEVER consults the connection's current schema - even a throwing getSchema`() {
        // R5 F3: the schemaless exemption was safe only by driver accident — the vendored
        // sqlite-jdbc hardcodes getSchema() = null, so the current-schema consult could not
        // throw TODAY. A future schemaless driver whose getSchema() throws (any of the
        // classified shapes) would turn a working unfiltered columns read into a 502/400.
        // The reorder makes the exemption structural: a schemaless dialect skips the
        // current-schema consult entirely.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "t", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.SQLITE, meta) { connection ->
                        every { connection.schema } throws java.sql.SQLException("Connection was closed", null, 0)
                    }

                introspector.columns(name, "t") shouldBe emptyList()
            },
            {
                // The same structural guarantee for a throwing getCatalog() — the consult
                // a catalog-routing schemaless dialect would make.
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "t", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.SQLITE, meta) { connection ->
                        every { connection.catalog } throws
                            java.sql.SQLNonTransientConnectionException("connection exception", "08001")
                    }

                introspector.columns(name, "t") shouldBe emptyList()
            },
        )
    }

    @Test
    fun `schemas reads TABLE_SCHEM from getSchemas for schema-filtered dialects`() {
        // Postgres-family: the schema vocabulary IS the JDBC schema — getSchemas()/TABLE_SCHEM.
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true andThen true andThen false
        every { schemasRs.getString("TABLE_SCHEM") } returns "public" andThen "pg_catalog"

        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        introspector.schemas(name).schemas shouldBe listOf("public")
    }

    @Test
    fun `schemas reads TABLE_CAT from getCatalogs for catalog-routing dialects`() {
        // Connector/J defaults (databaseTerm=CATALOG): getSchemas() reports a single blank
        // schema and the databases arrive as CATALOGS — the listing must read TABLE_CAT from
        // getCatalogs(), exactly the vocabulary the tables/columns routing already uses.
        val meta = mockk<DatabaseMetaData>()
        val catalogsRs = mockk<ResultSet>(relaxed = true)
        every { meta.catalogs } returns catalogsRs
        every { catalogsRs.next() } returns true andThen true andThen false
        every { catalogsRs.getString("TABLE_CAT") } returns "my_app" andThen "sys"

        val (introspector, name) = introspectorOver(Dialect.MYSQL, meta)

        introspector.schemas(name).schemas shouldBe listOf("my_app")
    }

    @Test
    fun `schemas skips blank names - the JDBC no-catalog sentinel is not a schema`() {
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true andThen true andThen false
        every { schemasRs.getString("TABLE_SCHEM") } returns "" andThen "public"

        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        introspector.schemas(name).schemas shouldBe listOf("public")
    }

    @Test
    fun `whitespace-only names are the blank sentinel everywhere - not names`() {
        // The blank-sentinel rule is ONE rule at the ResultSet boundary: blank or
        // whitespace-only → none. A driver reporting " " (not "") must not sneak a
        // whitespace "schema" into a listing, a table row, or the current-schema default.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val schemasRs = mockk<ResultSet>(relaxed = true)
                every { meta.schemas } returns schemasRs
                every { schemasRs.next() } returns true andThen true andThen false
                every { schemasRs.getString("TABLE_SCHEM") } returns "   " andThen "public"
                val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

                introspector.schemas(name).schemas shouldBe listOf("public")
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val tablesRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
                every { tablesRs.next() } returns true andThen false
                every { tablesRs.getString("TABLE_SCHEM") } returns "   "
                every { tablesRs.getString("TABLE_NAME") } returns "orders"
                every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
                val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

                introspector
                    .tables(name)
                    .tables
                    .single()
                    .schema shouldBe null
            },
            {
                // A whitespace-only current schema is "none" — and "none" on a schema-capable
                // dialect trips the unknown-current-schema guard, exactly like the "" sentinel.
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "deals", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } returns "   "
                    }

                shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "deals") }
            },
        )
    }

    @Test
    fun `columns treats the blank current-schema sentinel like none - the guard fires`() {
        // JDBC's "" sentinel means "objects without a catalog/schema" — a driver reporting it
        // must get the unknown-current-schema guard, NOT a match-nothing "" filter.
        val meta = mockk<DatabaseMetaData>()
        val columnsRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getColumns(null, null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val (introspector, name) =
            introspectorOver(Dialect.MYSQL, meta) { connection ->
                every { connection.catalog } returns ""
            }

        shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "orders") }
        verify(exactly = 0) { meta.getColumns(any(), any(), any(), any()) }
    }

    @Test
    fun `a BLANK caller filter behaves like an absent one - the default applies, not the empty sentinel`() {
        // Spring binds `?schema=` (empty query param) to a NON-NULL "" — which must not reach
        // JDBC: the empty string is the sentinel for "objects WITHOUT a schema" on
        // Postgres/H2 and a catalog literal on MySQL, silently matching nothing. The caller's
        // filter is normalized with the same blank-sentinel rule as driver-reported values,
        // so REST (`?schema=`) and MCP (which nulls blanks in MccpArguments) behave
        // identically: tables() spans schemas, columns() takes the current-schema default.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.MYSQL, meta) { connection ->
                        every { connection.catalog } returns "app"
                    }

                introspector.columns(name, "orders", schemaFilter = "") shouldBe emptyList()

                verify(exactly = 1) { meta.getColumns("app", null, "orders", "%") }
                verify(exactly = 0) { meta.getColumns("", null, "orders", "%") }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val tablesRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
                every { tablesRs.next() } returns false
                val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

                introspector.tables(name, schemaFilter = "").tables shouldBe emptyList()

                verify(exactly = 1) { meta.getTables(null, null, "%", any<Array<String>>()) }
                verify(exactly = 0) { meta.getTables(null, "", "%", any<Array<String>>()) }
            },
        )
    }

    @Test
    fun `blank REMARKS are absent - a driver reporting empty-string comments omits the key`() {
        // Connector/J reports REMARKS as "" for uncommented tables/columns (non-nullable
        // information_schema columns defaulting to ''); the wire contract is
        // omitted-when-none, and the introspector's ResultSet boundary is where "" -> null.
        val meta = mockk<DatabaseMetaData>()
        val tablesRs = mockk<ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen false
        every { tablesRs.getString("TABLE_SCHEM") } returns "public"
        every { tablesRs.getString("TABLE_NAME") } returns "orders"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        every { tablesRs.getString("REMARKS") } returns ""
        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        val table = introspector.tables(name).tables.single()

        assertAll(
            { table.remarks shouldBe null },
            { table.toWireMap().containsKey("remarks") shouldBe false },
        )
    }

    @Test
    fun `schemas excludes the mssql fixed-role floor - dbo is user data and stays`() {
        // SQL Server's built-in fixed-role/special schemas (db_owner, db_datareader, guest, ...)
        // list as ordinary schemas; dbo is the database's DEFAULT USER schema and must NOT be
        // excluded. No arm64 MSSQL container exists (pre-existing) — this is the
        // mocked-metadata unit verification, not container coverage.
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true andThen true andThen true andThen true andThen false
        every { schemasRs.getString("TABLE_SCHEM") } returns "dbo" andThen "db_datareader" andThen "guest" andThen "sales"
        val (introspector, name) = introspectorOver(Dialect.MSSQL, meta)

        introspector.schemas(name).schemas shouldBe listOf("dbo", "sales")
    }

    @Test
    fun `schemas excludes duckdb's pg_catalog beside information_schema`() {
        // Verified against the pinned duckdb_jdbc 1.5.5.1: getSchemas() reports main,
        // information_schema and pg_catalog — the bare {information_schema} default leaked
        // pg_catalog into the listing. No arm64 DuckDB-flavored container matrix here; like
        // MSSQL, this is the mocked-metadata unit verification.
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true andThen true andThen true andThen false
        every { schemasRs.getString("TABLE_SCHEM") } returns "main" andThen "pg_catalog" andThen "information_schema"
        val (introspector, name) = introspectorOver(Dialect.DUCKDB, meta)

        introspector.schemas(name).schemas shouldBe listOf("main")
    }

    @Test
    fun `the include-schemas allowlist exempts a named schema in all three operations - the floor still hides the rest`() {
        // §7A F5: Oracle's `apex_*` prefix entry hides a customer's OWN APEX_REPORTING schema
        // just like the engine's versioned ones, silently — the agent is then told data that
        // exists does not. A datasource registered with introspection_include_schemas:
        // ["apex_reporting"] sees that schema in get_schemas/get_tables/get_columns while
        // every other floor entry (dbsnmp, ordsys, apex_240100...) stays hidden.
        val include = listOf("apex_reporting")
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val schemasRs = mockk<ResultSet>(relaxed = true)
                every { meta.schemas } returns schemasRs
                every { schemasRs.next() } returns true andThen true andThen true andThen true andThen false
                every { schemasRs.getString("TABLE_SCHEM") } returns
                    "APEX_REPORTING" andThen "DBSNMP" andThen "APEX_240100" andThen "SALES"
                val (introspector, name) = introspectorOver(Dialect.ORACLE, meta, introspectionIncludeSchemas = include)

                introspector.schemas(name).schemas shouldBe listOf("APEX_REPORTING", "SALES")
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val tablesRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
                every { tablesRs.next() } returns true andThen true andThen false
                every { tablesRs.getString("TABLE_SCHEM") } returns "APEX_REPORTING" andThen "ORDSYS"
                every { tablesRs.getString("TABLE_NAME") } returns "reports"
                every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
                val (introspector, name) = introspectorOver(Dialect.ORACLE, meta, introspectionIncludeSchemas = include)

                introspector
                    .tables(name)
                    .tables
                    .single()
                    .schema shouldBe "APEX_REPORTING"
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                // schemaPattern is a true pattern argument — the underscore in the schema name
                // arrives ESCAPED (the exact-match rule), hence APEX\_REPORTING.
                every { meta.getColumns(null, "APEX\\_REPORTING", "REPORTS", "%") } returns columnsRs
                every { columnsRs.next() } returns true andThen false
                every { columnsRs.getString("TABLE_SCHEM") } returns "APEX_REPORTING"
                every { columnsRs.getString("TYPE_NAME") } returns "NUMBER"
                every { columnsRs.getInt("DATA_TYPE") } returns java.sql.Types.NUMERIC
                every { columnsRs.getInt("COLUMN_SIZE") } returns 38
                every { columnsRs.getInt("DECIMAL_DIGITS") } returns 0
                every { columnsRs.getInt("NULLABLE") } returns 1
                every { columnsRs.getString("COLUMN_NAME") } returns "AMOUNT"
                val (introspector, name) =
                    introspectorOver(Dialect.ORACLE, meta, introspectionIncludeSchemas = include) { connection ->
                        every { connection.schema } returns "APEX_REPORTING"
                    }

                introspector.columns(name, "REPORTS").map { it.column.name } shouldBe listOf("AMOUNT")
            },
        )
    }

    @Test
    fun `connection loss DURING the current-schema read is unreachable - not a silent no-current-schema`() {
        // F1: a connection that dies exactly during getSchema()/getCatalog() (SQLState 08S01
        // mid-lease) used to be swallowed by currentSchema()'s blanket SQLException catch and
        // reported as "no current schema" — the 400 parameter_required path, whose recommended
        // recovery (list schemas) would fail on the same dead connection. Only
        // SQLFeatureNotSupportedException (a driver legitimately reporting "none") may read as
        // "no current schema"; every other SQLException must reach the lease boundary's
        // connection-family classification as the catalogued 502 datasource_unreachable.
        assertAll(
            {
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } throws
                            java.sql.SQLNonTransientConnectionException("connection exception", "08001")
                    }

                shouldThrow<DatasourceUnreachableException> { introspector.columns(name, "deals") }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.MYSQL, meta) { connection ->
                        every { connection.catalog } throws
                            java.sql.SQLNonTransientConnectionException("connection exception", "08001")
                    }

                shouldThrow<DatasourceUnreachableException> { introspector.columns(name, "orders") }
            },
            {
                // The legitimate "driver reports none" keeps the no-current-schema meaning —
                // the 400 path stays reserved for it.
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } throws SQLFeatureNotSupportedException("getSchema unsupported")
                    }

                shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "deals") }
            },
        )
    }

    @Test
    fun `a RuntimeException from the metadata walk itself is NOT translated to unreachable`() {
        // The lease boundary translates; a defect in the walk (or a driver bug) stays what it
        // is — masking it as "datasource unreachable" would hide our own bugs.
        val meta = mockk<DatabaseMetaData>()
        every { meta.getTables(null, null, "%", any<Array<String>>()) } throws IllegalStateException("walk bug")
        val (introspector, name) = introspectorOver(Dialect.H2, meta)

        shouldThrow<IllegalStateException> { introspector.tables(name) }
    }

    @Test
    fun `current-schema exception shapes OUTSIDE the 08 family are classified - never a raw escape`() {
        // R5 F1: round 4's narrowed catch kept only SQLFeatureNotSupportedException as "none"
        // and let everything else PROPAGATE RAW (the lease boundary's post-lease translation
        // covers the connection family only, and the surfaces catch only the two module
        // exceptions). Three shapes verified against the shipped drivers (see
        // EmbeddedDialectBehaviorTest for the live reproducing cases):
        // (a) connection loss with a NON-08 state — H2's closed-object 90007, DuckDB's
        //     null-state plain SQLException "Connection was closed" — must be the catalogued
        //     502 datasource_unreachable, not a raw 500;
        // (b) a NON-connection failure from a QUERY-BACKED getSchema() — pgjdbc 42.7.13 runs
        //     `select current_schema()` on the server (bytecode-verified), so a statement
        //     cancel (57014) or permission error arrives as a PSQLException the 08 family
        //     does not cover — must be the catalogued unknown-current-schema 400, not raw;
        // (c) a driver signaling "unsupported" as a PLAIN SQLException SQLState 0A000 instead
        //     of the typed exception — must read as "driver reports none" (the 400 path),
        //     not raw.
        assertAll(
            {
                // (a) H2 90007 — the exact class/state the pinned h2 2.3.232 throws from
                // getSchema() on a closed connection (live-pinned in EmbeddedDialectBehaviorTest).
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.H2, meta) { connection ->
                        every { connection.schema } throws
                            org.h2.jdbc.JdbcSQLNonTransientException(
                                // Constructor is (message, sql, SQLSTATE, errorCode, cause, stackTrace)
                                // — bytecode-checked: only the third String reaches getSQLState().
                                "The object is already closed",
                                null,
                                "90007",
                                90007,
                                null,
                                null,
                            )
                    }

                shouldThrow<DatasourceUnreachableException> { introspector.columns(name, "deals") }
            },
            {
                // (a) DuckDB 1.5.5.x closed connection — plain java.sql.SQLException, NULL
                // SQLState, vendor code 0, the JDBC layer's own "Connection was closed" text
                // (native errors are ALSO plain null-state SQLExceptions with different
                // messages, so the message is the only discriminator — live-pinned in
                // EmbeddedDialectBehaviorTest).
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.DUCKDB, meta) { connection ->
                        every { connection.schema } throws java.sql.SQLException("Connection was closed", null, 0)
                    }

                shouldThrow<DatasourceUnreachableException> { introspector.columns(name, "deals") }
            },
            {
                // (b) pgjdbc statement-cancel from the current_schema() query — the real
                // PSQLException/PSQLState.QUERY_CANCELED shape, state 57014.
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } throws
                            org.postgresql.util.PSQLException(
                                "canceling statement due to user request",
                                org.postgresql.util.PSQLState.QUERY_CANCELED,
                            )
                    }

                shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "deals") }
            },
            {
                // (c) feature-unsupported as a plain SQLException, SQLState 0A000 — the
                // documented "some drivers use the state, not the type" shape.
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } throws java.sql.SQLException("feature not supported", "0A000")
                    }

                shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "deals") }
            },
        )
    }
}
