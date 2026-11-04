package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.duckdb.DuckDBConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The DuckDB twin of [H2StagingConcurrencyTest] and [StagingConnectionAccessTest]: leases are
 * concurrent up to `max-connections` and never beyond, each lease is its own duplicate of the
 * locked root, a committed table is visible on every other lease, a raced name has one winner,
 * and no public member hands out a connection or the pool.
 */
class DuckDbStagingConcurrencyTest {
    @TempDir
    lateinit var scratch: Path

    private val ids = listOf(ColumnSchema("id", LogicalType.INTEGER))

    @Test
    fun `two author blocks are inside JDBC on two different connections at the same instant`() {
        val staging = duckFactory(scratch, maxConnections = 2).open()
        val barrier = CyclicBarrier(2)
        val seen =
            runBlocking {
                (1..2)
                    .map {
                        async(Dispatchers.IO) {
                            staging.withConnection { c ->
                                // Both blocks must be inside a lease before either may leave it.
                                barrier.await(10, TimeUnit.SECONDS)
                                System.identityHashCode(c)
                            }
                        }
                    }.awaitAll()
            }
        seen.toSet().size shouldBe 2
        staging.close()
    }

    @Test
    fun `leases never exceed max-connections and a waiting lease is admitted when one returns`() {
        val staging = duckFactory(scratch, maxConnections = 2).open()
        val inside = AtomicInteger()
        val peakObserved = AtomicInteger()
        runBlocking {
            (1..8)
                .map {
                    async(Dispatchers.IO) {
                        staging.withConnection { c ->
                            peakObserved.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                            c.createStatement().use { st -> st.executeQuery("SELECT 1").use { rs -> rs.next() } }
                            delay(50)
                            inside.decrementAndGet()
                        }
                    }
                }.awaitAll()
        }
        peakObserved.get() shouldBe 2
        staging.peakActiveLeases shouldBe 2
        staging.close()
    }

    @Test
    fun `a table committed on one lease is visible to a read on another`() {
        val staging = duckFactory(scratch).open()
        runBlocking {
            staging.withConnection { writer ->
                writer.createStatement().use { it.execute("CREATE TABLE \"stg_w\" AS SELECT range AS id FROM range(10)") }
                // Read on a second lease while the first is still held.
                staging.scalarTextSuspending("SELECT count(*) FROM \"stg_w\"") shouldBe "10"
            }
        }
        staging.close()
    }

    @Test
    fun `a duplicate name raced by two stages has exactly one winner and an intact table`() {
        val staging = duckFactory(scratch).open()
        val outcomes =
            runBlocking {
                (1..2)
                    .map { n ->
                        async(Dispatchers.IO) {
                            runCatching { staging.stageRows("stg_race", ids, (1..1000).asSequence().map { listOf(n) }) }
                        }
                    }.awaitAll()
            }
        outcomes.count { it.isSuccess } shouldBe 1
        outcomes.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<StagingTableAlreadyExistsException>()
        staging.scalarText("SELECT count(*) || ':' || count(DISTINCT id) FROM \"stg_race\"") shouldBe "1000:1"
        staging.close()
    }

    @Test
    fun `stats under concurrent writers reports every completed stage exactly once`() {
        val staging = duckFactory(scratch).open()
        runBlocking {
            (1..4)
                .map { n -> async(Dispatchers.IO) { staging.stageRows("stg_c$n", ids, (1..1500).asSequence().map { listOf(it) }) } }
                .awaitAll()
        }
        val stats = runBlocking { staging.stats() }
        stats.tableCount shouldBe 4
        stats.totalRows shouldBe 6000L
        staging.close()
    }

    @Test
    fun `no public member of the DuckDB implementation yields a connection or the pool`() {
        val guarded = setOf(Connection::class.java, DuckDBConnection::class.java, DuckDbLeasePool::class.java)
        listOf(DuckDbStaging::class.java, DuckDbStagingFactory::class.java).forEach { type ->
            type.methods
                .filter { Modifier.isPublic(it.modifiers) && (it.returnType in guarded || it.parameterTypes.any { p -> p in guarded }) }
                .map { it.name }
                .shouldBeEmpty()
            type.declaredFields
                .filter { it.type in guarded || Function::class.java.isAssignableFrom(it.type) }
                .filterNot { Modifier.isPrivate(it.modifiers) }
                .map { it.name }
                .shouldBeEmpty()
        }
        // The pool holds no connection: every lease is a fresh duplicate, closed on return.
        DuckDbLeasePool::class.java.declaredFields
            .filter { it.type in guarded || Collection::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .shouldBeEmpty()
    }

    @Test
    fun `at capacity one a block owns its connection, so a concurrent operation waits`() {
        val staging = duckFactory(scratch, maxConnections = 1).open()
        val order = CopyOnWriteArrayList<String>()
        runBlocking {
            val holder =
                async(Dispatchers.IO) {
                    staging.withConnection {
                        order += "holder-in"
                        delay(200)
                        order += "holder-out"
                    }
                }
            delay(50)
            val waiter = async(Dispatchers.IO) { staging.withConnection { order += "waiter-in" } }
            awaitAll(holder, waiter)
        }
        order shouldBe listOf("holder-in", "holder-out", "waiter-in")
        shouldThrow<IllegalArgumentException> { duckProps(scratch, maxConnections = 0) }
        staging.close()
    }

    private suspend fun Staging.scalarTextSuspending(sql: String): String? =
        withConnection { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }
}
