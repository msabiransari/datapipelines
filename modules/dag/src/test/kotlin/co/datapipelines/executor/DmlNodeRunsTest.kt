package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.DmlInput
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.Staging
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * #1034 — a DML node's declared operation and result rows, through the real [NodeRunner] against
 * real H2 databases: one standing in for a customer datasource (every durable observable read on
 * a SECOND, plain `DriverManager` connection the runner never touched), one the real tempdb.
 *
 * The Postgres leg — the family's local write transaction, the wire encoding of an int64 key and
 * the full HTTP execute path — is `DmlOperationResultsIntegrationTest`.
 */
class DmlNodeRunsTest {
    private val executionId = UUID.randomUUID()
    private val staging: Staging = Fixtures.stagingFactory().create(executionId)
    private val handle = InMemoryCancellationRegistry().register(executionId)

    @AfterEach
    fun tearDown() = staging.close()

    // ------------------------------------------------------------------ insert

    @Test
    fun `a scalar insert returns its input row with the generated key as id, whatever the column is called`() =
        runBlocking<Unit> {
            val shop = shop()
            val sink = RecordingSink()

            val result =
                run(
                    shop,
                    "INSERT INTO orders (customer) VALUES (:customer)",
                    insert(input = DmlInput(parameters = listOf("customer")), output = NodeOutput.Caller),
                    values = mapOf("customer" to "ada"),
                    sink = sink,
                )

            result.rowsOut shouldBe 1
            sink.schema.shouldNotBeNull().map { it.name to it.type } shouldBe
                listOf("customer" to LogicalType.STRING, "id" to LogicalType.BIGINTEGER)
            val id = sink.rows.single()[1] as Long
            sink.rows shouldBe listOf(listOf("ada", id))
            rowsOf(shop) shouldBe listOf(id to "ada")
        }

    @Test
    fun `a supplied id is stored and returned unchanged, distinguishable from a generated one`() =
        runBlocking<Unit> {
            val shop = shop()
            stagePending(listOf(500L to "supplied"))
            val supplied = RecordingSink()
            val generated = RecordingSink()

            run(
                shop,
                "INSERT INTO orders (order_id, customer) VALUES (:id, :customer)",
                insert(input = DmlInput(table = "pending"), output = NodeOutput.Caller),
                sink = supplied,
            )
            run(
                shop,
                "INSERT INTO orders (customer) VALUES (:customer)",
                insert(input = DmlInput(parameters = listOf("customer")), output = NodeOutput.Caller),
                values = mapOf("customer" to "generated"),
                sink = generated,
            )

            supplied.rows shouldBe listOf(listOf("supplied", 500L))
            val generatedId = generated.rows.single()[1] as Long
            rowsOf(shop) shouldBe listOf(generatedId to "generated", 500L to "supplied").sortedBy { it.first }
        }

    @Test
    fun `a key past 2^53 comes back exact`() =
        runBlocking<Unit> {
            val shop = shop(identityStart = BEYOND_DOUBLE)
            val sink = RecordingSink()

            run(
                shop,
                "INSERT INTO orders (customer) VALUES (:customer)",
                insert(input = DmlInput(parameters = listOf("customer")), output = NodeOutput.Caller),
                values = mapOf("customer" to "big"),
                sink = sink,
            )

            sink.rows.single()[1] shouldBe BEYOND_DOUBLE
            rowsOf(shop) shouldBe listOf(BEYOND_DOUBLE to "big")
        }

    @Test
    fun `an empty staged input executes nothing and emits zero rows under the typed schema`() =
        runBlocking<Unit> {
            val shop = shop()
            stagePending(emptyList())
            val sink = RecordingSink()

            val result =
                run(
                    shop,
                    "INSERT INTO orders (order_id, customer) VALUES (:id, :customer)",
                    insert(input = DmlInput(table = "pending"), output = NodeOutput.Caller),
                    sink = sink,
                )

            result.rowsOut shouldBe 0
            sink.schema.shouldNotBeNull().map { it.name to it.type } shouldBe
                listOf("customer" to LogicalType.STRING, "id" to LogicalType.BIGINTEGER)
            sink.rows.shouldBeEmpty()
            rowsOf(shop).shouldBeEmpty()
        }

    @Test
    fun `a trigger rewriting a supplied key fails correlation and keeps no row of the insert`() =
        runBlocking<Unit> {
            val shop = shop(trigger = true)
            stagePending(listOf(1L to "kept_if_correlated", 2L to "rewrite"))

            val error =
                failure(
                    shop,
                    "INSERT INTO orders (order_id, customer) VALUES (:id, :customer)",
                    insert(input = DmlInput(table = "pending"), output = NodeOutput.Discard),
                )

            error.code shouldBe PipelineErrorCodes.Node.DML_KEY_CORRELATION_FAILED
            error.details["reason"] shouldBe "key_rewritten"
            error.details["row_index"] shouldBe 1
            // Row 0 was inserted and correlated before row 1 failed: the node-local transaction
            // rolled it back, read from a connection the runner never touched.
            rowsOf(shop).shouldBeEmpty()
            // No business value in the diagnostic (record §6).
            error.message shouldNotContain "rewrite"
            error.message shouldNotContain "1001"
        }

