package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The §9.2 serialization guarantee: two coroutines staging into the same instance at the same
 * time are serialized by the instance's own `Mutex`, on real threads (`Dispatchers.IO`),
 * through the one shared JDBC connection.
 *
 * **The row-count assertions alone were a false green (ST-TEST-1).** H2's own connection
 * happens to serialize enough internally that both tables still land with correct counts even
 * with the mutex removed — so the old test could not fail for the reason it claimed to guard.
 * The real assertion has to observe *our* lock, not the outcome, so the staging instance is
 * built over an instrumented `Connection` that counts how many staging operations are inside
 * the connection at once. Under the mutex that gauge can never exceed 1; without it, two
 * concurrent `stage()` calls drive it to 2.
 */
class H2StagingConcurrencyTest {
    private val props = H2StagingProperties()

    @Test
    fun `concurrent stage calls never overlap inside the connection`() {
        val executionId = UUID.randomUUID()
        // Instrumented `sa` connection, NOT the factory's restricted one: this test has to wrap
        // the Connection to count overlap, which the factory does not expose. It is about the
        // mutex, not about §9.5 privilege — that is H2StagingPrivilegeTest's job, on a real
        // factory instance. Read the `sa` here as a test harness, never as an endorsement.
        val raw = DriverManager.getConnection(stagingUrl(executionId, props), "sa", "")
        val gauge = ConcurrencyGauge()
        val staging = H2Staging(executionId, gauge.instrument(raw), props)

        val rowsPerTable = 4_000L
        SourceDb().use { srcA ->
            SourceDb().use { srcB ->
                val rsA = srcA.query("SELECT x AS id FROM SYSTEM_RANGE(1, $rowsPerTable)")
                val rsB = srcB.query("SELECT x AS id FROM SYSTEM_RANGE(1, $rowsPerTable)")

                val results =
                    runBlocking {
                        awaitAll(
                            async(Dispatchers.IO) { staging.stage(rsA, "stg_a", Dialect.H2) },
                            async(Dispatchers.IO) { staging.stage(rsB, "stg_b", Dialect.H2) },
                        )
                    }

                results.map { it.rowsStaged }.toSet() shouldBe setOf(rowsPerTable)
            }
        }

        // The load-bearing assertion: never two operations inside the connection at once.
        gauge.peak() shouldBe 1
        // Guard the guard — a gauge that never fired would make `peak() == 1` vacuous, and a
        // proxy that missed the JDBC calls entirely would read 0.
        gauge.calls() shouldBeGreaterThan 2

        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_a\"") } shouldBe rowsPerTable
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_b\"") } shouldBe rowsPerTable

        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 2
        stats.totalRows shouldBe rowsPerTable * 2

        staging.close()
    }

    /**
     * Wraps a [Connection] so every statement-producing call brackets a counter: entering marks
     * an operation in flight, and it stays in flight until the returned statement is closed.
     * The peak value is the maximum overlap the connection ever saw.
     */
    private class ConcurrencyGauge {
        private val inFlight = AtomicInteger()
        private val peak = AtomicInteger()
        private val calls = AtomicInteger()

        fun peak() = peak.get()

        fun calls() = calls.get()

        fun instrument(target: Connection): Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                InvocationHandler { _, method, args ->
                    val result = invoke(target, method, args)
                    if (method.name in STATEMENT_FACTORIES) {
                        enter()
                        return@InvocationHandler wrapStatement(result)
                    }
                    result
                },
            ) as Connection

        private fun wrapStatement(statement: Any?): Any? {
            val interfaces = statement!!.javaClass.interfaces
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                interfaces,
                InvocationHandler { _, method, args ->
                    val result = invoke(statement, method, args)
                    if (method.name == "close") exit()
                    result
                },
            )
        }

        private fun invoke(
            target: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }

        private fun enter() {
            calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
        }

        private fun exit() {
            inFlight.decrementAndGet()
        }

        private companion object {
            val STATEMENT_FACTORIES = setOf("createStatement", "prepareStatement", "prepareCall")
        }
    }
}
