package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SchemaSnapshot
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TableWithColumns
import co.datapipelines.datasources.TablesPage
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.TypeMappingWarning
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class DatasourceSchemaToolsTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val authorCtx = McpFixtures.ctx(Scope.AUTHOR)

    @Test
    fun `get_tables returns the tables-plus-truncated payload`() {
        every { introspector.tables("pg-prod", null) } returns TablesPage(listOf(TableInfo("public", "orders", "TABLE")), truncated = false)

        val payload = DatasourcesGetTablesTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx) as Map<*, *>

        @Suppress("UNCHECKED_CAST")
        val tables = payload["tables"] as List<Map<String, Any?>>
        assertAll(
            { payload["truncated"] shouldBe false },
            { tables.size shouldBe 1 },
            { tables[0]["schema"] shouldBe "public" },
            { tables[0]["name"] shouldBe "orders" },
            { tables[0]["type"] shouldBe "TABLE" },
        )
    }

    @Test
    fun `get_tables flags truncation when the cap dropped tables`() {
        every { introspector.tables("pg-prod", null) } returns TablesPage(emptyList(), truncated = true)

        val payload = DatasourcesGetTablesTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx) as Map<*, *>

        payload["truncated"] shouldBe true
    }

    @Test
    fun `get_tables pushes the schema filter through`() {
        every { introspector.tables("pg-prod", "sales") } returns TablesPage(emptyList(), truncated = false)

        val payload =
            DatasourcesGetTablesTool(introspector)
                .call(McpArguments(mapOf("name" to "pg-prod", "schema" to "sales")), authorCtx) as Map<*, *>

        (payload["tables"] as List<*>).size shouldBe 0
    }

    @Test
    fun `get_columns returns canonical and source types snake_case`() {
        every { introspector.columns("pg-prod", "orders", null) } returns
            listOf(
                ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList()),
                ColumnInfo(ColumnSchema("amount", LogicalType.DECIMAL, precision = 10, scale = 2), "numeric", emptyList()),
            )

        @Suppress("UNCHECKED_CAST")
        val payload =
            DatasourcesGetColumnsTool(introspector)
                .call(McpArguments(mapOf("name" to "pg-prod", "table" to "orders")), authorCtx) as List<Map<String, Any?>>

        assertAll(
            { payload.size shouldBe 2 },
            { payload[0]["name"] shouldBe "id" },
            { payload[0]["type"] shouldBe "INTEGER" },
            { payload[0]["nullable"] shouldBe false },
            { payload[0]["source_type"] shouldBe "int4" },
            { payload[0]["warnings"] shouldBe emptyList<String>() },
            { payload[1]["precision"] shouldBe 10 },
            { payload[1]["scale"] shouldBe 2 },
        )
    }

    @Test
    fun `get_columns carries the mapper's warning messages`() {
        every { introspector.columns("pg-prod", "wide", null) } returns
            listOf(
                ColumnInfo(
                    ColumnSchema("mystery", LogicalType.STRING),
                    "sql_variant",
                    listOf(TypeMappingWarning.sqlVariant("mystery")),
                ),
            )

        @Suppress("UNCHECKED_CAST")
        val payload =
            DatasourcesGetColumnsTool(introspector)
                .call(McpArguments(mapOf("name" to "pg-prod", "table" to "wide")), authorCtx) as List<Map<String, Any?>>

        payload[0]["warnings"] shouldBe listOf(TypeMappingWarning.sqlVariant("mystery").message)
    }

    @Test
    fun `get_columns without a table argument is invalid params`() {
        shouldThrow<McpError> {
            DatasourcesGetColumnsTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx)
        }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `get_schema returns the snapshot payload`() {
        every { introspector.snapshot("pg-prod") } returns
            SchemaSnapshot(
                datasource = "pg-prod",
                dialect = "POSTGRES",
                truncated = false,
                tables =
                    listOf(
                        TableWithColumns(
                            TableInfo("public", "orders", "TABLE"),
                            listOf(ColumnInfo(ColumnSchema("id", LogicalType.INTEGER, nullable = false), "int4", emptyList())),
                        ),
                    ),
            )

        @Suppress("UNCHECKED_CAST")
        val payload = DatasourcesGetSchemaTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val firstTable = (payload["tables"] as List<Map<String, Any?>>)[0]
        assertAll(
            { payload["datasource"] shouldBe "pg-prod" },
            { payload["dialect"] shouldBe "POSTGRES" },
            { payload["truncated"] shouldBe false },
            { (firstTable["table"] as Map<*, *>)["name"] shouldBe "orders" },
            { ((firstTable["columns"] as List<*>)[0] as Map<*, *>)["source_type"] shouldBe "int4" },
        )
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
                    DatasourcesGetTablesTool(real).call(McpArguments(mapOf("name" to "nope")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetColumnsTool(real).call(McpArguments(mapOf("name" to "nope", "table" to "orders")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetSchemaTool(real).call(McpArguments(mapOf("name" to "nope")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
            },
        )
    }

    @Test
    fun `a connection failure is the catalogued datasource_unreachable on every introspection tool`() {
        // A customer DB being down must reach the dispatcher as a catalogued
        // DatapipelinesException (isError envelope), never as -32603. The introspector's
        // DatasourceUnreachableException wraps both failure families (SQLException at the
        // lease, RuntimeException at pool build — the Hikari path is pinned by
        // SchemaIntrospectorTest); the tools translate the one type.
        every { introspector.tables("down", null) } throws DatasourceUnreachableException("down", RuntimeException("Connection refused"))
        every { introspector.columns("down", "orders", null) } throws DatasourceUnreachableException("down", RuntimeException("Connection refused"))
        every { introspector.snapshot("down") } throws DatasourceUnreachableException("down", RuntimeException("Connection refused"))

        assertAll(
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
            {
                shouldThrow<DatapipelinesException> {
                    DatasourcesGetSchemaTool(introspector).call(McpArguments(mapOf("name" to "down")), authorCtx)
                }.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE
            },
        )
    }
}
