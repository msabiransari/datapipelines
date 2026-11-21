package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.LocalWriteOperation
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.SseEventType
import co.datapipelines.executor.LocalWritePlanner.Destination
import co.datapipelines.pipeline.DmlInput
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1036's run-admission qualification gate: [LocalWritePlanner.qualify] consults the runtime
 * qualification matrix before the first business statement, so a covered engine that lacks
 * real-driver evidence (Oracle) or an operation the engine did not qualify (an insert on MySQL or
 * SQL Server) is REFUSED — never enrolled, and never downgraded to independent commits.
 */
class LocalWriteQualificationGateTest {
    private val planner =
        LocalWritePlanner(
            pipelines = PipelineResolver { _, _, _ -> null },
            datasources = FakeDatasourceRegistry(emptyMap()),
            maxCompositionDepth = 8,
        )

    private fun write(
        nodeId: String,
        operation: LocalWriteOperation,
    ) = Destination("target", nodeId, listOf(nodeId), operation)

    @Test
    fun `an enrolled plan on Oracle is refused as an unqualified transactional target`() {
        val verdict =
            planner.qualify(
                LocalWriteVerdict.Enroll("target"),
                Dialect.ORACLE,
                listOf(write("wb", LocalWriteOperation.WRITEBACK_APPEND)),
            )

        verdict as LocalWriteVerdict.Refuse
        verdict.reason shouldBe "unqualified_transactional_target"
        verdict.details["registration"] shouldBe "target"
        verdict.details["dialect"] shouldBe "ORACLE"
        verdict.details["qualification"] shouldBe "unavailable"
    }

    @Test
    fun `an insert on MySQL or SQL Server is refused as an unqualified write operation naming the node`() {
        listOf(Dialect.MYSQL, Dialect.MSSQL).forEach { dialect ->
            val verdict =
                planner.qualify(
                    LocalWriteVerdict.Enroll("target"),
                    dialect,
                    listOf(write("upd", LocalWriteOperation.UPDATE), write("ins", LocalWriteOperation.INSERT)),
                )
            verdict as LocalWriteVerdict.Refuse
            verdict.reason shouldBe "unqualified_write_operation"
            @Suppress("UNCHECKED_CAST")
            val writes = verdict.details["writes"] as List<Map<String, Any?>>
            writes.map { it["node_id"] to it["qualification"] } shouldBe listOf("ins" to "refused")
        }
    }

    @Test
    fun `qualified engines keep their enrolment for every qualified operation`() {
        val counted =
            listOf(
                write("upd", LocalWriteOperation.UPDATE),
                write("del", LocalWriteOperation.DELETE),
                write("app", LocalWriteOperation.WRITEBACK_APPEND),
                write("rep", LocalWriteOperation.WRITEBACK_REPLACE),
            )
        listOf(Dialect.POSTGRES, Dialect.MYSQL, Dialect.MSSQL).forEach { dialect ->
            planner.qualify(LocalWriteVerdict.Enroll("target"), dialect, counted) shouldBe LocalWriteVerdict.Enroll("target")
        }
        planner.qualify(LocalWriteVerdict.Enroll("target"), Dialect.POSTGRES, listOf(write("ins", LocalWriteOperation.INSERT))) shouldBe
            LocalWriteVerdict.Enroll("target")
    }

    @Test
    fun `ordinary and already refused verdicts pass through unchanged`() {
        planner.qualify(LocalWriteVerdict.Ordinary, Dialect.ORACLE, emptyList()) shouldBe LocalWriteVerdict.Ordinary
        val refused = LocalWriteVerdict.Refuse("external_ddl", emptyMap())
        planner.qualify(refused, Dialect.ORACLE, emptyList()) shouldBe refused
    }

    @Test
    fun `Oracle stays covered, so an Oracle writer is refused and never treated as an ordinary independent commit`() {
        (Dialect.ORACLE in LocalWritePlanner.ELIGIBLE_DIALECTS) shouldBe true
        (Dialect.H2 in LocalWritePlanner.ELIGIBLE_DIALECTS) shouldBe false
    }

