package co.datapipelines.staging

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The drain's observer boundaries (149 §3): a batch is FETCHED holding no lease, an output
 * connection is REQUESTED then ACQUIRED, the insert is WRITTEN, and the observer is called
 * outside the lease and never under the pool's lock. The rows-so-far progress the 108 sink
 * received rides the same observer.
 */
class H2StagingObserverTest {
    private class Recording : StageObserver {
        val calls = CopyOnWriteArrayList<String>()
        val rowsSoFar = CopyOnWriteArrayList<Long>()

        override fun fetchStarted() {
            calls += "fetch"
        }

        override fun fetchFinished(rows: Int) {
            calls += "fetched:$rows"
        }

        override fun connectionRequested() {
            calls += "request"
        }

        override fun connectionAcquired() {
            calls += "acquired"
        }

        override fun batchWritten(
            rows: Int,
            rowsSoFar: Long,
        ) {
            calls += "written:$rows"
            this.rowsSoFar += rowsSoFar
        }
    }

    @Test
    fun `stage reports fetch, request, acquire, write per batch and the empty closing batch`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INT)")
            src.exec("INSERT INTO t SELECT X FROM SYSTEM_RANGE(1, 5)")
            val observer = Recording()
            stagingOverConnections(UUID.randomUUID(), H2StagingProperties(insertBatchSize = 2)).use { staging ->
                val result = runBlocking { staging.stage(src.query("SELECT id FROM t ORDER BY id"), "stg", Dialect.H2, observer) }
                result.rowsStaged shouldBe 5
            }
            // The CREATE TABLE lease first; then 5 rows in batches of 2: three inserted batches,
            // the last (1 row) short — exhausted.
            observer.calls shouldContainExactly
                listOf(
                    "request",
                    "acquired",
                    "fetch",
                    "fetched:2",
                    "request",
                    "acquired",
                    "written:2",
                    "fetch",
                    "fetched:2",
                    "request",
                    "acquired",
                    "written:2",
                    "fetch",
                    "fetched:1",
                    "request",
                    "acquired",
                    "written:1",
                )
            observer.rowsSoFar shouldContainExactly listOf(2L, 4L, 5L)
        }
    }

    @Test
    fun `a zero-row source still writes one empty batch and reports it`() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INT)")
            val observer = Recording()
            stagingOverConnections(UUID.randomUUID(), H2StagingProperties()).use { staging ->
                runBlocking { staging.stage(src.query("SELECT id FROM t"), "stg", Dialect.H2, observer) }.rowsStaged shouldBe 0
            }
            observer.calls shouldContainExactly listOf("request", "acquired", "fetch", "fetched:0", "request", "acquired", "written:0")
            observer.rowsSoFar shouldContainExactly listOf(0L)
        }
    }

    @Test
    fun `stageRows reports the same boundaries for decoded rows`() {
        val observer = Recording()
        stagingOverConnections(UUID.randomUUID(), H2StagingProperties(insertBatchSize = 3)).use { staging ->
            val columns = listOf(ColumnSchema("n", LogicalType.INTEGER))
            val rows = (1..4).map { listOf<Any?>(it) }.asSequence()
            runBlocking { staging.stageRows("stg", columns, rows, observer) }.rowsStaged shouldBe 4
        }
        observer.calls shouldContainExactly
            listOf(
                "request",
                "acquired",
                "fetch",
                "fetched:3",
                "request",
                "acquired",
                "written:3",
                "fetch",
                "fetched:1",
                "request",
                "acquired",
                "written:1",
            )
    }

    @Test
    fun `the observer is never invoked while the drain holds a lease`() {
        // Every physical connection reports whether it is inside a lease's block; the observer
        // asserts none is. `acquired` is the one call made INSIDE the block, by design — it is
        // the boundary that says "holding", and it is made after checkout released the lock.
        val inside =
            java.util.concurrent.atomic
                .AtomicInteger()
        val violations = CopyOnWriteArrayList<String>()
        val observer =
            object : StageObserver {
                override fun fetchStarted() = check("fetch")

                override fun fetchFinished(rows: Int) = check("fetched")

                override fun connectionRequested() = check("request")

                override fun connectionAcquired() = Unit

                override fun batchWritten(
                    rows: Int,
                    rowsSoFar: Long,
                ) = check("written")

                private fun check(what: String) {
                    if (inside.get() != 0) violations += what
                }
            }
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INT)")
            src.exec("INSERT INTO t SELECT X FROM SYSTEM_RANGE(1, 10)")
            val staging =
                stagingOverConnections(UUID.randomUUID(), H2StagingProperties(insertBatchSize = 4)) { c -> LeaseMarking(c, inside) }
            staging.use { runBlocking { it.stage(src.query("SELECT id FROM t"), "stg", Dialect.H2, observer) } }
        }
        violations.isEmpty().shouldBeTrue()
    }

    /** Marks a connection "in use" between statement creation and close — a lease's working span. */
    private class LeaseMarking(
        private val delegate: Connection,
        private val inside: java.util.concurrent.atomic.AtomicInteger,
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): java.sql.PreparedStatement {
            inside.incrementAndGet()
            val statement = delegate.prepareStatement(sql)
            return object : java.sql.PreparedStatement by statement {
                override fun close() {
                    inside.decrementAndGet()
                    statement.close()
                }
            }
        }
    }
}
