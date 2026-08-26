package co.datapipelines.web.executions

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.metrics.WebMetrics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/**
 * The cursor's §7.6 rule order and its two served formats, over mocked stores.
 */
class ResultCursorTest {
    private val executions = mockk<ExecutionRepository>()
    private val resultStore = mockk<ResultStore>()
    private val cursor =
        ResultCursor(executions, resultStore, ResultConfig(pageSizeRows = 3, pageMaxRows = 10), WebMetrics(SimpleMeterRegistry()))

    private val owner = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val key = "dp:result:$executionId"

    private fun principal(
        userId: UUID = owner,
        scopes: Set<Scope> = setOf(Scope.READ),
    ) = AuthenticatedPrincipal(
        userId,
        "a@b.c",
        "A",
        scopes,
        AuthMethod.API_KEY,
        "dpk_x",
        workspace = WorkspaceContext(workspaceId, "acme"),
    )

    private fun record(
        status: ExecutionStatus,
        triggeredBy: UUID = owner,
        rows: Long? = 5,
    ) = ExecutionRecord(
        executionId = executionId,
        pipelineId = UUID.randomUUID(),
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        triggeredBy = triggeredBy,
        triggeredVia = ExecutionTrigger.REST,
        resultRowCount = rows,
    )

    @Test
    fun `an unknown or non-owned execution is the same 404`() {
        every { executions.findById(any(), executionId) } returns null
        shouldThrow<ApiException> { cursor.readable(executionId, principal()) }.code shouldBe "result.execution_not_found"

        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS, triggeredBy = UUID.randomUUID())
        shouldThrow<ApiException> { cursor.readable(executionId, principal()) }.code shouldBe "result.execution_not_found"
    }

    @Test
    fun `admin reads any execution`() {
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS, triggeredBy = UUID.randomUUID())
        cursor.readable(executionId, principal(userId = UUID.randomUUID(), scopes = setOf(Scope.ADMIN))).status shouldBe
            ExecutionStatus.SUCCESS
    }

    @Test
    fun `running is 409-incomplete and failed is 410-failed`() {
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        shouldThrow<ApiException> { cursor.readable(executionId, principal()) }.code shouldBe "result.execution_incomplete"

        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.FAILED)
        shouldThrow<ApiException> { cursor.readable(executionId, principal()) }.code shouldBe "result.execution_failed"

        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.ABORTED)
        shouldThrow<ApiException> { cursor.readable(executionId, principal()) }.code shouldBe "result.execution_failed"
    }

    @Test
    fun `a successful zero-caller execution yields the empty page, not an error`() {
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS, rows = null)
        val page = cursor.jsonPage(cursor.readable(executionId, principal()), 0L, null)
        page["total_rows"] shouldBe 0L
        page["has_more"] shouldBe false
    }

    @Test
    fun `an expired result is 410 expired`() {
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)
        every { resultStore.keyFor(executionId) } returns key
        every { resultStore.page(key, 0L, 3) } returns null
        shouldThrow<ApiException> { cursor.jsonPage(cursor.readable(executionId, principal()), 0L, null) }
            .code shouldBe "result.expired"
    }

    @Test
    fun `json page shape matches section 7-3`() {
        val expires = Instant.parse("2026-08-05T14:35:02Z")
        val schema = listOf(ColumnSchema("customer_id", LogicalType.INTEGER))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)
        every { resultStore.keyFor(executionId) } returns key
        every { resultStore.page(key, 0L, 3) } returns
            ResultPage(executionId, schema, listOf(listOf(1), listOf(2)), 0L, 3, 5, expires)

        val page = cursor.jsonPage(cursor.readable(executionId, principal()), 0L, null)
        page["row_count"] shouldBe 2
        page["total_rows"] shouldBe 5L
        page["has_more"] shouldBe true
        page["expires_at"] shouldBe "2026-08-05T14:35:02Z"
    }

    @Test
    fun `csv carries a header row and wire-string values, fully paged`() {
        val expires = Instant.parse("2026-08-05T14:35:02Z")
        val schema =
            listOf(
                ColumnSchema("customer_id", LogicalType.INTEGER),
                ColumnSchema("total", LogicalType.BIGDECIMAL, 18, 2),
                ColumnSchema("note", LogicalType.STRING),
            )
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)
        every { resultStore.keyFor(executionId) } returns key
        every { resultStore.page(key, 0L, 10) } returns
            ResultPage(executionId, schema, listOf(listOf(1, "12345.67", "a,b"), listOf(2, "9.10", null)), 0L, 10, 3, expires)
        every { resultStore.page(key, 2L, 10) } returns
            ResultPage(executionId, schema, listOf(listOf(3, "1.00", "say \"hi\"")), 2L, 10, 3, expires)

        val out = ByteArrayOutputStream()
        cursor.writeCsv(cursor.readable(executionId, principal()), out)

        out.toString(Charsets.UTF_8) shouldBe
            "customer_id,total,note\n1,12345.67,\"a,b\"\n2,9.10,\n3,1.00,\"say \"\"hi\"\"\"\n"
    }

    @Test
    fun `csv formula injection - STRING cells starting with formula characters are quote-prefixed`() {
        val expires = Instant.parse("2026-08-05T14:35:02Z")
        val schema =
            listOf(
                ColumnSchema("label", LogicalType.STRING),
                ColumnSchema("amount", LogicalType.BIGDECIMAL, 18, 2),
            )
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)
        every { resultStore.keyFor(executionId) } returns key
        every { resultStore.page(key, 0L, 10) } returns
            ResultPage(
                executionId,
                schema,
                listOf(
                    listOf("=HYPERLINK(\"http://evil\")", "9.10"),
                    listOf("+cmd", "-3.25"), // a negative BIGDECIMAL wire-string stays verbatim
                    listOf("@mention", null),
                    listOf("\tlead", "0.00"),
                ),
                0L,
                10,
                4,
                expires,
            )

        val out = ByteArrayOutputStream()
        cursor.writeCsv(cursor.readable(executionId, principal()), out)

        val expected =
            buildString {
                append("label,amount\n")
                append("\"'=HYPERLINK(\"\"http://evil\"\")\",9.10\n")
                append("'+cmd,-3.25\n")
                append("'@mention,\n")
                append("'\tlead,0.00\n")
            }
        out.toString(Charsets.UTF_8) shouldBe expected
    }

    @Test
    fun `unknown formats and the unserved arrow format are format_unsupported`() {
        shouldThrow<ApiException> { cursor.formatOf("yaml") }.code shouldBe "result.format_unsupported"
        shouldThrow<ApiException> { cursor.formatOf("arrow") }.code shouldBe "result.format_unsupported"
        cursor.formatOf(null) shouldBe "json"
        cursor.formatOf("CSV") shouldBe "csv"
    }
}
