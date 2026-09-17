package co.datapipelines.datasources.pooling

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLNonTransientConnectionException
import java.util.Properties
import javax.sql.DataSource

/**
 * 152 (#128) — the ownership rules of [LakeInstanceOwner] and [LakeInstanceDataSource], on the
 * REAL pinned DuckDB driver (the semantics under test are the driver's — `duplicate()` joins an
 * instance, a closed owner refuses — and a fake would only restate the assumptions).
 *
 * Every rule here is one the spec names as a guard: fully-initialized-or-closed, no silent
 * reconnect after owner loss, idempotent close, and the pool's close ORDER (Hikari first, then
 * the owner). [co.datapipelines.datasources.LakeInstanceLifecycleIntegrationTest] proves the same
 * rules through the production factory and a real pool.
 */
class LakeInstanceOwnerTest {
    /** The pool's own driver delegate on the anonymous in-memory URL — what the factory opens. */
    private fun driver(): DataSource = DriverDataSource("jdbc:duckdb::memory:", "org.duckdb.DuckDBDriver", Properties(), null, null)

    /** A delegate that remembers the connection it handed out, so a test can watch it close. */
    private class RememberingDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        lateinit var handedOut: Connection

        override fun getConnection(): Connection = delegate.connection.also { handedOut = it }
    }

    @Test
    fun `a failing initialization closes the owner connection - fully initialized or not at all`() {
        val delegate = RememberingDataSource(driver())

        val failure =
            shouldThrow<SQLException> {
                LakeInstanceOwner.open(delegate, "lake_init_fails") { connection ->
                    connection.createStatement().use { it.execute("CREATE SCHEMA ok") }
                    connection.createStatement().use { it.execute("SELECT * FROM no_such_table") }
                }
            }

        assertAll(
            { failure.message.orEmpty() shouldContain "no_such_table" },
            { withClue("the half-initialized instance must not survive") { delegate.handedOut.isClosed shouldBe true } },
        )
    }

    @Test
    fun `duplicates join the owner's instance - and a closed owner refuses new ones without reconnecting`() {
        val owner =
            LakeInstanceOwner.open(driver(), "lake_owner") { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE marker AS SELECT 42 AS answer") }
            }
        val dataSource = LakeInstanceDataSource(owner, sessionInit = emptyList())

        val survivor = dataSource.connection
        answerOn(survivor) shouldBe 42

        owner.close()
        owner.close() // idempotent — the second call is a no-op, not a second close
        val refusal = shouldThrow<SQLNonTransientConnectionException> { dataSource.connection }

        assertAll(
            { owner.isOpen shouldBe false },
            { refusal.sqlState shouldBe "08003" },
            { refusal.message.orEmpty() shouldContain "lake_owner" },
            { refusal.message.orEmpty() shouldContain owner.generation },
            // The borrower that was already out keeps its instance: the driver's native holder
            // outlives the owner handle, and nothing pulled the engine from under it.
            { withClue("an existing borrower is unaffected by the owner's close") { answerOn(survivor) shouldBe 42 } },
        )
        survivor.close()
    }

    @Test
    @Suppress("NestedBlockDepth") // nested `use` blocks are the leases' lifetimes — flattening would hide them
    fun `session init runs on every duplicate - and a failing session init closes that duplicate`() {
        val owner =
            LakeInstanceOwner.open(driver(), "lake_session") { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA nyc") }
                connection.createStatement().use { it.execute("CREATE TABLE nyc.marker AS SELECT 7 AS answer") }
            }
        owner.use {
            LakeInstanceDataSource(owner, sessionInit = listOf("SET search_path = 'nyc'")).connection.use { connection ->
                // The bare name resolves only because THIS connection's session init ran.
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT answer FROM marker").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 7
                    }
                }
            }
            shouldThrow<SQLException> {
                LakeInstanceDataSource(owner, sessionInit = listOf("SET search_path = 'no_such_schema'")).connection
            }.message.orEmpty() shouldContain "no_such_schema"
            withClue("a failed session init must not have taken the owner down") { owner.isOpen shouldBe true }
        }
    }

    @Test
    fun `a driver without duplicate is refused at open - a LAKE pool needs the shared-instance connection`() {
        val h2 = DriverDataSource("jdbc:h2:mem:lake_wrong_driver", "org.h2.Driver", Properties(), null, null)
        val delegate = RememberingDataSource(h2)

        val refusal = shouldThrow<SQLNonTransientConnectionException> { LakeInstanceOwner.open(delegate, "lake_h2") {} }

        assertAll(
            { refusal.message.orEmpty() shouldContain "no duplicate()" },
            { delegate.handedOut.isClosed shouldBe true },
        )
    }

    @Test
    fun `the pool closes Hikari first and the owner second - exactly once`() {
        val closeOrder = mutableListOf<String>()
        val hikari =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:duckdb::memory:"
                    maximumPoolSize = 1
                },
            )
        val owner =
            object : PoolInstanceOwner {
                override fun retire() {
                    closeOrder += "retired"
                }

                override fun close() {
                    closeOrder += if (hikari.isClosed) "owner-after-hikari" else "owner-BEFORE-hikari"
                }
            }
        val pool = HikariConnectionPool("lake_order", hikari, owner)

        pool.close()
        pool.close()

        assertAll(
            { closeOrder shouldBe listOf("retired", "owner-after-hikari") },
            { pool.isClosed shouldBe true },
        )
    }

    @Test
    fun `an owner lost OUTSIDE its own close is reported once - and every later duplicate refuses`() {
        val delegate = RememberingDataSource(driver())
        val owner = LakeInstanceOwner.open(delegate, "lake_lost") {}
        val dataSource = LakeInstanceDataSource(owner, sessionInit = emptyList())

        // A driver fault, not our close: the retained connection is closed under the owner.
        delegate.handedOut.close()
        val logged =
            capturingLogs {
                shouldThrow<SQLNonTransientConnectionException> { dataSource.connection }
                shouldThrow<SQLNonTransientConnectionException> { dataSource.connection }
            }

        assertAll(
            { owner.isOpen shouldBe false },
            { logged.count { it.startsWith("event=lake.instance_owner_lost datasource=lake_lost") } shouldBe 1 },
        )
        owner.close() // still a no-throw close on an already-dead connection
    }

    @Test
    @Suppress("ThrowsCount") // three DIFFERENT driver faults, one per branch of the owner's unwrapping
    fun `the driver's own duplicate failure surfaces as the driver's exception - wrapped only when it is not SQL`() {
        val sqlFault = FaultyConnection(driver().connection, onDuplicate = { throw SQLException("duplicate refused") })
        val runtimeFault = FaultyConnection(driver().connection, onDuplicate = { throw IllegalStateException("native fault") })
        val dyingFault =
            FaultyConnection(driver().connection).also { c ->
                c.onDuplicate = {
                    c.close()
                    throw SQLException("gone")
                }
            }

        assertAll(
            { shouldThrow<SQLException> { ownerOf(sqlFault).duplicate() }.message shouldBe "duplicate refused" },
            {
                val wrapped = shouldThrow<SQLException> { ownerOf(runtimeFault).duplicate() }
                wrapped.message.orEmpty() shouldContain "duplicate() on datasource 'lake_faulty' failed"
                wrapped.cause?.message shouldBe "native fault"
            },
            // The fault took the owner with it: named as an owner loss, cause attached.
            {
                val lost = shouldThrow<SQLNonTransientConnectionException> { ownerOf(dyingFault).duplicate() }
                lost.cause?.message shouldBe "gone"
            },
        )
    }

    @Test
    fun `a close that throws is logged - never propagated`() {
        val connection = FaultyConnection(driver().connection, onClose = { throw SQLException("close refused") })
        val owner = ownerOf(connection)

        val logged = capturingLogs { owner.close() }

        logged.count { it.startsWith("event=lake.instance_owner_close_failed datasource=lake_faulty") } shouldBe 1
    }

    @Test
    fun `the DataSource plumbing is the minimal honest surface`() {
        val owner = LakeInstanceOwner.open(driver(), "lake_plumbing") {}
        owner.use {
            val dataSource = LakeInstanceDataSource(owner, sessionInit = emptyList())
            dataSource.setLogWriter(null)
            dataSource.loginTimeout = 5
            assertAll(
                { dataSource.logWriter shouldBe null },
                // The one REAL knob: Hikari sets it from connectionTimeout and waits on it at shutdown.
                { dataSource.loginTimeout shouldBe 5 },
                { dataSource.isWrapperFor(DataSource::class.java) shouldBe false },
                { shouldThrow<SQLFeatureNotSupportedException> { dataSource.parentLogger } },
                { shouldThrow<SQLFeatureNotSupportedException> { dataSource.unwrap(DataSource::class.java) } },
            )
        }
    }

    private fun ownerOf(connection: Connection): LakeInstanceOwner =
        LakeInstanceOwner.open(
            object : DataSource by driver() {
                override fun getConnection(): Connection = connection
            },
            "lake_faulty",
        ) {}

    /**
     * A driver-shaped connection for the reflective seam: `DuckDBConnection` is final, so the
     * failure branches of `duplicate()`/`close()` are reached through a delegate that carries a
     * `duplicate()` of its own — the owner resolves it by name, exactly as it does the driver's.
     */
    @Suppress("unused") // duplicate() is called reflectively
    private class FaultyConnection(
        private val delegate: Connection,
        var onDuplicate: () -> Connection = { delegate },
        private val onClose: () -> Unit = { delegate.close() },
    ) : Connection by delegate {
        fun duplicate(): Connection = onDuplicate()

        override fun close() = onClose()
    }

    /** Formatted messages logged while [block] ran — the shape `ConnectionPoolManagerTest` uses. */
    private fun capturingLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun answerOn(connection: Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT answer FROM marker").use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }
}
