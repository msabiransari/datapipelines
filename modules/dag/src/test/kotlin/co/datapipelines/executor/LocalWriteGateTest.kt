package co.datapipelines.executor

import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * The executor's half of #1033: the root gate (plan before the first business write, refuse
 * multi-destination write sets as a FAILED run), and the root settle (commit after the last
 * node on success; rollback on failure). Real H2 behind POSTGRES-dialect registrations, so
 * "committed" and "rolled back" are read from a SECOND connection the executor never touched —
 * the verdict is the database's, not the executor's word.
 */
class LocalWriteGateTest {
    private fun postgresH2(
        name: String,
        ddl: List<String>,
    ) = h2Datasource(name, ddl).copy(dialect = Dialect.POSTGRES)

    private fun countFromOtherConnection(source: co.datapipelines.datasources.Datasource): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM tgt").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    private fun outcomes(registry: SimpleMeterRegistry): Map<String, Double> =
        registry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .groupBy { it.id.getTag("outcome")!! }
            .mapValues { (_, counters) -> counters.sumOf { it.count() } }

    private fun harness(
        registry: FakeDatasourceRegistry,
        metrics: ExecutorMetrics,
        sql: Map<String, String>,
    ): ExecutorHarness =
        ExecutorHarness(
            templateEngine = Fixtures.templateEngine(sql),
            registry = registry,
            metrics = metrics,
            localWritePlanner =
                LocalWritePlanner(
                    pipelines = PipelineResolver { _, _, _ -> null },
                    datasources = registry,
                    maxCompositionDepth = 8,
                ),
        )

    @Test
    fun `a plan with a second durable destination fails the run before any business write`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val pgOther = postgresH2("pg_other", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain, "pg_other" to pgOther))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO tgt VALUES (1)",
                    "out" to "SELECT 1 AS n",
                ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                        // A second durable destination by NAME, even on the same server class —
                        // the plan counts registrations, and this one writes back to pg_other.
                        Fixtures.node(
                            "out",
                            source = "tempdb",
                            output = NodeOutput.Datasource("pg_other", "tgt", WriteMode.APPEND),
                            dependsOn = listOf("w"),
                        ),
                    )

                // A catalogued setup failure (F9 shape): the run has already emitted
                // execution_started, ends FAILED, and the ORIGINAL DatapipelinesException is
                // rethrown for the caller — the house pattern of staging creation_failed.
                val thrown =
                    shouldThrow<DatapipelinesException> {
                        h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }
                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
                thrown.details["reason"] shouldBe "second_durable_destination"

                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
            }

            // Refused before any business write: neither destination saw a row.
            countFromOtherConnection(pgMain) shouldBe 0L
            countFromOtherConnection(pgOther) shouldBe 0L
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `a successful run commits the participant work`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("w" to "INSERT INTO tgt VALUES (7)"),
            ).use { h ->
                h.executor.execute(
                    Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("w", type = NodeType.DML, source = "pg_main")))),
                )
            }

            countFromOtherConnection(pgMain) shouldBe 1L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `a failing later node rolls back the participant work of the nodes before it`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO tgt VALUES (7)",
                    "boom" to "SELECT * FROM table_that_does_not_exist",
                ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                        Fixtures.node("boom", source = "tempdb", dependsOn = listOf("w")),
                    )

                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }
            }

            countFromOtherConnection(pgMain) shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }
}
