package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.KeyMode
import co.datapipelines.datasources.LocalWriteOperation
import co.datapipelines.datasources.LocalWriteQualification
import co.datapipelines.datasources.QualificationStatus
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.events.NodeFailed
import co.datapipelines.events.PipelineFailed
import co.datapipelines.pipeline.DmlInput
import co.datapipelines.pipeline.DmlKey
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1036's lane-specific integration contract: the single-database pipeline transaction qualified
 * on REAL PostgreSQL, MySQL (InnoDB) and SQL Server servers with the pinned drivers, every
 * business observable read from a SECOND connection the executor never touched. Each case drives
 * the production [PipelineExecutor] with the production planner and transaction; the only test
 * seams are the commit ledger (a hook at the commit boundary, where the mid-run observation and the
 * wire faults are placed) and, for lost acknowledgements, a loopback [FaultProxy] in front of the
 * real server.
 *
 * Each case prints `QUALIFICATION engine=… operation=… observed=…` lines — the evidence the matrix
 * ([LocalWriteQualification], dag-executor.md §16.6) cites. Oracle is not here: no project-approved
 * licensed environment exists, and the matrix records it UNAVAILABLE (refused at admission).
 *
 * Limitation stated, not hidden: the registration pool here is the module's plain
 * `DriverManager` pool ([FakeDatasourceRegistry]), not the production Hikari pool; the driver,
 * the server and every statement are real.
 */
