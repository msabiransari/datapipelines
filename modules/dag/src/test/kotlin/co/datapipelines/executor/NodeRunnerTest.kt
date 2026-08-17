package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.Staging
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRenderException
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * `NodeRunner` against real H2 databases — one standing in for an external datasource, one the
 * real per-execution tempdb (dag-executor.md §6, §14 "unit tests for executeNode").
 *
 * Every code path §14 lists: DQL with each output (tempdb / caller / datasource), DML, DDL;
 * success; template failure; connection failure; query failure; write-back failure.
 */
class NodeRunnerTest {
    private val executionId = UUID.randomUUID()
    private val staging: Staging = Fixtures.stagingFactory().create(executionId)
    private val handle = InMemoryCancellationRegistry().register(executionId)
    private val warnings = WarningSink()

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `DQL from a datasource stages into tempdb through the SOURCE dialect`() =
        runBlocking<Unit> {
            val ddl = listOf("CREATE TABLE orders (id INT, total DECIMAL(10,2))", "INSERT INTO orders VALUES (1, 9.99), (2, 1.50)")
            val source = h2Datasource("orders", ddl)
            val runner = runner(sql = "SELECT id, total FROM orders", registry = FakeDatasourceRegistry(mapOf("orders" to source)))
            val node = Fixtures.node("stage_orders", source = "orders", output = NodeOutput.Tempdb("stg_orders"))

            val result = runner.run(ExecutableNode.from(node), context())

            result.rowsOut shouldBe 2
            result.status shouldBe NodeStatus.SUCCESS
            staging.withQuery("""SELECT COUNT(*) FROM "stg_orders" """) { rs ->
                rs.next()
                rs.getInt(1)
            } shouldBe 2
        }

    @Test
    fun `DQL to the caller materializes into the result store and returns its key`() =
        runBlocking<Unit> {
            val source = h2Datasource("rep", listOf("CREATE TABLE rep (n INT)", "INSERT INTO rep VALUES (7)"))
            val store = InMemoryResultStore()
            val runner =
                runner(
                    sql = "SELECT n FROM rep",
                    registry = FakeDatasourceRegistry(mapOf("rep" to source)),
                    resultStore = store,
                )

            val result = runner.run(ExecutableNode.from(Fixtures.node("caller", source = "rep")), context())

            val key = result.callerResultRef.shouldNotBeNull()
            result.rowsOut shouldBe 1
            store.describe(key).shouldNotBeNull().firstPage shouldBe listOf(listOf(7))
        }

    @Test
    fun `a datasource caller node with a direct sink streams to it and never touches the result store`() =
        runBlocking<Unit> {
            // Design §4.2 `direct` delivery: the child execution's caller ResultSet goes straight
            // to the parent's in-process consumer; nothing is materialized into Redis.
            val source =
                h2Datasource(
                    "dsink",
                    listOf("CREATE TABLE t (n INT, s VARCHAR)", "INSERT INTO t VALUES (1, 'a'), (2, 'b')"),
                )
            val store = mockk<ResultStore>()
            val runner =
                runner(
                    sql = "SELECT n, s FROM t",
                    registry = FakeDatasourceRegistry(mapOf("dsink" to source)),
                    resultStore = store,
                )
            val sink = RecordingSink()

            val result = runner.run(ExecutableNode.from(Fixtures.node("caller", source = "dsink")), context(directSink = sink))

            result.callerResultRef.shouldBeNull()
            result.rowsOut shouldBe 2
            sink.schema.shouldNotBeNull().map { it.name } shouldBe listOf("n", "s")
            sink.rows shouldBe listOf(listOf(1, "a"), listOf(2, "b"))
            coVerify(exactly = 0) { store.materialize(any(), any(), any(), any()) }
            coVerify(exactly = 0) { store.materializeRows(any(), any(), any(), any()) }
        }

    @Test
    fun `a tempdb caller node with a direct sink streams to it and never touches the result store`() =
        runBlocking<Unit> {
            staging.execute("""CREATE TABLE "seed" (n INT)""")
            staging.execute("""INSERT INTO "seed" VALUES (1), (2), (3)""")
            val store = mockk<ResultStore>()
            val runner = runner(sql = """SELECT n FROM "seed" WHERE n > 1""", resultStore = store)
            val sink = RecordingSink()

            val result = runner.run(ExecutableNode.from(Fixtures.node("caller")), context(directSink = sink))

            result.callerResultRef.shouldBeNull()
            result.rowsOut shouldBe 2
            sink.rows shouldBe listOf(listOf(2), listOf(3))
            coVerify(exactly = 0) { store.materialize(any(), any(), any(), any()) }
        }

