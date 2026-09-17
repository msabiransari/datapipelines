package co.datapipelines.datasources.pooling

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.Fixtures
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 152 (R152-8) — the RETAINED owner connection's close is contained under the same nonfatal
 * exception policy as the duplicates': `LakeInstanceOwner.close()` never throws.
 *
 * The round-5 review showed the consequence of the SQLException-only catch that the previous
 * handback had called "a missing log line": a nonfatal `RuntimeException` from the driver's
 * close of the retained connection escaped through `HikariConnectionPool.close()`'s `finally`
 * into `ConnectionPoolManager.close()`'s loop over every retired pool — and the healthy,
 * unrelated H2 pool queued after the lake was never closed. These tests are that witness
 * through the REAL manager (its close loop and its reaper), plus the owner-level facts: the
 * error is reported with datasource and generation, the physical owner is not called closed
 * when the driver refused, duplicates are still cleaned, and a second close is a no-op.
 */
class LakeRetainedOwnerCloseTest {
    /** The owner's retained connection whose OWN close the test controls; duplicates close normally. */
    private class RefusingRetained(
        private val raw: Connection,
        private val refuseWith: (attempt: Int) -> Throwable?,
    ) : Connection by raw {
        val closeAttempts = AtomicInteger(0)

        @Suppress("unused") // reached reflectively by LakeInstanceOwner
        fun duplicate(): Connection = raw.javaClass.getMethod("duplicate").invoke(raw) as Connection

        override fun close() {
            refuseWith(closeAttempts.incrementAndGet())?.let { throw it }
            raw.close()
        }
    }

    private fun driver(): DataSource = DriverDataSource("jdbc:duckdb::memory:", "org.duckdb.DuckDBDriver", Properties(), null, null)

    private fun owner(
        name: String,
        retained: RefusingRetained,
    ): LakeInstanceOwner =
        LakeInstanceOwner.open(
            object : DataSource by driver() {
                override fun getConnection(): Connection = retained
            },
            name,
        ) {}

    @Test
    fun `a retained owner whose close throws SQLException is contained - reported, not called closed, duplicates still cleaned`() =
        retainedRefusalIsContained("lake_owner_sql") { SQLException("retained owner refuses to close") }

    @Test
    fun `a retained owner whose close throws a nonfatal RuntimeException is contained the same way`() =
        retainedRefusalIsContained("lake_owner_runtime") { IllegalStateException("controlled unchecked owner close failure") }

    private fun retainedRefusalIsContained(
        name: String,
        failure: () -> Throwable,
    ) {
        val raw = driver().connection
        val retained = RefusingRetained(raw) { attempt -> if (attempt == 1) failure() else null }
        val owner = owner(name, retained)
        owner.duplicate() // a duplicate still out at release: the generation must still clean it
        owner.retire()

        val logged = capturingLogs { owner.close() } // must not throw
        owner.close() // a second call is a no-op — no second attempt on the retained connection

        assertAll(
            { withClue("the physical owner is still open — the driver refused") { raw.isClosed shouldBe false } },
            { owner.isRetainedConnectionOpen shouldBe true },
            { owner.ownerCloseFailure?.message shouldBe failure().message },
            { retained.closeAttempts.get() shouldBe 1 },
            {
                logged.single { it.startsWith("event=lake.instance_owner_close_failed datasource=$name") } shouldContain
                    "error=\"${failure().message}\""
            },
            { logged.single { it.startsWith("event=lake.instance_closed datasource=$name") } shouldContain " retained_owner_closed=false" },
            { logged.single { it.startsWith("event=lake.instance_handles_closed datasource=$name") } shouldContain " closed=1 " },
            { withClue("the duplicate was cleaned regardless") { owner.liveDuplicates shouldBe 0 } },
        )
        raw.close() // the test's own cleanup through the raw driver connection
    }

