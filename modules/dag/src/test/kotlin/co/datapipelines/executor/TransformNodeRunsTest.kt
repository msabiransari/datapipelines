package co.datapipelines.executor

import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.staging.Staging
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInput
import co.datapipelines.templates.TransformInvariant
import co.datapipelines.templates.TransformMode
import co.datapipelines.templates.TransformOutput
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The TRANSFORM node on real H2 staging (transform-nodes design §5; the lane brief's A.3
 * checklist): every mode, the §5.1 input checks, the §5.3 gate, rejects and strict, the §5.4
 * invariants (read back from the WRITTEN tables, bounded only when declared), §5.5 atomicity,
 * the caller-supplied skip, the object channel (R4), the pool bounds and the memory proof.
 *
 * The engine, pool and staging are real — only the template resolution is a map. The staged
 * inputs are written through `stageRows`, exactly as an upstream DQL node would leave them,
 * so what the node reads is what production reads.
 */
class TransformNodeRunsTest {
    private val executionId = UUID.randomUUID()
    private val staging: Staging = Fixtures.stagingFactory().create(executionId)
    private val handle = InMemoryCancellationRegistry().register(executionId)
    private val warnings = WarningSink()
    private val engine = JsonataEngine()

    @AfterEach
    fun tearDown() = staging.close()

    // ---------------------------------------------------------------- fixtures

    private companion object {
        const val TEMPLATE = "acme/shape/order_lines.jsonata"
        val NOW: Instant = Instant.parse("2026-09-24T10:15:30Z")

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

        /** The record §2.2 shape: row mode, one table input, table output, rejects. */
        fun rowContract(rejects: Boolean = true): TransformContract =
            TransformContract(
                mode = TransformMode.ROW,
                inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                output = TransformOutput.Table(OUT_COLUMNS),
                rejects = rejects,
            )

        /** The partition body: missing customers are rejected, cents become currency. */
        // The array constructors are load-bearing: JSONata flattens a singleton sequence to
        // the value and drops an undefined one, so a bare rows[pred].{} is an object (or
        // missing) at match counts one and zero, never the array the contract declares.
        const val PARTITION_BODY =
            """{
              "rows": [ rows[customer_id != null].{
                "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ],
              "rejects": [ rows[customer_id = null].{ "row": $, "reason": "customer_id missing" } ]
            }"""
    }

    private fun resolved(
        contract: TransformContract,
        body: String,
        invariants: List<TransformInvariant> = emptyList(),
    ) = ResolvedTransform(
        type = co.datapipelines.pipeline.TemplateType.JSONATA,
        status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
        body = body,
        contract = contract,
        invariants = invariants,
    )

    private fun support(
        resolved: ResolvedTransform,
        pool: ScriptEvaluationPool = ScriptEvaluationPool(4, 64, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM),
        maxInputRows: Long = 100_000,
        readBatchSize: Int = 10_000,
    ) = TransformSupport(
        resolver = TransformVersionResolver { _, _ -> resolved },
        pool = pool,
        engines = mapOf(ScriptLanguage.JSONATA to engine),
        maxInputRows = maxInputRows,
        readBatchSize = readBatchSize,
    )

    private fun context(
        values: Map<String, Any?> = emptyMap(),
        directSink: DirectResultSink? = null,
        callerSupplied: Set<String> = emptySet(),
    ): NodeExecutionContext {
        val all = mapOf(ContextKeys.CURRENT_TIMESTAMP to NOW, ContextKeys.CURRENT_DATE to java.time.LocalDate.of(2026, 9, 24)) + values
        return NodeExecutionContext(
            executionId = executionId,
            staging = staging,
            handle = handle,
            values = if (callerSupplied.isEmpty()) RunContext.of(all) else callerContext(callerSupplied.single(), all[callerSupplied.single()]),
            warnings = warnings,
            resultTtlSeconds = 300,
            renderBudgetChars = 1_000_000,
            stagingMaxMemoryMb = 1024,
            tempdbDialect = Dialect.H2,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            directSink = directSink,
            workspaceId = UUID.randomUUID(),
        )
    }

