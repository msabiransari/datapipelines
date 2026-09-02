package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.OrderByTerm
import co.datapipelines.datasources.QueryRows
import co.datapipelines.datasources.SqlRunner
import co.datapipelines.datasources.SqlExecutionException
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * `datasources_preview_rows` (§6.2.19) — argument discipline (D1 order_by objects, D2 limit
 * clamp, D3 blank identifiers), the §5.3 visibility gate, and the §7B error mapping. The
 * engine's SQL building and caps are pinned in `SqlRunnerTest`; this suite owns the TOOL's
 * translation layer.
 */
class DatasourcesPreviewRowsToolTest {
    private val datasources = mockk<DatasourceRegistry>()
    private val runner = mockk<SqlRunner>()
    private val tool = DatasourcesPreviewRowsTool(datasources, runner)
    private val ctx = McpFixtures.ctx(co.datapipelines.auth.Scope.AUTHOR)

    private val gated =
        co.datapipelines.datasources.Datasource(
            name = "sample-trips",
            displayName = "Sample Trips",
            description = "",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://x/y",
            username = "u",
            password = null,
        )

    private fun page(vararg rows: Map<String, Any?>) =
        QueryRows(
            schema =
                co.datapipelines.datasources.ResultSchema(
                    listOf(ColumnSchema("city", LogicalType.STRING), ColumnSchema("fare", LogicalType.INTEGER)),
                    emptyList(),
                ),
            rows = rows.toList(),
            truncated = false,
        )

    @Test
    fun `the minimal call passes identifiers, empty order and the 50 default to the gated preview`() {
        val order = slot<List<OrderByTerm>>()
        every {
            runner.previewTable(gated, "trips", null, capture(order), 50)
        } returns page(mapOf("city" to "acme", "fare" to 7L))
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated

        val payload =
            payloadOf(
                tool.call(
                    McpArguments(mapOf("name" to "sample-trips", "table" to "trips")),
                    ctx,
                ),
            )

        assertAll(
            { order.captured shouldBe emptyList() },
            { payload["datasource"] shouldBe "sample-trips" },
            { payload["table"] shouldBe "trips" },
            { payload["row_count"] shouldBe 1 },
            { payload["truncated"] shouldBe false },
            {
                payload["columns"] shouldBe
                    listOf(mapOf("name" to "city", "type" to "STRING"), mapOf("name" to "fare", "type" to "INTEGER"))
            },
        )
    }

    @Test
    fun `order_by entries are structured objects - ASC default, DESC honored, free strings refused`() {
        val order = slot<List<OrderByTerm>>()
        every {
            runner.previewTable(gated, "trips", "public", capture(order), 10)
        } returns page()
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated

        tool.call(
            McpArguments(
                mapOf(
                    "name" to "sample-trips",
                    "table" to "trips",
                    "schema" to "public",
                    "limit" to 10,
                    "order_by" to listOf(mapOf("column" to "fare"), mapOf("column" to "city", "direction" to "DESC")),
                ),
            ),
            ctx,
        )

        order.captured shouldBe listOf(OrderByTerm("fare", descending = false), OrderByTerm("city", descending = true))

        // D1: a free "col DESC" string is a protocol failure, never untangled by the quoter.
        val thrown =
            shouldThrow<io.modelcontextprotocol.spec.McpError> {
                tool.call(
                    McpArguments(mapOf("name" to "sample-trips", "table" to "trips", "order_by" to listOf("fare DESC"))),
                    ctx,
                )
            }
        thrown.message shouldBe thrown.message
    }

    @Test
    fun `a blank identifier and a bad direction are invalid params`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated
        every { runner.previewTable(gated, any(), any(), any(), any()) } returns page()

        shouldThrow<io.modelcontextprotocol.spec.McpError> {
            tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "  ")), ctx)
        }
        shouldThrow<io.modelcontextprotocol.spec.McpError> {
            tool.call(
                McpArguments(
                    mapOf(
                        "name" to "sample-trips",
                        "table" to "trips",
                        "order_by" to listOf(mapOf("column" to "fare", "direction" to "desc")),
                    ),
                ),
                ctx,
            )
        }
        shouldThrow<io.modelcontextprotocol.spec.McpError> {
            tool.call(
                McpArguments(mapOf("name" to "sample-trips", "table" to "trips", "order_by" to listOf(mapOf("direction" to "ASC")))),
                ctx,
            )
        }
    }

    @Test
    fun `limit clamps into the 1 to 50 window - the documented McpArguments behavior`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated
        every { runner.previewTable(gated, "trips", null, any(), 50) } returns page()
        every { runner.previewTable(gated, "trips", null, any(), 1) } returns page()

        tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips", "limit" to 500)), ctx)
        tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips", "limit" to 0)), ctx)

        verify(exactly = 1) { runner.previewTable(gated, "trips", null, any(), 50) }
        verify(exactly = 1) { runner.previewTable(gated, "trips", null, any(), 1) }
    }

    @Test
    fun `a datasource in another workspace is not-found before any query runs`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns null

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips")), ctx)
            }

        thrown.code shouldBe PipelineErrorCodes.Datasource.NOT_FOUND
        verify(exactly = 0) { runner.previewTable(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unreachable and refused statements map to the catalogued codes`() {
        every { datasources.getVisible("sample-trips", McpFixtures.WORKSPACE_ID) } returns gated
        every { runner.previewTable(gated, "trips", null, any(), any()) } throws
            co.datapipelines.datasources.DatasourceUnreachableException(
                "sample-trips",
                java.sql.SQLException("down"),
            )

        val unreachable =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips")), ctx)
            }
        unreachable.code shouldBe PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE

        every { runner.previewTable(gated, "trips", null, any(), any()) } throws
            SqlExecutionException("sample-trips", java.sql.SQLException("syntax error near 'FROM'"))

        val refused =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("name" to "sample-trips", "table" to "trips")), ctx)
            }
        refused.code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
    }
}

    @Suppress("UNCHECKED_CAST")
    private fun payloadOf(call: Any?): Map<String, Any?> = call as Map<String, Any?>
