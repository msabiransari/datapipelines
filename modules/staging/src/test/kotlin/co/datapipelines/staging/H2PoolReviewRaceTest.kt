package co.datapipelines.staging

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Deterministic review counterexamples: opening/replacing sessions must participate in close ownership. */
class H2PoolReviewRaceTest {
    @Test
    fun `replacement finishing after close must itself be closed`() {
        val id = UUID.randomUUID()
        val url = stagingUrl(id, H2StagingProperties())
        val all = CopyOnWriteArrayList<Connection>()

        fun open(): Connection = DriverManager.getConnection(url, "sa", "").also { all += it }
        val armed = AtomicBoolean()
        val replacementOpened = CountDownLatch(1)
        val finishOpen = CountDownLatch(1)
        val initial = failingReset(open(), armed)
        val pool =
            H2ConnectionPool(id, initial, {
                open().also {
                    replacementOpened.countDown()
                    check(finishOpen.await(5, TimeUnit.SECONDS))
                }
            }, 1)
        try {
            runBlocking {
                armed.set(true)
                val lease = async(Dispatchers.IO) { pool.lease(LeaseKind.INTERNAL) { } }
                try {
                    check(replacementOpened.await(5, TimeUnit.SECONDS))
                    pool.close()
                } finally {
                    finishOpen.countDown()
                }
                lease.await()
            }
            pool.isClosed shouldBe true
            pool.physicalConnections shouldBe 0
            all.all { it.isClosed } shouldBe true
        } finally {
            finishOpen.countDown()
            pool.close()
            all.forEach { it.close() }
        }
    }

    @Test
    fun `two concurrent failed resets must not destroy the staged database`() {
        val id = UUID.randomUUID()
        val url = stagingUrl(id, H2StagingProperties())
        val all = CopyOnWriteArrayList<Connection>()
        val armed = AtomicBoolean()
        val closing = CountDownLatch(2)

        fun open(): Connection {
            val raw = DriverManager.getConnection(url, "sa", "").also { all += it }
            return failingReset(raw, armed) {
                if (armed.get()) {
                    closing.countDown()
                    check(closing.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val pool = H2ConnectionPool(id, open(), ::open, 2)
        try {
            runBlocking {
                pool.lease(LeaseKind.INTERNAL) { c ->
                    c.createStatement().use { it.execute("CREATE TABLE survivor (id INTEGER)") }
                }
                val inside = CountDownLatch(2)
                val release = CountDownLatch(1)
                val leases =
                    (1..2).map {
                        async(Dispatchers.IO) {
                            pool.lease(LeaseKind.INTERNAL) {
                                inside.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                        }
                    }
                try {
                    check(inside.await(5, TimeUnit.SECONDS))
                    armed.set(true)
                } finally {
                    release.countDown()
                }
                leases.awaitAll()
                armed.set(false)
                pool.lease(LeaseKind.INTERNAL) { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM survivor").use { rs ->
                            rs.next() shouldBe true
                            rs.getLong(1) shouldBe 0L
                        }
                    }
                }
            }
        } finally {
            armed.set(false)
            pool.close()
            all.forEach { it.close() }
        }
    }

    private fun failingReset(
        raw: Connection,
        armed: AtomicBoolean,
        beforeClose: () -> Unit = {},
    ): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "getAutoCommit" && armed.get()) throw SQLException("review reset failure", "HY000")
            if (method.name == "close") beforeClose()
            try {
                method.invoke(raw, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
}
