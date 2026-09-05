package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.Staging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The executor's runtime datasource resolution is WORKSPACE-SCOPED (025 A5; workspaces
 * design §5.3 — the 022 handback's self-flagged gap): save-time validation resolves a
 * datasource through the caller's workspace, but the execution path used to resolve by
 * NAME alone, so a pipeline saved while a datasource was GLOBAL still executed against it
 * after it was re-bound to another workspace. The node's gate now resolves through the
 * execution's workspace ([NodeExecutionContext.workspaceId], carried from
 * [ExecuteRequest.workspaceId] by the surface that knows the pipeline's workspace), and an
 * invisible datasource is the same `datasource_not_found` as an unknown one — the §5.3
 * no-oracle rule, applied at execution time.
 *
 * The write-back target gets the same gate: a `output.target: "datasource"` write into a
 * datasource the execution's workspace cannot see is not-found, not a silent write.
 */
class NodeRunnerWorkspaceScopeTest {
    private val executionId = UUID.randomUUID()
    private val workspaceA = UUID.randomUUID()
    private val workspaceB = UUID.randomUUID()
    private val staging: Staging = Fixtures.stagingFactory().create(executionId)
    private val handle = InMemoryCancellationRegistry().register(executionId)
    private val warnings = WarningSink()

    @AfterEach
    fun tearDown() = staging.close()

    @Test
    fun `a datasource bound to another workspace is datasource_not_found at execution`() =
        runBlocking<Unit> {
            val source = h2Datasource("sales", listOf("CREATE TABLE sales (n INT)", "INSERT INTO sales VALUES (1)"))
            // Bound to workspace B; this execution runs in workspace A.
            val registry = FakeDatasourceRegistry(mapOf("sales" to source), visibleTo = mapOf("sales" to setOf(workspaceB)))
            val runner = runner(sql = "SELECT n FROM sales", registry = registry)
            val node = Fixtures.node("stage", source = "sales", output = NodeOutput.Tempdb("stg_sales"))

            val failure =
                shouldThrow<NodeFailedSignal> {
                    runner.run(ExecutableNode.from(node), context(workspaceId = workspaceA))
                }

            failure.error.code shouldBe PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND
        }

    @Test
    fun `a datasource visible to the execution's workspace runs`() =
        runBlocking<Unit> {
            val source = h2Datasource("sales", listOf("CREATE TABLE sales (n INT)", "INSERT INTO sales VALUES (1)"))
            val registry =
                FakeDatasourceRegistry(
                    mapOf("sales" to source),
                    visibleTo = mapOf("sales" to setOf(workspaceA, workspaceB)),
                )
            val runner = runner(sql = "SELECT n FROM sales", registry = registry)

            val result =
                runner.run(
                    ExecutableNode.from(Fixtures.node("stage", source = "sales", output = NodeOutput.Tempdb("stg_sales"))),
                    context(workspaceId = workspaceA),
                )

            result.rowsOut shouldBe 1
        }

    @Test
    fun `a write-back target bound to another workspace is datasource_not_found`() =
        runBlocking<Unit> {
            val source = h2Datasource("src", listOf("CREATE TABLE src (n INT)", "INSERT INTO src VALUES (1)"))
            val target = h2Datasource("tgt", listOf("CREATE TABLE tgt (n INT)"))
            val registry =
                FakeDatasourceRegistry(
                    mapOf("src" to source, "tgt" to target),
                    visibleTo = mapOf("src" to setOf(workspaceA), "tgt" to setOf(workspaceB)),
                )
            val runner = runner(sql = "SELECT n FROM src", registry = registry)
            val node = Fixtures.node("wb", source = "src", output = NodeOutput.Datasource("tgt", "tgt", WriteMode.APPEND))

            val failure =
                shouldThrow<NodeFailedSignal> {
                    runner.run(ExecutableNode.from(node), context(workspaceId = workspaceA))
                }

            failure.error.code shouldBe PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND
        }

    // ------------------------------------------------------------------ helpers

    private fun runner(
        sql: String,
        registry: FakeDatasourceRegistry,
    ): NodeRunner {
        val (engine, _) = Fixtures.templateEngine(sql)
        return NodeRunner(engine, registry, JdbcWritebackRunner(registry), InMemoryResultStore(), ExecutorConfig())
    }

    private fun context(workspaceId: UUID): NodeExecutionContext =
        NodeExecutionContext(
            executionId = executionId,
            staging = staging,
            handle = handle,
            values = RunContext.of(emptyMap()),
            warnings = warnings,
            resultTtlSeconds = 300,
            renderBudgetChars = ExecutorConfig().renderOutputBudgetChars(),
            stagingMaxMemoryMb = 1024,
            tempdbDialect = co.datapipelines.typesystem.Dialect.H2,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            workspaceId = workspaceId,
        )
}