    @Test
    fun `a run writing back to an Oracle registration fails at admission with no lease and no business write`() {
        runBlocking {
            val oracle = h2Datasource("ora_main", listOf("CREATE TABLE tgt (id INT)")).copy(dialect = Dialect.ORACLE)
            val registry = LeaseCountingRegistry(mapOf("ora_main" to oracle))
            val meterRegistry = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("out" to "SELECT 1 AS id")),
                registry = registry,
                metrics = ExecutorMetrics(meterRegistry),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, maxCompositionDepth = 8),
            ).use { h ->
                val nodes =
                    listOf(Fixtures.node("out", source = "tempdb", output = NodeOutput.Datasource("ora_main", "tgt", WriteMode.APPEND)))
                val thrown = shouldThrow<DatapipelinesException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }

                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
                thrown.details["reason"] shouldBe "unqualified_transactional_target"
                thrown.details["dialect"] shouldBe "ORACLE"
                thrown.message shouldContain "not qualified"
                thrown.message shouldNotContain "SELECT"
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
            }
            registry.leases.get() shouldBe 0
            countRows(oracle) shouldBe 0L
            meterRegistry.find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS).counters().size shouldBe 0
        }
    }

    @Test
    fun `a MySQL insert inside an otherwise qualified plan is refused at admission before its update runs`() {
        runBlocking {
            val mysql =
                h2Datasource(
                    "my_main",
                    listOf("CREATE TABLE tgt (id INT)", "INSERT INTO tgt VALUES (1)"),
                ).copy(dialect = Dialect.MYSQL)
            val registry = LeaseCountingRegistry(mapOf("my_main" to mysql))
            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf("upd" to "UPDATE tgt SET id = 2", "ins" to "INSERT INTO tgt (id) VALUES (3)"),
                    ),
                registry = registry,
                metrics = ExecutorMetrics(SimpleMeterRegistry()),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, maxCompositionDepth = 8),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("upd", type = NodeType.DML, source = "my_main"),
                        Fixtures
                            .node("ins", type = NodeType.DML, source = "my_main", operation = "insert", dependsOn = listOf("upd"))
                            .copy(key = DmlKey("id", "INTEGER"), input = DmlInput(parameters = listOf("customer"))),
                    )
                val pipeline = Fixtures.pipeline(nodes, parameters = mapOf("customer" to Parameter(LogicalType.STRING)))
                val thrown = shouldThrow<DatapipelinesException> { h.executor.execute(Fixtures.request(pipeline)) }
                // The insert authority refuses first, inside the same family walk; the matrix agrees
                // (LocalWriteQualificationTest pins the two), so neither path can enroll it.
                thrown.code shouldBe PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED
            }
            registry.leases.get() shouldBe 0
            countRows(mysql, "SELECT COUNT(*) FROM tgt WHERE id = 1") shouldBe 1L
        }
    }

    @Test
    fun `a registration repointed to Oracle after planning is refused at enlist with no lease`() {
        runBlocking {
            val oracle = h2Datasource("ora_main", listOf("CREATE TABLE tgt (id INT)")).copy(dialect = Dialect.ORACLE)
            val registry = LeaseCountingRegistry(mapOf("ora_main" to oracle))
            val config = ExecutorConfig(maxParallelNodes = 1, executionTimeoutSeconds = 5)
            ExecutorDispatcher.forConfig(config, maxThreads = 1).use { dispatcher ->
                val transaction =
                    LocalWriteTransaction(
                        rootExecutionId = java.util.UUID.randomUUID(),
                        workspaceId = java.util.UUID.randomUUID(),
                        registration = "ora_main",
                        registry = registry,
                        metrics = ExecutorMetrics(SimpleMeterRegistry()),
                        dispatcher = dispatcher,
                    )

                val thrown =
                    shouldThrow<DatapipelinesException> {
                        transaction.withParticipant { it.createStatement().use { s -> s.execute("INSERT INTO tgt VALUES (1)") } }
                    }

                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
                thrown.details["reason"] shouldBe "registration_ineligible"
                thrown.message shouldContain "has not qualified"
            }
            registry.leases.get() shouldBe 0
            countRows(oracle) shouldBe 0L
        }
    }

    private fun countRows(
        source: Datasource,
        sql: String = "SELECT COUNT(*) FROM tgt",
    ): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    /** Counts every connection leased from any registration's pool — admission must lease none. */
    private class LeaseCountingRegistry(
        datasources: Map<String, Datasource>,
        private val base: FakeDatasourceRegistry = FakeDatasourceRegistry(datasources),
    ) : co.datapipelines.datasources.DatasourceRegistry by base {
        val leases = AtomicInteger()

        override fun poolFor(datasource: Datasource): ConnectionPool {
            val delegate = base.poolFor(datasource)
            return object : ConnectionPool by delegate {
                override fun leaseConnection(): java.sql.Connection {
                    leases.incrementAndGet()
                    return delegate.leaseConnection()
                }
            }
        }
    }
}