    @Test
    fun `an execution affecting other than one row fails the node and keeps nothing`() =
        runBlocking<Unit> {
            val shop = shop()
            listOf(
                "INSERT INTO orders (customer) SELECT :customer FROM (VALUES (1), (2)) AS v(n)" to 2,
                "INSERT INTO orders (customer) SELECT :customer FROM (VALUES (1)) AS v(n) WHERE 1 = 0" to 0,
            ).forEach { (sql, affected) ->
                val error =
                    failure(shop, sql, insert(input = DmlInput(parameters = listOf("customer"))), mapOf("customer" to "x"))

                error.code shouldBe PipelineErrorCodes.Node.DML_AFFECTED_ROWS_MISMATCH
                error.details["affected_rows"] shouldBe affected
                rowsOf(shop).shouldBeEmpty()
            }
        }

    @Test
    fun `an insert on an unqualified engine is refused before anything executes`() =
        runBlocking<Unit> {
            val shop = shop().copy(dialect = Dialect.MYSQL)

            val error =
                failure(
                    shop,
                    "INSERT INTO orders (customer) VALUES (:customer)",
                    insert(input = DmlInput(parameters = listOf("customer"))),
                    mapOf("customer" to "x"),
                )

            error.code shouldBe PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED
            error.details["dialect"] shouldBe "MYSQL"
            rowsOf(shop).shouldBeEmpty()
        }

    @Test
    fun `a template naming neither a value nor an input field fails before any write`() =
        runBlocking<Unit> {
            val shop = shop()

            val error =
                failure(
                    shop,
                    "INSERT INTO orders (customer) VALUES (:nobody)",
                    insert(input = DmlInput(parameters = listOf("customer"))),
                    mapOf("customer" to "x"),
                )

            error.code shouldBe PipelineErrorCodes.Node.SQL_PARAMETER_MISSING
            rowsOf(shop).shouldBeEmpty()
        }

    @Test
    fun `an insert result staged to tempdb is what a dependent node reads`() =
        runBlocking<Unit> {
            val shop = shop()

            run(
                shop,
                "INSERT INTO orders (customer) VALUES (:customer)",
                insert(input = DmlInput(parameters = listOf("customer")), output = NodeOutput.Tempdb("new_order")),
                values = mapOf("customer" to "ada"),
            )

            val id = rowsOf(shop).single().first
            staging.withQuery("""SELECT "customer", "id" FROM "new_order"""") { rs ->
                rs.next()
                listOf(rs.getString(1), rs.getLong(2))
            } shouldBe listOf("ada", id)
        }

    @Test
    fun `an insert into a tempdb table runs on the staging connection`() =
        runBlocking<Unit> {
            staging.execute("""CREATE TABLE "ledger" ("entry_id" BIGINT GENERATED BY DEFAULT AS IDENTITY, "note" VARCHAR(20))""")
            val sink = RecordingSink()
            val node =
                Fixtures
                    .node("ins", type = NodeType.DML, operation = "insert", dmlOutput = NodeOutput.Caller)
                    .copy(key = DmlKey("entry_id", "BIGINTEGER"), input = DmlInput(parameters = listOf("note")))

            runner("""INSERT INTO "ledger" ("note") VALUES (:note)""", FakeDatasourceRegistry(emptyMap()))
                .run(ExecutableNode.from(node, NOTE), context(mapOf("note" to "n1"), sink))

            val id = sink.rows.single()[1]
            staging.withQuery("""SELECT "entry_id" FROM "ledger"""") { rs ->
                rs.next()
                rs.getLong(1)
            } shouldBe id
        }

    // ------------------------------------------------------------------ update / delete

    @Test
    fun `update and delete emit one typed affected_rows row, for many matches and for zero`() =
        runBlocking<Unit> {
            val shop = shop(seed = listOf("a", "b", "c"))
            listOf(
                "UPDATE orders SET customer = 'z' WHERE customer <> 'none'" to 3L,
                "UPDATE orders SET customer = 'z' WHERE customer = 'none'" to 0L,
                "DELETE FROM orders WHERE customer = 'none'" to 0L,
                "DELETE FROM orders WHERE customer = 'z'" to 3L,
            ).forEach { (sql, affected) ->
                val sink = RecordingSink()
                val operation = if (sql.startsWith("UPDATE")) "update" else "delete"

                val result = run(shop, sql, dml(operation, NodeOutput.Caller), sink = sink)

                result.rowsOut shouldBe affected
                sink.schema shouldBe listOf(ColumnSchema("affected_rows", LogicalType.BIGINTEGER, nullable = false))
                sink.rows shouldBe listOf(listOf(affected))
            }
            rowsOf(shop).shouldBeEmpty()
        }

