package co.datapipelines.parameters

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LeasedStatement
import co.datapipelines.datasources.ReadOnlyStatementLease
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.typesystem.Dialect
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A stub JDBC driver for the bulkhead suites (record §12's P31 row): every lease is a fresh
 * connection whose one statement's `executeQuery` SPINS on a flag — a busy loop that reads neither
 * the thread's interrupt nor `cancel()` unless [honoursCancel] (MISTAKES: a stand-in must model the
 * property under test, and "blocks" is not "ignores cancel"). The pool behind it RECORDS every
 * discard, so "the abandoned connection was discarded" is an assertion, never assumed.
 */
internal class StubJdbc(
    private val honoursCancel: Boolean = false,
    /** Every statement returns at once — the pool's normal path. */
    private val quick: Boolean = false,
) {
    /** Once [releaseAll] ran, every LATER statement returns at once too — a queued submission starts after it. */
    private val releasing = AtomicBoolean(false)

    /** One statement's observable life. */
    inner class Stub {
        val started = AtomicBoolean(false)
        val released = AtomicBoolean(quick || releasing.get())
        val cancels = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        val ended = AtomicBoolean(false)

        val statement: PreparedStatement =
            proxy(PreparedStatement::class.java) { name, _ ->
                when (name) {
                    "executeQuery" -> {
                        spin()
                    }

                    "cancel" -> {
                        cancels.incrementAndGet()
                        if (honoursCancel) cancelled.set(true)
                        null
                    }

                    else -> {
                        null
                    }
                }
            }

        val connection: Connection =
            proxy(Connection::class.java) { name, _ ->
                when (name) {
                    "prepareStatement" -> statement
                    else -> null
                }
            }

        private fun spin(): ResultSet {
            started.set(true)
            try {
                while (!released.get()) {
                    if (cancelled.get()) throw SQLException("canceling statement due to user request", "57014")
                    Thread.onSpinWait()
                }
                return EMPTY_RESULT
            } finally {
                ended.set(true)
            }
        }
    }

    val stubs = CopyOnWriteArrayList<Stub>()

    /** Connections the pool was asked to DISCARD, in order. */
    val discarded = CopyOnWriteArrayList<Connection>()

    val pool =
        object : ConnectionPool {
            override val name = "stub"

            override fun leaseConnection(): Connection = Stub().also { stubs += it }.connection

            override fun discard(connection: Connection) {
                discarded += connection
            }

            override fun close() = Unit
        }

    private val registry: DatasourceRegistry = mockk<DatasourceRegistry>().also { every { it.poolFor(any()) } returns pool }

    private val lease = ReadOnlyStatementLease(registry)

    fun started(): Int = stubs.count { it.started.get() }

    fun releaseAll() {
        releasing.set(true)
        stubs.forEach { it.released.set(true) }
    }

    /**
     * A [SelectorTask] that leases through the REAL [ReadOnlyStatementLease] on the worker thread and
     * runs the stub statement; `abandon()` is the real [LeasedStatement.abandon] (cancel, then discard).
     */
    fun task(): SelectorTask =
        object : SelectorTask {
            private val leased = AtomicReference<LeasedStatement?>()
            private val abandoned = AtomicBoolean(false)

            override fun run(): SelectorRun {
                val statement = lease.open(DATASOURCE, "SELECT 1 AS value", emptyList(), 2, 10)
                leased.set(statement)
                if (abandoned.get()) statement.abandon()
                statement.use { it.query { } }
                return SelectorRun.Rows(emptyList(), emptyList())
            }

            override fun abandon() {
                abandoned.set(true)
                leased.get()?.abandon()
            }
        }

    companion object {
        val DATASOURCE =
            Datasource(name = "stub", displayName = "stub", dialect = Dialect.POSTGRES, jdbcUrl = "jdbc:postgresql://stub/stub")

        private val EMPTY_RESULT: ResultSet =
            proxy(ResultSet::class.java) { name, _ ->
                when (name) {
                    "next" -> false
                    else -> null
                }
            }

        /** A JDBC interface whose methods answer [answer] — null, false or 0 by the return type otherwise. */
        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(
            type: Class<T>,
            answer: (String, Array<out Any?>?) -> Any?,
        ): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "${type.simpleName}@${System.identityHashCode(self)}"
                    else -> answer(method.name, args) ?: defaultOf(method.returnType)
                }
            } as T

        private fun defaultOf(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
    }
}
