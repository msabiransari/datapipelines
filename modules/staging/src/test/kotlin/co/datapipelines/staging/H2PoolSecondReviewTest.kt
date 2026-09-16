package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Additional ownership checks at the exact 146b delivery, using real H2 sessions.
 *
 * The review's fixture, copied into the lane with its assertions untouched (146c); the only
 * lane edits are ktlint formatting and the detekt suppression below.
 */
class H2PoolSecondReviewTest {
    @Test
    @Suppress("ThrowsCount")
    fun `cleanup Error must not leave an ownerless live session after close`() {
        val id = UUID.randomUUID()
        val url = stagingUrl(id, H2StagingProperties())
        val raw = DriverManager.getConnection(url, "sa", "")
        val armed = AtomicBoolean()
        val wrapped =
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "getAutoCommit" && armed.get()) throw AssertionError("injected reset error")
                try {
                    method.invoke(raw, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
        val pool = H2ConnectionPool(id, wrapped, { DriverManager.getConnection(url, "sa", "") }, 1)
        try {
            runBlocking {
                armed.set(true)
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        pool.lease(LeaseKind.INTERNAL) { throw IllegalArgumentException("operation failed") }
                    }
                (failure.suppressed.single() is AssertionError) shouldBe true
            }
            armed.set(false)
            pool.close()
            listOf(raw.isClosed, pool.activeLeases, pool.physicalConnections) shouldBe listOf(true, 0, 0)
        } finally {
            armed.set(false)
            pool.close()
            raw.close()
        }
    }

    @Test
    fun `lease started after close is refused while the existing holder remains suspended`() {
        val id = UUID.randomUUID()
        val url = stagingUrl(id, H2StagingProperties())
        val raw = DriverManager.getConnection(url, "sa", "")
        val pool = H2ConnectionPool(id, raw, { DriverManager.getConnection(url, "sa", "") }, 1)
        try {
            runBlocking {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        pool.lease(LeaseKind.INTERNAL) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                pool.close()
                try {
                    val refusal =
                        shouldThrow<IllegalStateException> {
                            withTimeout(1_000) { pool.lease(LeaseKind.INTERNAL) { error("must never be called") } }
                        }
                    refusal::class shouldBe IllegalStateException::class
                    refusal.message.orEmpty() shouldContain "closed"
                } finally {
                    release.complete(Unit)
                    holder.await()
                }
            }
        } finally {
            pool.close()
            raw.close()
        }
    }

    @Test
    fun `runtime sweep failure must still close every idle session`() {
        val id = UUID.randomUUID()
        val url = stagingUrl(id, H2StagingProperties())
        val raw = DriverManager.getConnection(url, "sa", "")
        val pool = H2ConnectionPool(id, raw, { DriverManager.getConnection(url, "sa", "") }, 1)
        try {
            runCatching { pool.close { throw IllegalStateException("injected sweep failure") } }
            pool.close()
            listOf(raw.isClosed, pool.physicalConnections, pool.isClosed) shouldBe listOf(true, 0, true)
        } finally {
            raw.close()
        }
    }
}
