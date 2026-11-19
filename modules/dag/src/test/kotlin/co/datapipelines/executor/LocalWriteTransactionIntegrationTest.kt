package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #1033's lane-specific executable regression class (brief "Gates"): the write-transaction
 * contract of design §3/§9 on a REAL customer engine — this module's shared PostgreSQL 16
 * container — with every business observable read from a SECOND connection the executor never
 * touched (plain `DriverManager`), never from node_stats or the participant connection's view.
 *
 * ## Why the H2 suites are not enough here
 *
 * `LocalWriteGateTest` and `LocalWriteTransactionTest` prove the same state machine over H2
 * pools labelled POSTGRES. The §9 contract additionally needs the property only a genuinely
 * eligible engine can witness: the family's uncommitted writes invisible to an INDEPENDENT
 * client mid-run while a later same-registration read INSIDE a pinned child sees them —
 * Postgres MVCC under `autoCommit=false` — plus real driver commit/rollback for a plan whose
 * writer sits in a pinned child and whose write-back targets the same registration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalWriteTransactionIntegrationTest {
    /** A: the family's single durable registration. B: a second registration on the SAME server, reads only. */
    private val dbA = SharedPostgres.scratchDatabase("local_write_tx_a")
    private val dbB = SharedPostgres.scratchDatabase("local_write_tx_b")

    @BeforeAll
    fun seed() {
        withSecondConnection(dbA) { statement ->
            statement.execute("CREATE TABLE orders (id bigint PRIMARY KEY, note text NOT NULL)")
            statement.execute("CREATE TABLE order_lines (order_id text NOT NULL, note text NOT NULL)")
            statement.execute("CREATE TABLE seen (seen_count int NOT NULL)")
            statement.execute("CREATE TABLE wb_src (id bigint NOT NULL)")
            statement.execute("CREATE TABLE wb_target (id bigint NOT NULL)")
            statement.execute("INSERT INTO wb_src SELECT g FROM generate_series(1, $WB_ROWS) AS g")
        }
        withSecondConnection(dbB) { statement ->
            statement.execute("CREATE TABLE inventory (sku text PRIMARY KEY, qty int NOT NULL)")
            statement.execute("INSERT INTO inventory VALUES ('SKU-1', 5)")
        }
    }

    @BeforeEach
    fun clean() =
        withSecondConnection(dbA) { statement ->
            statement.execute("DELETE FROM orders")
            statement.execute("DELETE FROM order_lines")
            statement.execute("DELETE FROM seen")
            statement.execute("DELETE FROM wb_target")
        }

    @Test
    fun `the family commits together, on one participant connection, and only the family saw the uncommitted write`() {
        runBlocking {
            val counter = RegistrationLeaseCounter(baseRegistry(), REGISTRATION_A)
            val midRunCounts = mutableListOf<Pair<String, Long>>()
            val meterRegistry = SimpleMeterRegistry()

            harness(counter, ExecutorMetrics(meterRegistry), boom = false, afterChild = {
                // §9's mid-run observation, from an INDEPENDENT client while the family is still
                // open: NEITHER the root's write nor the child's read-marker is visible yet.
                midRunCounts += "orders" to countFromSecondConnection("orders")
                midRunCounts += "seen" to countFromSecondConnection("seen")
            }).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(familyGraph(boom = false)))).status shouldBe
                    ExecutionStatus.SUCCESS
            }

            // After the root's single commit, everything the family wrote is durable TOGETHER:
            // the root insert, the same-registration write-back, and both child writes.
            countFromSecondConnection("orders") shouldBe 1L
            countFromSecondConnection("order_lines") shouldBe 2L
            seenCountFromSecondConnection() shouldBe 1
            midRunCounts shouldContainExactly listOf("orders" to 0L, "seen" to 0L)
            // §9 "counting one participant connection": root write, write-back and both child
            // DMLs shared ONE lease on the registration — and it went back to the pool once.
            counter.leased.get() shouldBe 1
            counter.closed.get() shouldBe 1
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `a failing last required node rolls every family write back for an independent client`() {
        runBlocking {
            val counter = RegistrationLeaseCounter(baseRegistry(), REGISTRATION_A)
            val meterRegistry = SimpleMeterRegistry()

            harness(counter, ExecutorMetrics(meterRegistry), boom = true).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(familyGraph(boom = true))))
                }
            }

            // §9: "A second connection observes neither write" — root, write-back and child.
            countFromSecondConnection("orders") shouldBe 0L
            countFromSecondConnection("order_lines") shouldBe 0L
            countFromSecondConnection("seen") shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `revoking the live grant after the first write rolls that write back`() {
        runBlocking {
            val revocable = RevocableRegistry(baseRegistry(), REGISTRATION_A)
            val meterRegistry = SimpleMeterRegistry()

            harness(revocable, ExecutorMetrics(meterRegistry), boom = false, beforeChild = { revocable.revoke() }).use { h ->
                // The child's nodes can no longer resolve the registration (§A.3's live grant
                // change, revoked after the root's first write): the run fails, and the root's
                // write — durable by now had the feature been off — rolls back with the family.
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(familyGraph(boom = false))))
                }
            }

            countFromSecondConnection("orders") shouldBe 0L
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    @Test
    fun `a same-registration write-back drains its streaming source and commits (R2)`() {
        runBlocking {
            val counter = RegistrationLeaseCounter(baseRegistry(), REGISTRATION_A)
            val meterRegistry = SimpleMeterRegistry()

            harness(counter, ExecutorMetrics(meterRegistry), boom = false).use { h ->
                h.executor.execute(Fixtures.request(Fixtures.pipeline(sameRegGraph(boom = false)))).status shouldBe
                    ExecutionStatus.SUCCESS
            }

            // The same-registration source (a streaming portal on the participant connection) was
            // drained and closed before the write reused the connection: every row landed, on ONE
            // participant lease, committed at the root.
            countFromSecondConnection("wb_target") shouldBe WB_ROWS
            counter.leased.get() shouldBe 1
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "committed" to 1.0)
        }
    }

    @Test
    fun `a same-registration write-back rolls back on a later failure (R2)`() {
        runBlocking {
            val counter = RegistrationLeaseCounter(baseRegistry(), REGISTRATION_A)
            val meterRegistry = SimpleMeterRegistry()

            harness(counter, ExecutorMetrics(meterRegistry), boom = true).use { h ->
                shouldThrow<PipelineExecutionFailed> {
                    h.executor.execute(Fixtures.request(Fixtures.pipeline(sameRegGraph(boom = true))))
                }
            }

            countFromSecondConnection("wb_target") shouldBe 0L
            counter.leased.get() shouldBe 1
            outcomes(meterRegistry) shouldBe mapOf("started" to 1.0, "rolled_back" to 1.0)
        }
    }

    // -- the family ------------------------------------------------------------------

    /** Reads on B beside two-plus writes on A (root DML, same-registration write-back, child DMLs). */
    private fun familyGraph(boom: Boolean): List<Node> =
        listOf(
            Fixtures.node("read_b", source = REGISTRATION_B, output = NodeOutput.Tempdb("stg_b")),
            Fixtures.node("write_root", type = NodeType.DML, source = REGISTRATION_A, dependsOn = listOf("read_b")),
            Fixtures.node(
                "wb",
                source = "tempdb",
                output = NodeOutput.Datasource(REGISTRATION_A, "order_lines", WriteMode.APPEND),
                dependsOn = listOf("read_b"),
            ),
            Fixtures
                .node("child_ref", type = NodeType.PIPELINE, source = "", dependsOn = listOf("write_root"))
                .copy(pipeline = CHILD_REF),
        ) + if (boom) listOf(Fixtures.node("boom", source = "tempdb", dependsOn = listOf("child_ref"))) else emptyList()

    /** The pinned child: a later same-registration READ (recorded in `seen`) and its own write. */
    private fun childBody(): Pipeline =
        Fixtures.pipeline(
            listOf(
                Fixtures.node("child_seen", type = NodeType.DML, source = REGISTRATION_A),
                Fixtures.node("child_write", type = NodeType.DML, source = REGISTRATION_A),
            ),
        )

    /** A single DQL node whose SOURCE and write-back TARGET are the same registration (R2). */
    private fun sameRegGraph(boom: Boolean): List<Node> =
        listOf(
            Fixtures.node(
                "same_reg_wb",
                source = REGISTRATION_A,
                output = NodeOutput.Datasource(REGISTRATION_A, "wb_target", WriteMode.APPEND),
            ),
        ) + if (boom) listOf(Fixtures.node("boom", source = "tempdb", dependsOn = listOf("same_reg_wb"))) else emptyList()

    private fun sql(boom: Boolean): Map<String, String> =
        buildMap {
            put("read_b", "SELECT sku, qty FROM inventory ORDER BY sku")
            put("write_root", "INSERT INTO orders (id, note) VALUES (1, 'root')")
            put("wb", "SELECT sku AS order_id, 'from-b' AS note FROM stg_b ORDER BY order_id")
            put("child_seen", "INSERT INTO seen (seen_count) SELECT COUNT(*) FROM orders")
            put("child_write", "INSERT INTO order_lines (order_id, note) VALUES ('1', 'child')")
            put("same_reg_wb", "SELECT id FROM wb_src ORDER BY id")
            if (boom) put("boom", "SELECT * FROM table_that_does_not_exist")
        }

    /** The real-engine assembly: a planner that walks the pinned child, and a runner that re-enters the executor. */
    private fun harness(
        registry: DatasourceRegistry,
        metrics: ExecutorMetrics,
        boom: Boolean,
        beforeChild: () -> Unit = {},
        afterChild: () -> Unit = {},
    ): ExecutorHarness {
        val executorRef = AtomicReference<PipelineExecutor>()
        val child = childBody()
        val harness =
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql(boom)),
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
                        maxCompositionDepth = DEPTH,
                    ),
                subPipelineRunner =
                    SubPipelineRunner { node, ctx ->
                        beforeChild()
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
                                    // §3.3: the child INHERITS the family's transaction; it never plans or settles.
                                    localWriteTransaction = ctx.localWriteTransaction,
                                ),
                            )
                        afterChild()
                        NodeResult.of(node.id, 0, Instant.now(), childExecutionId = childResult.executionId)
                    },
            )
        executorRef.set(harness.executor)
        return harness
    }

    private fun baseRegistry(): DatasourceRegistry =
        FakeDatasourceRegistry(
            mapOf(
                REGISTRATION_A to registration(REGISTRATION_A, dbA),
                REGISTRATION_B to registration(REGISTRATION_B, dbB),
            ),
        )

    private fun registration(
        name: String,
        db: SharedPostgres.DatabaseRef,
    ): Datasource =
        Datasource(
            name = name,
            displayName = name,
            dialect = Dialect.POSTGRES,
            jdbcUrl = db.jdbcUrl,
            username = db.username,
            secret = db.password,
        )

    // -- independent clients (the verdict is the database's, never the executor's word) -------

    private fun withSecondConnection(
        db: SharedPostgres.DatabaseRef,
        block: (java.sql.Statement) -> Unit,
    ) {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use(block)
        }
    }

    private fun countFromSecondConnection(table: String): Long =
        DriverManager.getConnection(dbA.jdbcUrl, dbA.username, dbA.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> singleLong(rs) }
            }
        }

    private fun seenCountFromSecondConnection(): Int =
        DriverManager.getConnection(dbA.jdbcUrl, dbA.username, dbA.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT seen_count FROM seen").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun singleLong(rs: ResultSet): Long {
        rs.next()
        return rs.getLong(1)
    }

    private fun outcomes(registry: SimpleMeterRegistry): Map<String, Double> =
        registry
            .find(ExecutorMetrics.LOCAL_WRITE_TRANSACTIONS)
            .counters()
            .groupBy { it.id.getTag("outcome")!! }
            .mapValues { (_, counters) -> counters.sumOf { it.count() } }

    /** §9's "counting one participant connection": leases and returns on ONE registration's pool. */
    private class RegistrationLeaseCounter(
        private val base: DatasourceRegistry,
        private val registration: String,
    ) : DatasourceRegistry by base {
        val leased = AtomicInteger()
        val closed = AtomicInteger()

        override fun poolFor(datasource: Datasource): ConnectionPool {
            val delegate = base.poolFor(datasource)
            if (datasource.name != registration) return delegate
            return object : ConnectionPool by delegate {
                override fun leaseConnection(): Connection {
                    leased.incrementAndGet()
                    val conn = delegate.leaseConnection()
                    return object : Connection by conn {
                        override fun close() {
                            closed.incrementAndGet()
                            conn.close()
                        }
                    }
                }
            }
        }
    }

    /** §A.3's live grant change: the registration stops being visible mid-run, after the first write. */
    private class RevocableRegistry(
        private val base: DatasourceRegistry,
        private val registration: String,
    ) : DatasourceRegistry by base {
        private val revoked = AtomicBoolean()

        fun revoke() {
            revoked.set(true)
        }

        override fun getVisible(
            name: String,
            workspaceId: UUID,
        ): Datasource? = base.getVisible(name, workspaceId)?.takeIf { !(revoked.get() && name == registration) }
    }

    private companion object {
        const val REGISTRATION_A = "pg_family_a"
        const val REGISTRATION_B = "pg_reads_b"
        val CHILD_REF = PipelineNodeRef(name = "test/child_writer", version = 2)
        const val DEPTH = 8
        const val WB_ROWS = 1001L
    }
}