    @Test
    fun `DQL from tempdb to tempdb runs as CREATE TABLE AS and stays inside the staging lock`() =
        runBlocking<Unit> {
            staging.execute("""CREATE TABLE "seed" (n INT)""")
            staging.execute("""INSERT INTO "seed" VALUES (1), (2), (3)""")
            val runner = runner(sql = """SELECT n FROM "seed" WHERE n > 1""")
            val node = Fixtures.node("derive", output = NodeOutput.Tempdb("derived"))

            runner.run(ExecutableNode.from(node), context()).rowsOut shouldBe 2

            staging.withQuery("""SELECT COUNT(*) FROM "derived" """) { rs ->
                rs.next()
                rs.getInt(1)
            } shouldBe 2
        }

    @Test
    fun `DML reports affected rows and DDL reports zero`() =
        runBlocking<Unit> {
            staging.execute("""CREATE TABLE "acc" (n INT)""")
            val dml = runner(sql = """INSERT INTO "acc" VALUES (1), (2)""")
            val ddl = runner(sql = """CREATE TABLE "made" (n INT)""")

            dml.run(ExecutableNode.from(Fixtures.node("ins", type = NodeType.DML)), context()).rowsOut shouldBe 2
            ddl.run(ExecutableNode.from(Fixtures.node("ddl", type = NodeType.DDL)), context()).rowsOut shouldBe 0
        }

    @Test
    fun `write-back streams into the target datasource and replaces on REPLACE`() =
        runBlocking<Unit> {
            val source = h2Datasource("src", listOf("CREATE TABLE src (n INT)", "INSERT INTO src VALUES (1), (2)"))
            val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (n INT)", "INSERT INTO tgt VALUES (99)"))
            val registry = FakeDatasourceRegistry(mapOf("src" to source, "tgt" to target))
            val runner = runner(sql = "SELECT n FROM src", registry = registry)
            val node = Fixtures.node("wb", source = "src", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.REPLACE))

            runner.run(ExecutableNode.from(node), context()).rowsOut shouldBe 2

