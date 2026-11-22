package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.NodeStarted
import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The executor's half of #1033: the root gate (plan before the first business write, refuse
 * multi-destination write sets as a FAILED run), and the root settle (commit after the last
 * node on success; rollback on failure). Real H2 behind POSTGRES-dialect registrations, so
 * "committed" and "rolled back" are read from a SECOND connection the executor never touched —
 * the verdict is the database's, not the executor's word.
 */
@Suppress("LargeClass") // one write-transaction gate contract (plan/settle/drain budget/child assembly) over one H2 fixture pair
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
        config: ExecutorConfig = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60),
        subPipelineRunner: SubPipelineRunner? = null,
    ): ExecutorHarness =
        ExecutorHarness(
            templateEngine = Fixtures.templateEngine(sql),
            registry = registry,
            metrics = metrics,
            config = config,
            subPipelineRunner = subPipelineRunner,
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

    @Test
    fun `a DDL-only plan executes ordinarily with zero transaction leases (R1)`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("mk" to """CREATE TABLE "new_t" (n INT)"""),
            ).use { h ->
                val nodes = listOf(Fixtures.node("mk", type = NodeType.DDL, source = "pg_main"))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
            }

            // The DDL landed on the eligible engine, and the plan took zero transaction leases.
            DriverManager.getConnection(pgMain.jdbcUrl, pgMain.username, pgMain.secret ?: "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM new_t").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 0L
                    }
                }
            }
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `an eligible writer plus external DDL refuses before either target changes (R1)`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO tgt VALUES (1)",
                    "mk" to """CREATE TABLE "new_t" (n INT)""",
                ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                        Fixtures.node("mk", type = NodeType.DDL, source = "pg_main", dependsOn = listOf("w")),
                    )
                val thrown =
                    shouldThrow<DatapipelinesException> {
                        h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }
                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
                thrown.details["reason"] shouldBe "external_ddl"
            }

            // Refused before the writer ran: the eligible target changed nowhere.
            countFromOtherConnection(pgMain) shouldBe 0L
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `an excluded-only writer keeps its ordinary path with zero transaction leases (R5)`() {
        runBlocking {
            val warehouse = h2Datasource("warehouse", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("warehouse" to warehouse))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("w" to "INSERT INTO tgt VALUES (7)"),
            ).use { h ->
                val nodes = listOf(Fixtures.node("w", type = NodeType.DML, source = "warehouse"))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
            }

            // §3.2: the excluded engine never enrolls — its effect is preserved on the ordinary
            // path (committed independently), and the plan took zero transaction leases.
            countFromOtherConnection(warehouse) shouldBe 1L
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `an eligible DQL write-back-only plan enrolls one transaction (R5)`() {
        runBlocking {
            val pgMain =
                postgresH2(
                    "pg_main",
                    listOf("CREATE TABLE src (id INT)", "CREATE TABLE tgt (id INT)", "INSERT INTO src VALUES (1)"),
                )
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("wb" to "SELECT id FROM src"),
            ).use { h ->
                val nodes =
                    listOf(Fixtures.node("wb", source = "pg_main", output = NodeOutput.Datasource("pg_main", "tgt", WriteMode.APPEND)))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
            }

            // The write-back-only plan discovered its durable destination and enrolled ONE
            // transaction, committed at the root (the same-registration source is drained first).
            countFromOtherConnection(pgMain) shouldBe 1L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `cancelling the run rolls back the family write`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val slow = h2Datasource("slow_src", listOf("CREATE TABLE unused (n INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain, "slow_src" to slow))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO tgt VALUES (7)",
                    "slow" to Fixtures.SLOW_SQL,
                ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                        Fixtures.node("slow", source = "slow_src", dependsOn = listOf("w")),
                    )
                withTimeout(HARNESS_BUDGET_MS) {
                    val run =
                        async { shouldThrow<ExecutionAbortedException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) } }
                    // "slow" starts only after the write finished, so reaching it proves the write
                    // executed — then cancel while the slow statement holds the OTHER connection.
                    while (h.emitter.allOf<NodeStarted>().none { it.nodeId == "slow" }) delay(POLL_MS)
                    h.cancellations.cancel(
                        h.emitter
                            .allOf<NodeStarted>()
                            .first { it.nodeId == "slow" }
                            .executionId,
                        AbortReason.CANCELLED,
                    )
                    run.await()
                }
            }

            // §4 row 4: explicit cancellation before commit rolls the family's write back.
            countFromOtherConnection(pgMain) shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a same-registration write-back refuses a source over the configured result budget before any durable write (R2)`() {
        runBlocking {
            // One 128-byte value, JSON-encoded to 132 bytes: over a 32-byte configured budget.
            val pgMain =
                postgresH2(
                    "pg_main",
                    listOf(
                        "CREATE TABLE src (v TEXT)",
                        "CREATE TABLE tgt (v TEXT)",
                        "INSERT INTO src VALUES (RPAD('', 128, 'x'))",
                    ),
                )
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()
            val config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60, result = ResultConfig(maxSizeBytes = 32))

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("wb" to "SELECT v FROM src"),
                config = config,
            ).use { h ->
                val nodes =
                    listOf(Fixtures.node("wb", source = "pg_main", output = NodeOutput.Datasource("pg_main", "tgt", WriteMode.APPEND)))
                val thrown = shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }
                thrown.errorCode shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
            }

            // The refusal precedes any write: the target is untouched, and the family's enlisted
            // transaction rolled back (nothing had written yet, so the database shows zero rows).
            countFromOtherConnection(pgMain) shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a below-limit same-registration write-back succeeds under the configured budget (R2)`() {
        runBlocking {
            val pgMain =
                postgresH2(
                    "pg_main",
                    listOf(
                        "CREATE TABLE src (v TEXT)",
                        "CREATE TABLE tgt (v TEXT)",
                        "INSERT INTO src VALUES ('x')",
                    ),
                )
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()
            val config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60, result = ResultConfig(maxSizeBytes = 32))

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("wb" to "SELECT v FROM src"),
                config = config,
            ).use { h ->
                val nodes =
                    listOf(Fixtures.node("wb", source = "pg_main", output = NodeOutput.Datasource("pg_main", "tgt", WriteMode.APPEND)))
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
            }

            countFromOtherConnection(pgMain) shouldBe 1L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `an over-budget same-registration REPLACE leaves the old rows and rolls back the prior family write (R2)`() {
        runBlocking {
            // `tgt` already holds an old row. A family whose FIRST node writes a new row and whose
            // SECOND node REPLACEs `tgt` from a source over the configured budget must refuse
            // BEFORE the transactional clear — so the old row survives and the prior write rolls
            // back with the family.
            val pgMain =
                postgresH2(
                    "pg_main",
                    listOf(
                        "CREATE TABLE src (v TEXT)",
                        "CREATE TABLE tgt (v TEXT)",
                        "CREATE TABLE other (id INT)",
                        "INSERT INTO src VALUES (RPAD('', 128, 'x'))",
                        "INSERT INTO tgt VALUES ('old')",
                    ),
                )
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()
            val config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = 60, result = ResultConfig(maxSizeBytes = 32))

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO other VALUES (7)",
                    "wb" to "SELECT v FROM src",
                ),
                config = config,
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                        Fixtures.node(
                            "wb",
                            source = "pg_main",
                            output = NodeOutput.Datasource("pg_main", "tgt", WriteMode.REPLACE),
                            dependsOn = listOf("w"),
                        ),
                    )
                shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }
            }

            // The REPLACE's transactional DELETE never ran: the old row survives, the prior write
            // is rolled back, and the source over the budget wrote nothing.
            DriverManager.getConnection(pgMain.jdbcUrl, pgMain.username, pgMain.secret ?: "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM tgt WHERE v = 'old'").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 1L
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM other").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 0L
                    }
                }
            }
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a read-only graph spanning two registrations and staging returns normally with zero transaction leases (R5)`() {
        runBlocking {
            val pgA = postgresH2("pg_a", listOf("CREATE TABLE t1 (id INT)", "INSERT INTO t1 VALUES (1)"))
            val pgB = postgresH2("pg_b", listOf("CREATE TABLE t2 (id INT)", "INSERT INTO t2 VALUES (2)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_a" to pgA, "pg_b" to pgB))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "a" to "SELECT id FROM t1",
                    "b" to "SELECT id FROM t2",
                ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("a", source = "pg_a", output = NodeOutput.Tempdb("stg_a")),
                        Fixtures.node("b", source = "pg_b", dependsOn = listOf("a")),
                    )
                // readOnly=true: the root planner is skipped by design, so this run must keep the
                // ordinary path with zero pipeline-transaction leases whatever the graph spans.
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes), readOnly = true)).status shouldBe
                    ExecutionStatus.SUCCESS
            }

            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `a DDL node beside an excluded-only writer executes ordinarily with its effects and zero leases (R5)`() {
        runBlocking {
            val warehouse = h2Datasource("warehouse", listOf("CREATE TABLE tgt (id INT)"))
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("warehouse" to warehouse, "pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()

            harness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO tgt VALUES (7)",
                    "mk" to """CREATE TABLE "new_t" (n INT)""",
                ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "warehouse"),
                        Fixtures.node("mk", type = NodeType.DDL, source = "pg_main"),
                    )
                h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))).status shouldBe ExecutionStatus.SUCCESS
            }

            // The excluded writer's effect is preserved on the ordinary path (committed
            // independently), the eligible-engine DDL landed, and the plan took zero leases.
            countFromOtherConnection(warehouse) shouldBe 1L
            DriverManager.getConnection(pgMain.jdbcUrl, pgMain.username, pgMain.secret ?: "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM new_t").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 0L
                    }
                }
            }
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `two registrations pointing at one database still refuse as a second destination (R5)`() {
        runBlocking {
            // Two NAMES for the SAME physical database are two registrations (§3.1): a writer on
            // one and a write-back on the other refuse before either target changes.
            val aliasA = postgresH2("alias_a", listOf("CREATE TABLE tgt (id INT)"))
            val aliasB = aliasA.copy(name = "alias_b", displayName = "alias_b")
            val registry = FakeDatasourceRegistry(mapOf("alias_a" to aliasA, "alias_b" to aliasB))
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
                        Fixtures.node("w", type = NodeType.DML, source = "alias_a"),
                        Fixtures.node(
                            "out",
                            source = "tempdb",
                            output = NodeOutput.Datasource("alias_b", "tgt", WriteMode.APPEND),
                            dependsOn = listOf("w"),
                        ),
                    )
                val thrown =
                    shouldThrow<DatapipelinesException> {
                        h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }
                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
                thrown.details["reason"] shouldBe "second_durable_destination"
            }

            // Refused before either alias wrote: the shared physical database is untouched.
            countFromOtherConnection(aliasA) shouldBe 0L
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `cancelling during a held participant statement rolls the family write back (R5)`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val base = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()
            // r5: the synchronization point is the JDBC statement itself, not `node_started`
            // (emitted before JDBC entry — cancelling then proves nothing about an in-flight
            // statement). The wrapper latches when the slow query ENTERS the driver and counts
            // the `Statement.cancel()` calls that land while it is active.
            val queryEntered = AtomicBoolean()
            val statementActive = AtomicBoolean()
            val cancelsWhileActive = AtomicInteger()
            val registry =
                object : DatasourceRegistry by base {
                    override fun poolFor(datasource: co.datapipelines.datasources.Datasource): ConnectionPool {
                        val pool = base.poolFor(datasource)
                        return object : ConnectionPool by pool {
                            override fun leaseConnection(): Connection {
                                val connection = pool.leaseConnection()
                                return object : Connection by connection {
                                    override fun createStatement(
                                        resultSetType: Int,
                                        resultSetConcurrency: Int,
                                    ): Statement {
                                        val statement = connection.createStatement(resultSetType, resultSetConcurrency)
                                        return object : Statement by statement {
                                            override fun executeQuery(sql: String): ResultSet {
                                                queryEntered.set(true)
                                                statementActive.set(true)
                                                try {
                                                    return statement.executeQuery(sql)
                                                } finally {
                                                    statementActive.set(false)
                                                }
                                            }

                                            override fun cancel() {
                                                if (statementActive.get()) cancelsWhileActive.incrementAndGet()
                                                statement.cancel()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf(
                            "w" to "INSERT INTO tgt VALUES (7)",
                            "slow" to Fixtures.SLOW_SQL,
                        ),
                    ),
                registry = registry,
                metrics = ExecutorMetrics(meterRegistry),
                localWritePlanner =
                    LocalWritePlanner(
                        pipelines = PipelineResolver { _, _, _ -> null },
                        datasources = registry,
                        maxCompositionDepth = 8,
                    ),
            ).use { h ->
                val nodes =
                    listOf(
                        Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                        // The slow statement runs ON the family's participant connection: reaching
                        // it proves the write executed, then cancel while the participant statement
                        // is held.
                        Fixtures.node("slow", source = "pg_main", dependsOn = listOf("w")),
                    )
                withTimeout(HARNESS_BUDGET_MS) {
                    val run =
                        async { shouldThrow<ExecutionAbortedException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) } }
                    // The execution id is learned from `node_started`, but the cancel is only
                    // issued once the statement has ENTERED the driver.
                    while (h.emitter.allOf<NodeStarted>().none { it.nodeId == "slow" }) delay(POLL_MS)
                    while (!queryEntered.get()) delay(POLL_MS)
                    h.cancellations.cancel(
                        h.emitter
                            .allOf<NodeStarted>()
                            .first { it.nodeId == "slow" }
                            .executionId,
                        AbortReason.CANCELLED,
                    )
                    run.await()
                }
                queryEntered.get() shouldBe true
                (cancelsWhileActive.get() > 0) shouldBe true
            }

            // §4 row 4: cancellation while the participant statement is held rolls the write back.
            countFromOtherConnection(pgMain) shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a plan whose only writer sits in a pinned child enrolls one transaction (R5)`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()
            val child = Fixtures.pipeline(listOf(Fixtures.node("child_write", type = NodeType.DML, source = "pg_main")))

            childHarness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf("child_write" to "INSERT INTO tgt VALUES (7)"),
                child,
            ).use { h ->
                val root =
                    Fixtures.pipeline(
                        listOf(childRefNode()),
                    )
                h.executor.execute(Fixtures.request(root)).status shouldBe ExecutionStatus.SUCCESS
            }

            // The walk found the writer inside the pinned child (the root declares none): one
            // transaction enrolled and committed the child's write.
            countFromOtherConnection(pgMain) shouldBe 1L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `a second durable destination inside a pinned child refuses the run (R5)`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val pgOther = postgresH2("pg_other", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain, "pg_other" to pgOther))
            val meterRegistry = SimpleMeterRegistry()
            val child = Fixtures.pipeline(listOf(Fixtures.node("child_write", type = NodeType.DML, source = "pg_other")))

            childHarness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "w" to "INSERT INTO tgt VALUES (1)",
                    "child_write" to "INSERT INTO tgt VALUES (2)",
                ),
                child,
            ).use { h ->
                val root =
                    Fixtures.pipeline(
                        listOf(
                            Fixtures.node("w", type = NodeType.DML, source = "pg_main"),
                            childRefNode(listOf("w")),
                        ),
                    )
                val thrown =
                    shouldThrow<DatapipelinesException> {
                        h.executor.execute(Fixtures.request(root))
                    }
                thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_REFUSED
                thrown.details["reason"] shouldBe "second_durable_destination"
            }

            // The planner discovered the child's second destination BEFORE the root writer ran:
            // neither registration changed.
            countFromOtherConnection(pgMain) shouldBe 0L
            countFromOtherConnection(pgOther) shouldBe 0L
            outcomes(meterRegistry) shouldBe emptyMap()
        }
    }

    @Test
    fun `a failing required child rolls back the family write (R5)`() {
        runBlocking {
            val pgMain = postgresH2("pg_main", listOf("CREATE TABLE tgt (id INT)"))
            val registry = FakeDatasourceRegistry(mapOf("pg_main" to pgMain))
            val meterRegistry = SimpleMeterRegistry()
            val child =
                Fixtures.pipeline(
                    listOf(
                        Fixtures.node("child_write", type = NodeType.DML, source = "pg_main"),
                        Fixtures.node("child_boom", source = "tempdb", dependsOn = listOf("child_write")),
                    ),
                )

            childHarness(
                registry,
                ExecutorMetrics(meterRegistry),
                mapOf(
                    "child_write" to "INSERT INTO tgt VALUES (7)",
                    "child_boom" to "SELECT * FROM table_that_does_not_exist",
                ),
                child,
            ).use { h ->
                val root =
                    Fixtures.pipeline(
                        listOf(childRefNode()),
                    )
                shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(root)) }
            }

            // The child wrote inside the family transaction, then its last required node failed:
            // the ROOT rolls the family's write back, so the target is untouched.
            countFromOtherConnection(pgMain) shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    /** The `child_ref` PIPELINE node pinned to [CHILD_REF]. */
    private fun childRefNode(dependsOn: List<String> = emptyList()): Node =
        Fixtures.node("child_ref", type = NodeType.PIPELINE, source = "", dependsOn = dependsOn).copy(pipeline = CHILD_REF)

    /**
     * The production child-assembly harness: a planner that resolves [child] and a
     * [SubPipelineRunner] that re-enters the executor with the family's transaction inherited —
     * the same shape the PostgreSQL integration suite uses, over H2 fixtures.
     */
    private fun childHarness(
        registry: FakeDatasourceRegistry,
        metrics: ExecutorMetrics,
        sql: Map<String, String>,
        child: Pipeline,
    ): ExecutorHarness {
        val executorRef = AtomicReference<PipelineExecutor>()
        val harness =
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                registry = registry,
                metrics = metrics,
                localWritePlanner =
                    LocalWritePlanner(
                        pipelines =
                            PipelineResolver { _, name, version ->
                                if (name == CHILD_REF.name && version == CHILD_REF.version) {
                                    ResolvedPipeline(child, entityDiscarded = false)
                                } else {
                                    null
                                }
                            },
                        datasources = registry,
                        maxCompositionDepth = 8,
                    ),
                subPipelineRunner =
                    SubPipelineRunner { node, ctx ->
                        val childResult =
                            executorRef.get().execute(
                                ExecuteRequest(
                                    pipelineId = UUID.randomUUID(),
                                    pipelineVersion = CHILD_REF.version,
                                    pipeline = child,
                                    userId = ctx.userId,
                                    workspaceId = ctx.workspaceId,
                                    triggeredVia = ExecutionTrigger.PIPELINE,
                                    parentExecutionId = ctx.executionId,
                                    parentNodeId = node.id,
                                    rootExecutionId = ctx.rootExecutionId,
                                    compositionDepth = ctx.compositionDepth + 1,
                                    correlationId = ctx.correlationId ?: UUID.randomUUID(),
                                    readOnly = ctx.readOnly,
                                    localWriteTransaction = ctx.localWriteTransaction,
                                ),
                            )
                        NodeResult.of(node.id, 0, Instant.now(), childExecutionId = childResult.executionId)
                    },
            )
        executorRef.set(harness.executor)
        return harness
    }

    private companion object {
        const val POLL_MS = 5L
        const val HARNESS_BUDGET_MS = 60_000L
        val CHILD_REF = PipelineNodeRef(name = "test/child_writer", version = 2)
    }
}
