package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The r4 landing-review probes, retained verbatim as permanent regression guards (r5 "adopt this
 * guard"): the enrollment floor (DDL stays ordinary), lease hygiene on setup failure, the lost-ack
 * terminal-outcome rule, same-registration drain ordering on a real implicit-commit engine
 * (MySQL), and the configured drain budget — each a boundary the delivered suites measured only
 * at the unit seam or not at all. Sources at the r4 delivered commit e7a1d7b7 passed all eight;
 * the r5 lane keeps them green from here on.
 */
class LocalWriteLandingProbesTest {
    private fun planner(registry: DatasourceRegistry): LocalWritePlanner =
        LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8)

    private fun count(
        source: Datasource,
        table: String,
    ): Long =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    @Test
    fun `DDL only remains ordinary and executes without a transaction lease`() {
        runBlocking {
            val source = h2Datasource("ddl_only", emptyList()).copy(dialect = Dialect.POSTGRES)
            val registry = FakeDatasourceRegistry(mapOf(source.name to source))
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("ddl" to "CREATE TABLE ddl_survives (id INT)")),
                registry = registry,
                localWritePlanner = planner(registry),
            ).use { harness ->
                harness.executor
                    .execute(
                        Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("ddl", NodeType.DDL, source.name)))),
                    ).status shouldBe ExecutionStatus.SUCCESS
            }
            count(source, "ddl_survives") shouldBe 0L
        }
    }

    @Test
    fun `transaction setup failure returns the acquired lease`() {
        runBlocking {
            val source = h2Datasource("setup", emptyList()).copy(dialect = Dialect.POSTGRES)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val closes = AtomicInteger()
            lateinit var raw: Connection
            val registry =
                object : DatasourceRegistry by base {
                    override fun poolFor(datasource: Datasource): ConnectionPool =
                        object : ConnectionPool {
                            override val name = source.name

                            override fun leaseConnection(): Connection {
                                raw = DriverManager.getConnection(source.jdbcUrl, source.username, "")
                                return object : Connection by raw {
                                    override fun setAutoCommit(autoCommit: Boolean): Unit = throw SQLException("setup refused")

                                    override fun close() {
                                        closes.incrementAndGet()
                                        raw.close()
                                    }
                                }
                            }

                            override fun close() = Unit
                        }
                }
            ExecutorDispatcher.forConfig(ExecutorConfig(), maxThreads = 2).use { dispatcher ->
                val transaction =
                    LocalWriteTransaction(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        source.name,
                        registry,
                        ExecutorMetrics.inMemory(),
                        dispatcher,
                    )
                try {
                    shouldThrow<SQLException> { transaction.withParticipant { error("setup must refuse before work") } }
                    transaction.rollbackQuietly()
                    transaction.close()
                    closes.get() shouldBe 1
                } finally {
                    raw.close()
                }
            }
        }
    }

    @Test
    fun `lost commit acknowledgement keeps exactly one unknown terminal outcome after executor cleanup`() {
        runBlocking {
            val source = h2Datasource("lost_ack", listOf("CREATE TABLE tgt (id INT)")).copy(dialect = Dialect.POSTGRES)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val registry =
                object : DatasourceRegistry by base {
                    override fun poolFor(datasource: Datasource): ConnectionPool {
                        val pool = base.poolFor(datasource)
                        return object : ConnectionPool by pool {
                            override fun leaseConnection(): Connection {
                                val delegate = pool.leaseConnection()
                                return object : Connection by delegate {
                                    override fun commit() {
                                        delegate.commit()
                                        throw SQLException("acknowledgement lost after commit")
                                    }
                                }
                            }
                        }
                    }
                }
            val meters = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO tgt VALUES (1)")),
                registry = registry,
                metrics = ExecutorMetrics(meters),
                localWritePlanner = planner(registry),
            ).use { harness ->
                shouldThrow<DatapipelinesException> {
                    harness.executor.execute(
                        Fixtures.request(Fixtures.pipeline(listOf(Fixtures.node("write", NodeType.DML, source.name)))),
                    )
                }
            }
            // The second client proves rollback after commit cannot undo the durable write.
            count(source, "tgt") shouldBe 1L
            val outcomes =
                meters
                    .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
                    .counters()
                    .associate { it.id.getTag("outcome") to it.count() }
            outcomes shouldBe mapOf("started" to 1.0, "unknown" to 1.0)
        }
    }

    private fun mysqlSource(): Datasource {
        val mysql = SharedMysql.mysql
        return Datasource(
            name = "landing_mysql",
            displayName = "landing_mysql",
            dialect = Dialect.MYSQL,
            jdbcUrl = mysql.jdbcUrl,
            username = mysql.username,
            secret = mysql.password,
        )
    }

    private fun mysqlWriteback(
        mode: WriteMode,
        sameRegistration: Boolean,
        rows: Int,
    ) {
        runBlocking {
            val target = mysqlSource()
            val source = if (sameRegistration) target else target.copy(name = "landing_mysql_read")
            DriverManager.getConnection(target.jdbcUrl, target.username, target.secret).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS landing1033_source")
                    statement.execute("DROP TABLE IF EXISTS landing1033_target")
                    statement.execute("CREATE TABLE landing1033_source (id INT PRIMARY KEY) ENGINE=InnoDB")
                    statement.execute("CREATE TABLE landing1033_target (id INT PRIMARY KEY) ENGINE=InnoDB")
                    statement.execute("INSERT INTO landing1033_source VALUES " + (1..rows).joinToString(",") { "($it)" })
                }
            }
            val registry = FakeDatasourceRegistry(listOf(target, source).associateBy { it.name })
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("copy" to "SELECT id FROM landing1033_source ORDER BY id")),
                registry = registry,
                localWritePlanner = planner(registry),
            ).use { harness ->
                harness.executor
                    .execute(
                        Fixtures.request(
                            Fixtures.pipeline(
                                listOf(
                                    Fixtures.node(
                                        "copy",
                                        source = source.name,
                                        output = NodeOutput.Datasource(target.name, "landing1033_target", mode),
                                    ),
                                ),
                            ),
                        ),
                    ).status shouldBe ExecutionStatus.SUCCESS
            }
            count(target, "landing1033_target") shouldBe rows.toLong()
        }
    }

    @Test
    fun `MySQL same registration append drains input before writing the first batch`() {
        mysqlWriteback(WriteMode.APPEND, sameRegistration = true, rows = 1001)
    }

    @Test
    fun `MySQL same registration replace drains input before clearing the target`() {
        mysqlWriteback(WriteMode.REPLACE, sameRegistration = true, rows = 1)
    }

    @Test
    fun `MySQL independent read registration remains usable beside the writer`() {
        mysqlWriteback(WriteMode.APPEND, sameRegistration = false, rows = 1001)
    }

    private fun configuredDrainRun(limit: Long): Pair<ExecutionStatus?, Long> =
        runBlocking {
            val source =
                h2Datasource(
                    "configured_drain",
                    listOf(
                        "CREATE TABLE src (payload VARCHAR(200))",
                        "CREATE TABLE tgt (payload VARCHAR(200))",
                        "INSERT INTO src VALUES (REPEAT('x', 128))",
                    ),
                ).copy(dialect = Dialect.POSTGRES)
            val registry = FakeDatasourceRegistry(mapOf(source.name to source))
            val config = ExecutorConfig(result = ResultConfig(maxSizeBytes = limit))
            val status =
                ExecutorHarness(
                    templateEngine = Fixtures.templateEngine(mapOf("copy" to "SELECT payload FROM src")),
                    registry = registry,
                    config = config,
                    resultStore = InMemoryResultStore(config.result),
                    localWritePlanner = planner(registry),
                ).use { harness ->
                    runCatching {
                        harness.executor
                            .execute(
                                Fixtures.request(
                                    Fixtures.pipeline(
                                        listOf(
                                            Fixtures.node(
                                                "copy",
                                                source = source.name,
                                                output = NodeOutput.Datasource(source.name, "tgt", WriteMode.APPEND),
                                            ),
                                        ),
                                    ),
                                ),
                            ).status
                    }.getOrNull()
                }
            status to count(source, "tgt")
        }

    @Test
    fun `same registration drain respects a lower configured byte limit before durable writes`() {
        val (status, rows) = configuredDrainRun(32)
        rows shouldBe 0L
        status shouldBe null
    }

    @Test
    fun `same registration drain accepts rows below the configured byte limit control`() {
        val (status, rows) = configuredDrainRun(1024)
        status shouldBe ExecutionStatus.SUCCESS
        rows shouldBe 1L
    }
}
