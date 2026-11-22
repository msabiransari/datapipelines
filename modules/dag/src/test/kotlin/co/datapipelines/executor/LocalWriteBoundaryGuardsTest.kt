package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The r4 landing-review boundary guards, retained as permanent regression guards (r5 "adopt this
 * guard"), plus the r5 additions they flagged: pool/deadline/cancellation/readonly are observed at
 * REAL JDBC boundaries — an exhausted Hikari pool, a deadline cancelling an in-flight participant
 * statement, a live readonly flip refusing the write-back — and now a REAL implicit-commit engine
 * (MySQL) proving a REPLACE write-back followed by a later required-node failure restores the
 * target's pre-existing rows: had the drain used an implicit-commit TRUNCATE, those rows would be
 * gone for good no matter what the executor rolled back.
 */
class LocalWriteBoundaryGuardsTest {
    @Test
    fun `an exhausted real pool fails executor acquisition without a direct Hikari dependency`() {
        runBlocking {
            val source = exhaustedPoolSource()
            DriverManager.getConnection(source.jdbcUrl, source.username, source.secret).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS landing1033_pool")
                    statement.execute("CREATE TABLE landing1033_pool (id INT) ENGINE=InnoDB")
                }
            }
            ConnectionPoolManager.buildHikariPool(source).use { pool ->
                val base = FakeDatasourceRegistry(mapOf(source.name to source))
                val registry =
                    object : DatasourceRegistry by base {
                        override fun poolFor(datasource: Datasource): ConnectionPool = pool
                    }
                val meters = SimpleMeterRegistry()
                pool.leaseConnection().use {
                    ExecutorHarness(
                        templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO landing1033_pool VALUES (1)")),
                        registry = registry,
                        metrics = ExecutorMetrics(meters),
                        localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
                    ).use { harness ->
                        val started = System.nanoTime()
                        val failure =
                            shouldThrow<PipelineExecutionFailed> {
                                harness.executor.execute(
                                    Fixtures.request(
                                        Fixtures.pipeline(
                                            listOf(
                                                Fixtures.node("write", NodeType.DML, source.name),
                                            ),
                                        ),
                                    ),
                                )
                            }
                        failure.errorCode shouldBe PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
                        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                        (elapsedMillis >= pool.connectionTimeout.toMillis()) shouldBe true
                    }
                }
                count(source, "landing1033_pool") shouldBe 0L
                outcomes(meters) shouldBe emptyMap()
            }
        }
    }

    @Test
    fun `node wall clock deadline cancels an active participant query and rolls back prior write`() {
        runBlocking {
            val source =
                h2Datasource("deadline_review", listOf("CREATE TABLE tgt (id INT)"))
                    .copy(dialect = Dialect.POSTGRES)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val queryEntered = AtomicBoolean()
            val active = AtomicBoolean()
            val cancelsWhileActive = AtomicInteger()
            val registry =
                object : DatasourceRegistry by base {
                    override fun poolFor(datasource: Datasource): ConnectionPool {
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
                                                active.set(true)
                                                try {
                                                    return statement.executeQuery(sql)
                                                } finally {
                                                    active.set(false)
                                                }
                                            }

                                            override fun cancel() {
                                                if (active.get()) cancelsWhileActive.incrementAndGet()
                                                statement.cancel()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            val meters = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO tgt VALUES (1)", "slow" to Fixtures.SLOW_SQL)),
                registry = registry,
                metrics = ExecutorMetrics(meters),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
            ).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node("write", NodeType.DML, source.name),
                        Fixtures.node("slow", source = source.name, dependsOn = listOf("write"), timeoutSeconds = 1),
                    )
                val failure =
                    shouldThrow<PipelineExecutionFailed> {
                        harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }
                failure.errorCode shouldBe PipelineErrorCodes.Node.TIMEOUT
            }
            queryEntered.get() shouldBe true
            (cancelsWhileActive.get() > 0) shouldBe true
            count(source, "tgt") shouldBe 0L
            outcomes(meters) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `live readonly change before writeback refuses and rolls back earlier family write`() {
        runBlocking {
            val source =
                h2Datasource("readonly_review", listOf("CREATE TABLE tgt (id INT)", "CREATE TABLE wb (id INT)"))
                    .copy(dialect = Dialect.POSTGRES)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val revoked = AtomicBoolean()
            val registry =
                object : DatasourceRegistry by base {
                    override fun isReadonlyLive(name: String): Boolean? = revoked.get()

                    override fun poolFor(datasource: Datasource): ConnectionPool {
                        val pool = base.poolFor(datasource)
                        return object : ConnectionPool by pool {
                            override fun leaseConnection(): Connection {
                                val connection = pool.leaseConnection()
                                return object : Connection by connection {
                                    override fun prepareStatement(sql: String): PreparedStatement {
                                        val statement = connection.prepareStatement(sql)
                                        return object : PreparedStatement by statement {
                                            override fun executeUpdate(): Int {
                                                val result = statement.executeUpdate()
                                                revoked.set(true)
                                                return result
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            val meters = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO tgt VALUES (1)", "copy" to "SELECT id FROM tgt")),
                registry = registry,
                metrics = ExecutorMetrics(meters),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
            ).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node("write", NodeType.DML, source.name),
                        Fixtures.node(
                            "copy",
                            source = source.name,
                            dependsOn = listOf("write"),
                            output = NodeOutput.Datasource(source.name, "wb", WriteMode.APPEND),
                        ),
                    )
                val failure =
                    shouldThrow<PipelineExecutionFailed> {
                        harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }
                failure.errorCode shouldBe PipelineErrorCodes.Node.DATASOURCE_READONLY
            }
            revoked.get() shouldBe true
            count(source, "tgt") shouldBe 0L
            count(source, "wb") shouldBe 0L
            outcomes(meters) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    /**
     * R8 (dag seam) — a VISIBILITY grant revoked mid-run refuses the write-back as
     * `datasource_not_found`, distinct from the readonly flag (above) and soft deletion. The
     * CACHED read ([DatasourceRegistry.getVisible], the planner + enlist path) still answers the
     * entry, while the LIVE read ([DatasourceRegistry.getVisibleLive], the write-back shell's
     * write-time check) is gone — exactly what a `datasource_workspaces` row deleted after enlist
     * produces. The refusal lands before any write to the target, and the first write rolls back.
     */
    @Test
    fun `a visibility grant revoked mid-run refuses the write-back and rolls the family back`() {
        runBlocking {
            val source =
                h2Datasource("grant_review", listOf("CREATE TABLE tgt (id INT)", "CREATE TABLE wb (id INT)"))
                    .copy(dialect = Dialect.POSTGRES)
            val base = FakeDatasourceRegistry(mapOf(source.name to source))
            val revoked = AtomicBoolean()
            val registry =
                object : DatasourceRegistry by base {
                    override fun getVisibleLive(
                        name: String,
                        workspaceId: UUID,
                    ): Datasource? = if (revoked.get()) null else base.getVisible(name, workspaceId)

                    override fun poolFor(datasource: Datasource): ConnectionPool {
                        val pool = base.poolFor(datasource)
                        return object : ConnectionPool by pool {
                            override fun leaseConnection(): Connection {
                                val connection = pool.leaseConnection()
                                return object : Connection by connection {
                                    override fun prepareStatement(sql: String): PreparedStatement {
                                        val statement = connection.prepareStatement(sql)
                                        return object : PreparedStatement by statement {
                                            override fun executeUpdate(): Int {
                                                val result = statement.executeUpdate()
                                                // Revoke the grant after the FIRST family write.
                                                revoked.set(true)
                                                return result
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            val meters = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("write" to "INSERT INTO tgt VALUES (1)", "copy" to "SELECT id FROM tgt")),
                registry = registry,
                metrics = ExecutorMetrics(meters),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
            ).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node("write", NodeType.DML, source.name),
                        Fixtures.node(
                            "copy",
                            source = source.name,
                            dependsOn = listOf("write"),
                            output = NodeOutput.Datasource(source.name, "wb", WriteMode.APPEND),
                        ),
                    )
                val failure =
                    shouldThrow<PipelineExecutionFailed> {
                        harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                    }
                failure.errorCode shouldBe PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND
            }
            revoked.get() shouldBe true
            count(source, "tgt") shouldBe 0L
            count(source, "wb") shouldBe 0L
            outcomes(meters) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    /**
     * The r5 addition: REPLACE + later required failure on a REAL implicit-commit engine (MySQL).
     *
     * The target starts with pre-existing rows (90, 91); the copy REPLACEs them with the source's
     * one row; a later required node fails. Had the REPLACE drain cleared the target with an
     * implicit-commit statement (TRUNCATE), the DELETE would already be committed and the old rows
     * unrecoverable — MySQL itself is the oracle here, read from a second connection. The exact
     * ids (not just the count) prove the rows are the ORIGINALS, not fresh inserts.
     */
    @Test
    fun `a real implicit-commit driver restores pre-existing rows when a later required node fails`() {
        runBlocking {
            val mysql = SharedMysql.mysql
            val source =
                Datasource(
                    name = "implicit_commit_replace",
                    displayName = "implicit_commit_replace",
                    dialect = Dialect.MYSQL,
                    jdbcUrl = mysql.jdbcUrl,
                    username = mysql.username,
                    secret = mysql.password,
                )
            DriverManager.getConnection(source.jdbcUrl, source.username, source.secret).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE IF EXISTS r5_replace_src")
                    statement.execute("DROP TABLE IF EXISTS r5_replace_tgt")
                    statement.execute("CREATE TABLE r5_replace_src (id INT PRIMARY KEY) ENGINE=InnoDB")
                    statement.execute("CREATE TABLE r5_replace_tgt (id INT PRIMARY KEY) ENGINE=InnoDB")
                    statement.execute("INSERT INTO r5_replace_src VALUES (1)")
                    statement.execute("INSERT INTO r5_replace_tgt VALUES (90), (91)")
                }
            }
            val registry = FakeDatasourceRegistry(mapOf(source.name to source))
            val meters = SimpleMeterRegistry()
            ExecutorHarness(
                templateEngine =
                    Fixtures.templateEngine(
                        mapOf(
                            "copy" to "SELECT id FROM r5_replace_src ORDER BY id",
                            "boom" to "INSERT INTO r5_no_such_table VALUES (1)",
                        ),
                    ),
                registry = registry,
                metrics = ExecutorMetrics(meters),
                localWritePlanner = LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registry, 8),
            ).use { harness ->
                val nodes =
                    listOf(
                        Fixtures.node(
                            "copy",
                            source = source.name,
                            output = NodeOutput.Datasource(source.name, "r5_replace_tgt", WriteMode.REPLACE),
                        ),
                        Fixtures.node("boom", type = NodeType.DML, source = source.name, dependsOn = listOf("copy")),
                    )
                shouldThrow<PipelineExecutionFailed> {
                    harness.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }
            }
            ids(source, "r5_replace_tgt") shouldBe listOf(90L, 91L)
            count(source, "r5_replace_src") shouldBe 1L
            outcomes(meters) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    /** The single-connection MySQL registration whose pool the exhausted-pool case exhausts. */
    private fun exhaustedPoolSource(): Datasource {
        val mysql = SharedMysql.mysql
        return Datasource(
            name = "real_pool_review",
            displayName = "real_pool_review",
            dialect = Dialect.MYSQL,
            jdbcUrl = mysql.jdbcUrl,
            username = mysql.username,
            secret = mysql.password,
            properties =
                DatasourceProperties(
                    hikari =
                        mapOf(
                            "maximumPoolSize" to 1,
                            "minimumIdle" to 0,
                            "connectionTimeout" to 250,
                        ),
                ),
        )
    }

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

    private fun ids(
        source: Datasource,
        table: String,
    ): List<Long> =
        DriverManager.getConnection(source.jdbcUrl, source.username, source.secret ?: "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM $table ORDER BY id").use { rows ->
                    generateSequence { if (rows.next()) rows.getLong(1) else null }.toList()
                }
            }
        }

    private fun outcomes(meters: SimpleMeterRegistry): Map<String?, Double> =
        meters
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .associate { it.id.getTag("outcome") to it.count() }
}
