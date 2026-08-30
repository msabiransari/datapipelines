package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceAuditEvent
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceAuditSink
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * B5: an executor-triggered `pool_build` carries the **cause** — the execution and node that took
 * the first lease (datasources.md §7.4).
 *
 * ## Why this test had to exist at this level
 *
 * `ExecutorPrimitivesTest` already exercised `ExecutionAwareAuditSink` in isolation and passed
 * throughout, because in isolation you call `record()` inside `withCause` by hand. The defect was
 * one level up, in the *call site*: `NodeRunner` resolved `datasourceRegistry.poolFor(datasource)`
 * **before** entering `withCause`, and `pool_build` is emitted from inside `poolFor`'s
 * `computeIfAbsent` — not from `leaseConnection`. So the `ThreadLocal` was still unset when the
 * event fired and `cause` was null on every executor-triggered build. The carry-forward looked
 * wired and was inert.
 *
 * The pool here is a **real** [ConnectionPoolManager] with a real `computeIfAbsent` factory, so the
 * at-most-once emission point is the production one rather than a stand-in that happens to fire
 * where the test wants it.
 */
class AuditCauseIntegrationTest {
    @Test
    fun `a first-lease pool build carries the executing node as its cause`() =
        runBlocking<Unit> {
            val recorded = CopyOnWriteArrayList<DatasourceAuditEvent>()
            val sink = ExecutionAwareAuditSink(DatasourceAuditSink { recorded += it })
            val registry = PoolBuildingRegistry(source(), sink)
            val executionId = UUID.randomUUID()

            NodeRunner(
                templateEngine = Fixtures.templateEngine(mapOf("fetch" to "SELECT n FROM audit_src")),
                datasourceRegistry = registry,
                writebackRunner = JdbcWritebackRunner(registry),
                resultStore = InMemoryResultStore(),
                config = ExecutorConfig(),
                auditSink = sink,
            ).run(
                ExecutableNode.from(Fixtures.node("fetch", source = DATASOURCE)),
                context(executionId),
            )

            val poolBuild = recorded.single { it.event == DatasourceAuditEvents.POOL_BUILD }
            val cause = poolBuild.cause.shouldNotBeNull()
            cause.executionId shouldBe executionId.toString()
            cause.nodeId shouldBe "fetch"
            poolBuild.actor shouldBe DatasourceAuditEvent.SYSTEM_ACTOR
        }

    @Test
    fun `a pool build outside any execution still records, with no cause invented`() {
        // The wrapper must not fabricate a cause for an operator-initiated build: §7.4's `cause` is
        // "the execution that took the first lease", and there isn't one here.
        val recorded = CopyOnWriteArrayList<DatasourceAuditEvent>()
        val sink = ExecutionAwareAuditSink(DatasourceAuditSink { recorded += it })
        val registry = PoolBuildingRegistry(source(), sink)

        registry.poolFor(registry.get(DATASOURCE)!!).leaseConnection().close()

        recorded.single { it.event == DatasourceAuditEvents.POOL_BUILD }.cause.shouldBeNull()
    }

    private fun context(executionId: UUID) =
        NodeExecutionContext(
            executionId = executionId,
            staging = Fixtures.stagingFactory().create(executionId),
            handle = InMemoryCancellationRegistry().register(executionId),
            values = emptyMap(),
            warnings = WarningSink(),
            resultTtlSeconds = 300,
            renderBudgetChars = ExecutorConfig().renderOutputBudgetChars(),
            stagingMaxMemoryMb = 1024,
            tempdbDialect = Dialect.H2,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            workspaceId = UUID.randomUUID(),
        )

    private fun source(): Datasource =
        h2Datasource(DATASOURCE, listOf("CREATE TABLE audit_src (n INT)", "INSERT INTO audit_src VALUES (1)"))

    /**
     * A registry whose pools are built by the **real** [ConnectionPoolManager], emitting
     * `pool_build` from inside `computeIfAbsent` exactly as `DefaultDatasourceRegistry` does.
     *
     * Using the real manager rather than a hand-rolled fake is the point: the defect lived in *when*
     * the event fires relative to `withCause`, so a fake that fired it somewhere convenient would
     * have reproduced the bug's absence rather than its presence.
     */
    private class PoolBuildingRegistry(
        private val datasource: Datasource,
        auditSink: DatasourceAuditSink,
    ) : DatasourceRegistry {
        private val pools =
            ConnectionPoolManager { ds ->
                auditSink.record(
                    DatasourceAuditEvent(
                        timestamp = Instant.now(),
                        datasourceName = ds.name,
                        event = DatasourceAuditEvents.POOL_BUILD,
                        actor = DatasourceAuditEvent.SYSTEM_ACTOR,
                    ),
                )
                H2Pool(ds)
            }

        override fun list(dialect: Dialect?): List<Datasource> = listOf(datasource)

        override fun get(name: String): Datasource? = datasource.takeIf { it.name == name }

        override fun exists(name: String): Boolean = get(name) != null

        override fun save(
            datasource: Datasource,
            actor: UUID,
        ): Datasource = datasource

        override fun validate(datasource: Datasource): ValidationResult = ValidationResult.ok()

        override fun delete(name: String): DeleteResult = DeleteResult(true, name)

        override fun poolFor(datasource: Datasource): ConnectionPool = pools.poolFor(datasource)

        override fun testConnection(name: String): TestResult? = TestResult(true, Instant.now())
    }

    private class H2Pool(
        private val datasource: Datasource,
    ) : ConnectionPool {
        override val name: String get() = datasource.name

        override fun leaseConnection(): Connection = DriverManager.getConnection(datasource.jdbcUrl, datasource.username, "")

        override fun close() = Unit
    }

    private companion object {
        const val DATASOURCE = "audit_ds"
    }
}