class LocalTransactionDialectQualificationTest {
    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `the pinned driver and the server are the versions the matrix records`(engine: QualificationEngine) {
        engine.assumeRunnable()
        engine.reset()
        val capability = LocalWriteQualification.rows(engine.dialect, LocalWriteOperation.TRANSACTION).single()
        engine.secondConnection().use { connection ->
            val meta = connection.metaData
            val pinned = capability.driver.substringAfterLast(' ').substringBefore(".jre")
            report(engine, "versions", "server=${meta.databaseProductVersion} driver=${meta.driverVersion}")
            report(engine, "isolation", "default=${isolationName(connection.transactionIsolation)}")
            report(engine, "storage", engine.storageOf("acct"))
            meta.driverVersion shouldContain pinned
            meta.databaseProductVersion shouldStartWith engine.serverVersionPrefix
            engine.storageOf("acct").equals(capability.storage, ignoreCase = true) shouldBe true
        }
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `the family commits once, with the right counts, and a second connection saw nothing before the commit`(
        engine: QualificationEngine,
    ) {
        engine.assumeRunnable()
        engine.reset()
        val midRun = CopyOnWriteArrayList<String>()
        val meters = SimpleMeterRegistry()
        val counter = LeaseCounter(engine.registration(TARGET))

        runBlocking {
            harness(engine, counter, meters, onCommitAttempt = {
                midRun += engine.midRunView("log_t")
                midRun += engine.midRunView("acct")
            }).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(familyNodes(boom = false))))
                result.status shouldBe ExecutionStatus.SUCCESS
                val counts = result.nodeStats.associate { it.nodeId to it.rowsOut }
                report(engine, "update", "affected_rows several=${counts["upd2"]} zero=${counts["upd0"]}")
                report(engine, "delete", "affected_rows several=${counts["del2"]} zero=${counts["del0"]}")
                counts.filterKeys { it in COUNT_NODES } shouldBe mapOf("upd2" to 2L, "upd0" to 0L, "del2" to 2L, "del0" to 0L)
            }
        }

        report(engine, "transaction", "mid_run_second_connection=${midRun.joinToString(",")}")
        midRun.forEach { view -> withClue(view) { (view == "count=0" || view == "count=5" || view.startsWith("blocked(")) shouldBe true } }
        if (engine != QualificationEngine.MSSQL) midRun shouldBe listOf("count=0", "count=5")
        engine.ints("SELECT bal FROM acct ORDER BY id") shouldBe listOf(1, 1, 0)
        engine.long("SELECT COUNT(*) FROM log_t") shouldBe WB_ROWS
        engine.ints("SELECT id FROM wb_target ORDER BY id") shouldBe listOf(REPLACED_ID)
        report(engine, "writeback_append", "committed rows=${engine.long("SELECT COUNT(*) FROM log_t")}")
        report(engine, "writeback_replace", "committed target=${engine.ints("SELECT id FROM wb_target ORDER BY id")}")
        counter.leased.get() shouldBe 1
        outcomes(meters) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `a later failure rolls every write back, and the replaced table keeps its old rows`(engine: QualificationEngine) {
        engine.assumeRunnable()
        engine.reset()
        val meters = SimpleMeterRegistry()

        runBlocking {
            harness(engine, LeaseCounter(engine.registration(TARGET)), meters).use { h ->
                shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(familyNodes(boom = true)))) }
            }
        }

        val balances = engine.ints("SELECT bal FROM acct ORDER BY id")
        val replaced = engine.ints("SELECT id FROM wb_target ORDER BY id")
        report(engine, "rollback", "acct=$balances log_t=${engine.long("SELECT COUNT(*) FROM log_t")} wb_target=$replaced")
        balances shouldBe listOf(0, 0, 0, 0, 0)
        engine.long("SELECT COUNT(*) FROM log_t") shouldBe 0L
        replaced shouldBe listOf(OLD_ID_1, OLD_ID_2)
        outcomes(meters) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `a statement blocked past its timeout is cancelled and the family rolls back without a replay`(engine: QualificationEngine) {
        engine.assumeRunnable()
        engine.reset()
        val meters = SimpleMeterRegistry()
        val nodes =
            listOf(
                writeBack("app", "log_t", WriteMode.APPEND),
                Fixtures.node("upd_locked", type = NodeType.DML, source = TARGET, dependsOn = listOf("app"), queryTimeoutSeconds = 2),
            )

        engine.secondConnection().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeUpdate("UPDATE acct SET bal = 50 WHERE id = 1") }
            val startedAt = System.nanoTime()
            runBlocking {
                harness(engine, LeaseCounter(engine.registration(TARGET)), meters).use { h ->
                    shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }
                    val failed = h.emitter.allOf<NodeFailed>().single { it.nodeId == "upd_locked" }
                    report(engine, "statement_timeout", "code=${failed.error.code} after_ms=${elapsedMs(startedAt)}")
                    failed.error.code shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
                }
            }
            Duration.ofNanos(System.nanoTime() - startedAt) shouldBeLessThan Duration.ofSeconds(TIMEOUT_CEILING_S)
            holder.rollback()
        }

        engine.long("SELECT COUNT(*) FROM log_t") shouldBe 0L
        engine.ints("SELECT bal FROM acct WHERE id = 1") shouldBe listOf(0)
        outcomes(meters) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `a connection lost before the commit reaches the server ends OUTCOME_UNKNOWN and the server rolls back`(
        engine: QualificationEngine,
    ) {
        engine.assumeRunnable()
        engine.reset()
        val (host, port) = FaultProxy.authorityOf(engine.database.jdbcUrl)
        FaultProxy(host, port).use { proxy ->
            val meters = SimpleMeterRegistry()
            val registration = engine.registration(TARGET, proxy.route(engine.database.jdbcUrl))
            runBlocking {
                harness(engine, LeaseCounter(registration), meters, onCommitAttempt = proxy::cutAll).use { h ->
                    val thrown =
                        shouldThrow<DatapipelinesException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(lostAckNodes()))) }
                    thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
                    thrown.details["reason"] shouldBe "commit_refused"
                    h.emitter
                        .allOf<PipelineFailed>()
                        .single()
                        .status shouldBe ExecutionStatus.OUTCOME_UNKNOWN
                }
            }
            // The row lock is released only once the server has ended the dead session; waiting for
            // it on a second connection, then reading, shows what the server did with the writes.
            awaitRowLockFree(engine)
            val balance = engine.ints("SELECT bal FROM acct WHERE id = 1")
            report(
                engine,
                "lost_ack_before_commit",
                "status=OUTCOME_UNKNOWN acct1=$balance log_t=${engine.long("SELECT COUNT(*) FROM log_t")}",
            )
            balance shouldBe listOf(0)
            engine.long("SELECT COUNT(*) FROM log_t") shouldBe 0L
            outcomes(meters) shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `a commit whose acknowledgement is lost ends OUTCOME_UNKNOWN, landed exactly once and never replayed`(engine: QualificationEngine) {
        engine.assumeRunnable()
        engine.reset()
        val (host, port) = FaultProxy.authorityOf(engine.database.jdbcUrl)
        FaultProxy(host, port).use { proxy ->
            val meters = SimpleMeterRegistry()
            val registration = engine.registration(TARGET, proxy.route(engine.database.jdbcUrl))
            val config = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = LOST_ACK_BUDGET_S)
            runBlocking {
                harness(engine, LeaseCounter(registration), meters, config, onCommitAttempt = proxy::dropReplies).use { h ->
                    val thrown =
                        shouldThrow<DatapipelinesException> { h.executor.execute(Fixtures.request(Fixtures.pipeline(lostAckNodes()))) }
                    thrown.code shouldBe PipelineErrorCodes.Execution.LOCAL_WRITE_COMMIT_FAILED
                    thrown.details["reason"] shouldBe "commit_unanswered_within_budget"
                    h.emitter
                        .allOf<PipelineFailed>()
                        .single()
                        .status shouldBe ExecutionStatus.OUTCOME_UNKNOWN
                }
            }
            awaitRowLockFree(engine)
            val balance = engine.ints("SELECT bal FROM acct WHERE id = 1")
            report(
                engine,
                "lost_ack_after_commit",
                "status=OUTCOME_UNKNOWN acct1=$balance log_t=${engine.long("SELECT COUNT(*) FROM log_t")}",
            )
            // The server committed the forwarded COMMIT; the row moved once, not twice — no replay.
            balance shouldBe listOf(1)
            engine.long("SELECT COUNT(*) FROM log_t") shouldBe WB_ROWS
            outcomes(meters) shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `the pinned driver's generated key matches the matrix for a generated and a supplied key`(engine: QualificationEngine) {
        engine.assumeRunnable()
        engine.reset()
        val key = DeclaredKey("id", LogicalType.INTEGER)
        engine.secondConnection().use { connection ->
            val generated = probeKey(connection, "INSERT INTO k_gen (note) VALUES (?)", key) { it.setString(1, "g") }
            val supplied =
                probeKey(connection, "INSERT INTO k_sup (id, note) VALUES (?, ?)", key) {
                    it.setInt(1, SUPPLIED_ID)
                    it.setString(2, "s")
                }
            report(engine, "insert", "generated=$generated supplied=$supplied")
            val expected = LocalWriteQualification.rows(engine.dialect, LocalWriteOperation.INSERT).associate { it.keyMode to it.status }
            (generated.startsWith("key=")) shouldBe (expected[KeyMode.GENERATED] == QualificationStatus.QUALIFIED)
            (supplied == "key=$SUPPLIED_ID") shouldBe (expected[KeyMode.SUPPLIED] == QualificationStatus.QUALIFIED)
        }
    }

    @ParameterizedTest
    @EnumSource(QualificationEngine::class)
    fun `an enrolled insert commits correlated keys where qualified and is refused at admission where not`(engine: QualificationEngine) {
        engine.assumeRunnable()
        engine.reset()
        val counter = LeaseCounter(engine.registration(TARGET))
        val nodes =
            listOf(
                Fixtures
                    .node("ins_gen", type = NodeType.DML, source = TARGET, operation = "insert", dmlOutput = NodeOutput.Tempdb("gen_keys"))
                    .copy(key = DmlKey("id", "INTEGER"), input = DmlInput(parameters = listOf("note"))),
                Fixtures.node("sup_in", output = NodeOutput.Tempdb("sup_rows"), dependsOn = listOf("ins_gen")),
                Fixtures
                    .node("ins_sup", type = NodeType.DML, source = TARGET, operation = "insert", dependsOn = listOf("sup_in"))
                    .copy(key = DmlKey("id", "INTEGER"), input = DmlInput(table = "sup_rows")),
            )
        val pipeline = Fixtures.pipeline(nodes, parameters = mapOf("note" to Parameter(LogicalType.STRING, required = true)))
        val request = Fixtures.request(pipeline).copy(parameters = mapOf("note" to TextNode("g")))
        val qualified = LocalWriteQualification.status(engine.dialect, LocalWriteOperation.INSERT) == QualificationStatus.QUALIFIED

        runBlocking {
            harness(engine, counter, SimpleMeterRegistry()).use { h ->
                if (qualified) {
                    h.executor.execute(request).status shouldBe ExecutionStatus.SUCCESS
                } else {
                    val thrown = shouldThrow<DatapipelinesException> { h.executor.execute(request) }
                    thrown.code shouldBe PipelineErrorCodes.Node.DML_INSERT_UNQUALIFIED
                }
            }
        }

        val rows = engine.long("SELECT COUNT(*) FROM k_gen") + engine.long("SELECT COUNT(*) FROM k_sup")
        report(
            engine,
            "insert_run",
            if (qualified) "committed rows=$rows" else "refused_at_admission leases=${counter.leased.get()} rows=$rows",
        )
        if (qualified) {
            rows shouldBe 2L
            engine.ints("SELECT id FROM k_sup") shouldBe listOf(SUPPLIED_ID)
        } else {
            rows shouldBe 0L
            counter.leased.get() shouldBe 0
        }
    }

    @Test
    fun `MySQL MyISAM keeps its rows after the family's rollback, the recorded storage limitation`() {
        val engine = QualificationEngine.MYSQL
        engine.reset()
        engine.createMyisamTable()
        val nodes =
            listOf(
                writeBack("app_m", "m_t", WriteMode.APPEND),
                Fixtures.node("upd2", type = NodeType.DML, source = TARGET, dependsOn = listOf("app_m")),
                Fixtures.node("boom", source = "tempdb", dependsOn = listOf("upd2")),
            )

        runBlocking {
            harness(engine, LeaseCounter(engine.registration(TARGET)), SimpleMeterRegistry()).use { h ->
                shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }
            }
        }

        val kept = engine.long("SELECT COUNT(*) FROM m_t")
        report(
            engine,
            "transaction",
            "storage=${engine.storageOf("m_t")} rows_after_rollback=$kept acct=${engine.ints("SELECT bal FROM acct ORDER BY id")}",
        )
        engine.storageOf("m_t") shouldBe "MyISAM"
        kept shouldBe WB_ROWS
        engine.ints("SELECT bal FROM acct ORDER BY id") shouldBe listOf(0, 0, 0, 0, 0)
        LocalWriteQualification.rowsFor(engine.dialect).single { it.storage == "MyISAM" }.status shouldBe QualificationStatus.REFUSED
    }

    // -- the family ------------------------------------------------------------------

    /** Count statements for several and zero matches, an append and a replace — then, optionally, a failure. */
    private fun familyNodes(boom: Boolean): List<Node> =
        chain(
            listOf(
                Fixtures.node("upd2", type = NodeType.DML, source = TARGET),
                Fixtures.node("upd0", type = NodeType.DML, source = TARGET),
                Fixtures.node("del2", type = NodeType.DML, source = TARGET, operation = "delete"),
                Fixtures.node("del0", type = NodeType.DML, source = TARGET, operation = "delete"),
                writeBack("app", "log_t", WriteMode.APPEND),
                writeBack("rep", "wb_target", WriteMode.REPLACE),
            ) + if (boom) listOf(Fixtures.node("boom", source = "tempdb")) else emptyList(),
        )

    private fun lostAckNodes(): List<Node> =
        chain(listOf(Fixtures.node("upd1", type = NodeType.DML, source = TARGET), writeBack("app", "log_t", WriteMode.APPEND)))

    private fun writeBack(
        id: String,
        table: String,
        mode: WriteMode,
    ): Node = Fixtures.node(id, source = "tempdb", output = NodeOutput.Datasource(TARGET, table, mode))

    /** Each node depends on the one before it, so the order of the writes is the list's. */
    private fun chain(nodes: List<Node>): List<Node> =
        nodes.mapIndexed { i, node ->
            if (i ==
                0
            ) {
                node
            } else {
                node.copy(dependsOn = listOf(nodes[i - 1].id))
            }
        }

    private val sql: Map<String, String> =
        mapOf(
            "upd2" to "UPDATE acct SET bal = bal + 1 WHERE id IN (1, 2)",
            "upd1" to "UPDATE acct SET bal = bal + 1 WHERE id = 1",
            "upd0" to "UPDATE acct SET bal = bal + 1 WHERE id = 99",
            "del2" to "DELETE FROM acct WHERE id IN (4, 5)",
            "del0" to "DELETE FROM acct WHERE id = 99",
            "upd_locked" to "UPDATE acct SET bal = bal + 1 WHERE id = 1",
            "app" to "SELECT 1 AS id UNION ALL SELECT 2 UNION ALL SELECT 3",
            "app_m" to "SELECT 1 AS id UNION ALL SELECT 2 UNION ALL SELECT 3",
            "rep" to "SELECT $REPLACED_ID AS id",
            "boom" to "SELECT * FROM table_that_does_not_exist",
            "ins_gen" to "INSERT INTO k_gen (note) VALUES (:note)",
            "sup_in" to "SELECT $SUPPLIED_ID AS id, 's' AS note",
            "ins_sup" to "INSERT INTO k_sup (id, note) VALUES (:id, :note)",
        )

    private fun harness(
        engine: QualificationEngine,
        registry: DatasourceRegistry,
        meters: SimpleMeterRegistry,
        config: ExecutorConfig = ExecutorConfig(maxParallelNodes = 4, executionTimeoutSeconds = BUDGET_S),
        onCommitAttempt: () -> Unit = {},
    ): ExecutorHarness {
        val ledger =
            object : CommitLedger {
                // The hook must not throw: a refused attempt stamp stops the commit (#1035).
                override fun recordAttempt(executionId: UUID): Boolean = true.also { runCatching(onCommitAttempt) }

                override fun recordConfirmed(executionId: UUID) = true

                override fun recordResultUnavailable(executionId: UUID) = true
            }
        check(engine.dialect in LocalWritePlanner.ELIGIBLE_DIALECTS) { "not a transactional engine" }
        return ExecutorHarness(
            templateEngine = Fixtures.templateEngine(sql),
            registry = registry,
            config = config,
            metrics = ExecutorMetrics(meters),
            localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, DEPTH, ledger),
        )
    }

    // -- evidence --------------------------------------------------------------------

    private fun probeKey(
        connection: Connection,
        insert: String,
        key: DeclaredKey,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): String =
        connection.prepareStatement(insert, arrayOf(key.column)).use { statement ->
            bind(statement)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                val label = runCatching { keys.metaData.getColumnLabel(1) }.getOrDefault("none")
                try {
                    "key=${DmlKeys.read(keys, key, "probe", 0)}"
                } catch (e: DatapipelinesException) {
                    "refused(${e.details["reason"]}, label=$label)"
                }
            }
        }

    /** Waits until a second connection can lock row 1 — the server has settled the run's session. */
    private fun awaitRowLockFree(engine: QualificationEngine) {
        engine.secondConnection().use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.queryTimeout = LOCK_WAIT_S
                statement.executeUpdate("UPDATE acct SET bal = bal WHERE id = 1")
            }
            connection.rollback()
        }
    }

    private fun report(
        engine: QualificationEngine,
        operation: String,
        observed: String,
    ) = println("QUALIFICATION engine=${engine.name} operation=$operation observed=$observed")

    private fun isolationName(level: Int): String =
        when (level) {
            Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED"
            Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED"
            Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ"
            Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE"
            else -> "level_$level"
        }

    private fun elapsedMs(startedAt: Long): Long = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    private fun outcomes(registry: SimpleMeterRegistry): Map<String, Double> =
        registry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .groupBy { requireNotNull(it.id.getTag("outcome")) }
            .mapValues { (_, counters) -> counters.sumOf { it.count() } }

    /** Counts the registration's pool leases — one participant connection per enrolled run, none for a refused one. */
    private class LeaseCounter(
        registration: Datasource,
        private val base: FakeDatasourceRegistry = FakeDatasourceRegistry(mapOf(registration.name to registration)),
    ) : DatasourceRegistry by base {
        val leased = AtomicInteger()

        override fun poolFor(datasource: Datasource): ConnectionPool {
            val delegate = base.poolFor(datasource)
            return object : ConnectionPool by delegate {
                override fun leaseConnection(): Connection = delegate.leaseConnection().also { leased.incrementAndGet() }
            }
        }
    }

    private companion object {
        const val TARGET = "q_target"
        const val DEPTH = 8
        const val BUDGET_S = 60L
        const val LOST_ACK_BUDGET_S = 8L
        const val TIMEOUT_CEILING_S = 30L
        const val LOCK_WAIT_S = 30
        const val WB_ROWS = 3L
        const val REPLACED_ID = 7
        const val OLD_ID_1 = 100
        const val OLD_ID_2 = 101
        const val SUPPLIED_ID = 41
        val COUNT_NODES = setOf("upd2", "upd0", "del2", "del0")
    }
}
