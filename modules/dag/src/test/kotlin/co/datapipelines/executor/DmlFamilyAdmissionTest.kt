package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.DmlInput
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Run admission covers the WHOLE pinned family (write-transactions §4 row 1, #1034 round 2):
 * the DML shape rules (R1) and the CURRENT insert qualification (R2) are applied to the root
 * and every reachable child before any business statement, so an earlier writer — eligible or
 * excluded, in the parent or a sibling child — never runs ahead of a refusal a later node was
 * always going to earn.
 *
 * Every refusal case asserts three things from outside the executor: the catalogued code (and
 * the child it names), NO `NodeStarted` event, and the target's rows read on an INDEPENDENT
 * connection. Each target is an H2 database; the dialect copy on the registration models
 * enrollment and qualification (POSTGRES/MYSQL enroll, SQLITE is excluded) without claiming
 * that driver ran. The positive cases prove admission still lets a valid plan write.
 */
class DmlFamilyAdmissionTest {
    // ------------------------------------------------------------------ R1: shape across the family

    @Test
    fun `an operation-less pinned child refuses the whole run before an excluded parent writes`() {
        refusedChild(Dialect.H2, legacyWriter("kid"), PipelineErrorCodes.Validation.DML_OPERATION_REQUIRED)
    }

    @Test
    fun `an operation-less pinned child refuses the whole run before an eligible parent starts`() {
        refusedChild(Dialect.POSTGRES, legacyWriter("kid"), PipelineErrorCodes.Validation.DML_OPERATION_REQUIRED)
    }

    @Test
    fun `an insert with no key in a pinned child refuses the whole run before an excluded parent writes`() {
        refusedChild(Dialect.H2, keylessInsert("kid"), PipelineErrorCodes.Validation.DML_KEY_INVALID)
    }

    @Test
    fun `an insert with no key in a pinned child refuses the whole run before an eligible parent starts`() {
        refusedChild(Dialect.POSTGRES, keylessInsert("kid"), PipelineErrorCodes.Validation.DML_KEY_INVALID)
    }

    @Test
    fun `an operation-less node two levels down refuses the run and the refusal names the path to it`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.H2)
            val grandchild = Fixtures.pipeline(listOf(legacyWriter("deep")))
            val child =
                Fixtures.pipeline(
                    listOf(
                        Fixtures.node("hop", type = NodeType.PIPELINE, source = "").copy(pipeline = PipelineNodeRef("test/grandchild", 1)),
                    ),
                )
            family(shop, mapOf("test/child" to child, "test/grandchild" to grandchild)).use { family ->
                val refusal = family.refusal(PipelineErrorCodes.Validation.DML_OPERATION_REQUIRED)
                refusal.message?.contains("pinned child 'test/grandchild' v1") shouldBe true
                refusal.details["child"] shouldBe "test/grandchild@1"
                refusal.details["via"] shouldBe listOf("child", "hop")
                refusal.details["path"] shouldBe "nodes[0].operation"
            }
        }
    }

    // ------------------------------------------------------------------ R2: qualification across the family

    @Test
    fun `an unqualified insert refuses admission before an earlier valid update starts`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.MYSQL)
            val insert = scalarInsert("insert", dependsOn = listOf("first"))
            val pipeline =
                Fixtures.pipeline(
                    listOf(Fixtures.node("first", type = NodeType.DML, source = "shop"), insert),
                    customerParameter(),
                )
            family(shop, emptyMap(), parent = pipeline).use { family ->
                val refusal = family.refusal(PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED)
                // The root body is named by its node alone: no child, no path to one.
                refusal.details shouldBe mapOf("node" to "insert", "dialect" to Dialect.MYSQL.wire)
            }
        }
    }

    @Test
    fun `an unqualified insert in a pinned child refuses admission before an eligible parent update starts`() {
        refusedChild(Dialect.MYSQL, scalarInsert("kid"), PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED, customerParameter())
    }

    @Test
    fun `an unqualified insert in a pinned child refuses admission before an excluded parent update starts`() {
        // SQLITE is excluded from enrollment AND unqualified for inserts: the excluded-only family still refuses.
        refusedChild(Dialect.SQLITE, scalarInsert("kid"), PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED, customerParameter())
    }

    // ------------------------------------------------------------------ the node-level backstops stay

    @Test
    fun `an insert whose registration cannot be resolved is left to its own node failure`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.H2)
            val pipeline = Fixtures.pipeline(listOf(scalarInsert("insert", source = "missing")), customerParameter())
            family(shop, emptyMap(), parent = pipeline).use { family ->
                val thrown = family.runParent()
                // Not an admission refusal: the planner cannot know a dialect for a name that is not registered.
                (thrown as PipelineExecutionFailed).errorCode shouldBe PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND
                family.customers() shouldBe listOf("seed")
            }
        }
    }

    // ------------------------------------------------------------------ valid plans still run

    @Test
    fun `a qualified insert in a pinned child runs beside an update in an eligible parent`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.POSTGRES)
            family(
                shop,
                mapOf("test/child" to Fixtures.pipeline(listOf(scalarInsert("kid")), customerParameter())),
                childValues = kidValues(),
            ).use { family ->
                family.runParent()
                family.customers() shouldBe listOf("changed", "kid")
            }
        }
    }

    @Test
    fun `a qualified insert in a pinned child runs beside an update in an excluded-only family`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.H2)
            family(
                shop,
                mapOf("test/child" to Fixtures.pipeline(listOf(scalarInsert("kid")), customerParameter())),
                childValues = kidValues(),
            ).use { family ->
                family.runParent()
                family.customers() shouldBe listOf("changed", "kid")
            }
        }
    }

    @Test
    fun `update and delete only plans on an engine that cannot return keys are admitted`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.MYSQL)
            val child = Fixtures.pipeline(listOf(Fixtures.node("kid", type = NodeType.DML, source = "shop", operation = "delete")))
            family(shop, mapOf("test/child" to child), sql = mapOf("kid" to "DELETE FROM orders WHERE customer = 'nobody'")).use { family ->
                family.runParent()
                family.customers() shouldBe listOf("changed")
            }
        }
    }

    // ------------------------------------------------------------------ result retention (unchanged behavior)

    @Test
    fun `a caller retention failure after a correlated insert rolls back the participating write`() {
        runBlocking {
            val shop = shop().copy(dialect = Dialect.POSTGRES)
            val registry = FakeDatasourceRegistry(mapOf("shop" to shop))
            val failure = DatapipelinesException(code = PipelineErrorCodes.Result.TOO_LARGE, message = "fixture result retention refusal")
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("insert" to INSERT_SQL)),
                registry = registry,
                resultStore = InMemoryResultStore(failWith = failure),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
            ).use { harness ->
                val node = scalarInsert("insert", output = NodeOutput.Caller)
                val executionId = UUID.randomUUID()
                val request =
                    Fixtures
                        .request(
                            Fixtures.pipeline(listOf(node), customerParameter()),
                        ).copy(parameters = kidValues(), executionId = executionId)
                val thrown = runCatching { harness.executor.execute(request) }.exceptionOrNull()
                (thrown as PipelineExecutionFailed).errorCode shouldBe PipelineErrorCodes.Result.TOO_LARGE
                customersOf(shop) shouldBe listOf("seed")
                harness.resultStore.describe(harness.resultStore.keyFor(executionId)) shouldBe null
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A parent that updates `orders` (node `first`) and then runs the pinned child `test/child`,
     * the child's own node being [childNode] — then asserts the run was refused with [code],
     * naming the child, with the target untouched and nothing started.
     */
    private fun refusedChild(
        dialect: Dialect,
        childNode: Node,
        code: String,
        childParameters: Map<String, Parameter> = emptyMap(),
    ) {
        runBlocking {
            val shop = shop().copy(dialect = dialect)
            family(shop, mapOf("test/child" to Fixtures.pipeline(listOf(childNode), childParameters))).use { family ->
                val refusal = family.refusal(code)
                refusal.message?.contains("pinned child 'test/child' v1") shouldBe true
                refusal.details["child"] shouldBe "test/child@1"
                refusal.details["via"] shouldBe listOf("child")
            }
        }
    }

    private fun legacyWriter(id: String): Node = Fixtures.node(id, type = NodeType.DML, source = "shop").copy(operation = null)

    private fun keylessInsert(id: String): Node = Fixtures.node(id, type = NodeType.DML, source = "shop", operation = "insert")

    private fun scalarInsert(
        id: String,
        source: String = "shop",
        dependsOn: List<String> = emptyList(),
        output: NodeOutput = NodeOutput.Discard,
    ): Node =
        Fixtures
            .node(id, type = NodeType.DML, source = source, operation = "insert", dependsOn = dependsOn, dmlOutput = output)
            .copy(key = DmlKey("order_id", "BIGINTEGER"), input = DmlInput(parameters = listOf("customer")))

    private fun customerParameter(): Map<String, Parameter> = mapOf("customer" to Parameter(LogicalType.STRING))

    private fun kidValues() = mapOf("customer" to TextNode("kid"))

    private fun family(
        shop: Datasource,
        children: Map<String, Pipeline>,
        parent: Pipeline? = null,
        sql: Map<String, String> = emptyMap(),
        childValues: Map<String, TextNode> = emptyMap(),
    ): Family = Family(shop, children, parent, sql, childValues)

    /** One root pipeline over a registered H2 `shop`, its pinned children, and the harness that runs them. */
    private inner class Family(
        val shop: Datasource,
        children: Map<String, Pipeline>,
        parent: Pipeline?,
        sql: Map<String, String>,
        childValues: Map<String, TextNode>,
    ) : AutoCloseable {
        private val registry = FakeDatasourceRegistry(mapOf("shop" to shop))
        private val executor = AtomicReference<PipelineExecutor>()
        private val templates =
            mapOf(
                "first" to "UPDATE orders SET customer = 'changed'",
                "insert" to INSERT_SQL,
                "kid" to INSERT_SQL,
                "legacy" to "DELETE FROM orders",
                "deep" to "DELETE FROM orders",
            ) +
                sql
        private val root =
            parent ?: Fixtures.pipeline(
                listOf(Fixtures.node("first", type = NodeType.DML, source = "shop")) +
                    children.keys.sorted().take(1).map { name ->
                        Fixtures
                            .node(
                                "child",
                                type = NodeType.PIPELINE,
                                source = "",
                                dependsOn = listOf("first"),
                            ).copy(pipeline = PipelineNodeRef(name, 1))
                    },
            )
        val harness =
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(templates),
                registry = registry,
                localWritePlanner =
                    LocalWritePlanner(
                        PipelineResolver { _, name, _ -> children[name]?.let { ResolvedPipeline(it, entityDiscarded = false) } },
                        registry,
                        8,
                    ),
                subPipelineRunner =
                    SubPipelineRunner { node, ctx ->
                        val ref = node.pipeline!!
                        val result =
                            executor.get().execute(
                                ExecuteRequest(
                                    pipelineId = UUID.randomUUID(),
                                    pipelineVersion = ref.version,
                                    pipeline = children.getValue(ref.name),
                                    userId = ctx.userId,
                                    workspaceId = ctx.workspaceId,
                                    triggeredVia = ExecutionTrigger.PIPELINE,
                                    parameters = childValues,
                                    parentExecutionId = ctx.executionId,
                                    parentNodeId = node.id,
                                    rootExecutionId = ctx.rootExecutionId,
                                    compositionDepth = ctx.compositionDepth + 1,
                                    correlationId = ctx.correlationId ?: UUID.randomUUID(),
                                    readOnly = ctx.readOnly,
                                    localWriteTransaction = ctx.localWriteTransaction,
                                ),
                            )
                        NodeResult.of(node.id, 0, Instant.now(), childExecutionId = result.executionId)
                    },
            ).also { executor.set(it.executor) }

        /** Runs the root pipeline; returns the failure it ended with, or null when it succeeded. */
        fun runParent(): Throwable? = runBlocking { runCatching { harness.executor.execute(rootRequest()) }.exceptionOrNull() }

        /**
         * Runs the root pipeline and returns its ADMISSION refusal. The observable effects are
         * asserted first — the independent connection sees only the seed and no node started —
         * because a late node-level refusal throws the same code (`DmlPipelineExecutionTest`'s
         * order). Then: the catalogued [code] raised as a setup failure, never a node's, and
         * streamed as `pipeline_failed` with that code and no failed node.
         */
        fun refusal(code: String): DatapipelinesException {
            val thrown = runParent()
            assertNothingRan()
            val refusal = thrown.shouldBeInstanceOf<DatapipelinesException>()
            (refusal is PipelineExecutionFailed) shouldBe false
            refusal.code shouldBe code
            val failed = harness.emitter.firstOf<PipelineFailed>()
            failed.failedNodeId shouldBe null
            failed.error.code shouldBe code
            return refusal
        }

        private fun rootRequest() = Fixtures.request(root).copy(parameters = if (root.parameters.isEmpty()) emptyMap() else kidValues())

        fun customers(): List<String> = customersOf(shop)

        /** The refusal's observable contract: the independent connection sees only the seed, and no node ever started. */
        fun assertNothingRan() {
            customers() shouldBe listOf("seed")
            harness.emitter.events
                .filterIsInstance<NodeStarted>()
                .shouldBeEmpty()
        }

        override fun close() = harness.close()
    }

    private fun shop(): Datasource =
        h2Datasource(
            "shop",
            listOf(
                "CREATE TABLE orders (order_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, customer VARCHAR(40))",
                "INSERT INTO orders (customer) VALUES ('seed')",
            ),
        )

    private fun customersOf(shop: Datasource): List<String> =
        DriverManager.getConnection(shop.jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT customer FROM orders ORDER BY order_id").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    private companion object {
        const val INSERT_SQL = "INSERT INTO orders (customer) VALUES (:customer)"
    }
}