    @Test
    fun `through the real manager - a lake owner refusing to close does not stop the healthy owner-null H2 pool behind it`() {
        val raw = driver().connection
        val retained = RefusingRetained(raw, refusesFirstCloseUnchecked)
        val lake = lakeDatasource("lake_refusing")
        val h2 = Fixtures.h2(name = "healthy_h2")
        lateinit var h2Pool: HikariConnectionPool
        val manager =
            ConnectionPoolManager(poolFactory = { datasource ->
                if (datasource.name == lake.name) {
                    lakePool(datasource, retained)
                } else {
                    ConnectionPoolManager.buildHikariPool(datasource).also { h2Pool = it as HikariConnectionPool }
                }
            })

        manager.poolFor(lake).leaseConnection().use { it.createStatement().use { st -> st.executeQuery("SELECT 1").close() } }
        manager.poolFor(h2).leaseConnection().use { it.createStatement().use { st -> st.executeQuery("SELECT 1").close() } }
        manager.retire(lake.name) shouldBe true // the lake FIRST in the retired queue…
        manager.retire(h2.name) shouldBe true // …the healthy H2 pool behind it

        val logged = capturingLogs { manager.close() } // one call must visit both

        assertAll(
            { withClue("the healthy pool behind the refusing lake was closed") { h2Pool.isClosed shouldBe true } },
            { withClue("nothing left in the retired queue") { manager.retiringCount() shouldBe 0 } },
            { logged.count { it.startsWith("event=lake.instance_owner_close_failed datasource=lake_refusing") } shouldBe 1 },
            { withClue("the refused physical owner is the reported residual") { raw.isClosed shouldBe false } },
        )
        raw.close()
    }

    @Test
    fun `through the real reaper - the same containment lets a drained healthy pool behind a refusing lake be reaped`() {
        val raw = driver().connection
        val retained = RefusingRetained(raw, refusesFirstCloseUnchecked)
        val lake = lakeDatasource("lake_refusing_reaped")
        val h2 = Fixtures.h2(name = "healthy_h2_reaped")
        lateinit var h2Pool: HikariConnectionPool
        val manager =
            ConnectionPoolManager(poolFactory = { datasource ->
                if (datasource.name == lake.name) {
                    lakePool(datasource, retained)
                } else {
                    ConnectionPoolManager.buildHikariPool(datasource).also { h2Pool = it as HikariConnectionPool }
                }
            })

        manager.poolFor(lake).leaseConnection().close()
        manager.poolFor(h2).leaseConnection().close()
        manager.retire(lake.name) shouldBe true
        manager.retire(h2.name) shouldBe true

        val outcome = capturingLogs { manager.reapRetiring() shouldBe ReapOutcome(drained = 2, hardClosed = 0) }

        assertAll(
            { h2Pool.isClosed shouldBe true },
            { manager.retiringCount() shouldBe 0 },
            { outcome.count { it.startsWith("event=lake.instance_owner_close_failed") } shouldBe 1 },
        )
        manager.close()
        raw.close()
    }

    // ------------------------------------------------------------------ helpers

    /** The retained connection refuses its FIRST close with an unchecked exception; a later close would succeed. */
    private val refusesFirstCloseUnchecked: (Int) -> Throwable? =
        { attempt -> if (attempt == 1) IllegalStateException("controlled unchecked owner close failure") else null }

    private fun lakeDatasource(name: String) =
        Datasource(
            name = name,
            displayName = "Lake",
            dialect = co.datapipelines.typesystem.Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            credentialKind = co.datapipelines.datasources.CredentialKind.NONE,
        )

    /** A production-shaped LAKE pool (owner + LakeInstanceDataSource + real Hikari) whose retained connection the test controls. */
    private fun lakePool(
        datasource: Datasource,
        retained: RefusingRetained,
    ): ConnectionPool {
        val owner = owner(datasource.name, retained)
        val hikari =
            HikariDataSource(
                HikariConfig().apply {
                    dataSource = LakeInstanceDataSource(owner, sessionInit = emptyList())
                    minimumIdle = 0
                    maximumPoolSize = 1
                    initializationFailTimeout = -1
                    poolName = "ds-${datasource.name}"
                },
            )
        return HikariConnectionPool(datasource.name, hikari, owner)
    }

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
}
