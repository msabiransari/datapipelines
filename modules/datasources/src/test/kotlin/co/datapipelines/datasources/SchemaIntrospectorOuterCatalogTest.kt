package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DatabaseMetaData
import java.sql.ResultSet

/**
 * The outer-catalog read's classification (#336 D3) — the HALF of the current-namespace
 * read [SchemaIntrospectorRoutingTest]'s families cover for the innermost level.
 *
 * On a `hasOuterCatalog` shape a failing `getCatalog()` used to read as "no outer segment",
 * which is the shape that MERGED two ATTACHed catalogs' tables (datasources.md §7A). Now the
 * outer read runs the innermost read's own three families: a capability statement keeps the
 * fallback, connection loss is the catalogued 502, anything else is
 * [CurrentSchemaUnknownException] with the driver exception attached.
 */
class SchemaIntrospectorOuterCatalogTest {
    @Test
    fun `a failing outer-catalog read is classified, never the silent merge fallback (#336)`() {
        // The outer segment is what separates two ATTACHed catalogs' tables — `getCatalog()`
        // failing used to read as "no outer segment", which is the shape that MERGED two
        // catalogs' tables (087's measured hazard, datasources.md §7A). Now: a capability
        // statement keeps the fallback (the driver reports none — the one-catalog dialects
        // behave as before); any OTHER failure raises through the innermost read's own
        // families, whose surfaces (REST columns, the MCP tool, the schema tree) already
        // answer the two catalogued refusals.
        assertAll(
            {
                // (a) The typed capability statement: outer stays absent, the read proceeds
                //     with the innermost segment only — unchanged behaviour, pinned here so
                //     the raise below cannot widen back over it.
                val meta = mockk<DatabaseMetaData>()
                val columnsRs = mockk<ResultSet>(relaxed = true)
                every { meta.searchStringEscape } returns "\\"
                every { meta.getTables(null, "public", "%", any<Array<String>>()) } answers
                    { tablesResultSet("public", "orders") }
                every { meta.getColumns(null, "public", "orders", "%") } returns columnsRs
                every { columnsRs.next() } returns false
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.catalog } throws java.sql.SQLFeatureNotSupportedException("reports none")
                    }

                introspector.columns(name, "orders") shouldBe emptyList()
            },
            {
                // (b) A non-connection failure of the read itself (pgjdbc's getCatalog can hit
                //     the server) raises the catalogued unknown-current-schema refusal with the
                //     driver exception attached — on the old code this was a silent fallback.
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.POSTGRES, meta) { connection ->
                        every { connection.catalog } throws
                            org.postgresql.util.PSQLException(
                                "canceling statement due to user request",
                                org.postgresql.util.PSQLState.QUERY_CANCELED,
                            )
                    }

                val failure =
                    shouldThrow<CurrentSchemaUnknownException> { introspector.columns(name, "deals") }
                failure.cause!!.message.shouldContain("canceling statement")
            },
            {
                // (c) Connection loss (H2's closed-object 90007) raises the catalogued 502 —
                //     not "no outer segment" on a dead connection.
                val meta = mockk<DatabaseMetaData>()
                val (introspector, name) =
                    introspectorOver(Dialect.H2, meta) { connection ->
                        every { connection.catalog } throws
                            org.h2.jdbc.JdbcSQLNonTransientException(
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
        )
    }
}
