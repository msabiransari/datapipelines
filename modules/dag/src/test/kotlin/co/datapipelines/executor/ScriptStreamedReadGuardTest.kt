package co.datapipelines.executor

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.staging.DuckDbStaging
import co.datapipelines.staging.DuckDbStagingFactory
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingErrorCodes
import co.datapipelines.staging.StagingResultIncompleteException
import co.datapipelines.staging.StreamedReadGuard
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.ScriptContract
import co.datapipelines.templates.ScriptInput
import co.datapipelines.templates.ScriptMode
import co.datapipelines.templates.ScriptOutput
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * #994 rule G2 on the SCRIPT node's staging reads, on a real DuckDB staging (TEMPORARY, [StreamedReadGuard]): a table
 * input read through `withQuery` (class C1) whose engine fails mid-stream, and strict mode's reason sample (class C2,
 * `LIMIT n`, judged against `min(n, count)`) through `checkStrict`.
 */
class ScriptStreamedReadGuardTest {
    @TempDir
    lateinit var scratch: Path

    // ---------------------------------------------------------------- C1: a SCRIPT table input

    @Test
    fun `a SCRIPT input whose engine fails mid-stream fails the node as result_incomplete instead of a shorter output`() {
        withDuck { staging ->
            staging.execute(
                "CREATE VIEW stg_orders AS SELECT " +
                    "CAST(CASE WHEN range < $FAIL_AT THEN range ELSE error('boom') END AS INTEGER) AS order_id, " +
                    "CAST(100 AS INTEGER) AS amount_cents, CAST('c' AS VARCHAR) AS customer_id FROM range($ROWS)",
            )
            val error = failureOf(staging, node(output = NodeOutput.Tempdb("order_lines")), resolved(rowContract(rejects = false), MAP_BODY))
            error.code shouldBe StagingErrorCodes.RESULT_INCOMPLETE
            error.details["rows_expected"] shouldBe ROWS
            withClue("the read was cut short mid-stream") { ((error.details["rows_read"] as Long) in 1 until FAIL_AT + 1) shouldBe true }
        }
    }

    @Test
    fun `a clean SCRIPT input on DuckDB reads every row`() {
        withDuck { staging ->
            staging.execute(
                "CREATE TABLE stg_orders AS SELECT CAST(range AS INTEGER) AS order_id, CAST(100 AS INTEGER) AS amount_cents, " +
                    "CAST('c' AS VARCHAR) AS customer_id FROM range($CLEAN_ROWS)",
            )
            val result = run(staging, node(output = NodeOutput.Tempdb("order_lines")), resolved(rowContract(rejects = false), MAP_BODY))
            result.rowsOut shouldBe CLEAN_ROWS
        }
    }

    // ---------------------------------------------------------------- C2: strict's reason sample

    @Test
    fun `strict reports the first reasons of 3, 10 and 25 rejected rows as 3, 10 and 10`() {
        listOf(3 to 3, 10 to 10, 25 to 10).forEach { (rejected, reported) ->
            withDuck { staging ->
                stageOrders(staging, rejected)
                val error = failureOf(staging, node(strict = true), resolved(rowContract(rejects = true), PARTITION_BODY))
                withClue("$rejected rejected") {
                    error.code shouldBe PipelineErrorCodes.Script.REJECTS_STRICT
                    error.details["rejected"] shouldBe rejected.toLong()
                    (error.details["first_reasons"] as List<*>).size shouldBe reported
                }
            }
        }
    }

    @Test
    fun `strict's reason sample that fails at execution fails the node with the engine's error, never a strict count`() {
        withDuck { duck ->
            stageOrders(duck, 3)
            val swapping = SwappingStaging(duck)
            val error = failureOf(swapping, node(strict = true), resolved(rowContract(rejects = true), PARTITION_BODY))
            withClue("non-vacuity: the sample read was reached and swapped") { swapping.swapped shouldBe true }
            error.code shouldNotBe PipelineErrorCodes.Script.REJECTS_STRICT
            error.code shouldNotBe StagingErrorCodes.RESULT_INCOMPLETE
        }
    }

    /**
     * The ends-early case has no seam under `checkStrict` (its `withQuery` creates the statement inside staging), so this
     * drives the guard's own entry points with the text `checkStrict` builds (`SELECT "reason" FROM <rejects> LIMIT 10`,
     * ScriptNodeRuns.STRICT_REASON_SAMPLE) and a cursor that ends after k rows: expected = min(10, rejected).
     */
    @Test
    fun `strict's reason sample that ends early fails as result_incomplete - k=0 and k=4 of 25, k=1 of 3`() {
        withDuck { staging ->
            listOf(Triple(25, 0, 10L), Triple(25, 4, 10L), Triple(3, 1, 3L)).forEach { (rejected, k, expected) ->
                staging.execute("CREATE OR REPLACE TABLE rejects AS SELECT 'why ' || range AS reason FROM range($rejected)")
                val failure =
                    shouldThrow<StagingResultIncompleteException> {
                        staging.withConnection { c ->
                            StreamedReadGuard.inOneSnapshot(c) {
                                val count =
                                    c.createStatement().use {
                                        StreamedReadGuard.expectedRows(it, StreamedReadGuard.countOf(SAMPLE_SQL), "withQuery")
                                    }
                                withClue("the engine's count of the sample") { count shouldBe expected }
                                StreamedReadGuard.counted(endingAfter(k), count, "withQuery").use { rs -> while (rs.next()) rs.getString(1) }
                            }
                        }
                    }
                withClue("k=$k of $rejected") { failure.details shouldBe mapOf("rows_read" to k.toLong(), "rows_expected" to expected, "reader" to "withQuery") }
            }
        }
    }