    @Test
    fun `a discarded result reports its count and delivers nothing`() =
        runBlocking<Unit> {
            val shop = shop(seed = listOf("a", "b"))
            val store = InMemoryResultStore()

            val result =
                runner("DELETE FROM orders", registryOf(shop), store)
                    .run(ExecutableNode.from(dml("delete", NodeOutput.Discard)), context())

            result.rowsOut shouldBe 2
            result.callerResultRef.shouldBeNull()
            rowsOf(shop).shouldBeEmpty()
        }

    @Test
    fun `an operation-less node that bypassed admission is refused again before any statement`() =
        runBlocking<Unit> {
            val shop = shop(seed = listOf("a"))

            val error = failure(shop, "DELETE FROM orders", dml("delete", NodeOutput.Discard).copy(operation = null))

            error.code shouldBe PipelineErrorCodes.Validation.DML_OPERATION_REQUIRED
            rowsOf(shop).map { it.second } shouldBe listOf("a")
        }

    // ------------------------------------------------------------------ fixtures

    private fun shop(
        identityStart: Long = 1L,
        trigger: Boolean = false,
        seed: List<String> = emptyList(),
    ): Datasource =
        h2Datasource(
            "shop",
            buildList {
                add(
                    "CREATE TABLE orders (order_id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH $identityStart) PRIMARY KEY, " +
                        "customer VARCHAR(40) NOT NULL)",
                )
                if (trigger) {
                    add(
                        """CREATE TRIGGER rewrite_key BEFORE INSERT ON orders FOR EACH ROW CALL "${RewriteKeyTrigger::class.java.name}"""",
                    )
                }
                seed.forEach { add("INSERT INTO orders (customer) VALUES ('$it')") }
            },
        )

    private suspend fun stagePending(rows: List<Pair<Long, String>>) {
        staging.execute("""CREATE TABLE "pending" ("id" BIGINT, "customer" VARCHAR(40))""")
        rows.forEach { (id, customer) -> staging.execute("""INSERT INTO "pending" VALUES ($id, '$customer')""") }
    }

    private fun dml(
        operation: String,
        output: NodeOutput,
    ): Node = Fixtures.node("w", type = NodeType.DML, source = "shop", operation = operation, dmlOutput = output)

    private fun insert(
        input: DmlInput,
        output: NodeOutput = NodeOutput.Discard,
    ): Node = dml("insert", output).copy(key = DmlKey("order_id", "BIGINTEGER"), input = input)

    private suspend fun run(
        shop: Datasource,
        sql: String,
        node: Node,
        values: Map<String, Any?> = emptyMap(),
        sink: DirectResultSink? = null,
    ): NodeResult = runner(sql, registryOf(shop)).run(ExecutableNode.from(node, CUSTOMER), context(values, sink))

    private suspend fun failure(
        shop: Datasource,
        sql: String,
        node: Node,
        values: Map<String, Any?> = emptyMap(),
    ): MappedError = shouldThrow<NodeFailedSignal> { run(shop, sql, node, values) }.error

    private fun registryOf(shop: Datasource) = FakeDatasourceRegistry(mapOf("shop" to shop))

    private fun runner(
        sql: String,
        registry: FakeDatasourceRegistry,
        resultStore: ResultStore = InMemoryResultStore(),
    ): NodeRunner {
        val (engine, _) = Fixtures.templateEngine(sql)
        return NodeRunner(engine, registry, JdbcWritebackRunner(registry), resultStore, ExecutorConfig())
    }

    private fun context(
        values: Map<String, Any?> = emptyMap(),
        sink: DirectResultSink? = null,
    ): NodeExecutionContext =
        NodeExecutionContext(
            executionId = executionId,
            staging = staging,
            handle = handle,
            values = RunContext.of(values),
            warnings = WarningSink(),
            resultTtlSeconds = 300,
            renderBudgetChars = ExecutorConfig().renderOutputBudgetChars(),
            stagingMaxMemoryMb = 1024,
            tempdbDialect = Dialect.H2,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            directSink = sink,
            workspaceId = UUID.randomUUID(),
        )

    /** The orders table as an independent client sees it: (order_id, customer), by id. */
    private fun rowsOf(shop: Datasource): List<Pair<Long, String>> =
        DriverManager.getConnection(shop.jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT order_id, customer FROM orders ORDER BY order_id").use { rs ->
                    generateSequence { if (rs.next()) rs.getLong(1) to rs.getString(2) else null }.toList()
                }
            }
        }

    private companion object {
        /** 2^53 + 1: the first whole number a double cannot hold. */
        const val BEYOND_DOUBLE = 9_007_199_254_740_993L
        val CUSTOMER = mapOf("customer" to Parameter(LogicalType.STRING))
        val NOTE = mapOf("note" to Parameter(LogicalType.STRING))
    }
}
