package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

class ExecutionToolsTest {
    private val executions = mockk<ExecutionRepository>()
    private val ctx = McpFixtures.ctx(Scope.READ)

    @Test
    fun `list returns the caller's own executions`() {
        every { executions.findByUser(any(), McpFixtures.USER, limit = 50, offset = 0) } returns listOf(McpFixtures.executionRecord())

        val hits = ExecutionsListTool(executions).call(McpArguments(emptyMap()), ctx) as List<*>

        (hits.first() as Map<*, *>)["execution_id"] shouldBe McpFixtures.EXECUTION_ID.toString()
    }

    @Test
    fun `list filters by status`() {
        every { executions.findByUser(any(), McpFixtures.USER, limit = 50, offset = 0) } returns
            listOf(
                McpFixtures.executionRecord(status = ExecutionStatus.SUCCESS),
                McpFixtures.executionRecord(executionId = UUID.randomUUID(), status = ExecutionStatus.FAILED),
            )

        val failed = ExecutionsListTool(executions).call(McpArguments(mapOf("status" to "FAILED")), ctx) as List<*>

        failed.map { (it as Map<*, *>)["status"] } shouldContainExactly listOf("FAILED")
    }

    @Test
    fun `list by pipeline hides other users' executions from a non-admin`() {
        every { executions.findByPipeline(any(), McpFixtures.PIPELINE_ID, limit = 50, offset = 0) } returns
            listOf(
                McpFixtures.executionRecord(),
                McpFixtures.executionRecord(executionId = UUID.randomUUID(), triggeredBy = McpFixtures.OTHER_USER),
            )

        val mine =
            ExecutionsListTool(executions).call(
                McpArguments(mapOf("pipeline_id" to McpFixtures.PIPELINE_ID.toString())),
                ctx,
            ) as List<*>

        assertAll(
            { mine.size shouldBe 1 },
            { (mine.first() as Map<*, *>)["triggered_by"] shouldBe McpFixtures.USER.toString() },
        )
    }

    @Test
    fun `list by pipeline shows every user's executions to an admin`() {
        every { executions.findByPipeline(any(), McpFixtures.PIPELINE_ID, limit = 50, offset = 0) } returns
            listOf(
                McpFixtures.executionRecord(),
                McpFixtures.executionRecord(executionId = UUID.randomUUID(), triggeredBy = McpFixtures.OTHER_USER),
            )

        val all =
            ExecutionsListTool(executions).call(
                McpArguments(mapOf("pipeline_id" to McpFixtures.PIPELINE_ID.toString())),
                McpFixtures.ctx(Scope.ADMIN),
            ) as List<*>

        all.size shouldBe 2
    }

    @Test
    fun `get returns metadata with parsed parameters and node stats, and no rows`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns McpFixtures.executionRecord()

        @Suppress("UNCHECKED_CAST")
        val payload =
            ExecutionsGetTool(executions).call(
                McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())),
                ctx,
            ) as Map<String, Any?>

        assertAll(
            { payload["status"] shouldBe "SUCCESS" },
            { payload["triggered_via"] shouldBe "MCP" },
            { payload["duration_ms"] shouldBe 2_000L },
            { McpTools.readTree(payload["parameters"].toString())["month"].asText() shouldBe "2026-07" },
            { McpTools.readTree(payload["node_stats"].toString())[0]["node_id"].asText() shouldBe "fetch" },
            { payload.containsKey("rows") shouldBe false },
        )
    }

    @Test
    fun `get on a FAILED execution returns the full failure record verbatim`() {
        // 057/T85: the error object an agent reads must be the one the run produced — node
        // context, rendered SQL and the exception chain — with the root cause at the END of
        // caused_by. Nothing is rebuilt or filtered on the way out.
        val errorJson =
            """
            {"code":"pipeline.node.datasource_connection_failed",
             "message":"Failed to initialize pool",
             "details":{"phase":"connect"},
             "correlation_id":"${McpFixtures.CORRELATION_ID}",
             "node":{"id":"stage_daily_trips","type":"DQL","datasource":"sample-trips","dialect":"POSTGRES",
                     "template":"sample_trips_daily.sql","template_version":1},
             "sql":"SELECT * FROM trips WHERE borough = :borough",
             "exception":{"class":"java.lang.RuntimeException","message":"Failed to initialize pool",
                          "frames":["Boom.f0(Boom.kt:1)"],
                          "caused_by":[{"class":"org.postgresql.util.PSQLException",
                                        "message":"FATAL: password authentication failed for user \"dp_demo_ro\""}]}}
            """.trimIndent()
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(status = ExecutionStatus.FAILED).copy(
                failedNodeId = "stage_daily_trips",
                errorJson = errorJson,
                resultRowCount = null,
            )

        @Suppress("UNCHECKED_CAST")
        val payload =
            ExecutionsGetTool(executions).call(
                McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())),
                ctx,
            ) as Map<String, Any?>

        val error = McpTools.readTree(payload["error"].toString())
        assertAll(
            { error["code"].asText() shouldBe "pipeline.node.datasource_connection_failed" },
            { error["correlation_id"].asText() shouldBe McpFixtures.CORRELATION_ID.toString() },
            { error["node"]["datasource"].asText() shouldBe "sample-trips" },
            { error["sql"].asText() shouldBe "SELECT * FROM trips WHERE borough = :borough" },
            { error["exception"]["class"].asText() shouldBe "java.lang.RuntimeException" },
            { error["exception"]["caused_by"][0]["class"].asText() shouldBe "org.postgresql.util.PSQLException" },
        )
    }

    @Test
    fun `another user's execution is invisible, reported as not found`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(triggeredBy = McpFixtures.OTHER_USER)

        shouldThrow<DatapipelinesException> {
            ExecutionsGetTool(executions).call(McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())), ctx)
        }.code shouldBe PipelineErrorCodes.Result.EXECUTION_NOT_FOUND
    }

    @Test
    fun `an admin may read another user's execution`() {
        every { executions.findById(any(), McpFixtures.EXECUTION_ID) } returns
            McpFixtures.executionRecord(triggeredBy = McpFixtures.OTHER_USER)

        @Suppress("UNCHECKED_CAST")
        val payload =
            ExecutionsGetTool(executions).call(
                McpArguments(mapOf("execution_id" to McpFixtures.EXECUTION_ID.toString())),
                McpFixtures.ctx(Scope.ADMIN),
            ) as Map<String, Any?>

        payload["triggered_by"] shouldBe McpFixtures.OTHER_USER.toString()
    }
}
