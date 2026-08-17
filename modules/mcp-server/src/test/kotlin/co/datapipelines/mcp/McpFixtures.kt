package co.datapipelines.mcp

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.datasources.Datasource
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import io.modelcontextprotocol.spec.McpSchema
import java.time.Instant
import java.util.UUID

/**
 * Shared test data. Deliberately named without the `Test` suffix so the module's
 * `verifyTestsExecuted` guard counts only real test classes.
 */
object McpFixtures {
    val USER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val OTHER_USER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    val PIPELINE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val EXECUTION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val CORRELATION_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    fun principal(
        vararg scopes: Scope,
        userId: UUID = USER,
        method: AuthMethod = AuthMethod.API_KEY,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "agent@example.test",
            displayName = "Agent",
            scopes = scopes.toSet(),
            authMethod = method,
            keyId = "dpk_ABCDEFGHIJKL",
        )

    fun ctx(
        vararg scopes: Scope,
        userId: UUID = USER,
    ): McpToolContext = McpToolContext(principal(*scopes, userId = userId), CORRELATION_ID)

    fun request(
        tool: String,
        arguments: Map<String, Any?> = emptyMap(),
    ): McpSchema.CallToolRequest =
        McpSchema.CallToolRequest
            .builder(tool)
            .arguments(arguments)
            .build()

    fun pipelineRecord(
        id: UUID = PIPELINE_ID,
        name: String = "monthly_revenue",
        displayName: String = "Monthly Revenue",
        description: String = "Revenue by customer.",
        owner: UUID = USER,
        version: Int = 1,
    ): PipelineRecord =
        PipelineRecord(
            id = id,
            name = name,
            displayName = displayName,
            description = description,
            ownerId = owner,
            currentVersion = version,
            isDeleted = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-02T00:00:00Z"),
        )

    /** A minimal valid pipeline body: one DQL node with no `output` block → the caller node. */
    fun pipelineBody(
        name: String = "monthly_revenue",
        source: String = "pg-prod",
    ): String =
        """
        {
          "schema_version": 1,
          "name": "$name",
          "display_name": "Monthly Revenue",
          "description": "Revenue by customer.",
          "parameters": {},
          "nodes": [
            {
              "id": "fetch",
              "type": "DQL",
              "source": "$source",
              "template": {"id": "revenue.sql", "version": 1},
              "depends_on": []
            }
          ]
        }
        """.trimIndent()

    fun executionRecord(
        executionId: UUID = EXECUTION_ID,
        pipelineId: UUID = PIPELINE_ID,
        status: ExecutionStatus = ExecutionStatus.SUCCESS,
        triggeredBy: UUID = USER,
        startedAt: Instant = Instant.parse("2026-08-09T12:00:00Z"),
        resultRowCount: Long? = 3,
    ): ExecutionRecord =
        ExecutionRecord(
            executionId = executionId,
            pipelineId = pipelineId,
            pipelineVersion = 1,
            status = status,
            parametersJson = """{"month":"2026-07"}""",
            triggeredBy = triggeredBy,
            triggeredVia = ExecutionTrigger.MCP,
            correlationId = CORRELATION_ID,
            startedAt = startedAt,
            completedAt = startedAt.plusSeconds(2),
            durationMs = 2_000,
            nodeStatsJson = """[{"node_id":"fetch","status":"SUCCESS"}]""",
            resultRowCount = resultRowCount,
            resultSizeBytes = resultRowCount?.let { it * 100 },
        )

    fun template(
        id: String = "revenue.sql",
        version: Int = 1,
        dialect: Dialect = Dialect.POSTGRES,
        isLibrary: Boolean = false,
    ): Template =
        Template(
            id = id,
            version = version,
            dialect = dialect,
            displayName = "Revenue",
            description = "Expects: month (STRING).",
            body = "SELECT 1",
            isLibrary = isLibrary,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = USER,
        )

    fun datasource(
        name: String = "pg-prod",
        dialect: Dialect = Dialect.POSTGRES,
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Production Postgres",
            description = "Primary OLTP",
            dialect = dialect,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "reporting",
            password = "super-secret-password",
        )

    /**
     * A one-row `getTables` [java.sql.ResultSet] — the single (schema, name, type) row the
     * schema-tools tests' tables walk reports ([schemaColumn] selects the dialect's
     * vocabulary; TABLE_CAT for catalog-routing drivers). This module's OWN copy of the
     * small builder the datasources and web test sources also keep — no cross-module
     * coupling (R5 F8).
     */
    fun tablesResultSet(
        schema: String?,
        name: String,
        type: String = "TABLE",
        schemaColumn: String = "TABLE_SCHEM",
    ): java.sql.ResultSet {
        val rs = io.mockk.mockk<java.sql.ResultSet>(relaxed = true)
        io.mockk.every { rs.next() } returns true andThen false
        io.mockk.every { rs.getString(schemaColumn) } returns schema
        io.mockk.every { rs.getString("TABLE_NAME") } returns name
        io.mockk.every { rs.getString("TABLE_TYPE") } returns type
        return rs
    }

    /** The single text block of a tool result, parsed back into JSON. */
    fun payloadOf(result: McpSchema.CallToolResult): JsonNode =
        ExecutorJson.mapper.readTree((result.content().first() as McpSchema.TextContent).text())
}
