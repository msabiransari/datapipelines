package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ExplainPlanSummary
import co.datapipelines.datasources.QueryRows
import co.datapipelines.datasources.ResultSchema
import co.datapipelines.datasources.ScratchProbeOutcome
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.SqlProbeExecutionException
import co.datapipelines.datasources.SqlProbeParameter
import co.datapipelines.datasources.SqlProbeParameterException
import co.datapipelines.datasources.SqlProbeRefusalException
import co.datapipelines.datasources.SqlProbeTimeoutException
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.SQLException

/**
 * `sql_probe` (§7D, 107) — the tool's translation layer: argument binding (typed parameters,
 * clamped limits), the `tempdb` refusal, the §5.3 gate and the four-way error mapping. The
 * classifier, the EXPLAIN-first read and the timebox are pinned in the datasources module's
 * SqlProbe suites.
 */
class SqlProbeToolTest {
    private val datasources = mockk<DatasourceRegistry>()
    private val probe = mockk<SqlProbe>()
    private val tool = SqlProbeTool(datasources, probe)
    private val ctx = McpFixtures.ctx(co.datapipelines.auth.Scope.AUTHOR)

    private val gated = McpFixtures.datasource("pg-prod")

    private val plan =
        ExplainPlanSummary(
            scan = "seq",
            estimatedRows = "1000",
            raw = "Seq Scan on trips",
            partitionsScanned = null,
            partitionsTotal = null,
        )

    private fun result(rows: List<Map<String, Any?>> = listOf(mapOf("city" to "acme"))) =
        co.datapipelines.datasources.SqlProbeResult(
            rows =
                QueryRows(
                    schema = ResultSchema(listOf(ColumnSchema("city", LogicalType.STRING)), emptyList()),
                    rows = rows,
                    truncated = false,
                ),
            wallMs = 12,
            plan = plan,
        )

    private fun args(vararg extra: Pair<String, Any?>): McpArguments =
        McpArguments(mapOf("name" to "pg-prod", "sql" to "SELECT city FROM trips WHERE city = :city", *extra))

    @Test
    fun `the happy path passes typed parameters through and maps rows, wall time and the plan`() {
        val parameters = slot<Map<String, SqlProbeParameter>>()
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns gated
        every { probe.probe(gated, any(), capture(parameters), 50, 10) } returns result()

        val payload =
            tool.call(
                args(
                    "parameters" to
                        mapOf(
                            "city" to mapOf("type" to "STRING", "value" to "acme"),
                            "limit_day" to mapOf("type" to "DATE", "value" to null),
                        ),
                ),
                ctx,
            ) as Map<*, *>

        assertAll(
            { parameters.captured["city"] shouldBe SqlProbeParameter(LogicalType.STRING, "acme") },
            { parameters.captured["limit_day"] shouldBe SqlProbeParameter(LogicalType.DATE, null) },
            { payload["row_count_returned"] shouldBe 1 },
            { payload["truncated"] shouldBe false },
            { payload["wall_ms"] shouldBe 12 },
            { (payload["plan"] as Map<*, *>)["scan"] shouldBe "seq" },
            { (payload["plan"] as Map<*, *>)["raw"] shouldBe "Seq Scan on trips" },
        )
    }

    @Test
    fun `a non-SELECT refusal is an invalid-params fault and nothing is gated`() {
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns gated
        every { probe.probe(any(), any(), any(), any(), any()) } throws
            SqlProbeRefusalException("Only a SELECT or WITH statement is probeable.")

        val thrown =
            shouldThrow<McpError> {
                tool.call(McpArguments(mapOf("name" to "pg-prod", "sql" to "DELETE FROM trips")), ctx)
            }

        assertAll(
            { thrown.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS },
            { thrown.jsonRpcError.message() shouldBe "Invalid params: Only a SELECT or WITH statement is probeable." },
        )
    }

    @Test
    fun `a parameter fault is invalid-params and names the parameter, never the value`() {
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns gated
        every { probe.probe(any(), any(), any(), any(), any()) } throws SqlProbeParameterException.coercion("limit_day", LogicalType.DATE)

        val thrown = shouldThrow<McpError> { tool.call(args(), ctx) }

        assertAll(
            { thrown.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS },
            {
                thrown.jsonRpcError.message() shouldBe
                    "Invalid params: A probe parameter could not be coerced to its declared type DATE. Parameter: 'limit_day'."
            },
        )
    }

