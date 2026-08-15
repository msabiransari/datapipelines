package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
    fun `columns falls back to unfiltered minus system schemas when the driver reports no current schema`() {
        // A driver may return null (or throw SQLFeatureNotSupportedException) from
        // getSchema()/getCatalog() — the read is then unfiltered, with system schemas still
        // excluded row by row.
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

                introspector.columns(name, "deals") shouldBe emptyList()
                verify(exactly = 1) { meta.getColumns(null, null, "deals", "%") }
            },
            {
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getColumns(null, null, "deals", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.schema } throws SQLFeatureNotSupportedException()
                    }

                introspector.columns(name, "deals") shouldBe emptyList()
                verify(exactly = 1) { meta.getColumns(null, null, "deals", "%") }
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

        introspector.schemas(name) shouldBe listOf("public")
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

        introspector.schemas(name) shouldBe listOf("my_app")
    }

    @Test
    fun `schemas skips blank names - the JDBC no-catalog sentinel is not a schema`() {
        val meta = mockk<DatabaseMetaData>()
        val schemasRs = mockk<ResultSet>(relaxed = true)
        every { meta.schemas } returns schemasRs
        every { schemasRs.next() } returns true andThen true andThen false
        every { schemasRs.getString("TABLE_SCHEM") } returns "" andThen "public"

        val (introspector, name) = introspectorOver(Dialect.POSTGRES, meta)

        introspector.schemas(name) shouldBe listOf("public")
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
}