            DriverManager.getConnection(target.jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT COUNT(*), SUM(n) FROM tgt")
                    rs.next()
                    rs.getInt(1) shouldBe 2
                    rs.getInt(2) shouldBe 3
                }
            }
        }

    @Test
    fun `an upper-folded write-back target is reported as writeback_target_missing`() =
        runBlocking<Unit> {
            // Author DDL `CREATE TABLE tgt` in an upper-folding dialect stores `TGT`; write-back
            // quotes `"tgt"` per staging §4.5 and cannot match it. The contract under test is that
            // this surfaces as the actionable catalogued code, never as a silent no-op.
            val source = h2Datasource("srcU", listOf("CREATE TABLE srcU (n INT)", "INSERT INTO srcU VALUES (1)"))
            val target = upperFoldingH2Datasource("tgtU", listOf("CREATE TABLE tgt (n INT)"))
            val registry = FakeDatasourceRegistry(mapOf("srcU" to source, "tgtU" to target))
            val runner = runner(sql = "SELECT n FROM srcU", registry = registry)
            val node = Fixtures.node("wb", source = "srcU", output = NodeOutput.Datasource("tgtU", "tgt", WriteMode.REPLACE))

            failureOf(runner, node).code shouldBe PipelineErrorCodes.Node.WRITEBACK_TARGET_MISSING
        }

    @Test
    fun `a missing write-back target is writeback_target_missing`() =
        runBlocking<Unit> {
            val source = h2Datasource("src2", listOf("CREATE TABLE src2 (n INT)", "INSERT INTO src2 VALUES (1)"))
            val target = h2Datasource("tgt2", listOf("CREATE TABLE present (n INT)"))
            val registry = FakeDatasourceRegistry(mapOf("src2" to source, "tgt2" to target))
            val runner = runner(sql = "SELECT n FROM src2", registry = registry)
            val node = Fixtures.node("wb", source = "src2", output = NodeOutput.Datasource("tgt2", "absent", WriteMode.APPEND))

            failureOf(runner, node).code shouldBe PipelineErrorCodes.Node.WRITEBACK_TARGET_MISSING
        }

    @Test
    fun `a template render failure keeps the engine's own code`() =
        runBlocking<Unit> {
            val engine = mockk<TemplateEngine>()
            every { engine.render(any(), any(), any()) } throws
                TemplateRenderException("undefined variable: x", TemplateRef("t", 1))
            val runner = runnerWith(engine)

            failureOf(runner, Fixtures.node("render")).code shouldBe PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED
        }

    @Test
    fun `an unregistered datasource is datasource_not_found`() =
        runBlocking<Unit> {
            val runner = runner(sql = "SELECT 1")

            failureOf(runner, Fixtures.node("missing", source = "ghost")).code shouldBe
                PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND
        }

    @Test
    fun `an unreachable datasource is datasource_connection_failed`() =
        runBlocking<Unit> {
            val broken =
                Datasource(
                    name = "broken",
                    displayName = "broken",
                    dialect = Dialect.H2,
                    jdbcUrl = "jdbc:h2:mem:never;IFEXISTS=TRUE",
                    username = "sa",
                )
            val runner = runner(sql = "SELECT 1", registry = FakeDatasourceRegistry(mapOf("broken" to broken)))

            failureOf(runner, Fixtures.node("conn", source = "broken")).code shouldBe
                PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
        }

    @Test
    fun `bad SQL against a datasource is query_execution_failed`() =
        runBlocking<Unit> {
            val source = h2Datasource("q", listOf("CREATE TABLE q (n INT)"))
            val runner = runner(sql = "SELECT nope FROM q", registry = FakeDatasourceRegistry(mapOf("q" to source)))

            failureOf(runner, Fixtures.node("bad", source = "q")).code shouldBe
                PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
        }

    @Test
    fun `bad tempdb staging SQL is staging_failed`() =
        runBlocking<Unit> {
            val runner = runner(sql = "SELECT n FROM nothing_here")
            val node = Fixtures.node("stage", output = NodeOutput.Tempdb("out"))

            failureOf(runner, node).code shouldBe PipelineErrorCodes.Node.STAGING_FAILED
        }

    @Test
    fun `the per-execution render budget is passed to the engine, never the engine default`() =
        runBlocking<Unit> {
            val (engine, budgets) = Fixtures.templateEngine("SELECT 1 AS n")
            val runner = runnerWith(engine)

            runner.run(ExecutableNode.from(Fixtures.node("c")), context(renderBudget = 4096))

            budgets shouldBe listOf(4096L)
        }

    @Test
    fun `a PIPELINE node runs through the sub-pipeline runner, never through render or source resolution`() =
        runBlocking<Unit> {
            // Design §4.1: the dispatch happens BEFORE render/source — a PIPELINE node carries
            // neither a template nor a source, so reaching either would fail for the wrong reason.
            val engine = mockk<TemplateEngine>()
            val expected = NodeResult.of("child", 7, Instant.now())
            val registry = FakeDatasourceRegistry(emptyMap())
            val runner =
                NodeRunner(
                    engine,
                    registry,
                    JdbcWritebackRunner(registry),
                    InMemoryResultStore(),
                    ExecutorConfig(),
                    subPipelineRunner = SubPipelineRunner { _, _ -> expected },
                )

            val result = runner.run(ExecutableNode.from(Fixtures.node("child", type = NodeType.PIPELINE, source = "")), context())

            result shouldBe expected
            verify(exactly = 0) { engine.render(any(), any(), any()) }
        }

    @Test
    fun `a PIPELINE node without a wired sub-pipeline runner fails with child_execution_failed`() =
        runBlocking<Unit> {
            val runner = runner(sql = "SELECT 1")

            val thrown =
                shouldThrow<DatapipelinesException> {
                    runner.run(ExecutableNode.from(Fixtures.node("child", type = NodeType.PIPELINE, source = "")), context())
                }

            thrown.code shouldBe PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED
            thrown.details["node"] shouldBe "child"
        }

    // ------------------------------------------------------------------ helpers

    /** A runner over [engine] with no datasources — for the paths that fail before connecting. */
    private fun runnerWith(engine: TemplateEngine): NodeRunner {
        val empty = FakeDatasourceRegistry(emptyMap())
        return NodeRunner(engine, empty, JdbcWritebackRunner(empty), InMemoryResultStore(), ExecutorConfig())
    }

    private fun runner(
        sql: String,
        registry: FakeDatasourceRegistry = FakeDatasourceRegistry(emptyMap()),
        resultStore: ResultStore = InMemoryResultStore(),
        config: ExecutorConfig = ExecutorConfig(),
    ): NodeRunner {
        val (engine, _) = Fixtures.templateEngine(sql)
        return NodeRunner(engine, registry, JdbcWritebackRunner(registry), resultStore, config)
    }

    private fun context(
        renderBudget: Long = ExecutorConfig().renderOutputBudgetChars(),
        directSink: DirectResultSink? = null,
    ): NodeExecutionContext =
        NodeExecutionContext(
            executionId = executionId,
            staging = staging,
            handle = handle,
            values = emptyMap(),
            warnings = warnings,
            resultTtlSeconds = 300,
            renderBudgetChars = renderBudget,
            stagingMaxMemoryMb = 1024,
            tempdbDialect = Dialect.H2,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            directSink = directSink,
        )

    private suspend fun failureOf(
        runner: NodeRunner,
        node: co.datapipelines.pipeline.Node,
    ): MappedError = shouldThrow<NodeFailedSignal> { runner.run(ExecutableNode.from(node), context()) }.error
}
