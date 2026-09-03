package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.NodeFailed
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * The 057 failure record (T85): when a node fails, the `node_failed` event, the terminal
 * `pipeline_failed` event and the thrown record carry the SAME object — node context, rendered
 * SQL in `:name` form, and the exception chain, bounded — with bound parameter VALUES never
 * present anywhere but the (separate, by-design) parameters payload.
 *
 * The T85 shape is reproduced with a datasource whose pool cannot initialize: the synthetic
 * chain below is a pool-init RuntimeException wrapping a driver SQLException, exactly the
 * three-demo-executions failure the owner reported on 2026-09-02.
 */
class FailureTransparencyTest {
    @Test
    fun `a connect failure carries the node context, the rendered SQL and the bounded exception chain on every carrier`() =
        runBlocking<Unit> {
            val registry = throwingConnectRegistry(t85Chain())
            val nodes = listOf(Fixtures.node("stage_daily_trips", source = "d_trips", output = NodeOutput.Tempdb("stg")))
            val sql = mapOf("stage_daily_trips" to "SELECT * FROM trips")

            harness(sql, registry).use { h ->
                val failed =
                    shouldThrow<PipelineExecutionFailed> {
                        h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }

                val nodeRecord =
                    h.emitter
                        .firstOf<NodeFailed>()
                        .error
                        .shouldNotBeNull()

                // The node context: the datasource path decorated it (dialect in hand).
                nodeRecord.node.shouldNotBeNull().let { n ->
                    n.id shouldBe "stage_daily_trips"
                    n.type shouldBe "DQL"
                    n.datasource shouldBe "d_trips"
                    n.dialect shouldBe "H2"
                    n.template shouldBe "stage_daily_trips"
                    n.templateVersion shouldBe 1
                }

                // The rendered SQL, in :name form — the translated positional form never ships.
                nodeRecord.sql shouldBe "SELECT * FROM trips"

                // The exception chain: class, driver message, capped frames.
                nodeRecord.exception.shouldNotBeNull().let { x ->
                    x.className shouldBe "java.lang.RuntimeException"
                    x.message shouldBe "Failed to initialize pool"
                    x.frames shouldHaveSize ExceptionDetail.FRAMES_CAP
                    x.frames.first() shouldBe "Boom.f0(Boom.kt:1)"
                    x.causedBy shouldHaveSize 2
                    x.causedBy[0].className shouldBe "java.sql.SQLException"
                    x.causedBy[0].message shouldBe "FATAL: password authentication failed for user \"dp_demo_ro\""
                    // The ROOT cause sits at the END of the chain (SKILL.md reads it there).
                    x.causedBy.last().className shouldBe "java.lang.IllegalStateException"
                }

                // Same object, unchanged, on the terminal event and on the thrown record —
                // the executor does not rebuild the record between carriers.
                h.emitter.firstOf<PipelineFailed>().error shouldBe nodeRecord
                failed.errorRecord shouldBe nodeRecord
            }
        }

