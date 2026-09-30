package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.LakeBrokenTable
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The streaming commit in `datasourceQuery`'s finally (#336 D1).
 *
 * `SourceStreaming.enable` takes a Postgres source connection out of autocommit so the cursor
 * streams; the finally commits unconditionally, because a multi-statement DQL node's earlier
 * statements committed as they ran under autocommit and the commit restores that net effect.
 * The defect this class pins: that commit's own refusal was discarded — a node whose side
 * effects were NOT restored to autocommit's net effect reported SUCCESS.
 *
 * The connection double is a real H2 (MODE=PostgreSQL) connection behind a delegating wrapper
 * whose `commit()` refuses: the statement really executes, the cursor really drains, and only
 * the finalization lies. The datasource declares dialect POSTGRES because that is the one
 * dialect `SourceStreaming.enable` switches — the flag the finally commits on.
 */
class DatasourceQueryCommitTest {
    /** Counts commit attempts; the failure path's commit must still have been ATTEMPTED. */
    private val commitsAttempted = AtomicInteger()

    /** A connection that is real for everything but `commit()`, which refuses. */
    private class RefusingCommitConnection(
        delegate: Connection,
        private val commits: AtomicInteger,
    ) : Connection by delegate {
        override fun commit() {
            commits.incrementAndGet()
            throw SQLException("commit refused by the double", "08000")
        }
    }

    /** A one-connection pool over the refusing double. */
    private class RefusingCommitPool(
        private val datasource: Datasource,
        private val commits: AtomicInteger,
    ) : ConnectionPool {
        override val name: String get() = datasource.name

        override fun leaseConnection(): Connection =
            RefusingCommitConnection(
                DriverManager.getConnection(datasource.jdbcUrl, datasource.username, datasource.secret ?: ""),
                commits,
            )

        override fun close() = Unit
    }

    /** The registry the harness needs; every read answers the one fixture datasource. */
    private class SingleDatasourceRegistry(
        private val datasource: Datasource,
        private val commits: AtomicInteger,
    ) : DatasourceRegistry {
        override fun list(dialect: Dialect?): List<Datasource> = listOf(datasource)

        override fun get(name: String): Datasource? = datasource.takeIf { it.name == name }

        override fun getVisible(
            name: String,
            workspaceId: UUID,
        ): Datasource? = get(name)

        override fun getLive(name: String): Datasource? = get(name)

        override fun isReadonlyLive(name: String): Boolean? = get(name)?.isReadonly

        override fun exists(name: String): Boolean = get(name) != null

        override fun save(
            datasource: Datasource,
            actor: UUID,
        ): Datasource = datasource

        override fun validate(datasource: Datasource): ValidationResult = ValidationResult.ok()

        override fun delete(name: String): DeleteResult = DeleteResult(true, name)

        override fun poolFor(datasource: Datasource): ConnectionPool = RefusingCommitPool(datasource, commits)

        override fun lakeBrokenTables(datasourceName: String): List<LakeBrokenTable> = emptyList()

        override fun testConnection(name: String): TestResult? = TestResult(true, Instant.now())
    }

    /** A POSTGRES-declared datasource on a real H2 in PostgreSQL mode, one table, one row. */
    private fun postgresDialectSource(name: String): Datasource {
        val url = "jdbc:h2:mem:commit_${name}_${UUID.randomUUID().toString().replace(
            "-",
            "",
        )};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (n INT)")
                statement.execute("INSERT INTO t VALUES (1)")
            }
        }
        return Datasource(
            name = name,
            displayName = name,
            dialect = Dialect.POSTGRES,
            jdbcUrl = url,
            username = "sa",
        )
    }

    @Test
    fun `a refused commit fails an otherwise successful node with the catalogued commit code`() =
        runBlocking<Unit> {
            val source = postgresDialectSource("commit_success")
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(mapOf("a" to "SELECT n FROM t")),
                registry = SingleDatasourceRegistry(source, commitsAttempted),
            ).use { h ->
                val nodes = listOf(Fixtures.node("a", source = "commit_success", output = NodeOutput.Caller))
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))
                }

                commitsAttempted.get() shouldBe 1
                val failed = h.emitter.allOf<co.datapipelines.events.NodeFailed>().single()
                failed.error.code shouldBe "pipeline.node.commit_failed"
                failed.error.details["sql_state"] shouldBe "08000"
            }
        }

    /** The suppression contract of [SourceCommit.settle], asserted directly. */
    @Test
    fun `a refused commit under a primary failure is suppressed, and the primary propagates untouched`() {
        val primary = SQLException("the primary query failure")
        val commitFailure = SQLException("commit refused by the double", "08000")
        val url = postgresDialectSource("commit_unit").jdbcUrl
        val conn =
            object : Connection by DriverManager.getConnection(url, "sa", "") {
                override fun commit() {
                    commitsAttempted.incrementAndGet()
                    throw commitFailure
                }
            }

        // No primary: the refusal becomes the node's own failure — the catalogued code, the
        // commit failure as cause.
        val signal =
            shouldThrow<NodeFailedSignal> {
                SourceCommit.settle(conn, tookOutOfAutocommit = true, primary = null)
            }
        signal.error.code shouldBe "pipeline.node.commit_failed"
        signal.error.details["sql_state"] shouldBe "08000"
        signal.cause shouldBe commitFailure

        // A primary in flight: the refusal rides along suppressed; the primary is unchanged.
        SourceCommit.settle(conn, tookOutOfAutocommit = true, primary = primary)
        primary.suppressed shouldBe arrayOf<Throwable>(commitFailure)

        // Streaming never engaged: no commit is attempted at all.
        val before = commitsAttempted.get()
        SourceCommit.settle(conn, tookOutOfAutocommit = false, primary = primary)
        commitsAttempted.get() shouldBe before
    }
}
