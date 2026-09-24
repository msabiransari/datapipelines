package co.datapipelines.mcp

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * #119 — the tempdb payload an agent ACTUALLY receives, end to end: the real [SqlProbe] over the
 * real pinned H2, the real [SqlProbeTool] shaping, the real [McpToolDispatcher] envelope and the
 * real JSON text. [SqlProbeToolTest] mocks the scratch outcome and can only prove the shaping;
 * the engine premise — that a missing table stops H2 before a later syntax error — is proven
 * here on the driver, the way the acceptance audit found it (a probe whose SQL hash matched the
 * subsequently failing execution's).
 *
 * Also pinned: the audit row still carries the SQL's hash and length, never its text — the
 * privacy rule is unchanged by the contract correction.
 */
class SqlProbeTempdbWireTest {
    private class RecordingSink : AuditEventSink {
        val rows = mutableListOf<Map<String, Any?>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += details + ("event" to event)
        }
    }

    private val registry = mockk<DatasourceRegistry>()
    private val sink = RecordingSink()
    private val dispatcher = McpToolDispatcher(listOf(SqlProbeTool(registry, SqlProbe(registry))), sink)

    private fun call(
        sql: String,
        parameters: Map<String, Any?>? = null,
    ): McpSchema.CallToolResult =
        dispatcher.call(
            McpFixtures.request(
                "sql_probe",
                buildMap {
                    put("name", "tempdb")
                    put("sql", sql)
                    parameters?.let { put("parameters", it) }
                },
            ),
            McpFixtures.ctx(),
        )

    private fun text(result: McpSchema.CallToolResult): String = (result.content().single() as McpSchema.TextContent).text()

    @Suppress("UNCHECKED_CAST")
    private fun json(result: McpSchema.CallToolResult): Map<String, Any?> =
        ExecutorJson.mapper.readValue(text(result), Map::class.java) as Map<String, Any?>

    /**
     * The witness (#119 spec §2): on the empty scratch H2 stops at `absent_a` and never reaches
     * the `FULL OUTER JOIN` it does not support. The wire says so — `incomplete`, `parsed` a JSON
     * null that is PRESENT (not omitted), no rows — and the pre-#119 affirmative is gone.
     */
    @Test
    fun `a missing table is reported as incomplete validation on the wire, parsed null and present`() {
        val result = call("SELECT a.id FROM absent_a a FULL OUTER JOIN absent_b b ON a.id = b.id")
        val payload = json(result)

        assertAll(
            { result.isError().shouldBeFalse() },
            { payload["check"] shouldBe "syntax_and_names" },
            { payload["validation_status"] shouldBe "incomplete" },
            { payload.containsKey("parsed").shouldBeTrue() },
            { payload["parsed"].shouldBeNull() },
            { text(result) shouldContain "\"parsed\":null" },
            { payload["missing_table"] shouldBe "absent_a" },
            { ((payload["wall_ms"] as Number).toLong() >= 0L).shouldBeTrue() },
            { payload.containsKey("rows") shouldBe false },
            { payload.containsKey("row_count_returned") shouldBe false },
            { (payload["note"] as String) shouldContain "INCOMPLETE" },
            { (payload["note"] as String) shouldContain "use pipelines_execute to run the DAG" },
            { (payload["note"] as String) shouldNotContain "pipelines_execute_node" },
            { (payload["note"] as String) shouldNotContain "every name it defines itself resolved" },
            { (payload["note"] as String) shouldNotContain "Nothing about the SQL needs to change" },
        )
    }

    /** A self-contained statement executes: `executed`, `parsed: true`, bounded rows and schema. */
    @Test
    fun `a self-contained statement is executed on the wire with rows`() {
        val result =
            call(
                "SELECT t.hr FROM (VALUES (0),(1),(2)) AS t(hr) WHERE t.hr >= :min ORDER BY t.hr",
                parameters = mapOf("min" to mapOf("type" to "INTEGER", "value" to "1")),
            )
        val payload = json(result)

        assertAll(
            { result.isError().shouldBeFalse() },
            { payload["validation_status"] shouldBe "executed" },
            { payload["parsed"] shouldBe true },
            { payload["row_count_returned"] shouldBe 2 },
            { payload.containsKey("missing_table") shouldBe false },
        )
    }

    /** A statement H2 refuses is the catalogued error envelope, exactly as before — never `incomplete`. */
    @Test
    fun `an invalid self-contained statement is the catalogued error, not incomplete`() {
        val result = call("SELECT t.x FORM (VALUES (1)) AS t(x)")

        @Suppress("UNCHECKED_CAST")
        val error = json(result)["error"] as Map<String, Any?>
        assertAll(
            { result.isError().shouldBeTrue() },
            { error["code"] shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED },
            { (error["message"] as String) shouldContain "Syntax error" },
        )
    }

    /**
     * #186 — the scratch is de-privileged, and the WIRE says so: a SELECT-shaped host read
     * (`FILE_READ`) reaches H2's restricted scratch session and comes back as the probe's
     * existing not-permitted answer — `datasource.table_forbidden` inside an `isError` envelope,
     * never a 500 — with the file's token nowhere in the response. The file is a temp file this
     * test writes, carrying a random token; the control half lives in
     * `SqlProbeScratchContainmentTest`, which shows the same statement SUCCEED on an admin
     * session (so this refusal is about privilege, not SQL shape).
     */
    @Test
    fun `a host read through the scratch is the not-permitted envelope, and the content never travels`() {
        val token = "wire-token-${UUID.randomUUID()}"
        val secret = kotlin.io.path.createTempFile("probe-wire", ".txt")
        try {
            secret.toFile().writeText(token)

            val result = call("SELECT FILE_READ('${secret.toAbsolutePath()}', 'UTF-8')")

            @Suppress("UNCHECKED_CAST")
            val error = json(result)["error"] as Map<String, Any?>
            assertAll(
                { result.isError().shouldBeTrue() },
                { error["code"] shouldBe PipelineErrorCodes.Datasource.TABLE_FORBIDDEN },
                { text(result) shouldNotContain token },
            )
        } finally {
            secret.toFile().delete()
        }
    }

    /** The audit row records the hash and length of the SQL, never the text (107, unchanged by #119). */
    @Test
    fun `the tempdb call is audited by sql hash and length only`() {
        val sql = "SELECT a.id FROM absent_a a WHERE a.secret = 'do-not-log'"
        call(sql)

        val row = sink.rows.single { it["event"] == "mcp.tool.called" }
        assertAll(
            { row["outcome"] shouldBe "success" },
            { row["sql_length"] shouldBe sql.length },
            { (row["sql_sha256"] as String).length shouldBe 64 },
            { row.containsKey("sql").shouldBeFalse() },
            { row.values.none { it.toString().contains("do-not-log") }.shouldBeTrue() },
        )
    }
}
