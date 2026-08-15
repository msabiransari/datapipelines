package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.DatasourceRegistry
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
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * §7A tool DELEGATION over a mocked introspector: each tool parses its arguments, threads them
 * to [SchemaIntrospector], and serves the shared wire projections (`toWireMap`) unchanged —
 * the field-by-field shape of those projections is owned ONCE by `SchemaWireTest` in
 * `modules/datasources`, not re-asserted here per surface. Error paths (not-found, unreachable,
 * invalid params) are this layer's own behavior and stay fully asserted.
 */
class DatasourceSchemaToolsTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val authorCtx = McpFixtures.ctx(Scope.AUTHOR)

    private fun unreachable(name: String) = DatasourceUnreachableException(name, RuntimeException("Connection refused"))

    @Test
    fun `get_schemas threads its arguments and serves the shared wire projection`() {
        val page = co.datapipelines.datasources.SchemasPage(listOf("public", "sales"), truncated = true)
        every { introspector.schemas("pg-prod") } returns page

        val payload = DatasourcesGetSchemasTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx)

        payload shouldBe page.toWireMap()
    }

    @Test
    fun `get_tables threads its arguments and serves the shared wire projection`() {
        val page = TablesPage(listOf(TableInfo("public", "orders", "TABLE")), truncated = true)
        every { introspector.tables("pg-prod", "sales") } returns page

        val payload = DatasourcesGetTablesTool(introspector).call(McpArguments(mapOf("name" to "pg-prod", "schema" to "sales")), authorCtx)

        payload shouldBe page.toWireMap()
    }

    @Test
    fun `get_columns threads its arguments and serves the shared wire projection`() {
        val columns =
            listOf(
                ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList()),
                ColumnInfo(ColumnSchema("amount", LogicalType.DECIMAL, precision = 10, scale = 2), "numeric", emptyList()),
            )
        every { introspector.columns("pg-prod", "orders", null) } returns columns

        val payload = DatasourcesGetColumnsTool(introspector).call(McpArguments(mapOf("name" to "pg-prod", "table" to "orders")), authorCtx)

        payload shouldBe columns.map { it.toWireMap() }
    }

    @Test
    fun `get_columns without a table argument is invalid params`() {
        shouldThrow<McpError> {
            DatasourcesGetColumnsTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx)
        }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `an unknown datasource is the catalogued not-found on every introspection tool`() {
        // A real introspector over a registry that knows no such name — the true failure path.
        val registry = mockk<DatasourceRegistry>()
        every { registry.get("nope") } returns null
        val real = SchemaIntrospector(registry)

        assertAll(
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetSchemasTool(real).call(McpArguments(mapOf("name" to "nope")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetTablesTool(real).call(McpArguments(mapOf("name" to "nope")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetColumnsTool(real).call(McpArguments(mapOf("name" to "nope", "table" to "orders")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
        )
    }

    @Test
    fun `a mid-walk connection loss through a real introspector is the isError code - never -32603`() {
        // The round-3 widening, proven through the FULL MCP path: the tool delegates to a
        // real SchemaIntrospector whose metadata walk dies with SQLTimeoutException (a
        // connection-loss shape the old top-level-only classifier let escape as -32603).
        // The introspector translates at its lease boundary; the tool's own translation then
        // yields the catalogued DatapipelinesException the dispatcher envelopes as isError.
        val real = realIntrospectorThrowing(java.sql.SQLTimeoutException("timeout: network is dead"))

        val thrown =
            shouldThrow<DatapipelinesException> {
                DatasourcesGetTablesTool(real).call(McpArguments(mapOf("name" to "down")), authorCtx)
            }

        thrown.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
    }

    /** A real [SchemaIntrospector] over one connection whose metadata walk throws [failure]. */
    private fun realIntrospectorThrowing(failure: java.sql.SQLException): SchemaIntrospector {
        val meta = mockk<java.sql.DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } throws failure
        val connection = mockk<java.sql.Connection>()
        every { connection.metaData } returns meta
        every { connection.close() } returns Unit
        every { connection.schema } returns "public"
        every { connection.catalog } returns "app"
        val datasource =
            co.datapipelines.datasources.Datasource(
                name = "down",
                displayName = "Down",
                dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://db.internal:5432/app",
                username = "app",
                password = "secret",
            )
        val registry = mockk<DatasourceRegistry>()
        every { registry.get("down") } returns datasource
        every { registry.poolFor(datasource) } returns
            object : co.datapipelines.datasources.pooling.ConnectionPool {
                override val name: String = "down"

                override fun leaseConnection(): java.sql.Connection = connection

                override fun close() = Unit
            }
        return SchemaIntrospector(registry)
    }

    @Test
    fun `a no-schema read on a datasource with no current schema is the catalogued invalid-argument code`() {
        // The cannot-merge promise (mcp-server §6.2.17/§6.2.18) made mechanical: a database-less
        // MySQL registration yields no current schema and the unqualified read would merge
        // same-named tables across every visible database. Through a REAL introspector the
        // tool must surface the catalogued code (isError envelope via the dispatcher), never
        // a merged answer — with the message pointing at datasources_get_schemas.
        val meta = mockk<java.sql.DatabaseMetaData>()
        every { meta.searchStringEscape } returns "\\"
        val connection = mockk<java.sql.Connection>()
        every { connection.metaData } returns meta
        every { connection.close() } returns Unit
        every { connection.catalog } returns null
        val datasource =
            co.datapipelines.datasources.Datasource(
                name = "down",
                displayName = "Down",
                dialect = co.datapipelines.typesystem.Dialect.MYSQL,
                jdbcUrl = "jdbc:mysql://db.internal:3306",
                username = "app",
                password = "secret",
            )
        val registry = mockk<DatasourceRegistry>()
        every { registry.get("down") } returns datasource
        every { registry.poolFor(datasource) } returns
            object : co.datapipelines.datasources.pooling.ConnectionPool {
                override val name: String = "down"

                override fun leaseConnection(): java.sql.Connection = connection

                override fun close() = Unit
            }
        val real = SchemaIntrospector(registry)

        val thrown =
            shouldThrow<DatapipelinesException> {
                DatasourcesGetTablesTool(real).call(McpArguments(mapOf("name" to "down")), authorCtx)
            }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Execution.PARAMETER_REQUIRED },
            { thrown.message?.contains("datasources_get_schemas", ignoreCase = true) shouldBe true },
        )
    }

    @Test
    fun `a connection failure is the catalogued datasource_unreachable on every introspection tool`() {
        // A customer DB being down must reach the dispatcher as a catalogued
        // DatapipelinesException (isError envelope), never as -32603. The introspector's
        // DatasourceUnreachableException wraps both failure families (SQLException at the
        // lease, RuntimeException at pool build — the Hikari path is pinned by the
        // introspector tests); the tools translate the one type.
        every { introspector.schemas("down") } throws unreachable("down")
        every { introspector.tables("down", null) } throws unreachable("down")
        every { introspector.columns("down", "orders", null) } throws unreachable("down")

        assertAll(
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetSchemasTool(introspector).call(McpArguments(mapOf("name" to "down")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
            },
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetTablesTool(introspector).call(McpArguments(mapOf("name" to "down")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
            },
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetColumnsTool(introspector).call(McpArguments(mapOf("name" to "down", "table" to "orders")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
            },
        )
    }
}