    // ---------------------------------------------------------------- harness

    /** A delegating staging that turns the rejects table into a view failing at execution just before strict's sample. */
    private class SwappingStaging(
        private val delegate: DuckDbStaging,
    ) : Staging by delegate {
        var swapped = false

        override suspend fun <T> withQuery(
            sql: String,
            block: suspend (ResultSet) -> T,
        ): T {
            if (!swapped && sql.endsWith(" LIMIT 10") && "\"order_lines_rejected\"" in sql) {
                delegate.execute("DROP TABLE \"order_lines_rejected\"")
                delegate.execute("CREATE VIEW \"order_lines_rejected\" AS SELECT CAST(error('sample') AS VARCHAR) AS reason")
                swapped = true
            }
            return delegate.withQuery(sql, block)
        }
    }

    private fun withDuck(block: suspend (DuckDbStaging) -> Unit) {
        val id = UUID.randomUUID()
        val factory = DuckDbStagingFactory(DuckDbStagingProperties(scratch, MIB * 64, 4, MIB * 1024))
        (factory.create(id, factory.properties.defaultReservationBytes) as DuckDbStaging).use { runBlocking { block(it) } }
        factory.budget.reservedBytes shouldBe 0L
    }

    private suspend fun stageOrders(
        staging: Staging,
        rejected: Int,
    ) {
        staging.execute(
            "CREATE TABLE stg_orders AS SELECT CAST(range AS INTEGER) AS order_id, CAST(100 AS INTEGER) AS amount_cents, " +
                "CAST(NULL AS VARCHAR) AS customer_id FROM range($rejected)",
        )
    }

    private fun support(resolved: ResolvedScript) =
        ScriptSupport(
            resolver = ScriptVersionResolver { _, _ -> resolved },
            pool = ScriptEvaluationPool(4, 64, Duration.ofSeconds(30), ScriptEvaluationPool.SYSTEM),
            engines = mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
            maxInputRows = 1_000_000,
            readBatchSize = 10_000,
        )

    private fun context(staging: Staging): NodeExecutionContext {
        val id = staging.executionId
        return NodeExecutionContext(
            executionId = id,
            staging = staging,
            handle = InMemoryCancellationRegistry().register(id),
            values = RunContext.of(emptyMap()),
            warnings = WarningSink(),
            resultTtlSeconds = 300,
            renderBudgetChars = 1_000_000,
            stagingMaxMemoryMb = 64,
            tempdbDialect = Dialect.DUCKDB,
            userId = UUID.randomUUID(),
            rootExecutionId = id,
            workspaceId = UUID.randomUUID(),
        )
    }

    private suspend fun run(
        staging: Staging,
        node: ExecutableNode,
        resolved: ResolvedScript,
    ): NodeResult = ScriptNodeRuns.run(node, context(staging), Instant.now(), support(resolved), ExecutorConfig(), InMemoryResultStore())

    private suspend fun failureOf(
        staging: Staging,
        node: ExecutableNode,
        resolved: ResolvedScript,
    ): MappedError = shouldThrow<NodeFailedSignal> { run(staging, node, resolved) }.error

    private companion object {
        const val MIB = 1_048_576L
        const val ROWS = 400_000L
        const val FAIL_AT = 300_000L
        const val CLEAN_ROWS = 50_000L
        const val TEMPLATE = "acme/shape/order_lines.jsonata"
        const val SAMPLE_SQL = "SELECT \"reason\" FROM \"rejects\" LIMIT 10"

        val ORDER_COLUMNS =
            listOf(
                ContractColumn("order_id", LogicalType.INTEGER),
                ContractColumn("amount_cents", LogicalType.INTEGER),
                ContractColumn("customer_id", LogicalType.STRING, nullable = true),
            )

        val OUT_COLUMNS =
            listOf(
                ContractColumn("order_id", LogicalType.INTEGER),
                ContractColumn("amount", LogicalType.DECIMAL, 12, 2),
                ContractColumn("customer_id", LogicalType.STRING),
            )

        fun rowContract(rejects: Boolean) =
            ScriptContract(
                mode = ScriptMode.ROW,
                inputs = mapOf("orders" to ScriptInput.Table(ORDER_COLUMNS)),
                output = ScriptOutput.Table(OUT_COLUMNS),
                rejects = rejects,
            )

        const val MAP_BODY =
            """[ rows.{ "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ]"""

        const val PARTITION_BODY =
            """{
              "rows": [ rows[customer_id != null].{
                "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ],
              "rejects": [ rows[customer_id = null].{ "row": $, "reason": "customer_id missing" } ]
            }"""

        fun resolved(
            contract: ScriptContract,
            body: String,
        ) = ResolvedScript(
            type = co.datapipelines.pipeline.TemplateType.JSONATA,
            status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
            body = body,
            contract = contract,
            invariants = emptyList(),
        )

        fun node(
            output: NodeOutput = NodeOutput.Tempdb("order_lines", rejects = "order_lines_rejected"),
            strict: Boolean? = null,
        ): ExecutableNode =
            ExecutableNode.from(
                Node(
                    id = "shape_orders",
                    description = "script shape_orders",
                    type = NodeType.SCRIPT,
                    source = "",
                    template = TemplateRef(TEMPLATE, 3),
                    output = output,
                    dependsOn = listOf("stage_orders"),
                    inputs = mapOf("orders" to com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("stg_orders")),
                    strict = strict,
                ),
            )

        /** A cursor that ends after [k] rows, every value a reason text. */
        fun endingAfter(k: Int): ResultSet {
            var left = k
            return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
                when (method.name) {
                    "next" -> (left > 0).also { if (it) left-- }
                    "getString" -> "why"
                    else -> null
                }
            } as ResultSet
        }
    }
}