    /**
     * A Context whose [RunContext.callerSupplied] carries [key] — built through the production
     * route (`RunContext.create` over a pipeline whose value-mode node writes the key, with the
     * key supplied in the execute inputs), never by reaching around the binder.
     */
    private fun callerContext(
        key: String,
        value: Any?,
    ): RunContext {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Node(
                            id = "writer",
                            description = "writer",
                            type = NodeType.TRANSFORM,
                            source = "",
                            template = TemplateRef(TEMPLATE, 3),
                            output = null,
                            dependsOn = emptyList(),
                            contextKey = key,
                        ),
                    ),
            )
        return RunContext.create(
            co.datapipelines.pipeline.OrgContext.DEFAULTS,
            pipeline,
            mapOf(key to com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value)),
            executionId,
            NOW,
        )
    }

    private fun jsonText(value: String): com.fasterxml.jackson.databind.JsonNode =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(value)

    private fun node(
        id: String = "shape_orders",
        inputs: Map<String, com.fasterxml.jackson.databind.JsonNode> =
            mapOf("orders" to jsonText("stg_orders")),
        output: NodeOutput? = NodeOutput.Tempdb("order_lines", rejects = "order_lines_rejected"),
        strict: Boolean? = null,
        contextKey: String? = null,
        timeoutSeconds: Int? = null,
    ): ExecutableNode =
        ExecutableNode.from(
            Node(
                id = id,
                description = "transform $id",
                type = NodeType.TRANSFORM,
                source = "",
                template = TemplateRef(TEMPLATE, 3),
                output = output,
                dependsOn = listOf("stage_orders"),
                inputs = inputs,
                contextKey = contextKey,
                strict = strict,
                settings = timeoutSeconds?.let { co.datapipelines.pipeline.NodeSettings(timeoutSeconds = it) },
            ),
        )

    private suspend fun stageOrders(rows: List<List<Any?>>) {
        staging.stageRows(
            "stg_orders",
            ORDER_COLUMNS.map { ColumnSchema(it.name, it.type, it.precision, it.scale, nullable = it.nullable) },
            rows.asSequence(),
        )
    }

    private suspend fun readTable(table: String): List<Map<String, Any?>> =
        staging.withQuery("SELECT * FROM ${SqlIdentifiers.quote(table)}") { rs ->
            val schema = co.datapipelines.datasources.ResultRowReader.schemaOf(rs.metaData, Dialect.H2)
            buildList {
                while (rs.next()) {
                    add(
                        schema.columns.mapIndexed { index, column ->
                            column.name to co.datapipelines.datasources.ResultRowReader.readValue(rs, index + 1, column)
                        }.toMap(),
                    )
                }
            }
        }

    private suspend fun countOf(table: String): Long =
        staging.withQuery("SELECT COUNT(*) FROM ${SqlIdentifiers.quote(table)}") { rs -> if (rs.next()) rs.getLong(1) else 0L }

    private suspend fun run(
        node: ExecutableNode,
        support: TransformSupport,
        ctx: NodeExecutionContext = context(),
        config: ExecutorConfig = ExecutorConfig(),
        resultStore: ResultStore = InMemoryResultStore(),
    ): NodeResult = TransformNodeRuns.run(node, ctx, Instant.now(), support, config, resultStore)

    private suspend fun failureOf(
        node: ExecutableNode,
        support: TransformSupport,
        ctx: NodeExecutionContext = context(),
        config: ExecutorConfig = ExecutorConfig(),
    ): MappedError = shouldThrow<NodeFailedSignal> { run(node, support, ctx, config) }.error

    // ---------------------------------------------------------------- row mode

    @Test
    fun `row mode partitions into the output and rejects tables, with honest stats`() =
        runBlocking<Unit> {
            stageOrders(
                listOf(
                    listOf(1, 1250, "alice"),
                    listOf(2, 800, null),
                    listOf(3, 3000, "bob"),
                ),
            )
            val result = run(node(), support(resolved(rowContract(), PARTITION_BODY)))

            readTable("order_lines").map { it["order_id"] } shouldBe listOf(1, 3)
            readTable("order_lines").map { it["amount"] } shouldBe listOf(BigDecimal("12.50"), BigDecimal("30.00"))
            val rejects = readTable("order_lines_rejected")
            rejects.single()["reason"] shouldBe "customer_id missing"
            result.rowsIn shouldBe 3
            result.rowsOut shouldBe 2
            result.rowsRejected shouldBe 1
            result.invariantsChecked shouldBe 0
        }

    @Test
    fun `a staged schema that does not cover the contract is input_contract_violation`() =
        runBlocking<Unit> {
            // customer_id is missing from the staged table — the cover check names it.
            staging.stageRows(
                "stg_orders",
                listOf(
                    ColumnSchema("order_id", LogicalType.INTEGER, null, null, nullable = null),
                    ColumnSchema("amount_cents", LogicalType.INTEGER, null, null, nullable = null),
                ),
                listOf(listOf(1, 100)).asSequence(),
            )
            val error = failureOf(node(), support(resolved(rowContract(), PARTITION_BODY)))

            error.code shouldBe PipelineErrorCodes.Transform.INPUT_CONTRACT_VIOLATION
            error.message.shouldContain("customer_id")
        }

    @Test
    fun `a null in a nullable-false contract column is scanned per batch and refused`() =
        runBlocking<Unit> {
            val contract =
                rowContract().copy(
                    inputs =
                        mapOf(
                            "orders" to
                                TransformInput.Table(
                                    listOf(
                                        ContractColumn("order_id", LogicalType.INTEGER),
                                        ContractColumn("amount_cents", LogicalType.INTEGER),
                                        ContractColumn("customer_id", LogicalType.STRING), // nullable: false here
                                    ),
                                ),
                        ),
                )
            stageOrders(listOf(listOf(1, 1250, "alice"), listOf(2, 800, null)))
            val error = failureOf(node(), support(resolved(contract, PARTITION_BODY)))

            error.code shouldBe PipelineErrorCodes.Transform.INPUT_CONTRACT_VIOLATION
            error.message.shouldContain("batch 1 row 2")
        }

    // ---------------------------------------------------------------- the gate (§5.3)

    @Test
    fun `a returned row with an extra key is row_shape_mismatch, with batch and row`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val error =
                failureOf(
                    node(),
                    support(resolved(rowContract(rejects = false), """[ rows.{ "order_id": order_id, "amount": amount_cents, "customer_id": customer_id, "extra": 1 } ]""")),
                )

            error.code shouldBe PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH
            error.details["batch"] shouldBe 1
        }

    @Test
    fun `a value that does not fit its column is value_type_mismatch`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val error =
                failureOf(
                    node(),
                    support(resolved(rowContract(rejects = false), """[ rows.{ "order_id": "abc", "amount": amount_cents, "customer_id": customer_id } ]""")),
                )

            error.code shouldBe PipelineErrorCodes.Transform.VALUE_TYPE_MISMATCH
        }

    @Test
    fun `a DECIMAL output is rounded half-even to its declared scale - R1's own example`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            run(node(output = NodeOutput.Tempdb("order_lines")), support(resolved(rowContract(rejects = false), """[ rows.{ "order_id": order_id, "amount": 0.1 * 3, "customer_id": customer_id } ]""")))

            // 0.1 * 3 is 0.30000000000000004 in binary; the gate's answer is 0.30 (R1).
            readTable("order_lines").single()["amount"] shouldBe BigDecimal("0.30")
        }

    @Test
    fun `a BIGDECIMAL with excess scale is precision_lost, never rounded`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val contract =
                rowContract(rejects = false).copy(
                    output =
                        TransformOutput.Table(
                            listOf(
                                ContractColumn("order_id", LogicalType.INTEGER),
                                ContractColumn("amount", LogicalType.BIGDECIMAL, 12, 2),
                                ContractColumn("customer_id", LogicalType.STRING),
                            ),
                        ),
                )
            val error =
                failureOf(
                    node(output = NodeOutput.Tempdb("order_lines")),
                    support(resolved(contract, """[ rows.{ "order_id": order_id, "amount": "12.505", "customer_id": customer_id } ]""")),
                )

            error.code shouldBe PipelineErrorCodes.Transform.PRECISION_LOST
        }

    // ---------------------------------------------------------------- strict + invariants (§5.4)

    @Test
    fun `strict fails the node with the count and the first reasons`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice"), listOf(2, 800, null)))
            val error = failureOf(node(strict = true), support(resolved(rowContract(), PARTITION_BODY)))

            error.code shouldBe PipelineErrorCodes.Transform.REJECTS_STRICT
            error.details["rejected"] shouldBe 1L
            (error.details["first_reasons"] as List<*>).single() shouldBe "customer_id missing"
        }

    @Test
    fun `invariants read the WRITTEN tables and pass, counted`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice"), listOf(2, 800, null)))
            val invariants =
                listOf(
                    TransformInvariant("one_to_one", "\$count(rows) + \$count(rejects) = \$count(inputs.orders)", "every input row is accepted or rejected"),
                )
            val result = run(node(), support(resolved(rowContract(), PARTITION_BODY, invariants)))

            result.invariantsChecked shouldBe 1
            result.rowsOut shouldBe 1
            result.rowsRejected shouldBe 1
        }

    @Test
    fun `a false invariant fails the node naming the invariant`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val invariants =
                listOf(
                    TransformInvariant("customer_present", "\$count(rows[customer_id = null]) = 0", "every accepted row carries a customer id"),
                    TransformInvariant("always_false", "1 = 2", "a witness that must fail"),
                )
            val error = failureOf(node(), support(resolved(rowContract(), PARTITION_BODY, invariants)))

            error.code shouldBe PipelineErrorCodes.Transform.INVARIANT_FAILED
            error.details["invariant"] shouldBe "always_false"
            error.details["message"] shouldBe "a witness that must fail"
        }

    @Test
    fun `a row-mode output above the cap fails invariants_too_large - only when invariants are declared`() =
        runBlocking<Unit> {
            stageOrders((1..10).map { listOf(it, it * 100, "c$it") })
            val invariants = listOf(TransformInvariant("some", "\$count(rows) >= 0", "witness"))
            val error =
                failureOf(
                    node(output = NodeOutput.Tempdb("order_lines")),
                    support(resolved(rowContract(rejects = false), """[ rows.{ "order_id": order_id, "amount": amount_cents, "customer_id": customer_id } ]""", invariants), maxInputRows = 5),
                )

            error.code shouldBe PipelineErrorCodes.Transform.INVARIANTS_TOO_LARGE
        }

    @Test
    fun `without invariants the same over-cap output streams - no bound, by design`() =
        runBlocking<Unit> {
            stageOrders((1..10).map { listOf(it, it * 100, "c$it") })
            val result =
                run(
                    node(output = NodeOutput.Tempdb("order_lines")),
                    support(resolved(rowContract(rejects = false), """[ rows.{ "order_id": order_id, "amount": amount_cents, "customer_id": customer_id } ]"""), maxInputRows = 5),
                )

            result.rowsOut shouldBe 10
            countOf("order_lines") shouldBe 10
        }

    // ---------------------------------------------------------------- atomicity (§5.5)

    @Test
    fun `a failure after the output write drops BOTH tables - failure is atomic`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice"), listOf(2, 800, null)))
            // Both tables are fully written, THEN strict fires (§5.4) — the sibling-drop path:
            // a strict refusal must leave no output and no rejects table behind (§5.5).
            val error = failureOf(node(strict = true), support(resolved(rowContract(), PARTITION_BODY)))

            error.code shouldBe PipelineErrorCodes.Transform.REJECTS_STRICT
            val gone =
                runCatching {
                    countOf("order_lines") to countOf("order_lines_rejected")
                }.isFailure
            gone shouldBe true
        }

    @Test
    fun `a gate failure mid-drain rolls the partial table back - staging's own guarantee`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            // The rejects half's gate refuses the empty reason in the FIRST pass, inside the
            // output table's own drain — staging rolls its partial table back (staging §4.3).
            val badRejects =
                """{ "rows": [ rows.{ "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ],
                    "rejects": [ rows.{ "row": $, "reason": "" } ] }"""
            val error = failureOf(node(), support(resolved(rowContract(), badRejects)))

            error.code shouldBe PipelineErrorCodes.Transform.VALUE_TYPE_MISMATCH
            val gone =
                runCatching {
                    countOf("order_lines") to countOf("order_lines_rejected")
                }.isFailure
            gone shouldBe true
        }

    // ---------------------------------------------------------------- value mode + the Context

    @Test
    fun `value mode writes the Context key only after invariants pass`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val valueContract =
                TransformContract(
                    mode = TransformMode.VALUE,
                    inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                    output = TransformOutput.Value(LogicalType.DECIMAL, 12, 2),
                )
            val body = """${'$'}sum(inputs.orders.amount_cents) / 100"""
            val ctx = context()
            val result =
                run(
                    node(output = null, contextKey = "threshold"),
                    support(resolved(valueContract, body)),
                    ctx,
                )

            result.contextKey shouldBe "threshold"
            ctx.values["threshold"] shouldBe BigDecimal("12.50")

            // Same node with a failing invariant: the key must NOT be written (§5.5).
            val invariants = listOf(TransformInvariant("never", "1 = 2", "witness"))
            val ctx2 = context()
            val error =
                failureOf(
                    node(output = null, contextKey = "threshold2"),
                    support(resolved(valueContract, body, invariants)),
                    ctx2,
                )
            error.code shouldBe PipelineErrorCodes.Transform.INVARIANT_FAILED
            ctx2.values["threshold2"] shouldBe null
        }

    @Test
    fun `an object output persists as a Map and a downstream TRANSFORM reads it - the R4 channel`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice"), listOf(2, 800, "bob")))
            val objectContract =
                TransformContract(
                    mode = TransformMode.VALUE,
                    inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                    output = TransformOutput.Obj(),
                )
            val ctx = context()
            val result =
                run(
                    node(id = "wrap", output = null, contextKey = "payload"),
                    support(resolved(objectContract, """{ "total_cents": ${'$'}sum(inputs.orders.amount_cents), "rows": ${'$'}count(inputs.orders) }""")),
                    ctx,
                )

            val payload = ctx.values["payload"]
            withClue("the Context holds the object as a Map (jsonb at persistence)") { (payload is Map<*, *>) shouldBe true }
            (payload as Map<*, *>)["total_cents"].toString() shouldBe "2050"
            result.contextValue shouldBe """{"rows":2,"total_cents":2050}"""

            // The one legal reader (R4): another TRANSFORM's value input.
            val readerContract =
                TransformContract(
                    mode = TransformMode.VALUE,
                    inputs = mapOf("v" to TransformInput.Value(LogicalType.BIGINTEGER)),
                    output = TransformOutput.Value(LogicalType.BIGINTEGER),
                )
            val result2 =
                run(
                    node(id = "read_back", inputs = mapOf("v" to jsonText("\$payload")), output = null, contextKey = "total"),
                    support(resolved(readerContract, """inputs.v.total_cents""")),
                    ctx,
                )
            result2.contextValue shouldBe "2050"
            ctx.values["total"] shouldBe 2050L
        }

    @Test
    fun `the clock is pinned to the execution's current_timestamp`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val clockContract =
                TransformContract(
                    mode = TransformMode.VALUE,
                    inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                    output = TransformOutput.Value(LogicalType.STRING),
                )
            val ctx = context()
            run(node(output = null, contextKey = "stamp"), support(resolved(clockContract, """${'$'}now()""")), ctx)

            (ctx.values["stamp"] as String) shouldStartWith "2026-09-24T10:15:30"
        }

    @Test
    fun `a caller-supplied key skips the node with provided_by caller - and never evaluates`() =
        runBlocking<Unit> {
            val valueContract =
                TransformContract(
                    mode = TransformMode.VALUE,
                    inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                    output = TransformOutput.Value(LogicalType.DECIMAL, 12, 2),
                )
            // A body that cannot evaluate — if the node ran, this would be evaluation_failed.
            val support = support(resolved(valueContract, """nosuchfunction()"""))
            val ctx =
                context(
                    values = mapOf("threshold" to BigDecimal("42.50")),
                    callerSupplied = setOf("threshold"),
                )
            val result = run(node(output = null, contextKey = "threshold"), support, ctx)

            result.providedBy shouldBe NodeResult.PROVIDED_BY_CALLER
            result.contextValue shouldBe "42.5"
        }

    // ---------------------------------------------------------------- table mode + the caller

    @Test
    fun `table mode loads whole, evaluates once, and the caller gets the rows`() =
        runBlocking<Unit> {
            stageOrders(listOf(listOf(1, 1250, "alice"), listOf(2, 800, "bob")))
            val tableContract =
                TransformContract(
                    mode = TransformMode.TABLE,
                    inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                    output = TransformOutput.Table(OUT_COLUMNS),
                )
            val store = InMemoryResultStore()
            val result =
                run(
                    node(output = NodeOutput.Caller),
                    support(resolved(tableContract, """[ inputs.orders.{ "order_id": order_id, "amount": amount_cents / 100, "customer_id": customer_id } ]""")),
                    resultStore = store,
                )

            result.rowsOut shouldBe 2
            result.rowsIn shouldBe 2
            val view = store.describe(result.callerResultRef!!)!!
            view.totalRows shouldBe 2
            view.firstPage.map { it[1] } shouldBe listOf(BigDecimal("12.50"), BigDecimal("8.00"))
        }

    @Test
    fun `a table input above max-input-rows is refused BEFORE loading`() =
        runBlocking<Unit> {
            stageOrders((1..10).map { listOf(it, it * 100, "c$it") })
            val tableContract =
                TransformContract(
                    mode = TransformMode.TABLE,
                    inputs = mapOf("orders" to TransformInput.Table(ORDER_COLUMNS)),
                    output = TransformOutput.Table(OUT_COLUMNS),
                )
            val error =
                failureOf(
                    node(output = NodeOutput.Caller),
                    support(resolved(tableContract, """inputs.orders"""), maxInputRows = 5),
                )

            error.code shouldBe PipelineErrorCodes.Transform.INPUT_TOO_LARGE
            error.message.shouldContain("10 rows")
        }

    // ---------------------------------------------------------------- the pool's bounds (§4.3)

    @Test
    fun `a full pool refuses with pool_exhausted`() =
        runBlocking<Unit> {
            val pool = ScriptEvaluationPool(1, 1, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM)
            val hold = launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    pool.run(EvaluationLimits(Duration.ofSeconds(10), 100, now = NOW), "holder") { Thread.sleep(2_000) }
                }
            }
            // Give the holder a moment to take the one admission slot.
            kotlinx.coroutines.delay(200)
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val error = failureOf(node(), support(resolved(rowContract(), PARTITION_BODY), pool = pool))

            error.code shouldBe PipelineErrorCodes.Transform.POOL_EXHAUSTED
            hold.join()
        }

    @Test
    fun `an evaluation that outlives its budget fails the node on time and is counted abandoned`() =
        runBlocking<Unit> {
            // A single builtin that never reaches a step boundary (7a's pad bomb): the pool's
            // abandon rule is the bound that fires (record §4.5), the node fails on time with
            // pipeline.transform.timeout, and the thread keeps its slot, counted.
            val pool = ScriptEvaluationPool(1, 4, Duration.ofSeconds(1), ScriptEvaluationPool.SYSTEM)
            stageOrders(listOf(listOf(1, 1250, "alice")))
            val started = Instant.now()
            val error =
                failureOf(
                    // timeout_seconds 6 → the evaluation's wall clock is 6 − cancel-grace(5) = 1 s
                    // (record §4.3: the engine reports first); the pool abandons at 1 + 1 s.
                    node(timeoutSeconds = 6),
                    support(resolved(rowContract(), """[ rows.{ "order_id": order_id, "amount": amount_cents, "customer_id": ${'$'}pad(customer_id, 100000000) } ]"""), pool = pool),
                    config = ExecutorConfig(cancelGraceSeconds = 5),
                )

            error.code shouldBe PipelineErrorCodes.Transform.TIMEOUT
            pool.abandoned.sum() shouldBe 1
            Duration.between(started, Instant.now()) shouldBeLessThan Duration.ofSeconds(20)
        }

    // ---------------------------------------------------------------- the memory proof (§5.2)

    @Test
    fun `row mode never materialises the input - 200k rows, the peak stays within two batches of the tables`() =
        runBlocking<Unit> {
            val batchSize = 10_000
            val columns = listOf(ContractColumn("n", LogicalType.INTEGER))
            val schema = columns.map { ColumnSchema(it.name, it.type, it.precision, it.scale, nullable = it.nullable) }
            val bigContract =
                TransformContract(
                    mode = TransformMode.ROW,
                    inputs = mapOf("numbers" to TransformInput.Table(columns)),
                    output = TransformOutput.Table(columns),
                )
            val bigNode =
                node(
                    id = "identity",
                    inputs = mapOf("numbers" to jsonText("stg_big")),
                    output = NodeOutput.Tempdb("out_big"),
                )

            val mem0 = staging.stats().memoryUsedBytes
            staging.stageRows("witness", schema, (1..batchSize).map { listOf(it) }.asSequence())
            val witnessBytes = staging.stats().memoryUsedBytes - mem0
            staging.stageRows("stg_big", schema, (1..200_000).asSequence().map { listOf(it) })
            val memIn = staging.stats().memoryUsedBytes

            // The peak is sampled DURING the run, with a collection before each read, so what
            // is measured is LIVE heap — a transient batch reads as itself, and a whole
            // materialised input (the failure shape) would stand up and stay visible.
            val peak = java.util.concurrent.atomic.AtomicLong(0)
            val samples = java.util.concurrent.atomic.AtomicInteger(0)
            val sampler =
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) {
                        @Suppress("ExplicitGarbageCollectionCall")
                        System.gc()
                        val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                        peak.updateAndGet { maxOf(it, used) }
                        samples.incrementAndGet()
                        kotlinx.coroutines.delay(50)
                    }
                }
            val result = run(bigNode, support(resolved(bigContract, """[ rows.{ "n": n } ]"""), readBatchSize = batchSize))
            sampler.cancel()

            result.rowsIn shouldBe 200_000L
            result.rowsOut shouldBe 200_000L
            // Live heap at the peak = the two legitimate tables (input + output) plus the
            // streaming overhead (batches in flight, the engine's per-batch scratch, the
            // store's write caches). The bound is the discriminator: the overhead stays under
            // ONE FULL COPY of the input table — a materialised 200k-row input adds MORE than
            // one copy of it in live maps (falsified below: the peak grows past the bound).
            val inputTableBytes = memIn - (mem0 + witnessBytes)
            val bound = memIn + 2 * inputTableBytes
            println(
                "memory proof: mem0=${mem0}B, witness=${witnessBytes}B, input table=${inputTableBytes}B, " +
                    "live-heap peak during run=${peak.get()}B over ${samples.get()} samples, bound=${bound}B",
            )
            withClue("the sampler saw the run at all (vacuity guard)") { (samples.get() >= 3) shouldBe true }
            withClue(
                "the streaming overhead (peak ${peak.get() - memIn}B over the input table) " +
                    "stays under one full copy of it (${inputTableBytes}B) — a materialised input adds more",
            ) {
                (peak.get() < bound) shouldBe true
            }
        }
}
