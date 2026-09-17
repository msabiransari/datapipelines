package co.datapipelines.datasources.pooling

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.Connection
import java.sql.SQLException
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
            AutoCloseable {
                closeOrder += if (hikari.isClosed) "owner-after-hikari" else "owner-BEFORE-hikari"
            }
        val pool = HikariConnectionPool("lake_order", hikari, owner)

        pool.close()
        pool.close()

        assertAll(
            { closeOrder shouldBe listOf("owner-after-hikari") },
            { pool.isClosed shouldBe true },
        )
    }

    private fun answerOn(connection: Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT answer FROM marker").use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }
}
