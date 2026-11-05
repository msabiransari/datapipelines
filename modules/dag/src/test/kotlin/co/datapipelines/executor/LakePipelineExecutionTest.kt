package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.DeliveryRef
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeAppendPort
import co.datapipelines.pipeline.LakeAppendSession
import co.datapipelines.pipeline.LakeExecutionInput
import co.datapipelines.pipeline.LakePreparedExecution
import co.datapipelines.pipeline.LakeStatementExecution
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TempdbSettings
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.staging.DuckDbStagingFactory
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real DAG/native database, with only the remote publication port replaced by a recording implementation. */
class LakePipelineExecutionTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `trusted initialization precedes locked author SQL and re-list precedes publication`() {
        val session = Session()
        val factory = factory()
        harness(factory, session).use { h ->
            runBlocking { h.executor.execute(request()) }.status shouldBe ExecutionStatus.SUCCESS
            session.rows shouldBe listOf(11L, 22L)
            session.rechecked shouldBe true
            session.closed shouldBe true
            factory.budget.reservedBytes shouldBe 0L
        }
    }

    @Test
    fun `changed read fails the execution before any append and cleanup keeps its safe code`() {
        val session = Session(changed = true)
        val factory = factory()
        harness(factory, session).use { h ->
            runBlocking { shouldThrow<PipelineExecutionFailed> { h.executor.execute(request()) }.errorCode } shouldBe
                Lake.DELIVERY_CHANGED_DURING_READ
            session.appends.get() shouldBe 0
            session.failure shouldBe Lake.DELIVERY_CHANGED_DURING_READ
            session.closed shouldBe true
            factory.budget.reservedBytes shouldBe 0L
        }
    }

    @Test
    fun `a slow failing validation ancestor prevents a fast append from starting`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = Session()
        val factory = factory()
        harness(factory, session, entered, release).use { h ->
            runBlocking {
                val run = async(Dispatchers.Default) { runCatching { h.executor.execute(request(validation = true)) }.exceptionOrNull() }
                try {
                    entered.await(10, TimeUnit.SECONDS) shouldBe true
                    session.appends.get() shouldBe 0
                } finally {
                    release.countDown()
                }
                run.await().shouldBeInstanceOf<PipelineExecutionFailed>().failedNodeId shouldBe "validate"
            }
            session.appends.get() shouldBe 0
            session.rechecked shouldBe true
            session.closed shouldBe true
            factory.budget.reservedBytes shouldBe 0L
        }
    }

    private fun request(validation: Boolean = false): ExecuteRequest {
        val land =
            Fixtures
                .node("land", output = NodeOutput.Tempdb("staged"))
                .copy(delivery = DeliveryRef("feed", "batch"))
        val validate = Fixtures.node("validate", output = NodeOutput.Tempdb("checked"), dependsOn = listOf("land"))
        val append =
            Fixtures.node(
                "append",
                output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND),
                dependsOn = if (validation) listOf("land", "validate") else listOf("land"),
            )
        return Fixtures.request(
            Fixtures.pipeline(
                if (validation) listOf(land, validate, append) else listOf(land, append),
                settings = PipelineSettings(tempdb = TempdbSettings(engine = StagingEngine.DUCKDB)),
            ),
        )
    }

    private fun factory() = DuckDbStagingFactory(DuckDbStagingProperties(scratch, MIB * 64, 2, MIB * 256))

    private fun harness(
        factory: DuckDbStagingFactory,
        session: Session,
        entered: CountDownLatch? = null,
        release: CountDownLatch? = null,
    ): ExecutorHarness {
        val engine = mockk<TemplateEngine>()
        every { engine.render(any(), any(), any(), any()) } answers {
            when (firstArg<co.datapipelines.pipeline.TemplateRef>().id) {
                "land" -> {
                    "SELECT * FROM delivery"
                }

                "validate" -> {
                    entered?.countDown()
                    check(release?.await(10, TimeUnit.SECONDS) == true)
                    "SELECT error('validation refused') AS id"
                }

                "append" -> {
                    "SELECT * FROM staged"
                }

                else -> {
                    error("Unknown template")
                }
            }
        }
        val registry =
            FakeDatasourceRegistry(
                mapOf("managed" to Datasource("managed", "Managed", dialect = Dialect.LAKE, jdbcUrl = "jdbc:duckdb:")),
            )
        return ExecutorHarness(engine, registry = registry, stagingFactory = factory, lakeAppendPort = Port(session))
    }

    private class Port(
        private val session: Session,
    ) : LakeAppendPort {
        override val datasourceName = "managed"

        override fun <T : AutoCloseable> prepare(
            execution: LakeExecutionInput,
            delivery: DeliveryRef,
            context: Map<String, Any?>,
            reserve: (Long) -> T?,
        ): LakePreparedExecution<T> = LakePreparedExecution(requireNotNull(reserve(MIB * 128)), session)
    }

    private class Session(
        private val changed: Boolean = false,
    ) : LakeAppendSession {
        override val datasourceName = "managed"
        override val deadline: Instant = Instant.now().plusSeconds(60)
        val appends = AtomicInteger()
        var rechecked = false
        var closed = false
        var failure: String? = null
        var rows: List<Long> = emptyList()

        override fun initialize(
            connection: Connection,
            statements: LakeStatementExecution,
        ) {
            connection.createStatement().use { statement ->
                statements.execute(
                    statement,
                ) { statement.execute("CREATE TABLE delivery AS SELECT * FROM (VALUES (11::BIGINT), (22::BIGINT)) t(id)") }
            }
        }

        override fun readCompleted() {
            if (changed) throw DatapipelinesException(Lake.DELIVERY_CHANGED_DURING_READ, "Delivery changed.")
            rechecked = true
        }

        override fun append(
            target: NodeOutput.Datasource,
            input: LakeAppendInput,
            checkActive: () -> Unit,
        ): Long {
            checkActive()
            check(rechecked)
            appends.incrementAndGet()
            input.scan { result ->
                val values = mutableListOf<Long>()
                while (result.next()) values += result.getLong(1)
                rows = values
            }
            return rows.size.toLong()
        }

        override fun failed(code: String?) {
            failure = code
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}
