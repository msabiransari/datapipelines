package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DuckDbLeasePool] on stand-in connections: the lease is the connection's whole life, `close()`
 * never waits for a lease, and the finalizer runs exactly once — immediately, or on the last return.
 */
class DuckDbLeasePoolTest {
    private val opened = AtomicInteger()
    private val closed = AtomicInteger()
    private val finalized = AtomicInteger()

    /** A connection that records its close and, when asked, fails it. */
    private fun fakeConnection(failClose: Boolean = false): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "close" -> {
                    closed.incrementAndGet()
                    if (failClose) throw SQLException("close failed")
                    null
                }

                "isClosed" -> {
                    false
                }

                else -> {
                    throw UnsupportedOperationException(method.name)
                }
            }
        } as Connection

    private fun pool(
        maxConnections: Int = 2,
        failClose: Boolean = false,
    ) = DuckDbLeasePool(UUID.randomUUID(), maxConnections, { opened.incrementAndGet().let { fakeConnection(failClose) } }) {
        finalized.incrementAndGet()
    }

    @Test
    fun `each lease opens and closes its own connection, and close finalizes once when none is out`() {
        val pool = pool()
        runBlocking {
            pool.lease({ it }) { }
            pool.lease({ it }) { }
        }
        opened.get() shouldBe 2
        closed.get() shouldBe 2
        pool.close() shouldBe 0
        pool.close() shouldBe 0
        finalized.get() shouldBe 1
        pool.isFinalized shouldBe true
        shouldThrow<IllegalStateException> { runBlocking { pool.lease({ it }) { } } }.message.orEmpty() shouldContain "is closed"
    }

    @Test
    fun `close with a lease out returns at once and the last return finalizes`() {
        val pool = pool()
        runBlocking {
            val inside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lease =
                async(Dispatchers.IO) {
                    pool.lease({ it }) {
                        inside.complete(Unit)
                        release.await()
                    }
                }
            inside.await()
            pool.close() shouldBe 1
            finalized.get() shouldBe 0
            pool.isFinalized shouldBe false
            release.complete(Unit)
            lease.await()
        }
        finalized.get() shouldBe 1
        closed.get() shouldBe 1
    }

    @Test
    fun `the finalizer runs exactly once when many leases return after close`() {
        val pool = pool(maxConnections = 8)
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val entered = AtomicInteger()
            val leases =
                (1..8).map {
                    async(Dispatchers.IO) {
                        pool.lease({ it }) {
                            entered.incrementAndGet()
                            gate.await()
                        }
                    }
                }
            while (entered.get() < 8) yield()
            pool.close() shouldBe 8
            gate.complete(Unit)
            leases.awaitAll()
        }
        finalized.get() shouldBe 1
        pool.peakActiveLeases shouldBe 8
    }

    @Test
    fun `a failed close rides on the block's own failure and is thrown when the block succeeded`() {
        val failing = pool(failClose = true)
        val blockFailure = shouldThrow<IllegalStateException> { runBlocking { failing.lease({ it }) { error("block failed") } } }
        blockFailure.message shouldBe "block failed"
        blockFailure.suppressed.map { it.message } shouldContainExactly listOf("close failed")

        shouldThrow<SQLException> { runBlocking { failing.lease({ it }) { } } }.message shouldBe "close failed"
        // Every permit came back: the pool still finalizes.
        failing.close() shouldBe 0
        finalized.get() shouldBe 1
    }

    @Test
    fun `a failed open gives its slot back`() {
        val pool = DuckDbLeasePool(UUID.randomUUID(), 1, { throw SQLException("open failed") }) { finalized.incrementAndGet() }
        repeat(3) { shouldThrow<SQLException> { runBlocking { pool.lease({ it }) { } } } }
        pool.close() shouldBe 0
        finalized.get() shouldBe 1
    }

    @Test
    fun `the block sees the wrapped connection while the raw one is what closes`() {
        val pool = pool()
        val wrapped = fakeConnection()
        val seen = runBlocking { pool.lease({ wrapped }) { it } }
        (seen === wrapped) shouldBe true
        // The raw duplicate closed; the wrapper was never closed by the pool.
        closed.get() shouldBe 1
        pool.close()
    }
}
