package co.datapipelines.executor

import co.datapipelines.events.SseEventType
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An ACTUALLY lost staging pool through the real executor (146c; staging.md §9.2): the
 * producer node's restricted session fails its reset on return, it is the database's last
 * holder, and the replacement the pool opens is refused — so the pool is LOST. The next staging
 * operation (the producer's own post-write budget check, then any dependent) must fail with
 * the loss named, the execution must end with normal terminal bookkeeping, no session may
 * reconnect to a fresh empty database under the execution's name, and every restricted session
 * must be closed by cleanup.
 *
 * The injection goes through `H2StagingFactory`'s connector seam — the real factory, the real
 * bootstrap, the real pool; only the restricted sessions are proxied. This is not a mocked
 * `IllegalStateException`: the loss is the pool's own decision.
 */
class StagingLostExecutorTest {
    @Test
    fun `a dependent node fails with the loss named, the execution ends normally, and no empty database is reconnected`() =
        runBlocking<Unit> {
            val raw = CopyOnWriteArrayList<Connection>()
            val restrictedOpens = AtomicInteger()
            val armed = AtomicBoolean()
            val factory =
                H2StagingFactory(H2StagingProperties(maxConnections = 1)) { url, user, password ->
                    val real = DriverManager.getConnection(url, user, password)
                    if (user != RESTRICTED_USER) return@H2StagingFactory real
                    if (restrictedOpens.incrementAndGet() > 1) {
                        // The guardian's replacement: refused, so nothing can hold the database.
                        real.close()
                        throw SQLException("replacement refused", "08001")
                    }
                    raw += real
                    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                        // Armed by the producer node's first statement; the reset on its return then fails.
                        if (method.name == "createStatement" || method.name == "prepareStatement") armed.set(true)
                        if (method.name == "getAutoCommit" && armed.get()) throw SQLException("reset refused", "HY000")
                        try {
                            method.invoke(real, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    } as Connection
                }
            val nodes =
                listOf(
                    Fixtures.node("seed", type = NodeType.DDL),
                    Fixtures.node("read", output = NodeOutput.Caller, dependsOn = listOf("seed")),
                )
            val sql = mapOf("seed" to """CREATE TABLE "seeded" ("n" INT)""", "read" to """SELECT COUNT(*) AS c FROM "seeded"""")

            ExecutorHarness(templateEngine = Fixtures.templateEngine(sql), stagingFactory = factory).use { h ->
                val failed = shouldThrow<PipelineExecutionFailed> { h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes))) }

                // The loss is decided on `seed`'s own lease return; the very next staging operation
                // — `seed`'s post-write budget check (`stats()`), one lease later — is the first to
                // be refused, so `seed` is the node that fails and `read` never starts. Either way
                // the node reports the LOSS, not a stale success against an empty database.
                failed.failedNodeId shouldBe "seed"
                requireNotNull(failed.errorRecord) { "no error record" }.message shouldContain "was lost"
                h.emitter.allOf<co.datapipelines.events.NodeStarted>().map { it.nodeId } shouldBe listOf("seed")
                // Terminal bookkeeping as for any node failure: one node_failed, one pipeline_failed, no abort.
                h.emitter.count(SseEventType.NODE_FAILED) shouldBe 1
                h.emitter.count(SseEventType.PIPELINE_FAILED) shouldBe 1
                h.emitter.count(SseEventType.EXECUTION_ABORTED) shouldBe 0
                h.slots.inFlight shouldBe 0
                h.cancellations.liveExecutions shouldBe 0
            }
            // The initial restricted session plus the ONE refused replacement — never a third open
            // that would have created a fresh, empty database and let `read` succeed against it.
            restrictedOpens.get() shouldBe 2
            raw.size shouldBe 1
            raw.all { it.isClosed } shouldBe true
        }

    private companion object {
        const val RESTRICTED_USER = "STAGING_EXEC"
    }
}
