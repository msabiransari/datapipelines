package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SchemaSnapshot
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TableWithColumns
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

class DatasourceSchemaToolsTest {
    private val introspector = mockk<SchemaIntrospector>()
    private val authorCtx = McpFixtures.ctx(Scope.AUTHOR)

    @Test
    fun `get_tables returns snake_case table descriptors`() {
        every { introspector.tables("pg-prod", null) } returns listOf(TableInfo("public", "orders", "TABLE"))

        @Suppress("UNCHECKED_CAST")
        val payload =
            DatasourcesGetTablesTool(introspector).call(McpArguments(mapOf("name" to "pg-prod")), authorCtx) as List<Map<String, Any?>>

        assertAll(
            { payload.size shouldBe 1 },
            { payload[0]["schema"] shouldBe "public" },
            { payload[0]["name"] shouldBe "orders" },
            { payload[0]["type"] shouldBe "TABLE" },
        )
    }

    @Test
    fun `get_tables pushes the schema filter through`() {
        every { introspector.tables("pg-prod", "sales") } returns emptyList()

        val payload =
            DatasourcesGetTablesTool(introspector)
                .call(McpArguments(mapOf("name" to "pg-prod", "schema" to "sales")), authorCtx) as List<*>

        payload.size shouldBe 0
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
            { payload[1]["precision"] shouldBe 10 },
            { payload[1]["scale"] shouldBe 2 },
        )
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
}
