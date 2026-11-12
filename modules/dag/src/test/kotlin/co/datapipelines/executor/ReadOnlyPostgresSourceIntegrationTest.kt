package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * #463 A.3 — through the REAL executor, a DQL node on a read-only run (`ExecuteRequest.readOnly`,
 * what dashboard sources and published GET endpoints send) that attempts a write is refused BY
 * POSTGRES, while the same text on a writable run writes. The four cases of [ReadOnlySourceCases]
 * plus the entry-failure case: a lease the mode cannot enter fails closed with the connect code and
 * leaks nothing from a max-size-1 pool.
 *
 * The write-shaped text is a data-modifying CTE: one statement, run by the node's ordinary query
 * path, that inserts AND returns its row.
 */
internal class ReadOnlyPostgresSourceIntegrationTest : ReadOnlySourceCases() {
    override val datasource =
        Datasource(
            name = DATASOURCE,
            displayName = DATASOURCE,
            dialect = Dialect.POSTGRES,
            jdbcUrl = db.jdbcUrl,
            username = db.username,
            secret = db.password,
            properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 1, "minimumIdle" to 1, "connectionTimeout" to 3000)),
        )

    override val writeSql = "WITH w AS (INSERT INTO ro_t VALUES (1) RETURNING n) SELECT n FROM w"

    override fun connect(): Connection = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

    override fun refusedByDatabase(details: Map<String, Any?>): Boolean = details["sql_state"] == "25006"

    @Test
    fun `an entry failure fails closed with the connect code and the max-size-1 pool leaks no lease`() =
        runBlocking<Unit> {
            ConnectionPoolManager.buildHikariPool(datasource).use { real ->
                // The seam: every lease refuses setReadOnly(true), the mode's first Postgres step
                // after autocommit off — so its undo runs and must leave the lease reusable.
                val faulty =
                    object : ConnectionPool by real {
                        override fun leaseConnection(): Connection = refusingReadOnly(real.leaseConnection())
                    }
                val registry =
                    object : DatasourceRegistry by FakeDatasourceRegistry(mapOf(DATASOURCE to datasource)) {
                        override fun poolFor(datasource: Datasource): ConnectionPool = faulty
                    }
                ExecutorHarness(templateEngine = Fixtures.templateEngine(mapOf("q" to SELECT_SQL)), registry = registry).use { h ->
                    val failure =
                        shouldThrow<PipelineExecutionFailed> {
                            h.executor.execute(readOnlyRequest())
                        }
                    failure.errorCode shouldBe PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED
                }
                withClue("the failed lease went back to the max-size-1 pool (a leak times this lease out after 3 s)") {
                    real.leaseConnection().use { next ->
                        next.isReadOnly shouldBe false
                        next.autoCommit shouldBe true
                        next.createStatement().use { it.executeQuery("SELECT 1").use { rows -> rows.next() shouldBe true } }
                    }
                }
            }
        }

    /** A delegating lease that refuses `setReadOnly(true)`, and passes every other call through. */
    private fun refusingReadOnly(lease: Connection): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "setReadOnly" && args?.firstOrNull() == true) throw SQLException("planted fault: setReadOnly(true)")
            try {
                method.invoke(lease, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection

    private companion object {
        const val DATASOURCE = "pg_ro"

        /** A scratch database on the module's shared container: the suite's own source. */
        val db = SharedPostgres.scratchDatabase("read_only_source")
    }
}

/**
 * The four executor cases #463 requires of every dialect the read-only mode enforces, run through
 * the REAL executor on one DQL node whose output is the caller:
 *
 *  - **(i)** writable baseline — the write-shaped text ([writeSql]) succeeds on a writable run,
 *    returns its row, and the row persists (non-vacuity: the text really writes);
 *  - **(ii)** the same node on `readOnly = true` fails with `pipeline.node.query_execution_failed`,
 *    and the driver's error is the DATABASE's refusal ([refusedByDatabase] over the error details);
 *  - **(iii)** the persistent data is unchanged after (ii);
 *  - **(iv)** a plain `SELECT` succeeds on `readOnly = true` and returns the committed row.
 *
 * Each case starts from table `ro_t (n)` holding exactly one committed row.
 */
internal abstract class ReadOnlySourceCases {
    abstract val datasource: Datasource

    /** One SQL text that writes a row into `ro_t` AND returns at least one row. */
    abstract val writeSql: String

    /** A direct connection for setup and read-back, outside the executor. */
    abstract fun connect(): Connection

    /** Whether the failed node's error details carry the database's own refusal. */
    abstract fun refusedByDatabase(details: Map<String, Any?>): Boolean

    /** Dialect-specific setup after the table exists (MySQL's writing function). */
    open fun setup(connection: Connection) = Unit

    @BeforeEach
    fun resetTable() {
        connect().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TABLE IF EXISTS ro_t")
                s.execute("CREATE TABLE ro_t (n INTEGER)")
                s.execute("INSERT INTO ro_t VALUES (0)")
            }
            setup(c)
            if (!c.autoCommit) c.commit()
        }
    }

    @Test
    fun `(i) writable baseline - the write-shaped DQL writes and returns its row`() =
        runBlocking<Unit> {
            val result = run(writeSql, Fixtures.request(pipeline()))
            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single().rowsOut shouldBe 1
            withClue("non-vacuity: the text wrote on the writable run") { count() shouldBe 2L }
        }

    @Test
    fun `(ii) on readOnly the same DQL is refused by the database with query_execution_failed`() =
        runBlocking<Unit> {
            val failure = shouldThrow<PipelineExecutionFailed> { run(writeSql, readOnlyRequest()) }
            failure.errorCode shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
            withClue("the refusal is the database's: ${failure.errorDetails}") { refusedByDatabase(failure.errorDetails) shouldBe true }
        }

    @Test
    fun `(iii) the persistent data is unchanged after the refused run`() =
        runBlocking<Unit> {
            shouldThrow<PipelineExecutionFailed> { run(writeSql, readOnlyRequest()) }
            count() shouldBe 1L
        }

    @Test
    fun `(iv) a plain SELECT succeeds on readOnly`() =
        runBlocking<Unit> {
            val result = run(SELECT_SQL, readOnlyRequest())
            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single().rowsOut shouldBe 1
        }

    protected fun pipeline() = Fixtures.pipeline(listOf(Fixtures.node("q", source = datasource.name, output = NodeOutput.Caller)))

    protected fun readOnlyRequest(): ExecuteRequest = Fixtures.request(pipeline()).copy(readOnly = true)

    protected suspend fun run(
        sql: String,
        request: ExecuteRequest,
    ): ExecutionResult =
        ExecutorHarness(
            templateEngine = Fixtures.templateEngine(mapOf("q" to sql)),
            registry = FakeDatasourceRegistry(mapOf(datasource.name to datasource)),
        ).use { h -> h.executor.execute(request) }

    protected fun count(): Long =
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM ro_t").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    protected companion object {
        const val SELECT_SQL = "SELECT n FROM ro_t"
    }
}