    @Test
    fun `a 60-frame synthetic cause is capped at the frame limit, not dropped`() =
        runBlocking<Unit> {
            val deep = RuntimeException("deep")
            deep.stackTrace = Array(60) { i -> StackTraceElement("Deep", "frame$i", "Deep.kt", i + 1) }
            val registry = throwingConnectRegistry(deep)
            val nodes = listOf(Fixtures.node("n", source = "d_trips", output = NodeOutput.Tempdb("stg")))
            val sql = mapOf("n" to "SELECT 1")

            harness(sql, registry).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }
                val x =
                    h.emitter
                        .firstOf<NodeFailed>()
                        .error
                        .exception
                        .shouldNotBeNull()
                x.frames shouldHaveSize ExceptionDetail.FRAMES_CAP
                x.frames.first() shouldBe "Deep.frame0(Deep.kt:1)"
                // Capped, not dropped: the record still exists and still names the failure.
                x.className shouldBe "java.lang.RuntimeException"
                x.message shouldBe "deep"
                x.causedBy shouldHaveSize 0
            }
        }

    @Test
    fun `bound parameter values never leak into the failure events or the error record`() =
        runBlocking<Unit> {
            val registry = throwingConnectRegistry(t85Chain())
            val nodes = listOf(Fixtures.node("n", source = "d_trips", output = NodeOutput.Tempdb("stg")))
            val sql = mapOf("n" to "SELECT * FROM t WHERE borough = :borough")
            val pipeline =
                Fixtures.pipeline(
                    nodes,
                    parameters = mapOf("borough" to Parameter(type = LogicalType.STRING, required = true)),
                )

            harness(sql, registry).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(
                        Fixtures.request(pipeline).copy(parameters = mapOf("borough" to TextNode("SECRET_VALUE_XYZ"))),
                    )
                }
                val nodeFailed = ExecutorJson.write(h.emitter.firstOf<NodeFailed>().error)
                val terminal = ExecutorJson.write(h.emitter.firstOf<PipelineFailed>().error)
                nodeFailed shouldNotContain "SECRET_VALUE_XYZ"
                terminal shouldNotContain "SECRET_VALUE_XYZ"
                // The SQL half of the record keeps the :name form — the value never lands in it.
                nodeFailed shouldContain ":borough"
            }
        }

    @Test
    fun `error-detail structured omits exception and sql and keeps everything else`() =
        runBlocking<Unit> {
            val registry = throwingConnectRegistry(t85Chain())
            val nodes = listOf(Fixtures.node("n", source = "d_trips", output = NodeOutput.Tempdb("stg")))
            val sql = mapOf("n" to "SELECT * FROM trips")

            harness(sql, registry, ExecutorConfig(errorDetail = ErrorDetail.STRUCTURED)).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }
                val record = h.emitter.firstOf<NodeFailed>().error
                record.exception.shouldBeNull()
                record.sql.shouldBeNull()
                record.code shouldBe PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
                record.message shouldBe "Failed to initialize pool"
                record.node.shouldNotBeNull().datasource shouldBe "d_trips"
                // The driver's text lived only in the omitted chain — under structured it is
                // gone ENTIRELY, which is the redaction an operator chooses this mode for.
                ExecutorJson.write(record) shouldNotContain "dp_demo_ro"
            }
        }

    @Test
    fun `a render failure carries the node context and no SQL - none exists yet`() =
        runBlocking<Unit> {
            val engine = mockk<TemplateEngine>()
            every { engine.render(any(), any(), any()) } throws
                DatapipelinesException(
                    code = PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED,
                    message = "boom at render",
                )
            val nodes = listOf(Fixtures.node("n", output = NodeOutput.Caller))

            ExecutorHarness(engine, FakeDatasourceRegistry(emptyMap()), ExecutorConfig()).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }
                val record = h.emitter.firstOf<NodeFailed>().error
                record.sql.shouldBeNull()
                record.node.shouldNotBeNull().let { n ->
                    n.id shouldBe "n"
                    n.dialect shouldBe "H2" // tempdb source — the tempdb engine's dialect
                }
                record.exception.shouldNotBeNull().message shouldBe "boom at render"
            }
        }

    // ------------------------------------------------------------ fixtures

    /**
     * The T85 chain: pool-init failure wrapping the driver's password-authentication failure
     * wrapping one more level — three levels, a 60-frame top, the shape `poolFor`/`lease`
     * produces against a live Postgres with a stale credential.
     */
    private fun t85Chain(): RuntimeException {
        val root = IllegalStateException("connection is closed")
        val driver = SQLException("FATAL: password authentication failed for user \"dp_demo_ro\"", root)
        driver.stackTrace = Array(20) { i -> StackTraceElement("Driver", "d$i", "Driver.kt", i + 1) }
        val pool = RuntimeException("Failed to initialize pool", driver)
        pool.stackTrace = Array(60) { i -> StackTraceElement("Boom", "f$i", "Boom.kt", i + 1) }
        return pool
    }

    /**
     * A registry that RESOLVES `d_trips` (a real H2 entry, dialect in hand) but whose pool
     * fails to lease with [failure] — the registry-resolved, pool-init-failed shape of T85.
     */
    private fun throwingConnectRegistry(failure: Exception): DatasourceRegistry =
        FixedPoolRegistry(
            FakeDatasourceRegistry(mapOf("d_trips" to h2Datasource("d_trips", listOf("CREATE TABLE trips (n INT)")))),
            ThrowingPool(failure),
        )

    private fun harness(
        sqlByTemplateId: Map<String, String>,
        registry: DatasourceRegistry,
        config: ExecutorConfig = ExecutorConfig(),
    ) = ExecutorHarness(templateEngine = Fixtures.templateEngine(sqlByTemplateId), registry = registry, config = config)

    /** A registry whose EVERY pool fails to lease — the connect-phase failure under test. */
    private class FixedPoolRegistry(
        inner: DatasourceRegistry,
        private val pool: ConnectionPool,
    ) : DatasourceRegistry by inner {
        override fun poolFor(datasource: Datasource): ConnectionPool = pool
    }

    private class ThrowingPool(
        private val failure: Exception,
    ) : ConnectionPool {
        override val name: String = "boom"

        override fun leaseConnection(): Connection = throw failure

        override fun close() = Unit
    }
}
