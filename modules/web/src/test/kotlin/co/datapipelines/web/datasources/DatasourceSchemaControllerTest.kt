package co.datapipelines.web.datasources

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TablesPage
import co.datapipelines.datasources.toWireMap
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * §7A endpoint DELEGATION over a mocked introspector: the controller binds paths, threads the
 * query/table/schema arguments through, and serves the shared wire projections (`toWireMap`)
 * unchanged — the field-by-field shape of those projections is owned ONCE by `SchemaWireTest`
 * in `modules/datasources`, not re-asserted here per surface. Error paths (not-found,
 * unreachable) are this layer's own behavior and stay fully asserted.
 */
class DatasourceSchemaControllerTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val controller = DatasourceSchemaController(introspector)

    @Test
    fun `schemas delegates to the introspector and serves the shared wire projection`() {
        val page = co.datapipelines.datasources.SchemasPage(listOf("public", "sales"), truncated = false)
        every { introspector.schemas("pg-prod") } returns page

        val data = controller.schemas("pg-prod").data

        data shouldBe page.toWireMap()
        verify(exactly = 1) { introspector.schemas("pg-prod") }
    }

    @Test
    fun `tables delegates to the introspector and serves the shared wire projection`() {
        val page = TablesPage(listOf(TableInfo("public", "orders", "TABLE")), truncated = true)
        every { introspector.tables("pg-prod", "sales") } returns page

        val data = controller.tables("pg-prod", schema = "sales").data

        data shouldBe page.toWireMap()
        verify(exactly = 1) { introspector.tables("pg-prod", "sales") }
    }

    @Test
    fun `columns delegates to the introspector and serves the shared wire projection`() {
        val columns =
            listOf(
                ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList()),
                ColumnInfo(ColumnSchema("amount", LogicalType.DECIMAL, precision = 10, scale = 2), "numeric", emptyList()),
            )
        every { introspector.columns("pg-prod", "orders", null) } returns columns

        val data = controller.columns("pg-prod", "orders", schema = null).data

        data shouldBe columns.map { it.toWireMap() }
        verify(exactly = 1) { introspector.columns("pg-prod", "orders", null) }
    }

    @Test
    fun `an unknown datasource surfaces the catalogued not-found`() {
        every { introspector.tables("nope", null) } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Datasource.NOT_FOUND,
                message = "Datasource 'nope' is not registered in this environment.",
                details = mapOf("datasource" to "nope"),
            )

        shouldThrow<DatapipelinesException> { controller.tables("nope", schema = null) }
            .code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
    }

    @Test
    fun `a connection failure during introspection is the catalogued datasource_unreachable`() {
        // A customer DB being down is not a server error: the introspector's
        // DatasourceUnreachableException (its lease boundary wraps BOTH the SQLException lease
        // family and the RuntimeException pool-build family — PoolInitializationException on a
        // down database, which round 1 missed; that path is pinned by the introspector tests)
        // must surface as the §13.8 code (HTTP 502 via the catalog), never as the 500 backstop.
        every { introspector.tables("pg-prod", null) } throws
            DatasourceUnreachableException("pg-prod", RuntimeException("Connection refused"))

        val thrown = shouldThrow<DatapipelinesException> { controller.tables("pg-prod", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE },
            { thrown.details["datasource"] shouldBe "pg-prod" },
            { thrown.message shouldNotContain "Connection refused" },
        )
    }

    @Test
    fun `a mid-walk connection loss through a real introspector is the catalogued 502 - not a raw 500`() {
        // The round-3 widening, proven through the FULL web path: the controller delegates to
        // a real SchemaIntrospector whose metadata walk dies with SQLTimeoutException (a
        // connection-loss shape the old top-level-only classifier let escape as a raw 500).
        // The introspector's lease boundary must translate it to DatasourceUnreachableException,
        // which this controller maps to `pipeline.execution.datasource_unreachable` (HTTP 502
        // via ApiErrorCatalog — pinned here so the status mapping cannot drift either).
        val introspector = realIntrospectorThrowing(java.sql.SQLTimeoutException("timeout: network is dead"))
        val controller = DatasourceSchemaController(introspector)

        val thrown = shouldThrow<DatapipelinesException> { controller.tables("pg-prod", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE },
            {
                co.datapipelines.web.api.ApiErrorCatalog
                    .statusFor(thrown.code) shouldBe
                    org.springframework.http.HttpStatus.BAD_GATEWAY
            },
            { thrown.message shouldNotContain "network is dead" },
        )
    }

    @Test
    fun `an empty schema query param binds to non-null blank and still gets the default`() {
        // Spring binds `?schema=` (present-but-empty query param) to "", NOT null — the exact
        // binding a stray trailing `&` or an emptied form field produces. Driven through a
        // REAL introspector under the controller: the blank must normalize to absent, so
        // columns() takes the current-schema default (catalog "app" here) instead of sending
        // the '' sentinel to JDBC and silently reporting zero columns for an existing table.
        val meta = mockk<java.sql.DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        val columnsRs = mockk<java.sql.ResultSet>(relaxed = true)
        every { meta.getColumns("app", null, "orders", "%") } returns columnsRs
        every { columnsRs.next() } returns false
        val introspector =
            realIntrospectorOver(meta) { connection ->
                every { connection.catalog } returns "app"
            }
        val controller = DatasourceSchemaController(introspector)

        val data = controller.columns("pg-prod", "orders", schema = "").data

        (data as List<*>) shouldBe emptyList<Any?>()
        io.mockk.verify(exactly = 1) { meta.getColumns("app", null, "orders", "%") }
        io.mockk.verify(exactly = 0) { meta.getColumns("", null, "orders", "%") }
    }

    @Test
    fun `a no-schema COLUMNS read on a datasource with no current schema is the catalogued 400 - not a merged answer`() {
        // The cannot-merge promise (§9.7) made mechanical and scoped (R4 F6): a MySQL
        // registration with a database-less URL yields no current schema, and an
        // unqualified COLUMNS read would merge same-named tables' columns across every
        // visible database. Through a REAL introspector the controller must surface the
        // catalogued invalid-argument code (HTTP 400), with the message directing the caller
        // to the schemas listing. (An unfiltered /tables carries each row's own schema and
        // cannot merge — it keeps working; see the next test.)
        val meta = mockk<java.sql.DatabaseMetaData>()
        io.mockk.every { meta.searchStringEscape } returns "\\"
        val controller =
            DatasourceSchemaController(
                realIntrospectorOver(meta) { connection ->
                    io.mockk.every { connection.catalog } returns null
                },
            )

        val thrown = shouldThrow<DatapipelinesException> { controller.columns("pg-prod", "orders", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.PARAMETER_REQUIRED },
            {
                co.datapipelines.web.api.ApiErrorCatalog
                    .statusFor(thrown.code) shouldBe
                    org.springframework.http.HttpStatus.BAD_REQUEST
            },
            { thrown.message?.contains("schema", ignoreCase = true) shouldBe true },
            { thrown.details["datasource"] shouldBe "pg-prod" },
        )
    }

    @Test
    fun `an unfiltered TABLES listing on a datasource with no current schema spans schemas - not a 400`() {
        // R4 F6: the unknown-current-schema guard is scoped to columns(), where unfiltered
        // truly merges same-named tables' columns; a tables listing carries each row's own
        // schema, cannot merge, and keeps working exactly as before the guard existed.
        val meta = mockk<java.sql.DatabaseMetaData>()
        io.mockk.every { meta.searchStringEscape } returns "\\"
        val tablesRs =
            co.datapipelines.web.tablesResultSet("db1", "orders", schemaColumn = "TABLE_CAT")
        io.mockk.every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        val controller =
            DatasourceSchemaController(
                realIntrospectorOver(meta) { connection ->
                    io.mockk.every { connection.catalog } returns null
                },
            )

        val data = controller.tables("pg-prod", schema = null).data

        (data as Map<*, *>)["tables"] shouldBe
            listOf(mapOf("schema" to "db1", "name" to "orders", "type" to "TABLE"))
    }

    @Test
    fun `connection loss during the current-schema read is the catalogued 502 - not parameter_required`() {
        // F1: currentSchema() used to swallow ALL SQLException, so a connection that died
        // exactly during getCatalog()/getSchema() surfaced as "no current schema" — 400
        // parameter_required — and the recommended recovery (GET .../schemas) would fail on
        // the same dead connection. Proven through the FULL web path: the lease boundary
        // must classify the loss (502), keeping the 400 for a driver that legitimately
        // reports none.
        val meta = mockk<java.sql.DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        val introspector =
            realIntrospectorOver(meta) { connection ->
                every { connection.catalog } throws
                    java.sql.SQLNonTransientConnectionException("connection exception", "08001")
            }
        val controller = DatasourceSchemaController(introspector)

        val thrown = shouldThrow<DatapipelinesException> { controller.columns("pg-prod", "orders", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE },
            {
                co.datapipelines.web.api.ApiErrorCatalog
                    .statusFor(thrown.code) shouldBe
                    org.springframework.http.HttpStatus.BAD_GATEWAY
            },
            { thrown.message shouldNotContain "connection exception" },
        )
    }

    @Test
    fun `connection loss with a NON-08 state during the current-schema read is the catalogued 502 - not a raw 500`() {
        // R5 F1 shape (a) through the FULL web path: h2 2.3.232 reports a closed connection
        // OBJECT as JdbcSQLNonTransientException state 90007 — neither an 08 state nor a
        // connection-exception subclass, so the 08-family classifier cannot see it. Round 4's
        // narrowed catch let it propagate RAW past every translator (raw 500). The
        // introspector must classify it (502), with the driver's text staying off the wire.
        val meta = mockk<java.sql.DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        val controller =
            DatasourceSchemaController(
                realIntrospectorOver(
                    meta,
                    name = "h2-prod",
                    dialect = co.datapipelines.typesystem.Dialect.H2,
                    jdbcUrl = "jdbc:h2:mem:appprod",
                ) { connection ->
                    every { connection.schema } throws
                        org.h2.jdbc.JdbcSQLNonTransientException(
                            // Constructor is (message, sql, SQLSTATE, errorCode, cause, stackTrace).
                            "The object is already closed",
                            null,
                            "90007",
                            90007,
                            null,
                            null,
                        )
                },
            )

        val thrown = shouldThrow<DatapipelinesException> { controller.columns("h2-prod", "orders", schema = null) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE },
            {
                co.datapipelines.web.api.ApiErrorCatalog
                    .statusFor(thrown.code) shouldBe
                    org.springframework.http.HttpStatus.BAD_GATEWAY
            },
            { thrown.message shouldNotContain "already closed" },
        )
    }

    /**
     * A real [SchemaIntrospector] over one mock connection carrying [meta]. Name/dialect/URL
     * are parametrized because the current-schema read routes per dialect (catalog vs schema)
     * and the red shapes are driver-specific (R5 F1: an h2-closed connection on an H2
     * datasource, a duckdb-closed connection on a DuckDB one).
     */
    private fun realIntrospectorOver(
        meta: java.sql.DatabaseMetaData,
        name: String = "pg-prod",
        dialect: co.datapipelines.typesystem.Dialect = co.datapipelines.typesystem.Dialect.MYSQL,
        jdbcUrl: String = "jdbc:mysql://db.internal:3306/app",
        connectionSetup: (java.sql.Connection) -> Unit = {},
    ): SchemaIntrospector {
        val connection = mockk<java.sql.Connection>()
        every { connection.metaData } returns meta
        every { connection.close() } returns Unit
        connectionSetup(connection)
        val datasource =
            co.datapipelines.datasources.Datasource(
                name = name,
                displayName = name,
                dialect = dialect,
                jdbcUrl = jdbcUrl,
                username = "app",
                password = "secret",
            )
        val registry = mockk<co.datapipelines.datasources.DatasourceRegistry>()
        every { registry.get(name) } returns datasource
        every { registry.poolFor(datasource) } returns
            object : co.datapipelines.datasources.pooling.ConnectionPool {
                override val name: String = name

                override fun leaseConnection(): java.sql.Connection = connection

                override fun close() = Unit
            }
        return SchemaIntrospector(registry)
    }

    /** A real [SchemaIntrospector] over one connection whose metadata walk throws [failure]. */
    private fun realIntrospectorThrowing(failure: java.sql.SQLException): SchemaIntrospector {
        val meta = mockk<java.sql.DatabaseMetaData>()
        io.mockk.every { meta.searchStringEscape } returns "\\"
        io.mockk.every { meta.getTables(null, null, "%", any<Array<String>>()) } throws failure
        val connection = mockk<java.sql.Connection>()
        io.mockk.every { connection.metaData } returns meta
        io.mockk.every { connection.close() } returns Unit
        io.mockk.every { connection.schema } returns "public"
        io.mockk.every { connection.catalog } returns "app"
        val datasource =
            co.datapipelines.datasources.Datasource(
                name = "pg-prod",
                displayName = "PG",
                dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://db.internal:5432/app",
                username = "app",
                password = "secret",
            )
        val registry = mockk<co.datapipelines.datasources.DatasourceRegistry>()
        io.mockk.every { registry.get("pg-prod") } returns datasource
        io.mockk.every { registry.poolFor(datasource) } returns
            object : co.datapipelines.datasources.pooling.ConnectionPool {
                override val name: String = "pg-prod"

                override fun leaseConnection(): java.sql.Connection = connection

                override fun close() = Unit
            }
        return SchemaIntrospector(registry)
    }
}