    @Test
    fun `a timeout is the catalogued node-query code carrying wall_ms and the plan`() {
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns gated
        every { probe.probe(any(), any(), any(), any(), any()) } throws
            SqlProbeTimeoutException("pg-prod", 10_000, plan, SQLException("canceling statement due to statement timeout"))

        val thrown = shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT },
            { thrown.details["wall_ms"] shouldBe 10_000L },
            { thrown.details["reason"] shouldBe "timeout" },
            { (thrown.details["plan"] as Map<*, *>)["scan"] shouldBe "seq" },
        )
    }

    @Test
    fun `a driver refusal carries the bounded driver message under the node-query code`() {
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns gated
        every { probe.probe(any(), any(), any(), any(), any()) } throws
            SqlProbeExecutionException("pg-prod", SQLException("syntax error at or near \"FORM\""))

        val thrown = shouldThrow<DatapipelinesException> { tool.call(args(), ctx) }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED },
            { (thrown.message ?: "") shouldBe "The database refused the statement: syntax error at or near \"FORM\"" },
        )
    }

    /**
     * 2026-09-11 — `tempdb` is a syntax-and-names check against an empty scratch H2, never a
     * gated datasource read: the registry is not consulted and the real probe never runs. The
     * scratch outcome is the service's; the tool shapes it (`parsed`, `missing_table`, `note`).
     */
    @Test
    fun `tempdb runs the scratch check and never touches the registry or the real probe`() {
        every { probe.probeScratch(any(), any(), any(), any(), any()) } returns
            ScratchProbeOutcome.Parsed(missingTable = "stg_orders", wallMs = 3)

        @Suppress("UNCHECKED_CAST")
        val payload =
            tool.call(McpArguments(mapOf("name" to "tempdb", "sql" to "SELECT 1 FROM stg_orders")), ctx) as Map<String, Any?>

        assertAll(
            { payload["check"] shouldBe "syntax_and_names" },
            { payload["parsed"] shouldBe true },
            { payload["missing_table"] shouldBe "stg_orders" },
            { (payload["note"] as String) shouldContain "exists only inside a full execution" },
        )
        verify(exactly = 0) { datasources.getVisible(any(), any()) }
        verify(exactly = 0) { probe.probe(any(), any(), any(), any(), any()) }
    }

    /** An H2 error in the scratch check is the same catalogued refusal a real probe reports. */
    @Test
    fun `a tempdb statement H2 refuses is the node-query code with H2's message`() {
        every { probe.probeScratch(any(), any(), any(), any(), any()) } throws
            SqlProbeExecutionException("tempdb", SQLException("Column \"column1\" not found", "42S22", 42122))

        val thrown =
            shouldThrow<DatapipelinesException> {
                tool.call(McpArguments(mapOf("name" to "tempdb", "sql" to "SELECT column1 FROM (VALUES (1))")), ctx)
            }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED },
            { (thrown.message ?: "") shouldContain "column1" },
        )
    }

    @Test
    fun `limit and timeout clamp into the documented windows`() {
        val limits = mutableListOf<Pair<Int, Int>>()
        every { datasources.getVisible("pg-prod", McpFixtures.WORKSPACE_ID) } returns gated
        every { probe.probe(gated, any(), any(), 500, 30) } answers {
            limits += 500 to 30
            result()
        }
        every { probe.probe(gated, any(), any(), 1, 1) } answers {
            limits += 1 to 1
            result()
        }

        tool.call(args("limit" to 10_000, "timeout_seconds" to 300), ctx)
        tool.call(args("limit" to 0, "timeout_seconds" to 0), ctx)

        limits shouldBe listOf(500 to 30, 1 to 1)
    }

    @Test
    fun `an unknown parameter type is invalid-params, not a guessed bind`() {
        shouldThrow<McpError> {
            tool.call(args("parameters" to mapOf("x" to mapOf("type" to "CLOBBER", "value" to "1"))), ctx)
        }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
        verify(exactly = 0) { probe.probe(any(), any(), any(), any(), any()) }
    }
}
