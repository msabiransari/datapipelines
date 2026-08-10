package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The read/write surface beyond [Staging.stage]: [Staging.withQuery] draining a cursor under
 * the lock, and [Staging.execute] running DML, both through the mutex-guarded connection.
 *
 * `withQuery` replaced a `query(): ResultSet` that handed a live cursor to a caller who then
 * read it with the lock free (v1.5, ST-SEC-1). Since the caller node's drain is suspending
 * Redis I/O (§6.1), a concurrent `stage`/`execute` could execute on the shared connection mid
 * cursor — the §9.2 corruption case. The lock-holding test below is the regression guard.
 */
class H2StagingQueryTest {
    private val props = H2StagingProperties()
    private val staging = H2StagingFactory(props).create(UUID.randomUUID())

    @AfterEach
    fun tearDown() = staging.close()

    /**
     * Stages three rows under a **lowercase** `id` label.
     *
     * The alias is explicit because the source stand-in is H2, which folds an unquoted `id` in
     * the source SQL up to `ID` — so the staged column would be `"ID"` and the unquoted `id`
     * references below could not resolve now that the staging database lower-folds (§11.3). A
     * real Postgres/MySQL source hands us lowercase labels already, and §11.3 tells template
     * authors to alias to lowercase for exactly this reason; the alias makes this fixture the
     * shape production sees. Mixed-case labels keep their own coverage in H2StagingTableTest and
     * H2StagingCaseFoldingTest.
     */
    private fun stageThreeRows() {
        SourceDb().use { src ->
            src.exec("CREATE TABLE t (id INTEGER)")
            src.exec("INSERT INTO t VALUES (1), (2), (3)")
            runBlocking { staging.stage(src.query("SELECT id AS \"id\" FROM t"), "stg_q", Dialect.H2) }
        }
    }

    @Test
    fun `withQuery drains the cursor inside the block`() {
        stageThreeRows()
        val ids =
            runBlocking {
                staging.withQuery("SELECT id FROM \"stg_q\" ORDER BY id") { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                }
            }
        ids shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `withQuery returns the block's value and closes the statement afterwards`() {
        stageThreeRows()
        // Capture the cursor's own statement so the post-block state is observable (ST-TEST-7).
        var statementAfterBlock: java.sql.Statement? = null
        val total =
            runBlocking {
                staging.withQuery("SELECT SUM(id) FROM \"stg_q\"") { rs ->
                    statementAfterBlock = rs.statement
                    rs.next()
                    rs.getLong(1)
                }
            }

        total shouldBe 6L
        requireNotNull(statementAfterBlock).isClosed shouldBe true
    }

    @Test
    fun `withQuery applies the configured query timeout and fetch size`() {
        stageThreeRows()
        val observed =
            runBlocking {
                staging.withQuery("SELECT id FROM \"stg_q\"") { rs ->
                    rs.statement.queryTimeout to rs.statement.fetchSize
                }
            }

        observed shouldBe (props.queryTimeoutSeconds to props.resultBatchSize)
    }

    @Test
    fun `execute runs DML against staging and returns the affected row count`() {
        stageThreeRows()
        val deleted = runBlocking { staging.execute("DELETE FROM \"stg_q\" WHERE id > 1") }
        deleted shouldBe 2L
        staging.readFromStaging { scalarLong(it, "SELECT COUNT(*) FROM \"stg_q\"") } shouldBe 1L
    }

    @Test
    fun `the lock is held across the whole withQuery block, so a contending stage waits`() {
        stageThreeRows()
        val events = CopyOnWriteArrayList<String>()

        // The contender's SOURCE is built up front, OUTSIDE the timed window. Building it inside
        // the async — a whole in-memory H2 plus DDL and an insert — placed its setup cost between
        // `draining.await()` and the staging call, so a setup slower than the hold window would
        // produce the expected ordering even with no lock at all. Only `stage()` may sit inside
        // the window (testing LOW-1).
        SourceDb().use { src ->
            src.exec("CREATE TABLE t2 (id INTEGER)")
            src.exec("INSERT INTO t2 VALUES (9)")
            val contenderRows = src.query("SELECT id FROM t2")

            runBlocking {
                val draining = CompletableDeferred<Unit>()
                val reader =
                    async(Dispatchers.IO) {
                        staging.withQuery("SELECT id FROM \"stg_q\"") { rs: ResultSet ->
                            rs.next()
                            events += "drain-start"
                            draining.complete(Unit)
                            // Stands in for the caller node's suspending drain to the result store.
                            delay(BLOCK_HOLD_MILLIS)
                            while (rs.next()) {
                                rs.getInt(1)
                            }
                            events += "drain-end"
                        }
                    }
                draining.await()
                val contender =
                    async(Dispatchers.IO) {
                        staging.stage(contenderRows, "stg_contender", Dialect.H2)
                        events += "staged"
                    }
                awaitAll(reader, contender)
            }
        }

        // If withQuery released the lock (or handed the cursor out), "staged" lands between the
        // two drain markers — a statement executing on the shared connection mid-cursor.
        events.toList() shouldBe listOf("drain-start", "drain-end", "staged")
    }

    private companion object {
        const val BLOCK_HOLD_MILLIS = 300L
    }
}
